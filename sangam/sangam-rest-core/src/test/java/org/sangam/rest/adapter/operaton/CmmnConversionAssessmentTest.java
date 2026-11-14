// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.PackageToolsPort.AssessmentFile;
import org.sangam.rest.port.PackageToolsPort.AssessmentRow;

/**
 * M-127: the EC-21 assessment of a CMMN file keeps its "runs as is" row and adds the CMMN to BPMN conversion verdict
 * (MAPPED with a draft to review, REJECTED with one row per unsupported construct, PATTERN per warning). The
 * AssessmentRow shape and its classification values are unchanged.
 */
class CmmnConversionAssessmentTest {

    static byte[] cmmn(String caseBody) {
        return ("<definitions xmlns=\"http://www.omg.org/spec/CMMN/20151109/MODEL\" id=\"defs\""
                + " targetNamespace=\"http://example/cmmn\"><case id=\"loan\" name=\"Loan\">" + caseBody
                + "</case></definitions>").getBytes(StandardCharsets.UTF_8);
    }

    static List<AssessmentRow> assess(byte[] content) {
        return new OperatonPackageToolsAdapter(null, null, null, null).assess(null,
                List.of(new AssessmentFile("loan.cmmn", content)));
    }

    static AssessmentRow row(List<AssessmentRow> rows, String construct) {
        return rows.stream().filter(r -> construct.equals(r.construct())).findFirst()
                .orElseThrow(() -> new AssertionError(construct + " missing in " + rows));
    }

    @Test
    void theConverterIsOnTheTestClassPath() {
        assertTrue(CmmnConversionRows.AVAILABLE, "sangam-modeller is an optional dependency of sangam-rest");
    }

    @Test
    void aConvertibleCaseIsMappedToABpmnDraft() {
        List<AssessmentRow> rows = assess(cmmn("<casePlanModel id=\"plan\" autoComplete=\"true\">"
                + "<planItem id=\"check\" definitionRef=\"checkDef\"/><humanTask id=\"checkDef\" name=\"Check\"/>"
                + "</casePlanModel>"));
        assertEquals("SUPPORTED", row(rows, "cmmn:definitions").classification());
        AssessmentRow verdict = row(rows, "cmmn:toBpmn");
        assertEquals("MAPPED", verdict.classification());
        assertEquals("defs", verdict.element());
        assertTrue(verdict.note().contains("reviewed"), verdict.note());
        assertTrue(rows.stream().noneMatch(r -> "REJECTED".equals(r.classification())), rows.toString());
    }

    @Test
    void anUnsupportedConstructRejectsTheConversionOnly() {
        List<AssessmentRow> rows = assess(cmmn("<casePlanModel id=\"plan\">"
                + "<planItem id=\"a\" definitionRef=\"d\"><itemControl><repetitionRule/></itemControl></planItem>"
                + "<humanTask id=\"d\"/></casePlanModel>"));
        assertEquals("SUPPORTED", row(rows, "cmmn:definitions").classification());
        AssessmentRow verdict = row(rows, "cmmn:toBpmn");
        assertEquals("REJECTED", verdict.classification());
        assertTrue(verdict.note().contains("outside the converter's subset"), verdict.note());
        assertEquals("REJECTED", row(rows, "cmmn:toBpmn CMMN-UNSUPPORTED-REPETITION").classification());
    }

    @Test
    void aWarningIsAPatternToReview() {
        List<AssessmentRow> rows = assess(cmmn("<caseRoles id=\"roles\"><role id=\"clerk\" name=\"Clerk\"/></caseRoles>"
                + "<casePlanModel id=\"plan\"><planItem id=\"a\" definitionRef=\"d\"/><humanTask id=\"d\"/></casePlanModel>"));
        assertEquals("MAPPED", row(rows, "cmmn:toBpmn").classification());
        AssessmentRow roles = row(rows, "cmmn:toBpmn CMMN-CASE-ROLES");
        assertEquals("PATTERN", roles.classification());
        assertEquals("loan", roles.element());
    }
}
