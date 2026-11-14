// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.LowCodePort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.http.ResponseEntity;

/**
 * EC-06, owner decision 265 at the HTTP edge: a REST-backed create is 202 Accepted with the operationId as resource
 * reference and the operation as {@code Location}; a DB-backed create stays 201; the operation GET needs lowcode.read
 * before the port is asked.
 */
class LowCodeControllerRestBackedTest {

    static final RequestContext CTX = new RequestContext("t1", "user-1", Set.of(), null, null, "key-1", null,
            "127.0.0.1", "OIDC");

    final List<String> calls = new ArrayList<>();
    LowCodePort.RecordCreation creation;
    LowCodePort.RecordOperation operation;

    LowCodePort port() {
        return (LowCodePort) Proxy.newProxyInstance(LowCodePort.class.getClassLoader(), new Class<?>[]{LowCodePort.class},
                (p, m, a) -> switch (m.getName()) {
                    case "createRecord" -> {
                        calls.add("create " + a[1] + " " + a[2]);
                        yield creation;
                    }
                    case "recordOperation" -> {
                        calls.add("operation " + a[1] + " " + a[2]);
                        yield operation;
                    }
                    default -> throw new AssertionError("not used: " + m.getName());
                });
    }

    static PermissionPort holding(String... granted) {
        Set<String> g = Set.of(granted);
        return new PermissionPort() {
            @Override
            public boolean has(RequestContext ctx, String permission) {
                return g.contains(permission);
            }

            @Override
            public Set<String> permissions(RequestContext ctx) {
                return g;
            }

            @Override
            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return false;
            }
        };
    }

    LowCodeController controller(String... granted) {
        return new LowCodeController(port(), holding(granted));
    }

    @Test
    void aRestBackedCreateIs202WithTheOperationAsLocationAndResourceRef() {
        LowCodePort.RecordOperation accepted = new LowCodePort.RecordOperation("6f1c1f0e-0000-4000-8000-000000000001",
                "vendors", "PENDING", null, null);
        creation = LowCodePort.RecordCreation.accepted(accepted);
        ResponseEntity<Object> r = controller(Permissions.LOWCODE_RECORD_WRITE).createRecord(CTX, "vendors",
                Map.of("name", "Acme"));
        assertEquals(202, r.getStatusCode().value());
        assertEquals("/api/v1/data-objects/vendors/records/operations/6f1c1f0e-0000-4000-8000-000000000001",
                r.getHeaders().getLocation().toString());
        assertEquals("6f1c1f0e-0000-4000-8000-000000000001", r.getHeaders().getFirst(Lists.RESOURCE_REF));
        assertSame(accepted, r.getBody());
    }

    @Test
    void aDbBackedCreateStays201WithTheRecord() {
        Map<String, Object> record = Map.of("id", "r-1", "name", "Acme");
        creation = LowCodePort.RecordCreation.created(record);
        ResponseEntity<Object> r = controller(Permissions.LOWCODE_RECORD_WRITE).createRecord(CTX, "vendors",
                Map.of("name", "Acme"));
        assertEquals(201, r.getStatusCode().value());
        assertEquals("/api/v1/data-objects/vendors/records/r-1", r.getHeaders().getLocation().toString());
        assertEquals("r-1", r.getHeaders().getFirst(Lists.RESOURCE_REF));
        assertSame(record, r.getBody());
    }

    @Test
    void theOperationIsReadWithLowcodeReadOnly() {
        operation = new LowCodePort.RecordOperation("op-1", "vendors", "COMPLETED", Map.of("id", "v-1"), null);
        assertSame(operation, controller(Permissions.LOWCODE_READ).recordOperation(CTX, "vendors", "op-1"));
        assertEquals(List.of("operation vendors op-1"), calls);

        ApiProblem p = assertThrows(ApiProblem.class, () -> controller().recordOperation(CTX, "vendors", "op-1"));
        assertEquals(403, p.status());
        assertEquals(1, calls.size(), "the port is not asked without lowcode.read");
    }

    @Test
    void aRecordCreationIsEitherCreatedOrAccepted() {
        assertThrows(IllegalArgumentException.class, () -> new LowCodePort.RecordCreation(null, null));
        assertThrows(IllegalArgumentException.class, () -> new LowCodePort.RecordCreation(Map.of(),
                new LowCodePort.RecordOperation("op", "k", "PENDING", null, null)));
        assertTrue(LowCodePort.RecordCreation.created(Map.of()).accepted() == null);
    }
}
