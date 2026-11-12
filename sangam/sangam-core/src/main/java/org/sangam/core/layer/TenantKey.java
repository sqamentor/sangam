// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

/**
 * The tenant part of a product-table key (SL-86).
 *
 * <p>Two concepts are kept apart:
 * <ul>
 *   <li><b>Shared</b>: a row that belongs to no tenant (a shared or pack definition, the shared configuration fallback,
 *       the key of untenanted work in case, programme, ports and the pack ledgers). Its key is {@link #SHARED}, one fixed
 *       reserved value: {@link AbsentKey#NONE}. It used to be {@code ''}, which Oracle stores as NULL (ORA-01400 in the
 *       NOT NULL key; {@code tenant_id = ''} never matches).</li>
 *   <li><b>Attribution</b>: untenanted <i>work</i> that the engine, integration, geo, modeller or packs charge to a
 *       tenant uses the configured default tenant ({@code sangam.tenant.default}, resolved once in sangam-server). That
 *       value may be a real tenant and is not handled here.</li>
 * </ul>
 * {@link #SHARED} can never be a real tenant id: every tenant entry point refuses it ({@link #requireNotShared}); the
 * control plane's tenant-code grammar already excludes it.
 */
public final class TenantKey {

    /** Stored tenant key of a row that belongs to no tenant. */
    public static final String SHARED = AbsentKey.NONE;

    private TenantKey() {
    }

    /** The key to bind: {@link #SHARED} for no tenant (null or blank), otherwise the tenant id. */
    public static String of(String tenantOrNull) {
        return tenantOrNull == null || tenantOrNull.isBlank() ? SHARED : tenantOrNull;
    }

    /**
     * The engine's tenant for a stored key: null (no engine tenant) for {@link #SHARED}. A blank key also reads as no
     * tenant, so rows written before the migration read the same way.
     */
    public static String toEngine(String key) {
        return key == null || key.isBlank() || SHARED.equals(key) ? null : key;
    }

    public static boolean isShared(String key) {
        return toEngine(key) == null;
    }

    /** Refuses the reserved shared key as a real tenant id (token claim, onboarding, engine identity). */
    public static String requireNotShared(String tenantId) {
        if (SHARED.equals(tenantId)) {
            throw new IllegalArgumentException("'" + SHARED + "' is reserved for rows shared by all tenants and cannot be a tenant id");
        }
        return tenantId;
    }
}
