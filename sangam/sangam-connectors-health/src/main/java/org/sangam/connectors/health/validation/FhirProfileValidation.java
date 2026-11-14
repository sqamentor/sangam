// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.validation;

import java.util.List;
import java.util.Objects;

/**
 * FHIR R4 profile validation (decision 183), framework-independent and free of HAPI types so that callers compile and
 * run without the optional HAPI jars. Obtain an instance from {@link FhirProfileValidations}; when HAPI is absent the
 * capability is missing and nothing is validated (fail closed, never "valid").
 *
 * <p>Profiles and terminology are whatever the instance was built with: the FHIR R4 base definitions always, plus the
 * conformance resources a tenant supplies (for example a US Core package for US tenants only). No profile is a
 * universal default.
 */
public interface FhirProfileValidation {

    /**
     * Validates one FHIR R4 resource given as JSON against the base definitions and every profile named in its
     * {@code meta.profile} that this instance knows.
     *
     * @param resourceJson the resource as JSON text, never null
     * @return the outcome; {@link Result#valid()} is false when any issue is ERROR or FATAL
     */
    Result validate(String resourceJson);

    /** Issue severity, as FHIR OperationOutcome {@code issue.severity}. */
    enum Severity { INFORMATION, WARNING, ERROR, FATAL }

    /**
     * One validation issue.
     *
     * @param severity  severity
     * @param location  FHIRPath-style location of the issue, or an empty string
     * @param messageId the validator's stable message id, or an empty string
     * @param message   human-readable text
     */
    record Issue(Severity severity, String location, String messageId, String message) {
        public Issue {
            Objects.requireNonNull(severity, "severity");
            location = location == null ? "" : location;
            messageId = messageId == null ? "" : messageId;
            message = message == null ? "" : message;
        }

        /** True for ERROR and FATAL. */
        public boolean blocking() {
            return severity == Severity.ERROR || severity == Severity.FATAL;
        }
    }

    /**
     * The validation outcome.
     *
     * @param issues every issue, in validator order
     */
    record Result(List<Issue> issues) {
        public Result {
            issues = List.copyOf(issues);
        }

        /** No ERROR or FATAL issue. */
        public boolean valid() {
            return issues.stream().noneMatch(Issue::blocking);
        }
    }
}
