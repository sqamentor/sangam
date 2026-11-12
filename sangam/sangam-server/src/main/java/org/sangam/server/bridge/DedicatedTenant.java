// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * M-016 (owner decision 51, Q-02 option A): database per tenant is realised as one server instance bound to one tenant
 * database, with its own outbox, relay, inbox, idempotency store and Liquibase run, placed and routed by the ADR-D-21
 * cell control plane (one engine per JVM). This value is the binding of such an instance to its tenant, set by
 * {@value #PROPERTY}; without the property nothing here is active and the shared single-database default is unchanged.
 *
 * <p>The rules are fail-closed (owner architecture directive 2026-11-12): an instance bound to one tenant never serves
 * another tenant, and it refuses to start when its configuration or its database contradict the binding. Plain Java (no
 * framework type), so a future host adapter reuses it unchanged.
 */
public final class DedicatedTenant {

    /** The tenant this instance and its database are dedicated to. */
    public static final String PROPERTY = "sangam.tenancy.dedicated-tenant";

    private final String tenant;

    private DedicatedTenant(String tenant) {
        this.tenant = tenant;
    }

    /**
     * @param configured the value of {@value #PROPERTY}
     * @throws IllegalStateException when it is blank or the platform wildcard {@code *}
     */
    public static DedicatedTenant of(String configured) {
        String t = configured == null ? "" : configured.trim();
        if (t.isEmpty() || "*".equals(t)) {
            throw new IllegalStateException(PROPERTY + " must name one tenant (got '" + (configured == null ? "" : configured)
                    + "'); remove the property for a shared instance");
        }
        return new DedicatedTenant(t);
    }

    public String tenant() {
        return tenant;
    }

    /** True when this instance may serve {@code requestTenant}; a request without a tenant is not tenant data. */
    public boolean admits(String requestTenant) {
        return requestTenant == null || requestTenant.isBlank() || tenant.equals(requestTenant.trim());
    }

    /**
     * The attribution tenant (untenanted engine, integration and packs work, {@code AttributionTenant}) must be the
     * bound tenant: otherwise such rows would be charged to a tenant that does not live in this database.
     *
     * @throws IllegalStateException naming both properties
     */
    public void requireAttribution(String attributionTenant) {
        if (!tenant.equals(attributionTenant)) {
            throw new IllegalStateException(PROPERTY + "=" + tenant + " needs sangam.tenant.default=" + tenant
                    + " (SANGAM_TENANT_DEFAULT): untenanted work is charged to the attribution tenant, which is now '"
                    + attributionTenant + "' and does not live in this tenant's database");
        }
    }

    /** The property that makes a server a cell (ADR-D-21; {@code CellWiring}). */
    public static final String CELL_PROPERTY = "sangam.cell.id";

    /**
     * A dedicated instance is a cell (decision 51: placed and routed by the control plane). The cell wiring is also
     * what gates the broker inbox per tenant, so without it a message of another tenant would be stored in this
     * tenant's database.
     *
     * @param cellId the value of {@value #CELL_PROPERTY}
     * @throws IllegalStateException when it is blank
     */
    public void requireCell(String cellId) {
        if (cellId == null || cellId.isBlank()) {
            throw new IllegalStateException(PROPERTY + "=" + tenant + " needs " + CELL_PROPERTY + " (SANGAM_CELL_ID): a"
                    + " dedicated instance is a cell placed by the control plane, and the cell wiring gates the broker"
                    + " inbox per tenant");
        }
    }

    /**
     * The tenant directory of this instance's database may hold the bound tenant only (or nothing yet, before
     * onboarding). Another tenant means the database is shared, and serving it as dedicated would mix tenants.
     *
     * @throws IllegalStateException listing the other tenants
     */
    public void requireDirectory(Collection<String> directoryTenants) {
        List<String> others = directoryTenants.stream().filter(Objects::nonNull).map(String::trim)
                .filter(t -> !t.isEmpty() && !tenant.equals(t)).distinct().sorted().toList();
        if (!others.isEmpty()) {
            throw new IllegalStateException(PROPERTY + "=" + tenant + " but this instance's database also holds tenant(s) "
                    + others + ": a dedicated instance needs a database of its own (move the other tenants to a shared"
                    + " cell first, or remove the property)");
        }
    }

    /** The tenant directory's isolation value that promises a database of the tenant's own (AdminPort.Tenant). */
    public static final String DATABASE = "DATABASE";

    /**
     * Once the bound tenant exists in the directory, its recorded isolation must be {@value #DATABASE}: the directory
     * then says what this instance actually provides, and a tenant recorded as SHARED or SCHEMA is not silently served
     * from a dedicated database (nor the other way round).
     *
     * @param isolation the bound tenant's recorded isolation (SHARED | SCHEMA | DATABASE)
     * @throws IllegalStateException for any other value
     */
    public void requireIsolation(String isolation) {
        if (!DATABASE.equals(isolation)) {
            throw new IllegalStateException(PROPERTY + "=" + tenant + " but the tenant directory records isolation '"
                    + isolation + "' for it; set the tenant's isolation to " + DATABASE + " (tenant admin) or remove the"
                    + " property");
        }
    }

    @Override
    public String toString() {
        return "DedicatedTenant[" + tenant + "]";
    }
}
