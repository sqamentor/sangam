// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiContractPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * M-068: the four ApiContracts routes as the contract states them: put needs package.author, the reads package.read; a
 * put body that is not a JSON object is 400 VALIDATION_FAILED before the port is called; the version query reaches the
 * port unchanged (null = latest); lists carry X-Total-Count; the put answer carries the package as resource reference.
 */
class ApiContractControllerTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final RequestContext CTX = new RequestContext("t1", "alice", Set.of(), null, null, null, null, "127.0.0.1",
            "DEV_TOKEN");
    static final ApiContractPort.ApiContractSummary SUMMARY = new ApiContractPort.ApiContractSummary("t1", "p1", "pets", 3,
            "OPENAPI", "3.1.0", "ab".repeat(32));

    /** Grants only the permissions listed and records every check. */
    static final class Grants implements PermissionPort {
        final Set<String> granted;
        final List<String> checked = new ArrayList<>();

        Grants(String... granted) {
            this.granted = Set.of(granted);
        }

        @Override
        public boolean has(RequestContext ctx, String permission) {
            checked.add(permission);
            return granted.contains(permission);
        }

        @Override
        public Set<String> permissions(RequestContext ctx) {
            return granted;
        }

        @Override
        public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
            return true;
        }
    }

    /** Records the calls; answers fixed values. */
    static final class Port implements ApiContractPort {
        final List<Object[]> calls = new ArrayList<>();

        @Override
        public ApiContractSummary put(RequestContext ctx, String packageId, String contractId, JsonNode document) {
            calls.add(new Object[]{"put", packageId, contractId, document});
            return SUMMARY;
        }

        @Override
        public List<ApiContractSummary> list(RequestContext ctx, String packageId) {
            calls.add(new Object[]{"list", packageId});
            return List.of(SUMMARY, SUMMARY);
        }

        @Override
        public ApiContract get(RequestContext ctx, String packageId, String contractId, Integer version) {
            calls.add(new Object[]{"get", packageId, contractId, version});
            return new ApiContract("t1", packageId, contractId, version == null ? 3 : version, "OPENAPI", "3.1.0",
                    SUMMARY.sha256(), JSON.createObjectNode().put("openapi", "3.1.0"));
        }

        @Override
        public List<ContractOperation> operations(RequestContext ctx, String packageId, String contractId, Integer version) {
            calls.add(new Object[]{"operations", packageId, contractId, version});
            return List.of(new ContractOperation("getPet", "OPENAPI", "GET", "/pets/{id}", null, null, null, null,
                    Map.of(), List.of()));
        }
    }

    @Test
    void putNeedsPackageAuthorAndAnswersTheSummaryWithThePackageAsResourceRef() throws Exception {
        Port port = new Port();
        Grants grants = new Grants(Permissions.PACKAGE_AUTHOR);
        JsonNode doc = JSON.readTree("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"x\",\"version\":\"1\"},\"paths\":{}}");
        ResponseEntity<ApiContractPort.ApiContractSummary> r = new ApiContractController(port, grants)
                .putApiContract(CTX, "p1", "pets", doc);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertSame(SUMMARY, r.getBody());
        assertEquals("p1", r.getHeaders().getFirst(Lists.RESOURCE_REF));
        assertEquals(List.of(Permissions.PACKAGE_AUTHOR), grants.checked);
        assertEquals("pets", port.calls.get(0)[2]);
        assertSame(doc, port.calls.get(0)[3]);
    }

    @Test
    void aBodyThatIsNotAJsonObjectIsRefusedBeforeThePort() throws Exception {
        Port port = new Port();
        ApiContractController c = new ApiContractController(port, new Grants(Permissions.PACKAGE_AUTHOR));
        for (JsonNode bad : new JsonNode[]{null, JSON.readTree("[1]"), JSON.readTree("\"text\""), JSON.readTree("1")}) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> c.putApiContract(CTX, "p1", "pets", bad));
            assertEquals(400, p.status());
            assertEquals("VALIDATION_FAILED", p.code());
        }
        assertTrue(port.calls.isEmpty());
    }

    @Test
    void putWithoutPackageAuthorIsForbiddenEvenWithPackageRead() {
        Port port = new Port();
        ApiContractController c = new ApiContractController(port, new Grants(Permissions.PACKAGE_READ));
        ApiProblem p = assertThrows(ApiProblem.class, () -> c.putApiContract(CTX, "p1", "pets",
                JSON.createObjectNode().put("openapi", "3.1.0")));
        assertEquals(403, p.status());
        assertTrue(port.calls.isEmpty());
    }

    @Test
    void theReadsNeedPackageReadAndPassTheVersionThrough() {
        Port port = new Port();
        Grants grants = new Grants(Permissions.PACKAGE_READ);
        ApiContractController c = new ApiContractController(port, grants);

        ResponseEntity<List<ApiContractPort.ApiContractSummary>> list = c.listApiContracts(CTX, "p1");
        assertEquals("2", list.getHeaders().getFirst(Lists.TOTAL));
        assertEquals(2, list.getBody().size());

        assertEquals(3, c.getApiContract(CTX, "p1", "pets", null).version(), "no version = the latest");
        assertNull(port.calls.get(1)[3]);
        assertEquals(2, c.getApiContract(CTX, "p1", "pets", 2).version());
        assertEquals(2, port.calls.get(2)[3]);

        ResponseEntity<List<ApiContractPort.ContractOperation>> ops = c.listApiContractOperations(CTX, "p1", "pets", 1);
        assertEquals("1", ops.getHeaders().getFirst(Lists.TOTAL));
        assertEquals("getPet", ops.getBody().get(0).operationId());
        assertEquals(1, port.calls.get(3)[3]);
        assertEquals(List.of(Permissions.PACKAGE_READ, Permissions.PACKAGE_READ, Permissions.PACKAGE_READ,
                Permissions.PACKAGE_READ), grants.checked);
    }

    @Test
    void theReadsWithoutPackageReadAreForbidden() {
        Port port = new Port();
        ApiContractController c = new ApiContractController(port, new Grants());
        assertEquals(403, assertThrows(ApiProblem.class, () -> c.listApiContracts(CTX, "p1")).status());
        assertEquals(403, assertThrows(ApiProblem.class, () -> c.getApiContract(CTX, "p1", "pets", null)).status());
        assertEquals(403, assertThrows(ApiProblem.class, () -> c.listApiContractOperations(CTX, "p1", "pets", null))
                .status());
        assertTrue(port.calls.isEmpty());
    }
}
