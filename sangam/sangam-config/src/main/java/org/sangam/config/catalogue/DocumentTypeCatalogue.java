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

        /**
         * Steps owned by another tenant or an external body (PFM-076: an independent authority approving withdrawals;
         * donor no-objection; ministerial direction PFM-077). Step shape:
         * {@code {role: CONTROLLER_OF_BUDGET, ownerTenant: KE.COB, external: true, sla: 5WD, refusalBlocks: true}}.
         * Packs route such a step through the core referral block to {@code ownerTenant}.
         */
        public List<Map<String, Object>> externalSteps() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> s : Tree.maps(raw.get("steps"))) {
                if (Tree.bool(s.get("external"), false) || s.get("ownerTenant") != null) {
                    out.add(s);
                }
            }
            return out;
        }

        /** Steps that record personal liability of the signer (certifying officer, ordenador de despesa; PFM-048). */
        public List<String> liableRoles() {
            List<String> out = new ArrayList<>();
            for (Map<String, Object> s : Tree.maps(raw.get("steps"))) {
                if ("PERSONAL".equalsIgnoreCase(Tree.str(s.get("liability"), ""))) {
                    out.add(Tree.str(s.get("role"), null));
                }
            }
            return out;
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
        return byForm(formNumber, null);
    }

    /** {@link #byForm(String)} among the types of one form kind ({@code null} or blank: every type). */
    public Optional<DocumentType> byForm(String formNumber, String formKind) {
        for (DocumentType t : ofKind(List.copyOf(types.values()), formKind)) {
            if (formNumber.equals(t.formNumber()) || Tree.strings(t.get("legacyForms")).contains(formNumber)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /**
     * Every type a reference names: the type whose id it is, and the types whose form number (or a legacy form number)
     * it is. A process carries a document type as its catalogue id, such as the Union's {@code GPF_WITHDRAWAL} or
     * Rajasthan's {@code RJ_FVC}, or as a form number, such as {@code GOI:RPR 42}; exactly one match identifies the
     * type, more than one is ambiguous and is never decided by catalogue order. One form can carry several types
     * (Rajasthan's {@code RJ:GA 84} carries the FVC bill and its sub-types; the Union's {@code GOI:RPR 29} the contingent,
     * supplier and entertainment claims), so the catalogue id is the reference that always identifies one type.
     */
    public List<DocumentType> byReference(String ref) {
        if (ref == null || ref.isBlank()) {
            return List.of();
        }
        java.util.Set<DocumentType> out = new java.util.LinkedHashSet<>();
        type(ref).ifPresent(out::add);
        for (DocumentType t : types.values()) {
            if (ref.equals(t.formNumber()) || Tree.strings(t.get("legacyForms")).contains(ref)) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    /**
     * {@link #byReference(String)} restricted to one form kind (the row's {@code formKind}, for example {@code BILL}):
     * a type of another kind, or a row that declares no kind, does not match. A {@code null} or blank kind applies no
     * restriction, so a caller that names no kind sees the catalogue as before.
     */
    public List<DocumentType> byReference(String ref, String formKind) {
        return ofKind(byReference(ref), formKind);
    }

    /** The types of {@code candidates} whose {@code formKind} is {@code formKind}; all of them when no kind is named. */
    public static List<DocumentType> ofKind(List<DocumentType> candidates, String formKind) {
        if (formKind == null || formKind.isBlank()) {
            return candidates;
        }
        return candidates.stream().filter(t -> formKind.equals(t.formKind())).toList();
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
     * The catalogue's contribution to the profile linter ({@code ProfileValidator.with(DocumentTypeCatalogue::profileCheck)},
     * the "document-type chains" check of its javadoc): the {@link #lint()} findings of a resolved profile. The
     * composition root registers it, so a profile version whose document type names an approval chain the catalogue
     * does not define is refused at activation instead of failing the running pack's chain step ({@code CHAIN_UNKNOWN}).
     */
    public static List<Finding> profileCheck(ResolvedProfile profile) {
        return of(profile).lint();
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
        List<Map<String, Object>> lines = linesOf(doc);
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
        boolean amounted = !Tree.maps(doc.get("lines")).isEmpty() || doc.get("amount") != null;
        if (total.signum() == 0 && amounted && !t.nilAllowed()) {
            out.add(Finding.error("NIL_NOT_ALLOWED", "lines", "net amount zero is not allowed for " + typeId, ref));
        }
        // SL-122: the limit and the document amounts are both integer minor units (SD-398 / PACKS4: packs send money
        // in minor units, profilegen money.minor() writes profile money in minor units), so they compare as they are.
        // A limit that is set but is not a plain number is an error, never a skipped check.
        Object rawMax = t.get("limits.maxAmount");
        BigDecimal max;
        try {
            max = Tree.decimal(rawMax, null);
        } catch (NumberFormatException e) {
            max = null;
        }
        if (rawMax != null && max == null) {
            out.add(Finding.error("LIMIT_INVALID", "limits.maxAmount", "type limit '" + rawMax
                    + "' is not an amount in minor units", ref));
        } else if (max != null && amounted && total.compareTo(max) > 0) {
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

    /**
     * The document's lines (SL-122): its {@code lines} when it has them, else one line made of its top-level
     * {@code headOfAccount} (the classification) and {@code amount}, as a single-head bill sends them, so the
     * allow-list, the nil rule and the amount limit apply to it too; a document with neither has no lines.
     */
    static List<Map<String, Object>> linesOf(Map<String, Object> doc) {
        List<Map<String, Object>> lines = Tree.maps(doc.get("lines"));
        if (!lines.isEmpty() || (doc.get("headOfAccount") == null && doc.get("amount") == null)) {
            return lines;
        }
        Map<String, Object> one = new LinkedHashMap<>();
        if (doc.get("headOfAccount") != null) {
            one.put("classification", doc.get("headOfAccount"));
        }
        if (doc.get("amount") != null) {
            one.put("amount", doc.get("amount"));
        }
        return List.of(one);
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
