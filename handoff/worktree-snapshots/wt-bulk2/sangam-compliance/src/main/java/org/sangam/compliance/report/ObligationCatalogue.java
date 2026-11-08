// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.report;

import org.sangam.compliance.jurisdiction.JurisdictionPack;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The pack's obligation catalogue ({@code obligations} TSV: id, citation, bearer, check, status, text). {@code bearer}
 * is SANGAM, SHARED, DF (deployer) or an elevated-tier marker; {@code check} names the evidence area the report uses
 * to judge the obligation automatically; {@code status} is the research verification mark. IN: CMP-001 … CMP-153 from
 * spike/results/COMPLIANCE-REQUIREMENTS.md.
 */
public final class ObligationCatalogue {

    public record Obligation(String id, String citation, String bearer, String check, String verification, String text) {
    }

    private ObligationCatalogue() {
    }

    public static List<Obligation> load(JurisdictionPack pack) {
        List<Obligation> out = new ArrayList<>();
        if (pack.obligationsPath() == null) {
            return out;
        }
        try (InputStream in = ObligationCatalogue.class.getClassLoader().getResourceAsStream(pack.obligationsPath())) {
            if (in == null) {
                return out;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] c = line.split("\t", -1);
                if (c.length >= 6) {
                    out.add(new Obligation(c[0], c[1], c[2], c[3], c[4], c[5]));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(out);
    }
}
