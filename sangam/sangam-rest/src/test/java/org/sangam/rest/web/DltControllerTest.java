// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.DltPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Owner decisions 304 and 305 at the REST layer: the registration routes ask for {@code dlt.registration.read} (GET)
 * and {@code dlt.registration.write} (each change) before the port is called, so the two permissions guard the routes
 * they were named for; the delivery-report hook asks for no permission (the signature is its authentication) and hands
 * the port the raw body and the request headers. Without sangam-integration the port's defaults answer 404 for the hook
 * and 501 CAPABILITY_NOT_INSTALLED for the registration.
 */
class DltControllerTest {

    static final RequestContext CTX = new RequestContext("T1", "user:tenant-admin", Set.of(), null, null, null, null, null, "OIDC");

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

    /** Records every call that reaches the port. */
    static final class Port implements DltPort {
        final List<String> calls = new ArrayList<>();
        Map<String, String> hookHeaders;
        byte[] hookBody;

        @Override
        public DeliveryReportResult deliveryReports(String tenant, String connectorId, Map<String, String> headers, byte[] body) {
            calls.add("hook " + tenant + " " + connectorId);
            hookHeaders = headers;
            hookBody = body;
            return new DeliveryReportResult(1, 0, 0);
        }

        @Override
        public Registration registration(RequestContext ctx) {
            calls.add("get " + ctx.tenantId());
            return new Registration("E1", List.of("GOVSVC"), List.of(), List.of(), 3);
        }

        @Override
        public RegistrationChange setEntity(RequestContext ctx, EntityRequest request) {
            calls.add("entity " + request.entityId());
            return new RegistrationChange(true, 1);
        }

        @Override
        public RegistrationChange addHeader(RequestContext ctx, HeaderRequest request) {
            calls.add("header " + request.header());
            return new RegistrationChange(true, 2);
        }

        @Override
        public RegistrationChange addTemplate(RequestContext ctx, Template request) {
            calls.add("template " + request.templateId());
            return new RegistrationChange(true, 3);
        }

        @Override
        public RegistrationChange addUrlPrefix(RequestContext ctx, UrlPrefixRequest request) {
            calls.add("url " + request.prefix());
            return new RegistrationChange(true, 4);
        }
    }

    private static void refused(Runnable call, Port port, Perms perms, String permission) {
        ApiProblem p = assertThrows(ApiProblem.class, call::run);
        assertEquals(403, p.status());
        assertEquals("ACTION_NOT_PERMITTED", p.code());
        assertEquals(List.of(permission), perms.asked, "exactly this permission is asked for");
        assertEquals(List.of(), port.calls, "a refused caller never reaches the port");
        perms.asked.clear();
    }

    @Test
    void theReadRouteNeedsTheReadPermission() {
        Port port = new Port();
        Perms writeOnly = new Perms(Permissions.DLT_REGISTRATION_WRITE);
        refused(() -> new DltController(port, writeOnly).registration(CTX), port, writeOnly, "dlt.registration.read");

        Perms read = new Perms(Permissions.DLT_REGISTRATION_READ);
        DltPort.Registration r = new DltController(port, read).registration(CTX);
        assertEquals("E1", r.entityId());
        assertEquals(3, r.revision());
        assertEquals(List.of("get T1"), port.calls);
    }

