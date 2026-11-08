// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy;

import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiles a {@link Condition} to SQL over a resource table alias, and evaluates the same condition in Java. Both
 * forms come from one definition (VI.4 rule 1). A condition that names an attribute without a column cannot compile
 * and is rejected for list-visible actions at deployment ({@link #requireCompilable}).
 */
public final class ConditionCompiler {

    private ConditionCompiler() {
    }

    /** Rejects (VI.4 rule 1) a condition that does not compile for the descriptor. */
    public static void requireCompilable(Condition c, ResourceDescriptor d) {
        if (!d.hasTable()) {
            throw new SangamSecurityException.PolicyRejected("resource type " + d.resourceType()
                    + " has no table: list-visible conditional policies cannot compile to SQL");
        }
        for (String attr : attributes(c, new ArrayList<>())) {
            if (d.column(attr).isEmpty()) {
                throw new SangamSecurityException.PolicyRejected("attribute '" + attr + "' of " + d.resourceType()
                        + " is not stored as a column; the policy cannot compile to SQL");
            }
        }
        validatePrincipalRefs(c);
    }

    public static boolean compilable(Condition c, ResourceDescriptor d) {
        try {
            requireCompilable(c, d);
            return true;
        } catch (SangamSecurityException.PolicyRejected e) {
            return false;
        }
    }

    /** SQL form over {@code alias}. */
    public static SqlPredicate compile(Condition c, ResourceDescriptor d, String alias, SecurityIdentity who) {
        List<Object> params = new ArrayList<>();
        String sql = sql(c, d, alias, who, params);
        return new SqlPredicate(sql, params);
    }

    private static String sql(Condition c, ResourceDescriptor d, String alias, SecurityIdentity who, List<Object> params) {
        return switch (c) {
            case Condition.And and -> join(and.parts(), " AND ", "1=1", d, alias, who, params);
            case Condition.Or or -> join(or.parts(), " OR ", "1=0", d, alias, who, params);
            case Condition.Not not -> "NOT (" + sql(not.inner(), d, alias, who, params) + ")";
            case Condition.Null n -> col(d, alias, n.attribute()) + (n.isNull() ? " IS NULL" : " IS NOT NULL");
            case Condition.In in -> {
                List<Object> values = new ArrayList<>();
                for (Condition.Operand o : in.values()) {
                    Object v = value(o, who);
                    if (v instanceof Collection<?> coll) {
                        values.addAll(coll);
                    } else {
                        values.add(v);
                    }
                }
                if (values.isEmpty()) {
                    yield "1=0";
                }
                params.addAll(values.stream().map(ConditionCompiler::bindable).toList());
                yield col(d, alias, in.attribute()) + " IN (" + String.join(",", java.util.Collections.nCopies(values.size(), "?")) + ")";
            }
            case Condition.Compare cmp -> {
                Object v = value(cmp.operand(), who);
                if (v instanceof Collection<?>) {
                    throw new SangamSecurityException.PolicyRejected("a set-valued principal reference needs 'in'");
                }
                if (v == null) {
                    yield cmp.op() == Condition.Op.NE ? col(d, alias, cmp.attribute()) + " IS NOT NULL" : "1=0";
                }
                params.add(bindable(v));
                yield col(d, alias, cmp.attribute()) + " " + cmp.op().sql() + " ?";
            }
        };
    }

    private static String join(List<Condition> parts, String op, String empty, ResourceDescriptor d, String alias,
                               SecurityIdentity who, List<Object> params) {
        if (parts.isEmpty()) {
            return empty;
        }
        List<String> out = new ArrayList<>();
        for (Condition p : parts) {
            out.add("(" + sql(p, d, alias, who, params) + ")");
        }
        return String.join(op, out);
    }

    private static String col(ResourceDescriptor d, String alias, String attr) {
        String column = d.column(attr).orElseThrow(() -> new SangamSecurityException.PolicyRejected(
                "attribute '" + attr + "' is not a column of " + d.resourceType()));
        return alias + "." + column;
    }

    // ------------------------------------------------------------------ Java form

    /** Same semantics as the SQL form, over an attribute map (SQL NULL semantics: comparisons with null are false). */
    public static boolean evaluate(Condition c, Map<String, ?> attributes, SecurityIdentity who) {
        return switch (c) {
            case Condition.And and -> and.parts().stream().allMatch(p -> evaluate(p, attributes, who));
            case Condition.Or or -> or.parts().stream().anyMatch(p -> evaluate(p, attributes, who));
            case Condition.Not not -> {
                Boolean inner = tri(not.inner(), attributes, who);
                yield inner != null && !inner;
            }
            case Condition.Null n -> (attributes.get(n.attribute()) == null) == n.isNull();
            case Condition.In in -> {
                Object a = attributes.get(in.attribute());
                if (a == null) {
                    yield false;
                }
                for (Condition.Operand o : in.values()) {
                    Object v = value(o, who);
                    if (v instanceof Collection<?> coll) {
                        for (Object x : coll) {
                            if (compare(a, x) == 0) {
                                yield true;
                            }
                        }
                    } else if (v != null && compare(a, v) == 0) {
                        yield true;
                    }
                }
                yield false;
            }
            case Condition.Compare cmp -> {
                Object a = attributes.get(cmp.attribute());
                Object v = value(cmp.operand(), who);
                if (v == null) {
                    yield cmp.op() == Condition.Op.NE && a != null;
                }
                if (a == null) {
                    yield false;
                }
                int r = compare(a, v);
                yield switch (cmp.op()) {
                    case EQ -> r == 0;
                    case NE -> r != 0;
                    case LT -> r < 0;
                    case LE -> r <= 0;
                    case GT -> r > 0;
                    case GE -> r >= 0;
                    default -> false;
                };
            }
        };
    }

    /** Three-valued evaluation, so NOT over a NULL comparison stays false as in SQL. */
    private static Boolean tri(Condition c, Map<String, ?> attributes, SecurityIdentity who) {
        if (c instanceof Condition.Compare cmp && attributes.get(cmp.attribute()) == null) {
            return null;
        }
        if (c instanceof Condition.In in && attributes.get(in.attribute()) == null) {
            return null;
        }
        return evaluate(c, attributes, who);
    }

    private static int compare(Object a, Object b) {
        if (a instanceof Number || b instanceof Number) {
            try {
                return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
            } catch (NumberFormatException e) {
                return a.toString().compareTo(b.toString());
            }
        }
        return Objects.toString(a).compareTo(Objects.toString(b));
    }

    private static Object value(Condition.Operand o, SecurityIdentity who) {
        if (!o.isPrincipal()) {
            return o.literal();
        }
        return switch (o.principalRef()) {
            case "personId" -> who.personId();
            case "tenantId" -> who.tenantId();
            case "seats" -> who.seats();
            case "groups" -> who.groups();
            case "roles" -> who.roles();
            default -> throw new SangamSecurityException.PolicyRejected("unknown principal reference " + o.principalRef());
        };
    }

    private static Object bindable(Object v) {
        if (v instanceof BigDecimal || v instanceof Long || v instanceof Integer || v instanceof String) {
            return v;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return v == null ? null : v.toString();
    }

    private static List<String> attributes(Condition c, List<String> out) {
        switch (c) {
            case Condition.And and -> and.parts().forEach(p -> attributes(p, out));
            case Condition.Or or -> or.parts().forEach(p -> attributes(p, out));
            case Condition.Not not -> attributes(not.inner(), out);
            case Condition.Null n -> out.add(n.attribute());
            case Condition.In in -> out.add(in.attribute());
            case Condition.Compare cmp -> out.add(cmp.attribute());
        }
        return out;
    }

    private static void validatePrincipalRefs(Condition c) {
        switch (c) {
            case Condition.And and -> and.parts().forEach(ConditionCompiler::validatePrincipalRefs);
            case Condition.Or or -> or.parts().forEach(ConditionCompiler::validatePrincipalRefs);
            case Condition.Not not -> validatePrincipalRefs(not.inner());
            case Condition.Null n -> { }
            case Condition.In in -> in.values().forEach(ConditionCompiler::validateRef);
            case Condition.Compare cmp -> validateRef(cmp.operand());
        }
    }

    private static void validateRef(Condition.Operand o) {
        if (o.isPrincipal() && !List.of("personId", "tenantId", "seats", "groups", "roles").contains(o.principalRef())) {
            throw new SangamSecurityException.PolicyRejected("unknown principal reference " + o.principalRef());
        }
    }
}
