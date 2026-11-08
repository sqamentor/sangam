// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Outcome of the anti-spoofing and tamper checks on a packet or a piece of evidence.
 * Status: VERIFIED (all checks pass), FLAGGED (a check failed or warned — held for human review, never silently
 * accepted), REJECTED (the bytes do not match their own declared hash: the evidence is not what the device said).
 */
public record IntegrityReport(Status status, List<Check> checks) {

    public enum Status { VERIFIED, FLAGGED, REJECTED }

    public enum Outcome { PASS, WARN, FAIL }

    public record Check(String code, Outcome outcome, String detail) {
    }

    public static final class Builder {
        private final List<Check> checks = new ArrayList<>();
        private boolean rejected;

        public Builder pass(String code, String detail) {
            checks.add(new Check(code, Outcome.PASS, detail));
            return this;
        }

        public Builder warn(String code, String detail) {
            checks.add(new Check(code, Outcome.WARN, detail));
            return this;
        }

        public Builder fail(String code, String detail) {
            checks.add(new Check(code, Outcome.FAIL, detail));
            return this;
        }

        public Builder reject(String code, String detail) {
            rejected = true;
            return fail(code, detail);
        }

        public Builder add(Check c) {
            checks.add(c);
            return this;
        }

        public IntegrityReport build() {
            Status s = rejected ? Status.REJECTED
                    : checks.stream().anyMatch(c -> c.outcome() != Outcome.PASS) ? Status.FLAGGED : Status.VERIFIED;
            return new IntegrityReport(s, List.copyOf(checks));
        }
    }

    public List<Check> problems() {
        return checks.stream().filter(c -> c.outcome() != Outcome.PASS).toList();
    }

    public Map<String, Object> toMap() {
        return Map.of("status", status.name(), "checks", checks);
    }
}
