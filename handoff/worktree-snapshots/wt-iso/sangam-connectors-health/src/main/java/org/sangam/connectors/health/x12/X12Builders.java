// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.x12;

import org.sangam.connectors.health.x12.X12.Segment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builders for the outbound HIPAA transactions (US-HLT-085…088). Input is the connector's plain map so that a pack can
 * fill it from process variables; the builders produce the segments between ST and SE, following the loop structure
 * of the 005010 TR3s (2000A information source / billing provider, 2000B receiver / subscriber, 2000C patient, 2300
 * claim, 2400 service line). Only the commonly used, situational-minimal segments are produced; payer-specific
 * companion-guide segments are added through {@code extraSegments} (list of {@code "ID*el*el"} strings).
 *
 * <p>Common input keys: {@code payerName}, {@code payerId}, {@code providerName}, {@code providerNpi},
 * {@code providerTaxId}, {@code subscriberId}, {@code subscriberLast}, {@code subscriberFirst}, {@code subscriberDob}
 * (CCYYMMDD), {@code subscriberGender} (M/F/U), {@code traceNumber}.
 */
public final class X12Builders {

    public static final String V270 = "005010X279A1";
    public static final String V276 = "005010X212";
    public static final String V278 = "005010X217";
    public static final String V837P = "005010X222A1";
    public static final String V837I = "005010X223A2";
    public static final String V835 = "005010X221A1";
    public static final String V999 = "005010X231A1";

    private X12Builders() {
    }

    static String s(Map<String, Object> in, String k) {
        Object v = in.get(k);
        return v == null ? "" : X12.clean(v.toString(), null);
    }

    static String req(Map<String, Object> in, String k) {
        String v = s(in, k);
        if (v.isEmpty()) {
            throw new IllegalArgumentException("X12 input '" + k + "' is required");
        }
        return v;
    }

    private static String today() {
        return java.time.LocalDate.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }

    /** 270 eligibility inquiry; {@code serviceTypes} comma list (30 = health benefit plan coverage). */
    public static List<Segment> eligibility270(Map<String, Object> in, String trace) {
        List<Segment> b = new ArrayList<>();
        b.add(Segment.of("BHT", "0022", "13", trace, today(), "1200"));
        b.add(Segment.of("HL", "1", "", "20", "1"));
        b.add(Segment.of("NM1", "PR", "2", req(in, "payerName"), "", "", "", "", "PI", req(in, "payerId")));
        b.add(Segment.of("HL", "2", "1", "21", "1"));
        b.add(Segment.of("NM1", "1P", "2", req(in, "providerName"), "", "", "", "", "XX", req(in, "providerNpi")));
        b.add(Segment.of("HL", "3", "2", "22", "0"));
        b.add(Segment.of("TRN", "1", trace, "9" + pad10(s(in, "providerTaxId"))));
        b.add(Segment.of("NM1", "IL", "1", req(in, "subscriberLast"), s(in, "subscriberFirst"), "", "", "", "MI",
                req(in, "subscriberId")));
        if (!s(in, "subscriberDob").isEmpty()) {
            b.add(Segment.of("DMG", "D8", s(in, "subscriberDob"), s(in, "subscriberGender")));
        }
        b.add(Segment.of("DTP", "291", "D8", s(in, "serviceDate").isEmpty() ? today() : s(in, "serviceDate")));
        String types = s(in, "serviceTypes").isEmpty() ? "30" : s(in, "serviceTypes");
        for (String t : types.split(",")) {
            b.add(Segment.of("EQ", t.trim()));
        }
        extra(in, b);
        return b;
    }

