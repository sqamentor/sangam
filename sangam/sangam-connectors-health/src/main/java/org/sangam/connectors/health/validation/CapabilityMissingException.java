// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.validation;

/**
 * An optional capability of the health connectors module is not installed (decisions 183 and 187). The message starts
 * with {@link #CODE} and names the capability; it never carries request data.
 */
public final class CapabilityMissingException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** Problem code shared with the host capability model. */
    public static final String CODE = "CAPABILITY_MISSING";

    private final String capability;

    public CapabilityMissingException(String capability, String reason) {
        super(CODE + ": " + capability + " (" + reason + ")");
        this.capability = capability;
    }

    /** The missing capability id. */
    public String capability() {
        return capability;
    }
}
