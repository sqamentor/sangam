// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.tenant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.sangam.core.layer.TenantKey;
import org.sangam.core.security.SangamSecurityException;

/**
 * Owner decision 145 (and the tenant side of 144): the engine tenant is the only tenant source, case-variable tenants
 * are untrusted, tenant-owned work without an engine tenant fails closed, the system scope never grants tenant
 * connectors, shared jobs on tenant-owned resources need authorised resource metadata, and continuations keep their
 * tenant. Pure unit tests: the policy touches no persistence, so no dialect (Oracle, SQL Server) case applies here; the
 * dialect tests belong to the persisting callers.
 */
class ExecutionTenantPolicyTest {

    private static final String CTX = "unit test operation";
    private static final String[] NO_TENANT = {null, "", "   ", "\t", TenantKey.SHARED};

    private static TenantResolutionException refused(TenantRefusal expected, Executable call) {
        TenantResolutionException e = assertThrows(TenantResolutionException.class, call);
        assertEquals(expected, e.refusal());
        assertEquals(expected.code(), e.code());
        assertEquals(403, e.httpStatus());
        assertInstanceOf(SangamSecurityException.class, e);
        assertTrue(e.getMessage().startsWith(expected.code() + ": " + CTX), e.getMessage());
        return e;
    }

    // ---- tenant-owned work ----

    @Test
    void tenantOwnedWorkResolvesToTheEngineTenant() {
        ResolvedTenant r = ExecutionTenantPolicy.tenantOwned("t1", null, CTX);
        assertEquals(ResolvedTenant.Scope.TENANT, r.scope());
        assertEquals("t1", r.tenantId());
        assertEquals("t1", r.engineTenant());
        assertEquals(ResolvedTenant.Reason.ENGINE_TENANT, r.reason());
        assertNull(r.resourceType());
        assertNull(r.resourceId());
        assertEquals("t1", r.requireTenant(CTX));
    }

    @Test
    void aForgedVariableTenantIsRefusedAndNeverLeaksIntoTheMessage() {
        TenantResolutionException e = refused(TenantRefusal.CONFLICTING_TENANT,
                () -> ExecutionTenantPolicy.tenantOwned("victim-tenant", "attacker-tenant", CTX));
        assertEquals(TenantOperation.TENANT_OWNED, e.operation());
        assertEquals(CTX, e.context());
        assertFalse(e.getMessage().contains("victim-tenant"), "no tenant id in the message");
        assertFalse(e.getMessage().contains("attacker-tenant"), "no variable value in the message");
    }

    @Test
    void aVariableTenantEqualToTheEngineTenantOrBlankIsHarmless() {
        assertEquals("t1", ExecutionTenantPolicy.tenantOwned("t1", "t1", CTX).tenantId());
        for (String none : NO_TENANT) {
            assertEquals("t1", ExecutionTenantPolicy.tenantOwned("t1", none, CTX).tenantId(), String.valueOf(none));
        }
    }

    @Test
    void aMissingEngineTenantFailsClosedEvenWhenAVariableNamesATenant() {
        for (String none : NO_TENANT) {
            refused(TenantRefusal.MISSING_ENGINE_TENANT, () -> ExecutionTenantPolicy.tenantOwned(none, null, CTX));
            refused(TenantRefusal.MISSING_ENGINE_TENANT, () -> ExecutionTenantPolicy.tenantOwned(none, "t1", CTX));
        }
    }

    @Test
    void aNonBlankTenantIsNeverTrimmedIntoAnotherTenant() {
        refused(TenantRefusal.CONFLICTING_TENANT, () -> ExecutionTenantPolicy.tenantOwned("t1", " t1", CTX));
        assertEquals(" t1", ExecutionTenantPolicy.normalise(" t1"));
    }

    // ---- shared (system) work ----

    @Test
    void genuinelySharedWorkResolvesToTheSystemScope() {
        for (String none : NO_TENANT) {
            ResolvedTenant r = ExecutionTenantPolicy.shared(none, none, CTX);
            assertTrue(r.isSystem());
            assertNull(r.tenantId());
            assertNull(r.engineTenant());
            assertEquals(ResolvedTenant.Reason.SHARED_WORK, r.reason());
        }
    }

