// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.identity;

import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ComplianceException.Code;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.ports.CompliancePorts.NationalIdVault;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * National identifier protection: detection (pack regex + checksum), masking (keep the last digits only), redaction of
 * free text, and a guard that refuses raw numbers in workflow variables, replacing them by vault reference keys.
 * IN: Aadhaar (Verhoeff checksum, keep last 4) — Aadhaar Act s.29, Sharing of Information Regulations reg. 6, UIDAI
 * ADV circulars (CMP-140 … CMP-142).
 */
public final class NationalIdGuard {

    public record Finding(String type, String path, String masked) {
    }

    private final ComplianceConfig.Provider config;
    private final NationalIdVault vault;

    public NationalIdGuard(ComplianceConfig.Provider config, NationalIdVault vault) {
        this.config = config;
        this.vault = vault;
    }

    public List<Finding> scan(String tenantId, String text) {
        List<Finding> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (JurisdictionPack.NationalIdentifier id : config.forTenant(tenantId).pack.nationalIdentifiers()) {
            Matcher m = Pattern.compile(id.regex()).matcher(text);
            while (m.find()) {
                if (valid(id, m.group())) {
                    out.add(new Finding(id.code(), null, mask(id, m.group())));
                }
            }
        }
        return out;
    }

    public String redact(String tenantId, String text) {
        if (text == null) {
            return null;
        }
        String r = text;
        for (JurisdictionPack.NationalIdentifier id : config.forTenant(tenantId).pack.nationalIdentifiers()) {
            Matcher m = Pattern.compile(id.regex()).matcher(r);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(valid(id, m.group()) ? mask(id, m.group()) : m.group()));
            }
            m.appendTail(sb);
            r = sb.toString();
        }
        return r;
    }

    /** Raw identifiers in string variables, by variable name. */
    public List<Finding> scanVariables(String tenantId, Map<String, ?> variables) {
        List<Finding> out = new ArrayList<>();
        variables.forEach((k, v) -> {
            if (v instanceof CharSequence s) {
                scan(tenantId, s.toString()).forEach(f -> out.add(new Finding(f.type(), k, f.masked())));
            }
        });
        return out;
    }

    /** Replaces a raw identifier by its vault reference key; refuses when no vault is configured. */
    public String tokenise(String tenantId, String type, String number) {
        if (vault == null) {
            throw new ComplianceException(Code.NATIONAL_ID_NOT_TOKENISED, type + " numbers must be stored as vault reference keys; no vault configured");
        }
        return vault.referenceKey(tenantId, type, digits(number));
    }

    /** Refuses raw identifiers in a set of variables (call from a task listener or the REST layer). */
    public void requireNoRawIdentifiers(String tenantId, Map<String, ?> variables) {
        List<Finding> f = scanVariables(tenantId, variables);
        if (!f.isEmpty()) {
            throw new ComplianceException(Code.NATIONAL_ID_NOT_TOKENISED, "raw national identifier in variables " + f.stream().map(Finding::path).toList());
        }
    }

    public static String mask(JurisdictionPack.NationalIdentifier id, String number) {
        String d = digits(number);
        int keep = Math.min(id.keepLast(), d.length());
        return "X".repeat(d.length() - keep) + d.substring(d.length() - keep);
    }

    public static boolean valid(JurisdictionPack.NationalIdentifier id, String number) {
        String d = digits(number);
        return switch (id.checksum().toUpperCase()) {
            case "VERHOEFF" -> verhoeff(d);
            case "LUHN" -> luhn(d);
            default -> true;
        };
    }

    static String digits(String s) {
        return s.replaceAll("[^0-9]", "");
    }

    private static final int[][] D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5}, {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7}, {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2}, {7, 6, 5, 9, 8, 2, 1, 0, 4, 3}, {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};
    private static final int[][] P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4}, {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7}, {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5}, {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};

    static boolean verhoeff(String d) {
        if (d.isEmpty()) {
            return false;
        }
        int c = 0;
        for (int i = 0; i < d.length(); i++) {
            c = D[c][P[i % 8][d.charAt(d.length() - 1 - i) - '0']];
        }
        return c == 0;
    }

    static boolean luhn(String d) {
        int sum = 0;
        boolean alt = false;
        for (int i = d.length() - 1; i >= 0; i--) {
            int n = d.charAt(i) - '0';
            if (alt) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            alt = !alt;
        }
        return !d.isEmpty() && sum % 10 == 0;
    }
}
