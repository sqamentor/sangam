// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.common;

import java.util.List;

/** A refused configuration or rule operation, with the findings (and their rule citations) that refused it. */
public class ConfigException extends RuntimeException {

    private final String code;
    private final transient List<Finding> findings;

    public ConfigException(String code, String message) {
        this(code, message, List.of());
    }

    public ConfigException(String code, String message, List<Finding> findings) {
        super(code + ": " + message);
        this.code = code;
        this.findings = List.copyOf(findings);
    }

    public String code() {
        return code;
    }

    public List<Finding> findings() {
        return findings;
    }
}
