// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.modeller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.sangam.modeller.mock.ServiceMocks;
import org.sangam.rest.modules.ModulePaging;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service mocks for model tests. Mocks act on shared connectors, so these routes need a platform operator's
 * {@code ops.write}. There is no bean on a production profile, so every route answers "not installed" there (R80). Rules added
 * here last until the process restarts and stay on this node; file rules come from {@code sangam.modeller.mocks.file}.
 */
@RestController
@RequestMapping("/api/v1/service-mocks")
public class ServiceMocksController {

    private final ObjectProvider<ServiceMocks> mocks;
    private final PermissionPort permissions;

    public ServiceMocksController(ObjectProvider<ServiceMocks> mocks, PermissionPort permissions) {
        this.mocks = mocks;
        this.permissions = permissions;
    }

    public record MockRow(String id, String service, String operation, boolean enabled, String scope, Map<String, Object> response, Long delayMs) {
    }

    public record CreateMock(String service, String operation, Map<String, Object> response, Long delayMs, Boolean enabled) {
    }

    public record Toggle(Boolean enabled) {
    }

    public record GlobalState(boolean enabled) {
    }

    @GetMapping
    public ResponseEntity<List<MockRow>> list(RequestContext ctx, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.OPS_READ);
        ServiceMocks m = mocks();
        List<MockRow> rows = new ArrayList<>();
        m.rules().forEach(r -> rows.add(row(m, r)));
        return ModulePaging.slice(rows, page, size);
    }

    @PostMapping
    public ResponseEntity<MockRow> create(RequestContext ctx, @RequestBody CreateMock b) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        if (b.service() == null || b.service().isBlank() || b.operation() == null || b.operation().isBlank() || b.response() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "service, operation and response are required");
        }
        if (b.delayMs() != null && (b.delayMs() < 0 || b.delayMs() > 60_000)) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "delayMs must be between 0 and 60000");
        }
        ServiceMocks m = mocks();
        ServiceMocks.Rule rule = new ServiceMocks.Rule(b.service(), b.operation(), Map.of(), b.response(), null, b.delayMs() == null ? null : Duration.ofMillis(b.delayMs()));
        m.addRule(rule);
        if (b.enabled() != null) {
            m.setServiceEnabled(b.service(), b.enabled());
        }
        return ResponseEntity.status(201).body(row(m, rule));
    }

    @PutMapping("/global")
    public GlobalState global(RequestContext ctx, @RequestBody Toggle b) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        ServiceMocks m = mocks();
        m.setGlobalEnabled(Boolean.TRUE.equals(b.enabled()));
        return new GlobalState(m.globalEnabled());
    }

    /** Toggles are per service in the module, so this affects every rule of the row's service. */
    @PatchMapping("/{id}")
    public MockRow toggle(RequestContext ctx, @PathVariable String id, @RequestBody Toggle b) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        ServiceMocks m = mocks();
        ServiceMocks.Rule rule = m.rules().stream().filter(r -> id(r).equals(id)).findFirst().orElseThrow(() -> ApiProblem.notFound("service mock", id));
        m.setServiceEnabled(rule.service(), Boolean.TRUE.equals(b.enabled()));
        return row(m, rule);
    }

    private ServiceMocks mocks() {
        ServiceMocks m = mocks.getIfAvailable();
        if (m == null) {
            throw ApiProblem.notInstalled("service mocks", "a non-production profile");
        }
        return m;
    }

    private static String id(ServiceMocks.Rule r) {
        return r.service() + "." + r.operation();
    }

    private static MockRow row(ServiceMocks m, ServiceMocks.Rule r) {
        return new MockRow(id(r), r.service(), r.operation(), m.globalEnabled() && m.serviceEnabled(r.service()), "NODE", r.output(), r.delay() == null ? null : r.delay().toMillis());
    }
}
