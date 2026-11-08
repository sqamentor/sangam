// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.catalogue;

import org.sangam.config.common.Tree;

import java.util.List;
import java.util.Map;

/**
 * One document type of a catalogue (a bill / claim form, an invoice type, a claim form of an insurer, a permit form).
 * All of it is data in a profile section {@code documentTypes: [...]}:
 *
 * <pre>
 * - id: TA_TOUR
 *   family: PERSONAL_CLAIM
 *   formNumber: "RPR-14A"           # the jurisdiction's own form number
 *   legacyForms: ["GAR-14"]
 *   name: {en: ..., hi: ...}
 *   fields: [{id: claimant, required: true}, {id: journeyFrom}]
 *   attachments: [{id: tickets, required: true}]
 *   certificates: [CERT_ENTITLEMENT]
 *   classification: {scheme: HOA, allow: ["2*-*-*-*-*-11", "2*-*-*-*-*-12"], uniformSegments: [major]}
 *   sanction: NONE | REQUIRED | REFERENCE
 *   approvalChain: CLAIM_STD          # a chain template id
 *   paymentRail: DEFAULT              # rail id or DEFAULT
 *   paymentModes: [ADVICE, CHEQUE]
 *   timeBarMonths: 24                 # older claims route to the time-barred procedure
 *   limits: {maxAmount: null}
 *   nilAllowed: false
 *   retention: {category: FIN-VOUCHER, years: 10}
 *   integrations: {sourceSystem: HRMS}
 *   ruleRef: "..."
 * </pre>
 */
public record DocumentType(String id, Map<String, Object> raw) {

    public String family() {
        return Tree.str(raw.get("family"), null);
    }

    public String formNumber() {
        return Tree.str(raw.get("formNumber"), null);
    }

    public String approvalChain() {
        return Tree.str(raw.get("approvalChain"), null);
    }

    public String paymentRail() {
        return Tree.str(raw.get("paymentRail"), "DEFAULT");
    }

    public String sanction() {
        return Tree.str(raw.get("sanction"), "NONE");
    }

    public int timeBarMonths() {
        return Tree.integer(raw.get("timeBarMonths"), 0);
    }

    public boolean nilAllowed() {
        return Tree.bool(raw.get("nilAllowed"), false);
    }

    public List<Map<String, Object>> fields() {
        return Tree.maps(raw.get("fields"));
    }

    public List<Map<String, Object>> attachments() {
        return Tree.maps(raw.get("attachments"));
    }

    public List<String> certificates() {
        return Tree.strings(raw.get("certificates"));
    }

    public Map<String, Object> classification() {
        return Tree.map(raw.get("classification"));
    }

    public Object get(String path) {
        return Tree.get(raw, path);
    }
}
