// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.tenant;

import java.util.regex.Pattern;

import org.sangam.core.layer.TenantKey;

/**
 * Owner decision 144: the audit scope of genuinely shared or system-owned engine work, and the one authoritative check
 * that it is not an ordinary customer tenant. Framework-free.
 *
 * <p>A system audit scope is {@value #PREFIX} followed by a name of lower-case letters, digits, {@code .}, {@code _}
 * and {@code -} ({@code system:audit}, {@code system:engine-eu}). It can never be a customer tenant:
 * <ul>
 *   <li>the platform creates tenants only with ids of the grammar {@code [A-Za-z0-9][A-Za-z0-9_.-]{0,59}}
 *       (TenantAdminService, OnboardingService), which has no {@code :};</li>
 *   <li>every authenticated identity whose tenant is a system scope or the shared key is refused at the entry
 *       ({@link #requireTenantId}, called by the server's identity filter), so no caller ever acts in, or reads as, the
 *       system scope through a tenant path;</li>
 *   <li>the shared row key {@link TenantKey#SHARED} is not a scope: it marks rows that belong to no tenant and carries
 *       no audit chain of its own.</li>
 * </ul>
 * The configured scope ({@code systemAuditScope} of the engine settings) has no default: {@link #validate} refuses a
 * missing value as well as an invalid one, so the start is refused.
 */
public final class SystemAuditScope {

    /** Reserved prefix of every system audit scope. */
    public static final String PREFIX = "system:";

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,47}");

    private SystemAuditScope() {
    }

    /**
     * The configured system audit scope, unchanged when valid.
     *
     * @param setting the name of the setting, for the message (never a secret)
     * @throws IllegalStateException for a missing, blank or invalid value, the shared key, or a value that could be a
     *                               customer tenant (no {@value #PREFIX} prefix)
     */
    public static String validate(String configured, String setting) {
        String key = setting == null || setting.isBlank() ? "systemAuditScope" : setting;
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(key + " is not set: decision 144 needs an explicitly configured system audit"
                    + " scope (" + PREFIX + "<name>); the start is refused");
        }
        if (!isSystemScope(configured)) {
            throw new IllegalStateException(key + " must be " + PREFIX + "<name> (lower-case letters, digits, '.', '_',"
                    + " '-'; at most 48): a value without the reserved prefix could be a customer tenant (decision 144);"
                    + " the start is refused");
        }
        return configured;
    }

    /** Whether {@code id} is a well-formed system audit scope. */
    public static boolean isSystemScope(String id) {
        return id != null && id.startsWith(PREFIX) && NAME.matcher(id.substring(PREFIX.length())).matches();
    }

    /** Whether {@code id} uses the reserved prefix at all (well-formed or not). */
    public static boolean isReserved(String id) {
        return id != null && (id.startsWith(PREFIX) || TenantKey.SHARED.equals(id));
    }

    /**
     * The tenant-id check of every identity entry: the shared key and anything with the reserved {@value #PREFIX}
     * prefix are refused as a tenant id.
     *
     * @return {@code tenantId}, for chaining
     * @throws IllegalArgumentException for a reserved id (the message names no value)
     */
    public static String requireTenantId(String tenantId) {
        if (isReserved(tenantId)) {
            throw new IllegalArgumentException("a reserved id (the shared key or a " + PREFIX
                    + " system audit scope) cannot be a tenant id (decision 144)");
        }
        return tenantId;
    }
}
