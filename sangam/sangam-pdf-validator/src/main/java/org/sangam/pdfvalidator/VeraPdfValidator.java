// SPDX-License-Identifier: Apache-2.0
package org.sangam.pdfvalidator;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.sangam.integration.document.pdf.PdfRenderer;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.VeraPDFFoundry;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;
import org.verapdf.pdfa.validation.profiles.RuleId;

/**
 * The validation step of M-081 (owner decision 95): the rendered bytes are checked by veraPDF, the reference
 * PDF/A and PDF/UA validator (greenfield parser, MPL-2.0 option of its dual licence), once against the PDF/A-2b
 * profile and once against the PDF/UA-1 profile (ISO 14289-1 machine checks). It reads the bytes with its own parser,
 * never the renderer's. Owner decisions 114 and 119: it runs only inside the separate validator service
 * ({@link PdfValidatorService}, image {@code deploy/docker/pdf-validator.Dockerfile}), because veraPDF needs Rhino and
 * Rhino never enters the server; the server reaches it through {@code RemotePdfValidator}. It is deliberately not a
 * {@link java.util.ServiceLoader} provider. Fail closed: a parse or validation error is a finding, never a pass.
 */
public final class VeraPdfValidator implements PdfRenderer.Validator {

    /** At most this many failed checks are listed per profile; the count of the rest is given. */
    static final int MAX_FINDINGS = 50;

    private static final class Foundry {
        static final VeraPDFFoundry INSTANCE;

        static {
            VeraGreenfieldFoundryProvider.initialise();
            INSTANCE = Foundries.defaultInstance();
        }
    }

    public VeraPdfValidator() {
        // ServiceLoader constructor; the veraPDF foundry is initialised on first use
    }

    /** {@code veraPDF} with the library version when the jar declares one. */
    public static String name() {
        String v = VeraGreenfieldFoundryProvider.class.getPackage() == null ? null
                : VeraGreenfieldFoundryProvider.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "veraPDF" : "veraPDF " + v;
    }

    static PDFAFlavour flavour(PdfRenderer.Conformance c) {
        return switch (c) {
            case PDF_A_2B -> PDFAFlavour.PDFA_2_B;
            case PDF_UA_1 -> PDFAFlavour.PDFUA_1;
        };
    }

    @Override
    public PdfRenderer.Report validate(byte[] pdf, Set<PdfRenderer.Conformance> required) {
        List<String> findings = new ArrayList<>();
        if (pdf == null || pdf.length == 0) {
            findings.add("PARSE: no bytes");
            return new PdfRenderer.Report(false, name(), findings);
        }
        for (PdfRenderer.Conformance c : PdfRenderer.Conformance.values()) {
            if (required.contains(c)) {
                check(pdf, c, findings);
            }
        }
        return new PdfRenderer.Report(findings.isEmpty(), name(), findings);
    }

    private static void check(byte[] pdf, PdfRenderer.Conformance c, List<String> findings) {
        PDFAFlavour flavour = flavour(c);
        try (PDFAParser parser = Foundry.INSTANCE.createParser(new ByteArrayInputStream(pdf), flavour);
             PDFAValidator validator = Foundry.INSTANCE.createValidator(flavour, false)) {
            ValidationResult result = validator.validate(parser);
            if (result.isCompliant()) {
                return;
            }
            int listed = 0;
            int failed = 0;
            for (TestAssertion a : result.getTestAssertions()) {
                if (a.getStatus() != TestAssertion.Status.FAILED) {
                    continue;
                }
                failed++;
                if (listed < MAX_FINDINGS) {
                    findings.add(c + " " + rule(a.getRuleId()) + ": " + a.getMessage()
                            + (a.getLocationContext() == null ? "" : " at " + a.getLocationContext()));
                    listed++;
                }
            }
            if (failed > listed) {
                findings.add(c + ": " + (failed - listed) + " more failed checks");
            }
            if (failed == 0) {
                findings.add(c + ": not compliant (" + result.getJobEndStatus() + ")");
            }
        } catch (Exception e) {
            findings.add(c + " PARSE: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String rule(RuleId id) {
        if (id == null) {
            return "?";
        }
        String spec = id.getSpecification() == null ? "?" : id.getSpecification().name();
        return spec + " " + id.getClause() + "-" + id.getTestNumber();
    }
}
