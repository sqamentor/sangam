// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.List;

/**
 * Owner decisions 145, 199 and 381(3): the migration of running instances that the removed {@code tenantId} variable
 * fallback carried. {@link #inventory} lists the root instances the engine-tenant-only rule refuses (read-only);
 * {@link #repair} moves one of them to the tenant an operator names on authoritative ownership evidence, through the
 * change-tenant cut-over, and audits it. Platform operation (it spans tenants); the server binds it with the engine.
 */
public interface TenantMigrationPort {

    /** A tenant variable of an instance as stored: untrusted business data, never a tenant source. */
    record TenantClaim(String variableName, String value) {
    }

    /**
     * One refused root instance. {@code finding} is the refusal code ({@code TENANT_CONTEXT_MISSING} or
     * {@code TENANT_CONTEXT_CONFLICT}); {@code definitionTenant} is the deployment tenant of its definition, evidence
     * only.
     */
    record TenantFallbackInstance(String processInstanceId, String definitionKey, String engineTenant,
                                  String definitionTenant, String finding, boolean reliedOnTheFallback,
                                  List<TenantClaim> claims) {
    }

    /**
     * Repair of one instance: the tenant that owns it, the reference of the ownership evidence (a record id, file or
     * ticket the operator checked; required) and the reason.
     */
    record TenantRepairRequest(String targetTenant, String evidenceRef, String reason) {
    }

    /** The cut-over: the old instance is cancelled, the new one runs in {@code targetTenant} at the same activities. */
    record TenantRepairResult(String processInstanceId, String newProcessInstanceId, String fromTenant,
                              String targetTenant, String finding, String evidenceRef) {
    }

    List<TenantFallbackInstance> inventory(RequestContext ctx);

    TenantRepairResult repair(RequestContext ctx, String processInstanceId, TenantRepairRequest request);
}
