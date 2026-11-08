// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy;

import org.sangam.core.security.SangamSecurityException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An attribute condition of a policy ({@code AUTH_EFFECTIVE.condition_id}, VI.4 rule 2). The condition language is
 * deliberately small so that every condition compiles to SQL over stored columns (VI.4 rule 1) and evaluates to the
 * same result in Java for virtual resources.
 *
 * <p>JSON form (stored in {@code AUTH_CONDITION.expression}):
 * <pre>
 * {"and":[c1,c2]} | {"or":[..]} | {"not":c}
 * {"attr":"assignee","op":"eq","principal":"personId"}
 * {"attr":"priority","op":"ge","value":50}
 * {"attr":"taskDefinitionKey","op":"in","values":["a","b"]}
 * {"attr":"owner","op":"isNull"}
 * </pre>
 * Principal references: {@code personId}, {@code tenantId}, {@code seats}, {@code groups}, {@code roles}.
 */
public sealed interface Condition {

    enum Op {
        EQ, NE, LT, LE, GT, GE, IN, IS_NULL, NOT_NULL;

        static Op parse(String s) {
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "eq", "=", "==" -> EQ;
                case "ne", "!=", "<>" -> NE;
                case "lt", "<" -> LT;
                case "le", "<=" -> LE;
                case "gt", ">" -> GT;
                case "ge", ">=" -> GE;
                case "in" -> IN;
                case "isnull" -> IS_NULL;
                case "notnull" -> NOT_NULL;
                default -> throw new SangamSecurityException.PolicyRejected("unknown condition operator " + s);
            };
        }

        String sql() {
            return switch (this) {
                case EQ -> "=";
                case NE -> "<>";
                case LT -> "<";
                case LE -> "<=";
                case GT -> ">";
                case GE -> ">=";
                default -> throw new IllegalStateException(name());
            };
        }
    }

    /** Operand: a literal, or a reference to the caller ({@code principal}). */
    record Operand(Object literal, String principalRef) {
        public static Operand of(Object v) {
            return new Operand(v, null);
        }

        public static Operand principal(String ref) {
            return new Operand(null, ref);
        }

        public boolean isPrincipal() {
            return principalRef != null;
        }
    }

    record Compare(String attribute, Op op, Operand operand) implements Condition {
    }

    record In(String attribute, List<Operand> values) implements Condition {
        public In {
            values = List.copyOf(values);
        }
    }

    record Null(String attribute, boolean isNull) implements Condition {
    }

    record And(List<Condition> parts) implements Condition {
        public And {
            parts = List.copyOf(parts);
        }
    }

    record Or(List<Condition> parts) implements Condition {
        public Or {
            parts = List.copyOf(parts);
        }
    }

    record Not(Condition inner) implements Condition {
    }

    // ------------------------------------------------------------------ JSON (Jackson tree as Map/List)

    @SuppressWarnings("unchecked")
    static Condition fromMap(Map<String, Object> m) {
        if (m.containsKey("and")) {
            return new And(((List<Object>) m.get("and")).stream().map(o -> fromMap((Map<String, Object>) o)).toList());
        }
        if (m.containsKey("or")) {
            return new Or(((List<Object>) m.get("or")).stream().map(o -> fromMap((Map<String, Object>) o)).toList());
        }
        if (m.containsKey("not")) {
            return new Not(fromMap((Map<String, Object>) m.get("not")));
        }
        Object attr = m.get("attr");
        if (!(attr instanceof String a) || a.isBlank()) {
            throw new SangamSecurityException.PolicyRejected("condition needs attr, and/or/not");
        }
        Op op = Op.parse(String.valueOf(m.getOrDefault("op", "eq")));
        switch (op) {
            case IS_NULL, NOT_NULL -> {
                return new Null(a, op == Op.IS_NULL);
            }
            case IN -> {
                List<Operand> ops = new ArrayList<>();
                if (m.get("principal") instanceof String ref) {
                    ops.add(Operand.principal(ref));
                } else if (m.get("values") instanceof List<?> vs) {
                    vs.forEach(v -> ops.add(Operand.of(v)));
                } else {
                    throw new SangamSecurityException.PolicyRejected("'in' needs values or principal");
                }
                return new In(a, ops);
            }
            default -> {
                Operand o = m.get("principal") instanceof String ref ? Operand.principal(ref) : Operand.of(m.get("value"));
                return new Compare(a, op, o);
            }
        }
    }

    static Map<String, Object> toMap(Condition c) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (c) {
            case And and -> m.put("and", and.parts().stream().map(Condition::toMap).toList());
            case Or or -> m.put("or", or.parts().stream().map(Condition::toMap).toList());
            case Not not -> m.put("not", toMap(not.inner()));
            case Null n -> {
                m.put("attr", n.attribute());
                m.put("op", n.isNull() ? "isNull" : "notNull");
            }
            case In in -> {
                m.put("attr", in.attribute());
                m.put("op", "in");
                if (in.values().size() == 1 && in.values().get(0).isPrincipal()) {
                    m.put("principal", in.values().get(0).principalRef());
                } else {
                    m.put("values", in.values().stream().map(Operand::literal).toList());
                }
            }
            case Compare cmp -> {
                m.put("attr", cmp.attribute());
                m.put("op", cmp.op().name().toLowerCase(Locale.ROOT));
                if (cmp.operand().isPrincipal()) {
                    m.put("principal", cmp.operand().principalRef());
                } else {
                    m.put("value", cmp.operand().literal());
                }
            }
        }
        return m;
    }
}
