// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.config;

/** Endpoint permissions of {@code /api/v1/config} (sangam-config). */
public final class ConfigPermissions {

    /** Resolve profiles, read versions, master codes, catalogues, validate. */
    public static final String READ = "config.read";
    /** Maker: save drafts, submit, seed master codes, apply correction slips. */
    public static final String WRITE = "config.write";
    /** Checker: approve, reject, activate (maker-checker is enforced by ConfigAdminService). */
    public static final String ADMIN = "config.admin";

    private ConfigPermissions() {
    }
}
