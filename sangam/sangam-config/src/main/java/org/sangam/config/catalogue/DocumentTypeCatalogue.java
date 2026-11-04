// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.catalogue;

import org.sangam.config.common.Finding;
import org.sangam.config.common.Tree;
import org.sangam.config.profile.ResolvedProfile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The document-type catalogue of a resolved profile (section {@code documentTypes}) and the approval-chain templates
 * (section {@code approvalChains}), with the checks a document instance must pass before it enters its chain.
 */
public final class DocumentTypeCatalogue {

    /**
     * An approval-chain template: ordered steps by role, which roles may return or reject, and the signature policy.
     * <pre>
     * approvalChains:
     *   - id: PAY_OFFICE_3
     *     steps: [{role: DEALING_HAND}, {role: ASSISTANT_OFFICER}, {role: PAYING_OFFICER, sign: true}]
     *     returnBy: [PAYING_OFFICER]       # only these roles may return to the originator
     *     mayEdit: false                    # reviewers approve or object, never edit
     *     queue: {token: true, fifo: true, distributorRole: BILL_DISTRIBUTOR}
     *     signatures: [{atOrAbove: 1000000, count: 2, secondSignatoryRole: SECOND_SIGNATORY}]
     *     sequence: [ORIGINATOR, PAYING_OFFICER]
     * </pre>
     */
    public record ChainTemplate(String id, List<String> roles, List<String> returnBy, boolean mayEdit,
                                Map<String, Object> raw) {

        /** Number of signatures needed for an amount (highest matching band). */
        public int signaturesFor(BigDecimal amount) {
            int n = 1;
            for (Map<String, Object> b : Tree.maps(raw.get("signatures"))) {
                BigDecimal at = Tree.decimal(b.get("atOrAbove"), null);
                if (at != null && amount.compareTo(at) >= 0) {
                    n = Math.max(n, Tree.integer(b.get("count"), 1));
                }
            }
            return n;
        }

        public boolean mayReturn(String role) {
            return returnBy.isEmpty() || returnBy.contains(role);
        }
    }

    private final Map<String, DocumentType> types = new LinkedHashMap<>();
    private final Map<String, ChainTemplate> chains = new LinkedHashMap<>();

    public DocumentTypeCatalogue(List<Map<String, Object>> typeRows, List<Map<String, Object>> chainRows) {
        for (Map<String, Object> r : typeRows) {
            if (!Tree.bool(r.get("disabled"), false)) {
                types.put(Tree.str(r.get("id"), null), new DocumentType(Tree.str(r.get("id"), null), r));
            }
        }
        for (Map<String, Object> c : chainRows) {
            List<String> roles = new ArrayList<>();
            for (Map<String, Object> s : Tree.maps(c.get("steps"))) {
                roles.add(Tree.str(s.get("role"), null));
            }
            chains.put(Tree.str(c.get("id"), null), new ChainTemplate(Tree.str(c.get("id"), null), List.copyOf(roles),
                    Tree.strings(c.get("returnBy")), Tree.bool(c.get("mayEdit"), false), c));
        }
    }

    public static DocumentTypeCatalogue of(ResolvedProfile p) {
        return new DocumentTypeCatalogue(p.list("documentTypes"), p.list("approvalChains"));
    }

    public Optional<DocumentType> type(String id) {
        return Optional.ofNullable(types.get(id));
    }