    /** 276 claim status request for one claim ({@code patientControlNumber}, {@code totalCharge}, {@code serviceDate}). */
    public static List<Segment> claimStatus276(Map<String, Object> in, String trace) {
        List<Segment> b = new ArrayList<>();
        b.add(Segment.of("BHT", "0010", "13", trace, today(), "1200"));
        b.add(Segment.of("HL", "1", "", "20", "1"));
        b.add(Segment.of("NM1", "PR", "2", req(in, "payerName"), "", "", "", "", "PI", req(in, "payerId")));
        b.add(Segment.of("HL", "2", "1", "21", "1"));
        b.add(Segment.of("NM1", "41", "2", req(in, "providerName"), "", "", "", "", "46", s(in, "submitterId").isEmpty()
                ? req(in, "providerNpi") : s(in, "submitterId")));
        b.add(Segment.of("HL", "3", "2", "19", "1"));
        b.add(Segment.of("NM1", "1P", "2", req(in, "providerName"), "", "", "", "", "XX", req(in, "providerNpi")));
        b.add(Segment.of("HL", "4", "3", "22", "0"));
        b.add(Segment.of("DMG", "D8", s(in, "subscriberDob"), s(in, "subscriberGender")));
        b.add(Segment.of("NM1", "IL", "1", req(in, "subscriberLast"), s(in, "subscriberFirst"), "", "", "", "MI",
                req(in, "subscriberId")));
        b.add(Segment.of("TRN", "1", trace));
        b.add(Segment.of("REF", "EJ", req(in, "patientControlNumber")));
        if (!s(in, "payerClaimNumber").isEmpty()) {
            b.add(Segment.of("REF", "1K", s(in, "payerClaimNumber")));
        }
        b.add(Segment.of("AMT", "T3", amount(in.get("totalCharge"))));
        b.add(Segment.of("DTP", "472", "RD8", s(in, "serviceDate") + "-" + (s(in, "serviceDateTo").isEmpty()
                ? s(in, "serviceDate") : s(in, "serviceDateTo"))));
        extra(in, b);
        return b;
    }

    /**
     * 278 services review request. {@code requestCategory} UM01 (HS health services review, AR admission review, SC
     * specialty care), {@code certificationType} UM02 (I initial), {@code serviceType} UM03, {@code levelOfService}
     * UM06 (U urgent → expedited, E elective), {@code procedureCode}, {@code diagnosisCode}, {@code quantity}.
     */
    public static List<Segment> serviceReview278(Map<String, Object> in, String trace) {
        List<Segment> b = new ArrayList<>();
        b.add(Segment.of("BHT", "0007", "13", trace, today(), "1200"));
        b.add(Segment.of("HL", "1", "", "20", "1"));
        b.add(Segment.of("NM1", "X3", "2", req(in, "payerName"), "", "", "", "", "PI", req(in, "payerId")));
        b.add(Segment.of("HL", "2", "1", "21", "1"));
        b.add(Segment.of("NM1", "1P", "2", req(in, "providerName"), "", "", "", "", "XX", req(in, "providerNpi")));
        b.add(Segment.of("HL", "3", "2", "22", "1"));
        b.add(Segment.of("NM1", "IL", "1", req(in, "subscriberLast"), s(in, "subscriberFirst"), "", "", "", "MI",
                req(in, "subscriberId")));
        if (!s(in, "subscriberDob").isEmpty()) {
            b.add(Segment.of("DMG", "D8", s(in, "subscriberDob"), s(in, "subscriberGender")));
        }
        b.add(Segment.of("HL", "4", "3", "EV", "1"));
        b.add(Segment.of("TRN", "1", trace, "9" + pad10(s(in, "providerTaxId"))));
        String cat = s(in, "requestCategory").isEmpty() ? "HS" : s(in, "requestCategory");
        b.add(Segment.of("UM", cat, s(in, "certificationType").isEmpty() ? "I" : s(in, "certificationType"),
                s(in, "serviceType").isEmpty() ? "1" : s(in, "serviceType"), s(in, "placeOfService").isEmpty() ? ""
                        : s(in, "placeOfService") + ":B", "", s(in, "levelOfService")));
        if (!s(in, "diagnosisCode").isEmpty()) {
            b.add(Segment.of("HI", "ABK:" + s(in, "diagnosisCode").replace(".", "")));
        }
        b.add(Segment.of("HL", "5", "4", "SS", "0"));
        b.add(Segment.of("TRN", "1", trace + "-1", "9" + pad10(s(in, "providerTaxId"))));
        b.add(Segment.of("UM", cat, "I"));
        if (!s(in, "procedureCode").isEmpty()) {
            b.add(Segment.of("SV1", "HC:" + s(in, "procedureCode"), "", "UN", s(in, "quantity").isEmpty() ? "1"
                    : s(in, "quantity")));
        }
        extra(in, b);
        return b;
    }

