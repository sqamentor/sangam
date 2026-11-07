// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.api;

import java.util.List;

/**
 * One finding of an onboarding step, the readiness checklist or the dry run.
 *
 * @param severity ERROR blocks go-live (and the step, unless it is advisory), WARNING is reported, INFO is a note
 * @param code     stable machine code ({@code PLACEHOLDER_USED}, {@code CONNECTOR_UNBOUND}, ...)
 * @param step     the step that found it
 * @param subject  what it is about: a pack id, a connector id, a profile path, a decision key ...
 * @param message  human-readable explanation with the fix
 */
public record Problem(Severity severity, String code, StepId step, String subject, String message) {

    public enum Severity { ERROR, WARNING, INFO }

    public static Problem error(StepId step, String code, String subject, String message) {
        return new Problem(Severity.ERROR, code, step, subject, message);
    }

    public static Problem warn(StepId step, String code, String subject, String message) {
        return new Problem(Severity.WARNING, code, step, subject, message);
    }

    public static Problem info(StepId step, String code, String subject, String message) {
        return new Problem(Severity.INFO, code, step, subject, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    public static boolean anyError(List<Problem> problems) {
        return problems.stream().anyMatch(Problem::isError);
    }
}
