// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.extension.TenantCustomFieldRegistry;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.Download;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.web.TenantCustomFieldController.CustomField;
import org.sangam.rest.web.TenantCustomFieldController.DefineRequest;
import org.sangam.rest.web.TenantCustomFieldController.RetireRequest;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * SL-40: the REST mapping of {@code /api/v1/custom-fields} without a server: permissions ({@code form.read} to read,
 * {@code tenant.admin} to change), pagination headers, 201/200 responses with the resource reference, the audit on the
 * tenant's security chain, and the problems 400 / 404 / 409 / 422 / 501.
 */
class TenantCustomFieldControllerTest {

    private static final String FORM = "form:grant-application";
    private static final Map<String, Object> STR = Map.of("type", "string");

    /** Grants the permissions in {@link #held}; records every permission asked for. */
    static final class Perms implements PermissionPort {
        final Set<String> held;
        final List<String> asked = new ArrayList<>();

        Perms(String... held) {
            this.held = Set.of(held);
        }

        @Override
        public boolean has(RequestContext ctx, String permission) {
            asked.add(permission);
            return held.contains(permission);
        }

        @Override
        public Set<String> permissions(RequestContext ctx) {
            return held;
        }

        @Override
        public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
            return true;
        }
    }

    /** Records chain, type and payload of class-B events. */
    static final class Audit implements AuditPort {
        final List<String> events = new ArrayList<>();
        final List<Map<String, ?>> payloads = new ArrayList<>();

        @Override
        public String record(RequestContext ctx, String chainId, String eventType, Map<String, ?> payload) {
            events.add(chainId + "|" + eventType);
            payloads.add(payload);
            return "e";
        }

        @Override
        public Page<AuditEntry> timeline(RequestContext ctx, String businessKey, boolean includeHistory, Page.PageRequest page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<AuditEntry> search(RequestContext ctx, AuditFilter filter, Page.PageRequest page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VerifyResult verify(RequestContext ctx, String chainId, String businessKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Download export(RequestContext ctx, String businessKey, String format) {
            throw new UnsupportedOperationException();
        }
    }

    private static final RequestContext CTX = new RequestContext("t1", "admin-1", Set.of("sangam-tenant-admin"), "seat-1",
            null, "idem-1", null, null, "OIDC");

    private final TenantCustomFieldRegistry registry = new TenantCustomFieldRegistry(Set.of("object:vendor"),
            (t, a, x, p) -> { });
    private final Audit audit = new Audit();

    private TenantCustomFieldController controller(PermissionPort perms) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("registry", registry);
        return new TenantCustomFieldController(beans.getBeanProvider(TenantCustomFieldRegistry.class), perms, audit,
                new RestProperties());
    }

    private final TenantCustomFieldController api = controller(new Perms(Permissions.FORM_READ, Permissions.TENANT_ADMIN));

    private static DefineRequest def(String objectType, String key, String from) {
        return new DefineRequest(objectType, key, "Label", null, null, STR, from, null);
    }

    @Test
    void defineIs201WithLocationAndIsAuditedOnTheSecurityChain() {
        ResponseEntity<CustomField> r = api.define(CTX, new DefineRequest(FORM, "x_code", "Code", true, null,
                Map.of("type", "string", "pattern", "^[0-9]{3}$"), "2026-04-01", null));
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        // UI-DEMO-1: the field is named by query parameters (an object type carries a form key, which may carry '/')
        assertEquals("/api/v1/custom-fields/versions?objectType=form%3Agrant-application&key=x_code",
                r.getHeaders().getLocation().toString());
        assertEquals("form:grant-application/x_code@1", r.getHeaders().getFirst(Lists.RESOURCE_REF));
        CustomField f = r.getBody();
        assertEquals(1, f.version());
        assertTrue(f.required());
        assertEquals("2026-04-01", f.effectiveFrom());
        assertNull(f.effectiveTo());
        assertEquals(List.of("sec:t1|CUSTOM_FIELD_DEFINED"), audit.events);
        assertEquals("x_code", audit.payloads.get(0).get("key"));
        assertEquals(CTX.actor(), audit.payloads.get(0).get("actor"));
        assertEquals(1, registry.versions("t1", FORM, "x_code").size(), "stored for the caller's tenant");
        assertTrue(registry.versions("t2", FORM, "x_code").isEmpty(), "never another tenant");
    }

    @Test
    void listIsPagedWithTotalsAndFilteredByObjectTypeAndDate() {
        api.define(CTX, def(FORM, "x_code", "2026-04-01"));
        api.define(CTX, def(FORM, "x_code", "2027-01-01"));
        api.define(CTX, def("object:vendor", "x_gstin", "2026-04-01"));
        ResponseEntity<List<CustomField>> first = api.list(CTX, null, null, 0, 2);
        assertEquals("3", first.getHeaders().getFirst(Lists.TOTAL));
        assertEquals(2, first.getBody().size());
        assertEquals(1, api.list(CTX, null, null, 1, 2).getBody().size());
        assertEquals(2, api.list(CTX, FORM, null, null, null).getBody().size());
        List<CustomField> inForce = api.list(CTX, FORM, "2026-12-31", null, null).getBody();
        assertEquals(1, inForce.size());
        assertEquals(1, inForce.get(0).version());
        assertEquals("2027-01-01", inForce.get(0).effectiveTo(), "v1 closed on v2's start");
        ApiProblem badDate = assertThrows(ApiProblem.class, () -> api.list(CTX, null, "31-12-2026", null, null));
        assertEquals(400, badDate.status());
        assertEquals("on", badDate.extensions().get("field"));
        assertEquals(400, assertThrows(ApiProblem.class, () -> api.list(CTX, null, null, 0, 0)).status());
    }

    @Test
    void versionsOfAnUnknownFieldAre404() {
        api.define(CTX, def(FORM, "x_code", "2026-04-01"));
        assertEquals(1, api.versions(CTX, FORM, "x_code").getBody().size());
        ApiProblem p = assertThrows(ApiProblem.class, () -> api.versions(CTX, FORM, "x_none"));
        assertEquals(404, p.status());
        assertEquals("NOT_FOUND", p.code());
    }

    @Test
    void retireNeedsAReasonClosesTheVersionAndIsAudited() {
        api.define(CTX, def(FORM, "x_code", "2026-04-01"));
        assertEquals("REASON_REQUIRED", assertThrows(ApiProblem.class,
                () -> api.retire(CTX, FORM, "x_code", new RetireRequest("2027-04-01", " "))).code());
        ApiProblem missing = assertThrows(ApiProblem.class, () -> api.retire(CTX, FORM, "x_code", null));
        assertEquals("effectiveTo", missing.extensions().get("field"));
        ApiProblem unknown = assertThrows(ApiProblem.class,
                () -> api.retire(CTX, FORM, "x_none", new RetireRequest("2027-04-01", "obsolete")));
        assertEquals(404, unknown.status());
        ResponseEntity<CustomField> r = api.retire(CTX, FORM, "x_code", new RetireRequest("2027-04-01", "obsolete"));
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("2027-04-01", r.getBody().effectiveTo());
        assertEquals("form:grant-application/x_code@1", r.getHeaders().getFirst(Lists.RESOURCE_REF));
        assertEquals("sec:t1|CUSTOM_FIELD_RETIRED", audit.events.get(1));
        assertEquals("obsolete", audit.payloads.get(1).get("reason"));
    }

    @Test
    void ruleViolationsAre422AndMalformedRequestsAre400() {
        ApiProblem badKey = assertThrows(ApiProblem.class, () -> api.define(CTX, def(FORM, "district", "2026-04-01")));
        assertEquals(422, badKey.status());
        assertEquals("CUSTOM_FIELD_INVALID", badKey.code());
        assertTrue(!badKey.detail().startsWith("CUSTOM_FIELD_INVALID"), badKey.detail());
        assertEquals(422, assertThrows(ApiProblem.class, () -> api.define(CTX, new DefineRequest(FORM, "x_a", null, null,
                null, Map.of("type", "number"), "2026-04-01", null))).status(), "R33: no binary numbers");
        assertEquals(422, assertThrows(ApiProblem.class,
                () -> api.define(CTX, def("object:unregistered", "x_a", "2026-04-01"))).status());
        api.define(CTX, def(FORM, "x_code", "2026-04-01"));
        assertEquals(422, assertThrows(ApiProblem.class, () -> api.define(CTX, def(FORM, "x_code", "2026-01-01"))).status(),
                "a back-dated version");
        assertEquals("schema", assertThrows(ApiProblem.class, () -> api.define(CTX, new DefineRequest(FORM, "x_a", null,
                null, null, null, "2026-04-01", null))).extensions().get("field"));
        assertEquals("effectiveFrom", assertThrows(ApiProblem.class,
                () -> api.define(CTX, def(FORM, "x_a", "April"))).extensions().get("field"));
        assertEquals(400, assertThrows(ApiProblem.class, () -> api.define(CTX, null)).status());
        assertEquals(1, audit.events.size(), "refused requests are not audited");
    }

    @Test
    void concurrentChangesAre409AndOtherFailuresAreNotRewritten() {
        ApiProblem p = assertThrows(ApiProblem.class, () -> TenantCustomFieldController.call(() -> {
            throw new TenantCustomFieldRegistry.ConcurrentChangeException("x_code v2 exists");
        }));
        assertEquals(409, p.status());
        assertEquals("CUSTOM_FIELD_CONFLICT", p.code());
        assertEquals("x_code v2 exists", p.detail());
        IllegalArgumentException other = new IllegalArgumentException("not a registry refusal");
        assertSame(other, assertThrows(IllegalArgumentException.class, () -> TenantCustomFieldController.call(() -> {
            throw other;
        })));
    }

    @Test
    void readsNeedFormReadAndChangesNeedTenantAdmin() {
        Perms reader = new Perms(Permissions.FORM_READ);
        TenantCustomFieldController ro = controller(reader);
        ro.list(CTX, null, null, null, null);
        assertThrows(ApiProblem.class, () -> ro.versions(CTX, FORM, "x_none"));
        ApiProblem denied = assertThrows(ApiProblem.class, () -> ro.define(CTX, def(FORM, "x_code", "2026-04-01")));
        assertEquals(403, denied.status());
        assertEquals(403, assertThrows(ApiProblem.class,
                () -> ro.retire(CTX, FORM, "x_code", new RetireRequest("2027-04-01", "r"))).status());
        assertEquals(List.of(Permissions.FORM_READ, Permissions.FORM_READ, Permissions.TENANT_ADMIN,
                Permissions.TENANT_ADMIN), reader.asked);
        assertEquals(403, assertThrows(ApiProblem.class,
                () -> controller(new Perms()).list(CTX, null, null, null, null)).status());
    }

    @Test
    void withoutATenantOrARegistryTheCallIsRefused() {
        RequestContext noTenant = new RequestContext(" ", "p", Set.of(), null, null, null, null, null, "OIDC");
        assertEquals("TENANT_REQUIRED", assertThrows(ApiProblem.class,
                () -> api.list(noTenant, null, null, null, null)).code());
        TenantCustomFieldController none = new TenantCustomFieldController(
                new StaticListableBeanFactory().getBeanProvider(TenantCustomFieldRegistry.class),
                new Perms(Permissions.FORM_READ), audit, new RestProperties());
        assertEquals(501, assertThrows(ApiProblem.class, () -> none.list(CTX, null, null, null, null)).status());
    }

    @Test
    void customFieldDatesAreIsoStrings() {
        CustomField f = CustomField.of(new TenantCustomFieldRegistry.FieldDefinition(FORM, "x_a", 2, null, false, true,
                STR, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 1, 1)));
        assertEquals("2026-04-01", f.effectiveFrom());
        assertEquals("2027-01-01", f.effectiveTo());
        assertEquals("form:grant-application/x_a@2", TenantCustomFieldController.ref(
                new TenantCustomFieldRegistry.FieldDefinition(FORM, "x_a", 2, null, false, true, STR,
                        LocalDate.of(2026, 4, 1), null)));
    }
}