    /** Looks a type up by its form number (or a legacy form number). */
    public Optional<DocumentType> byForm(String formNumber) {
        for (DocumentType t : types.values()) {
            if (formNumber.equals(t.formNumber()) || Tree.strings(t.get("legacyForms")).contains(formNumber)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    public List<DocumentType> family(String family) {
        return types.values().stream().filter(t -> family.equals(t.family())).toList();
    }

    public List<DocumentType> all() {
        return List.copyOf(types.values());
    }

    public Optional<ChainTemplate> chain(String id) {
        return Optional.ofNullable(chains.get(id));
    }

    /** Catalogue integrity: every type's chain exists; ids unique (by construction). */
    public List<Finding> lint() {
        List<Finding> out = new ArrayList<>();
        for (DocumentType t : types.values()) {
            if (t.approvalChain() != null && !chains.containsKey(t.approvalChain())) {
                out.add(Finding.error("CHAIN_UNKNOWN", "documentTypes." + t.id(), "approval chain '" + t.approvalChain()
                        + "' not defined", null));
            }
            if (t.formNumber() == null) {
                out.add(Finding.warn("FORM_NUMBER_MISSING", "documentTypes." + t.id(), "no form number", null));
            }
        }
        return out;
    }

    /**
     * Checks one document instance against its type.
     *
     * @param doc         field values; {@code lines} is a list of {@code {classification, amount}}; {@code attachments}
     *                    a list of attachment ids; {@code certificates} a list of certificate ids; {@code dueDate}
     * @param segmentOf   function giving a segment value of a classification code (from the registry)
     */
    public List<Finding> check(String typeId, Map<String, Object> doc, LocalDate asOf,
                               java.util.function.BiFunction<String, String, String> segmentOf) {
        List<Finding> out = new ArrayList<>();
        DocumentType t = types.get(typeId);
        if (t == null) {
            out.add(Finding.error("TYPE_UNKNOWN", "type", "document type '" + typeId + "' not in the catalogue", null));
            return out;
        }
        String ref = Tree.str(t.get("ruleRef"), null);
        for (Map<String, Object> f : t.fields()) {
            String id = Tree.str(f.get("id"), "");
            if (Tree.bool(f.get("required"), false) && (doc.get(id) == null || doc.get(id).toString().isBlank())) {
                out.add(Finding.error("FIELD_REQUIRED", id, "field " + id + " is required", ref));
            }
        }
        List<String> att = Tree.strings(doc.get("attachments"));
        for (Map<String, Object> a : t.attachments()) {
            String id = Tree.str(a.get("id"), "");
            if (Tree.bool(a.get("required"), false) && !att.contains(id)) {
                out.add(Finding.error("ATTACHMENT_REQUIRED", id, "attachment " + id + " is required", ref));
            }
        }
        List<String> certs = Tree.strings(doc.get("certificates"));
        for (String c : t.certificates()) {
            if (!certs.contains(c)) {
                out.add(Finding.error("CERTIFICATE_REQUIRED", c, "certificate " + c + " is required", ref));
            }
        }
        List<Map<String, Object>> lines = Tree.maps(doc.get("lines"));
        Map<String, Object> cls = t.classification();
        List<String> allow = Tree.strings(cls.get("allow"));
        BigDecimal total = BigDecimal.ZERO;
        Map<String, String> uniform = new LinkedHashMap<>();
        for (Map<String, Object> l : lines) {
            String code = Tree.str(l.get("classification"), "");
            total = total.add(Tree.decimal(l.get("amount"), BigDecimal.ZERO));
            if (!allow.isEmpty() && allow.stream().noneMatch(p -> glob(p, code))) {
                out.add(Finding.error("CLASSIFICATION_NOT_ALLOWED", code, "classification not allowed for " + typeId, ref));
            }
            for (String seg : Tree.strings(cls.get("uniformSegments"))) {
                String v = segmentOf == null ? null : segmentOf.apply(code, seg);
                String prev = uniform.putIfAbsent(seg, v);
                if (prev != null && !prev.equals(v)) {
                    out.add(Finding.error("MIXED_SEGMENT", seg, "one document may not mix " + seg + " values (" + prev
                            + ", " + v + ")", Tree.str(cls.get("uniformRuleRef"), ref)));
                }
            }
        }
        if (total.signum() == 0 && !lines.isEmpty() && !t.nilAllowed()) {
            out.add(Finding.error("NIL_NOT_ALLOWED", "lines", "net amount zero is not allowed for " + typeId, ref));
        }
        BigDecimal max = Tree.decimal(t.get("limits.maxAmount"), null);
        if (max != null && total.compareTo(max) > 0) {
            out.add(Finding.error("AMOUNT_ABOVE_LIMIT", "lines", "amount above the type limit " + max, ref));
        }
        LocalDate due = Tree.date(doc.get("dueDate"), null);
        if (t.timeBarMonths() > 0 && due != null && ChronoUnit.MONTHS.between(due, asOf) >= t.timeBarMonths()) {
            out.add(Finding.warn("TIME_BARRED", "dueDate", "claim older than " + t.timeBarMonths()
                    + " months: route to the time-barred procedure", Tree.str(t.get("timeBarRuleRef"), ref)));
        }
        if ("REQUIRED".equals(t.sanction()) && doc.get("sanctionRef") == null) {
            out.add(Finding.error("SANCTION_REQUIRED", "sanctionRef", "a sanction reference is required", ref));
        }
        return out;
    }

    /** Glob match on codes: {@code *} matches any run of characters, {@code ?} one character. */
    public static boolean glob(String pattern, String value) {
        StringBuilder re = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            re.append(c == '*' ? ".*" : c == '?' ? "." : java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        return value.matches(re.toString());
    }
}
