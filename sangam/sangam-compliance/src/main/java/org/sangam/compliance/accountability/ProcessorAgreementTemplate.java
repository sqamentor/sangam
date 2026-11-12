// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.accountability;

import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.jurisdiction.JurisdictionPack;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The data-processor agreement template of a jurisdiction pack (M-087; IN: DPDP Act s.8(2) "valid contract", rule
 * 6(1)(f) security-safeguard clauses, CMP-020 and CMP-059). Pack data: {@code <processorAgreementTemplates>/
 * processor-agreement_<lang>.md} in the {@code PdfMarkup} text form (headings, paragraphs, one table), so the same text
 * renders as PDF/A through the M-081 renderer. Placeholders are {@code {{name}}}; {@link #fill} refuses a missing or
 * blank value rather than issuing an agreement with gaps. The first release ships English only (owner decision 36).
 */
public record ProcessorAgreementTemplate(String jurisdiction, String language, String text) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    /** The pack's template in {@code language}, or empty when the pack ships none in that language. */
    public static Optional<ProcessorAgreementTemplate> bundled(JurisdictionPack pack, String language) {
        if (pack.processorAgreementPath() == null || language == null || !language.matches("[a-z]{2,3}")) {
            return Optional.empty();
        }
        String res = pack.processorAgreementPath() + "/processor-agreement_" + language + ".md";
        try (InputStream in = ProcessorAgreementTemplate.class.getClassLoader().getResourceAsStream(res)) {
            return in == null ? Optional.empty()
                    : Optional.of(new ProcessorAgreementTemplate(pack.code(), language, new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The placeholder names, in order of first use. */
    public List<String> placeholders() {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return new ArrayList<>(out);
    }

    /** The agreement text with every placeholder filled; a missing or blank value is refused with the list of names. */
    public String fill(Map<String, String> values) {
        List<String> missing = placeholders().stream()
                .filter(p -> values == null || values.get(p) == null || values.get(p).isBlank()).toList();
        if (!missing.isEmpty()) {
            throw ComplianceException.invalid("the processor agreement needs values for " + missing);
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(values.get(m.group(1)).strip()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
