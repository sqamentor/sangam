// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.common;

import java.util.ArrayList;
import java.util.List;

/**
 * One validation or rule outcome. Every rule outcome carries the legal citation it rests on (IFC-016, IFS-067: "every
 * validation message and objection memo cites the rule").
 *
 * @param severity ERROR blocks, WARNING informs, PLACEHOLDER marks an [U] value that must be confirmed before go-live
 * @param code     stable machine code, e.g. {@code REAPP_CHARGED_VOTED}
 * @param path     profile path or field the finding is about (may be null)
 * @param message  English message (UI translates by code)
 * @param ruleRef  legal source, e.g. "DFPR 2024 r.10(4)"
 */
public record Finding(Severity severity, String code, String path, String message, String ruleRef) {

    public enum Severity { ERROR, WARNING, PLACEHOLDER, INFO }

    public static Finding error(String code, String path, String message, String ruleRef) {
        return new Finding(Severity.ERROR, code, path, message, ruleRef);
    }

    public static Finding warn(String code, String path, String message, String ruleRef) {
        return new Finding(Severity.WARNING, code, path, message, ruleRef);
    }

    public static Finding info(String code, String path, String message, String ruleRef) {
        return new Finding(Severity.INFO, code, path, message, ruleRef);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    public static boolean anyError(List<Finding> findings) {
        return findings.stream().anyMatch(Finding::isError);
    }

    public static List<Finding> errors(List<Finding> findings) {
        List<Finding> out = new ArrayList<>();
        for (Finding f : findings) {
            if (f.isError()) {
                out.add(f);
            }
        }
        return out;
    }
}
