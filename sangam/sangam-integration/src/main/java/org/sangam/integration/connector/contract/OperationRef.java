// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.contract;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M-068: the value of {@code sangam:operationRef} on a task, {@code contractId[@version]#operationId}: an operation of an
 * OpenAPI or AsyncAPI document stored for the package under {@code contractId}. Without a version the latest stored
 * version is meant; a deployed model should pin one (the deployment resolves and records it).
 */
public record OperationRef(String contractId, Integer version, String operationId) {

    private static final Pattern FORM =
            Pattern.compile("([A-Za-z0-9_.-]{1,120})(?:@([0-9]{1,9}))?#([A-Za-z0-9_.-]{1,200})");

    public OperationRef {
        if (contractId == null || !contractId.matches("[A-Za-z0-9_.-]{1,120}")) {
            throw new IllegalArgumentException("operationRef: invalid contract id");
        }
        if (operationId == null || !operationId.matches("[A-Za-z0-9_.-]{1,200}")) {
            throw new IllegalArgumentException("operationRef: invalid operation id");
        }
        if (version != null && version < 1) {
            throw new IllegalArgumentException("operationRef: version must be 1 or more");
        }
    }

    /** Parses {@code contractId[@version]#operationId}; anything else is refused. */
    public static OperationRef parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("operationRef is missing");
        }
        Matcher m = FORM.matcher(value.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("operationRef must be contractId[@version]#operationId: " + value);
        }
        return new OperationRef(m.group(1), m.group(2) == null ? null : Integer.valueOf(m.group(2)), m.group(3));
    }

    /** The same reference pinned to {@code v}. */
    public OperationRef pinned(int v) {
        return new OperationRef(contractId, v, operationId);
    }

    @Override
    public String toString() {
        return contractId + (version == null ? "" : "@" + version) + "#" + operationId;
    }
}
