// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.List;

import org.sangam.modeller.cmmn.CmmnConversion;
import org.sangam.modeller.cmmn.CmmnToBpmnConverter;
import org.sangam.rest.port.PackageToolsPort.AssessmentRow;

/**
 * M-127 (R45 / E16): the CMMN to BPMN conversion verdict as assessment rows (EC-21), next to the row that says the
 * CMMN model runs as it is on Operaton.
 * <ul>
 *   <li>{@code cmmn:toBpmn}: MAPPED when the model is in the converter's bounded subset (a BPMN draft exists and
 *       needs review), REJECTED when it is not (handle it manually or let its cases run off on the CMMN engine);</li>
 *   <li>{@code cmmn:toBpmn <code>}: one row per finding: an ERROR is REJECTED, a WARNING (converted, behaviour to
 *       review) is PATTERN; INFO notes are left out.</li>
 * </ul>
 * sangam-modeller is an optional dependency of sangam-rest. This class is the only one in the adapter that links it,
 * and {@link OperatonPackageToolsAdapter} touches it only after {@link #AVAILABLE} says the converter is on the class
 * path, so the adapter loads without the module.
 */
final class CmmnConversionRows {

    static final String CONVERTER = "org.sangam.modeller.cmmn.CmmnToBpmnConverter";

    /** True when sangam-modeller (the converter) is on the class path. */
    static final boolean AVAILABLE = present();

    private CmmnConversionRows() {
    }

    private static boolean present() {
        try {
            Class.forName(CONVERTER, false, CmmnConversionRows.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Adds the conversion rows of the CMMN file {@code file}. Call only when {@link #AVAILABLE}. */
    static void add(String file, byte[] content, String definitionsId, List<AssessmentRow> rows) {
        CmmnConversion c = CmmnToBpmnConverter.convert(file, content);
        long errors = c.errors().size();
        rows.add(c.converted()
                ? new AssessmentRow(file, definitionsId, "cmmn:toBpmn", "MAPPED", "converts to a BPMN draft that"
                        + " must be reviewed before deployment (M-127); " + c.mapping().size()
                        + " plan items mapped to BPMN activities of the same ids")
                : new AssessmentRow(file, definitionsId, "cmmn:toBpmn", "REJECTED", "not converted to BPMN: " + errors
                        + (errors == 1 ? " construct is" : " constructs are") + " outside the converter's subset;"
                        + " convert by hand or let the cases run off on the CMMN engine (M-127)"));
        for (CmmnConversion.Diagnostic d : c.diagnostics()) {
            String classification = switch (d.severity()) {
                case ERROR -> "REJECTED";
                case WARNING -> "PATTERN";
                case INFO -> null;
            };
            if (classification != null) {
                rows.add(new AssessmentRow(file, d.elementId().isEmpty() ? definitionsId : d.elementId(),
                        "cmmn:toBpmn " + d.code(), classification, d.message()));
            }
        }
    }
}
