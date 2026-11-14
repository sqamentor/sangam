// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.adapter.memory.GroupPermissionAdapter;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;

/**
 * Owner decision 273: a stored bulk command response ({@code bulk.job.create}, {@code bulk.chunk.upload},
 * {@code bulk.job.close}, {@code bulk.job.abort}) is replayed only to a caller who still holds the stored job's sink
 * permission, resolved through {@link BulkJobPermissions} from the {@code bulk-job:<id>} resource ref in the caller's
 * tenant. An unknown job, another tenant's job, a missing or malformed ref, a failing resolver and a bridge without a
 * resolver all refuse. Written for the test phase (not run here).
 */
class BulkReplayPermissionTest {

    /** A module-admin permission (Permissions.MODULE_ADMIN): the tenant admin persona holds it, the operator does not. */
    private static final String OPS_TENANT_ADMIN = "ops.tenant.admin";

    private static final List<String> BULK = List.of("bulk.job.create", "bulk.chunk.upload", "bulk.job.close",
            "bulk.job.abort");

    /** Tenant t1, job j1: the tenant-variable sink (ops.tenant.admin, as the contract's example); j2: a sink holding
     * process.write. Another tenant's job and unknown jobs resolve to nothing, as the agreed contract says. */
    private static final Map<String, String> T1_JOBS = Map.of("j1", OPS_TENANT_ADMIN, "j2",
            Permissions.PROCESS_WRITE);

    private final List<String> lookups = new ArrayList<>();
    private final BulkJobPermissions resolver = (tenant, job) -> {
        lookups.add(tenant + "/" + job);
        return "t1".equals(tenant) ? Optional.ofNullable(T1_JOBS.get(job)) : Optional.empty();
    };

    private final SecurityPermissionBridge bridge = new SecurityPermissionBridge(null, null, new GroupPermissionAdapter(),
            resolver);

    private static RequestContext ctx(String tenant, String... groups) {
        return new RequestContext(tenant, "p1", Set.of(groups), null, null, null, null, null, "OIDC");
    }

    @Test
    void aHolderOfTheSinkPermissionGetsTheReplay() {
        RequestContext operator = ctx("t1", Permissions.GROUP_OPERATOR);   // holds process.write
        RequestContext opsAdmin = ctx("t1", Permissions.GROUP_TENANT_ADMIN);
        for (String type : BULK) {
            assertTrue(bridge.mayReplay(operator, type, "bulk-job:j2"), type);
            assertTrue(bridge.mayReplay(opsAdmin, type, "bulk-job:j1"), type);
        }
        assertTrue(lookups.contains("t1/j2"), "the job is resolved in the caller's tenant");
    }

    @Test
    void withoutTheSinkPermissionTheReplayIsRefused() {
        RequestContext auditor = ctx("t1", Permissions.GROUP_AUDITOR);     // neither process.write nor ops.tenant.admin
        RequestContext worker = ctx("t1", Permissions.GROUP_WORKER);
        for (String type : BULK) {
            assertFalse(bridge.mayReplay(auditor, type, "bulk-job:j1"), type);
            assertFalse(bridge.mayReplay(auditor, type, "bulk-job:j2"), type);
            assertFalse(bridge.mayReplay(worker, type, "bulk-job:j2"), type);
            // holding another job's sink permission does not help: the operator holds j2's, not j1's
            assertFalse(bridge.mayReplay(ctx("t1", Permissions.GROUP_OPERATOR), type, "bulk-job:j1"), type);
        }
    }

    @Test
    void anUnknownJobOrAnotherTenantsJobIsRefused() {
        RequestContext operator = ctx("t1", Permissions.GROUP_OPERATOR);
        RequestContext otherTenant = ctx("t2", Permissions.GROUP_OPERATOR);
        for (String type : BULK) {
            assertFalse(bridge.mayReplay(operator, type, "bulk-job:nope"), type);
            assertFalse(bridge.mayReplay(otherTenant, type, "bulk-job:j2"), type);
        }
        assertTrue(lookups.contains("t2/j2"), "another tenant's caller looks the job up in its own tenant only");
    }

    @Test
    void aMissingOrMalformedResourceRefIsRefusedWithoutALookup() {
        RequestContext operator = ctx("t1", Permissions.GROUP_OPERATOR);
        for (String ref : new String[] {null, "", "j2", "bulk-job:", "bulk-job: ", "bulk-job:j2,j1", "job:j2",
                "BULK-JOB:j2"}) {
            assertFalse(bridge.mayReplay(operator, "bulk.chunk.upload", ref), String.valueOf(ref));
        }
        assertEquals(List.of(), lookups);
        assertFalse(bridge.mayReplay(ctx(null, Permissions.GROUP_OPERATOR), "bulk.chunk.upload", "bulk-job:j2"),
                "no tenant");
    }

    @Test
    void aMissingOrFailingResolverRefuses() {
        RequestContext operator = ctx("t1", Permissions.GROUP_OPERATOR);
        SecurityPermissionBridge none = new SecurityPermissionBridge(null, null, new GroupPermissionAdapter());
        SecurityPermissionBridge noneExplicit = new SecurityPermissionBridge(null, null, new GroupPermissionAdapter(),
                (BulkJobPermissions) null);
        SecurityPermissionBridge failing = new SecurityPermissionBridge(null, null, new GroupPermissionAdapter(),
                (tenant, job) -> {
                    throw new IllegalStateException("store down");
                });
        SecurityPermissionBridge nullAnswer = new SecurityPermissionBridge(null, null, new GroupPermissionAdapter(),
                (tenant, job) -> null);
        SecurityPermissionBridge blank = new SecurityPermissionBridge(null, null, new GroupPermissionAdapter(),
                (tenant, job) -> Optional.of(" "));
        for (String type : BULK) {
            for (SecurityPermissionBridge b : List.of(none, noneExplicit, failing, nullAnswer, blank)) {
                assertFalse(b.mayReplay(operator, type, "bulk-job:j2"), type);
            }
        }
    }

    @Test
    void theBulkTypesUseTheSinkRuleAndNothingElse() {
        for (String type : BULK) {
            assertEquals(new ReplayRules.BulkJobSink(), bridge.replayRule(type), type);
        }
    }

    @Test
    void preconditionsOfThePersonas() {
        GroupPermissionAdapter g = new GroupPermissionAdapter();
        assertTrue(g.has(ctx("t1", Permissions.GROUP_TENANT_ADMIN), OPS_TENANT_ADMIN));
        assertFalse(g.has(ctx("t1", Permissions.GROUP_OPERATOR), OPS_TENANT_ADMIN));
        assertTrue(g.has(ctx("t1", Permissions.GROUP_OPERATOR), Permissions.PROCESS_WRITE));
        assertFalse(g.has(ctx("t1", Permissions.GROUP_AUDITOR), Permissions.PROCESS_WRITE));
        assertFalse(g.has(ctx("t1", Permissions.GROUP_AUDITOR), OPS_TENANT_ADMIN));
        assertFalse(g.has(ctx("t1", Permissions.GROUP_WORKER), Permissions.PROCESS_WRITE));
    }
}