    @Test
    void everyChangeNeedsTheWritePermission() {
        Port port = new Port();
        // the read permission, tenant.admin or admin.write do not open a change
        Perms others = new Perms(Permissions.DLT_REGISTRATION_READ, "tenant.admin", "admin.write");
        DltController denied = new DltController(port, others);
        refused(() -> denied.setEntity(CTX, new DltPort.EntityRequest("E1")), port, others, "dlt.registration.write");
        refused(() -> denied.addHeader(CTX, new DltPort.HeaderRequest("GOVSVC")), port, others, "dlt.registration.write");
        refused(() -> denied.addTemplate(CTX, new DltPort.Template("T-1", "GOVSVC", "Hello {#var#}")), port, others,
                "dlt.registration.write");
        refused(() -> denied.addUrlPrefix(CTX, new DltPort.UrlPrefixRequest("https://example.gov.in/")), port, others,
                "dlt.registration.write");

        Perms write = new Perms(Permissions.DLT_REGISTRATION_WRITE);
        DltController allowed = new DltController(port, write);
        assertEquals(new DltPort.RegistrationChange(true, 1), allowed.setEntity(CTX, new DltPort.EntityRequest("E1")));
        assertEquals(new DltPort.RegistrationChange(true, 2), allowed.addHeader(CTX, new DltPort.HeaderRequest("GOVSVC")));
        assertEquals(new DltPort.RegistrationChange(true, 3),
                allowed.addTemplate(CTX, new DltPort.Template("T-1", "GOVSVC", "Hello {#var#}")));
        assertEquals(new DltPort.RegistrationChange(true, 4),
                allowed.addUrlPrefix(CTX, new DltPort.UrlPrefixRequest("https://example.gov.in/")));
        assertEquals(List.of("entity E1", "header GOVSVC", "template T-1", "url https://example.gov.in/"), port.calls);
        assertEquals(List.of("dlt.registration.write", "dlt.registration.write", "dlt.registration.write",
                "dlt.registration.write"), write.asked);
    }

    @Test
    void theDeliveryReportHookAsksForNoPermissionAndPassesTheRawRequest() {
        Port port = new Port();
        Perms none = new Perms();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/hooks/sms-dlt/T1/by-connector-id/delivery-reports");
        request.addHeader("X-Sangam-Signature", "t=1,v1=abc");
        byte[] body = "{\"status\":\"DELIVERED\",\"clientRef\":\"op-1\"}".getBytes(StandardCharsets.UTF_8);

        DltPort.DeliveryReportResult r = new DltController(port, none).deliveryReports("T1", "sms-dlt", body, request);

        assertEquals(new DltPort.DeliveryReportResult(1, 0, 0), r);
        assertEquals(List.of(), none.asked, "the signature is the authentication: no Sangam permission is asked for");
        assertEquals(List.of("hook T1 sms-dlt"), port.calls);
        assertSame(body, port.hookBody, "the exact bytes the signature covers");
        assertEquals("t=1,v1=abc", port.hookHeaders.get("X-Sangam-Signature"));

        new DltController(port, none).deliveryReports("T1", "sms-dlt", null, request);
        assertEquals(0, port.hookBody.length, "a request without a body reaches the port as an empty body (refused there)");
    }

    @Test
    void withoutTheIntegrationModuleTheHookKnowsNoConnectorAndTheRegistrationIsNotInstalled() {
        DltPort absent = new DltPort() {
        };
        Perms all = new Perms(Permissions.DLT_REGISTRATION_READ, Permissions.DLT_REGISTRATION_WRITE);
        DltController c = new DltController(absent, all);
        ApiProblem hook = assertThrows(ApiProblem.class, () -> c.deliveryReports("T1", "sms-dlt", new byte[0],
                new MockHttpServletRequest("POST", "/api/v1/hooks/sms-dlt/T1/by-connector-id/delivery-reports")));
        assertEquals(404, hook.status());
        for (Runnable call : List.<Runnable>of(() -> c.registration(CTX),
                () -> c.setEntity(CTX, new DltPort.EntityRequest("E1")),
                () -> c.addHeader(CTX, new DltPort.HeaderRequest("GOVSVC")),
                () -> c.addTemplate(CTX, new DltPort.Template("T-1", "GOVSVC", "Hello")),
                () -> c.addUrlPrefix(CTX, new DltPort.UrlPrefixRequest("https://example.gov.in/")))) {
            ApiProblem p = assertThrows(ApiProblem.class, call::run);
            assertEquals(501, p.status());
            assertEquals("CAPABILITY_NOT_INSTALLED", p.code());
        }
        assertTrue(Permissions.DLT_REGISTRATION_READ.equals("dlt.registration.read")
                && Permissions.DLT_REGISTRATION_WRITE.equals("dlt.registration.write"), "the names W4-B acknowledged");
    }
}