    @Test
    void theSystemScopeNeverGrantsATenantConnectorCredentialDataOrCaller() {
        ResolvedTenant system = ExecutionTenantPolicy.shared(null, null, CTX);
        refused(TenantRefusal.SHARED_SCOPE_NOT_ALLOWED, () -> system.requireTenant(CTX));
        refused(TenantRefusal.SHARED_SCOPE_NOT_ALLOWED, () -> system.requireOwnedBy("t1", CTX));
        refused(TenantRefusal.SHARED_SCOPE_NOT_ALLOWED, () -> system.requireOwnedBy(null, CTX));
        refused(TenantRefusal.SHARED_SCOPE_NOT_ALLOWED, () -> system.requireCaller("t1", CTX));
    }

    @Test
    void sharedWorkWithATenantOnEitherSideIsAConflictNotTheSystemScope() {
        refused(TenantRefusal.CONFLICTING_TENANT, () -> ExecutionTenantPolicy.shared("t1", null, CTX));
        refused(TenantRefusal.CONFLICTING_TENANT, () -> ExecutionTenantPolicy.shared(null, "t1", CTX));
        refused(TenantRefusal.CONFLICTING_TENANT, () -> ExecutionTenantPolicy.shared("t1", "t1", CTX));
    }

    // ---- shared jobs on tenant-owned resources ----

    @Test
    void aSharedJobOnATenantResourceWithoutMetadataIsBlocked() {
        for (String none : NO_TENANT) {
            refused(TenantRefusal.SHARED_SCOPE_NOT_ALLOWED,
                    () -> ExecutionTenantPolicy.onTenantResource(none, null, null, CTX));
        }
        refused(TenantRefusal.SHARED_SCOPE_NOT_ALLOWED,
                () -> ExecutionTenantPolicy.onTenantResource(null, "t1", null, CTX));
    }

    @Test
    void aSharedJobOnATenantResourceGetsTheAuthorisedMetadataTenant() {
        ResourceTenantGrant grant = new ResourceTenantGrant("t1", "batch", "b-42", "batch owner column");
        ResolvedTenant r = ExecutionTenantPolicy.onTenantResource(null, null, grant, CTX);
        assertEquals(ResolvedTenant.Scope.TENANT, r.scope());
        assertEquals("t1", r.tenantId());
        assertNull(r.engineTenant(), "the original engine tenant stays distinct from the resolved tenant");
        assertEquals(ResolvedTenant.Reason.RESOURCE_METADATA, r.reason());
        assertEquals("batch", r.resourceType());
        assertEquals("b-42", r.resourceId());
        assertEquals("t1", ExecutionTenantPolicy.onTenantResource("t1", "t1", grant, CTX).tenantId());
        assertEquals("t1", ExecutionTenantPolicy.onTenantResource("t1", "t1", grant, CTX).engineTenant());
    }

    @Test
    void metadataThatDisagreesWithTheEngineOrAVariableIsAConflict() {
        ResourceTenantGrant grant = new ResourceTenantGrant("t1", "job definition", "jd-1", "job definition owner");
        refused(TenantRefusal.CONFLICTING_TENANT, () -> ExecutionTenantPolicy.onTenantResource("t2", null, grant, CTX));
        refused(TenantRefusal.CONFLICTING_TENANT, () -> ExecutionTenantPolicy.onTenantResource(null, "t2", grant, CTX));
    }