    /**
     * 837P or 837I claim. Keys: {@code institutional} (true → 837I), {@code patientControlNumber}, {@code totalCharge},
     * {@code placeOfService} (837P) / {@code facilityTypeCode} (837I, e.g. 11 + frequency), {@code frequency} (1
     * original, 7 replacement, 8 void), {@code payerClaimNumber} (for 7/8), {@code diagnosisCodes} (comma list, first =
     * principal), {@code priorAuthNumber}, {@code lines}: list of {@code procedureCode}, {@code modifiers},
     * {@code revenueCode} (837I), {@code charge}, {@code units}, {@code serviceDate}.
     */
    @SuppressWarnings("unchecked")
    public static List<Segment> claim837(Map<String, Object> in, String trace) {
        boolean inst = Boolean.parseBoolean(String.valueOf(in.getOrDefault("institutional", "false")));
        List<Segment> b = new ArrayList<>();
        b.add(Segment.of("BHT", "0019", "00", trace, today(), "1200", "CH"));
        b.add(Segment.of("NM1", "41", "2", req(in, "providerName"), "", "", "", "", "46", s(in, "submitterId").isEmpty()
                ? req(in, "providerNpi") : s(in, "submitterId")));
        b.add(Segment.of("PER", "IC", s(in, "contactName").isEmpty() ? "BILLING" : s(in, "contactName"), "TE",
                s(in, "contactPhone").isEmpty() ? "0000000000" : s(in, "contactPhone")));
        b.add(Segment.of("NM1", "40", "2", req(in, "payerName"), "", "", "", "", "46", req(in, "payerId")));
        b.add(Segment.of("HL", "1", "", "20", "1"));
        b.add(Segment.of("NM1", "85", "2", req(in, "providerName"), "", "", "", "", "XX", req(in, "providerNpi")));
        b.add(Segment.of("N3", s(in, "providerAddress").isEmpty() ? "UNKNOWN" : s(in, "providerAddress")));
        b.add(Segment.of("N4", s(in, "providerCity").isEmpty() ? "UNKNOWN" : s(in, "providerCity"),
                s(in, "providerState").isEmpty() ? "XX" : s(in, "providerState"),
                s(in, "providerZip").isEmpty() ? "000000000" : s(in, "providerZip")));
        b.add(Segment.of("REF", "EI", s(in, "providerTaxId").isEmpty() ? "000000000" : s(in, "providerTaxId")));
        b.add(Segment.of("HL", "2", "1", "22", "0"));
        b.add(Segment.of("SBR", "P", "18", s(in, "groupNumber"), "", "", "", "", "", s(in, "claimFilingCode").isEmpty()
                ? "CI" : s(in, "claimFilingCode")));
        b.add(Segment.of("NM1", "IL", "1", req(in, "subscriberLast"), s(in, "subscriberFirst"), "", "", "", "MI",
                req(in, "subscriberId")));
        if (!s(in, "subscriberDob").isEmpty()) {
            b.add(Segment.of("DMG", "D8", s(in, "subscriberDob"), s(in, "subscriberGender")));
        }
        b.add(Segment.of("NM1", "PR", "2", req(in, "payerName"), "", "", "", "", "PI", req(in, "payerId")));
        String freq = s(in, "frequency").isEmpty() ? "1" : s(in, "frequency");
        String clm05 = inst ? (s(in, "facilityTypeCode").isEmpty() ? "11" : s(in, "facilityTypeCode")) + ":A:" + freq
                : (s(in, "placeOfService").isEmpty() ? "11" : s(in, "placeOfService")) + ":B:" + freq;
        b.add(Segment.of("CLM", req(in, "patientControlNumber"), amount(in.get("totalCharge")), "", "", clm05,
                inst ? "" : "Y", "A", "Y", "Y"));
        if (!"1".equals(freq) && !s(in, "payerClaimNumber").isEmpty()) {
            b.add(Segment.of("REF", "F8", s(in, "payerClaimNumber")));
        }
        if (!s(in, "priorAuthNumber").isEmpty()) {
            b.add(Segment.of("REF", "G1", s(in, "priorAuthNumber")));
        }
        String dx = s(in, "diagnosisCodes");
        if (!dx.isEmpty()) {
            String[] codes = dx.split(",");
            List<String> hi = new ArrayList<>();
            for (int i = 0; i < codes.length && i < 12; i++) {
                String q = inst ? (i == 0 ? "ABK" : "ABF") : (i == 0 ? "ABK" : "ABF");
                hi.add(q + ":" + codes[i].trim().replace(".", ""));
            }
            b.add(Segment.of("HI", hi.toArray(String[]::new)));
        }
        Object linesObj = in.get("lines");
        List<Map<String, Object>> lines = linesObj instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        int n = 0;
        for (Map<String, Object> line : lines) {
            n++;
            b.add(Segment.of("LX", String.valueOf(n)));
            String proc = s(line, "procedureCode") + (s(line, "modifiers").isEmpty() ? ""
                    : ":" + s(line, "modifiers").replace(",", ":"));
            if (inst) {
                b.add(Segment.of("SV2", req(line, "revenueCode"), s(line, "procedureCode").isEmpty() ? "" : "HC:" + proc,
                        amount(line.get("charge")), "UN", s(line, "units").isEmpty() ? "1" : s(line, "units")));
            } else {
                b.add(Segment.of("SV1", "HC:" + proc, amount(line.get("charge")), "UN",
                        s(line, "units").isEmpty() ? "1" : s(line, "units"), "", "", "1"));
            }
            String date = s(line, "serviceDate").isEmpty() ? s(in, "serviceDate") : s(line, "serviceDate");
            if (!date.isEmpty()) {
                b.add(Segment.of("DTP", "472", "D8", date));
            }
            b.add(Segment.of("REF", "6R", trace + "-" + n));
        }
        extra(in, b);
        return b;
    }