    @Test
    void aGrantNeedsEveryFieldAndNeverTheSharedKey() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceTenantGrant(TenantKey.SHARED, "r", "1", "a"));
        for (String blank : Arrays.asList(null, "", "  ")) {
            assertThrows(IllegalArgumentException.class, () -> new ResourceTenantGrant(blank, "r", "1", "a"));
            assertThrows(IllegalArgumentException.class, () -> new ResourceTenantGrant("t1", blank, "1", "a"));
            assertThrows(IllegalArgumentException.class, () -> new ResourceTenantGrant("t1", "r", blank, "a"));
            assertThrows(IllegalArgumentException.class, () -> new ResourceTenantGrant("t1", "r", "1", blank));
        }
    }

    @Test
    void aGrantIsAcceptedOnlyForSharedWorkOnATenantResource() {
        ResourceTenantGrant grant = new ResourceTenantGrant("t1", "r", "1", "a");
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionTenantPolicy.resolve(TenantOperation.TENANT_OWNED, "t1", null, grant, CTX));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionTenantPolicy.resolve(TenantOperation.SHARED, null, null, grant, CTX));
    }

    @Test
    void theOperationKindAndAContextLabelAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> ExecutionTenantPolicy.resolve(null, "t1", null, null, CTX));
        for (String blank : Arrays.asList(null, "", " ")) {
            assertThrows(IllegalArgumentException.class, () -> ExecutionTenantPolicy.tenantOwned("t1", null, blank));
        }
    }

    // ---- connectors, credentials, callers ----

    @Test
    void connectorAndCredentialOwnershipIsVerifiedAgainstTheResolvedTenant() {
        ResolvedTenant t1 = ExecutionTenantPolicy.tenantOwned("t1", null, CTX);
        assertDoesNotThrow(() -> t1.requireOwnedBy("t1", CTX));
        refused(TenantRefusal.CONFLICTING_TENANT, () -> t1.requireOwnedBy("t2", CTX));
        for (String none : NO_TENANT) {
            refused(TenantRefusal.CONFLICTING_TENANT, () -> t1.requireOwnedBy(none, CTX));
        }
    }

    @Test
    void crossTenantCallerAccessAtStartOrCorrelationIsRefused() {
        ResolvedTenant t1 = ExecutionTenantPolicy.tenantOwned("t1", null, CTX);
        assertDoesNotThrow(() -> t1.requireCaller("t1", CTX));
        refused(TenantRefusal.CONFLICTING_TENANT, () -> t1.requireCaller("t2", CTX));
        for (String none : NO_TENANT) {
            refused(TenantRefusal.CONFLICTING_TENANT, () -> t1.requireCaller(none, CTX));
        }
    }

    // ---- async jobs, retries, incidents, callbacks ----

    @Test
    void aRetryOrCallbackKeepsItsTenantContext() {
        ResolvedTenant captured = ExecutionTenantPolicy.tenantOwned("t1", null, CTX);
        ResolvedTenant retry = ExecutionTenantPolicy.tenantOwned("t1", null, CTX);
        assertEquals(captured, retry, "the same inputs resolve the same way on every attempt");
        assertSame(retry, ExecutionTenantPolicy.requireContinuation(captured, retry, CTX));
        ResolvedTenant system = ExecutionTenantPolicy.shared(null, null, CTX);
        assertSame(system, ExecutionTenantPolicy.requireContinuation(ExecutionTenantPolicy.shared(null, null, CTX), system, CTX));
    }

    @Test
    void aContinuationThatChangedTenantOrScopeOrLostItsContextStops() {
        ResolvedTenant t1 = ExecutionTenantPolicy.tenantOwned("t1", null, CTX);
        ResolvedTenant t2 = ExecutionTenantPolicy.tenantOwned("t2", null, CTX);
        ResolvedTenant system = ExecutionTenantPolicy.shared(null, null, CTX);
        refused(TenantRefusal.CONFLICTING_TENANT, () -> ExecutionTenantPolicy.requireContinuation(t1, t2, CTX));
        refused(TenantRefusal.CONFLICTING_TENANT, () -> ExecutionTenantPolicy.requireContinuation(t1, system, CTX));
        refused(TenantRefusal.CONFLICTING_TENANT, () -> ExecutionTenantPolicy.requireContinuation(system, t1, CTX));
        TenantResolutionException e = refused(TenantRefusal.MISSING_ENGINE_TENANT,
                () -> ExecutionTenantPolicy.requireContinuation(null, t1, CTX));
        assertNull(e.operation());
        assertThrows(IllegalArgumentException.class, () -> ExecutionTenantPolicy.requireContinuation(t1, null, CTX));
    }

    // ---- result invariants and codes ----

    @Test
    void aResolvedTenantHasATenantExactlyWhenItIsNotTheSystemScope() {
        assertThrows(IllegalArgumentException.class, () -> new ResolvedTenant(ResolvedTenant.Scope.SYSTEM, "t1", null,
                ResolvedTenant.Reason.SHARED_WORK, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedTenant(ResolvedTenant.Scope.TENANT, null, null,
                ResolvedTenant.Reason.ENGINE_TENANT, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedTenant(ResolvedTenant.Scope.TENANT, "t1", "t1",
                ResolvedTenant.Reason.SHARED_WORK, null, null));
    }

    @Test
    void refusalCodesAreStable() {
        assertEquals("TENANT_CONTEXT_MISSING", TenantRefusal.MISSING_ENGINE_TENANT.code());
        assertEquals("TENANT_CONTEXT_CONFLICT", TenantRefusal.CONFLICTING_TENANT.code());
        assertEquals("TENANT_SHARED_SCOPE_NOT_ALLOWED", TenantRefusal.SHARED_SCOPE_NOT_ALLOWED.code());
        assertEquals(3, TenantRefusal.values().length);
    }
}