    /** 999 acknowledgment of one received group (accepted, or rejected with the envelope errors). */
    public static List<Segment> ack999(X12.Group g, List<String> errors) {
        List<Segment> b = new ArrayList<>();
        b.add(Segment.of("AK1", g.functionalId(), g.gs().el(6), g.gs().el(8)));
        for (X12.Transaction t : g.transactions()) {
            b.add(Segment.of("AK2", t.type(), t.controlNumber(), t.implementation()));
            b.add(Segment.of("IK5", errors.isEmpty() ? "A" : "R", errors.isEmpty() ? "" : "5"));
        }
        int n = g.transactions().size();
        b.add(Segment.of("AK9", errors.isEmpty() ? "A" : "R", String.valueOf(n), String.valueOf(n),
                String.valueOf(errors.isEmpty() ? n : 0)));
        return b;
    }

    static String amount(Object v) {
        if (v == null || v.toString().isBlank()) {
            return "0";
        }
        return new BigDecimal(v.toString().trim()).stripTrailingZeros().toPlainString();
    }

    private static String pad10(String taxId) {
        String t = taxId.replaceAll("[^0-9]", "");
        return t.isEmpty() ? "000000000" : t;
    }

    @SuppressWarnings("unchecked")
    private static void extra(Map<String, Object> in, List<Segment> b) {
        Object e = in.get("extraSegments");
        if (e instanceof List<?> l) {
            for (Object o : l) {
                String[] p = o.toString().split("\\*", -1);
                b.add(Segment.of(p[0], java.util.Arrays.copyOfRange(p, 1, p.length)));
            }
        }
    }
}
