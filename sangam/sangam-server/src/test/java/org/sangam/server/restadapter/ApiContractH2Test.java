// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.contract.ApiContractStore;
import org.sangam.integration.connector.contract.JdbcApiContractStore;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.adapter.jdbc.ApiContractAdapter;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.adapter.jdbc.JdbcPackageAdapter;
import org.sangam.rest.port.ApiContractPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.PackageSigning;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * M-068 on the real changelog (H2, table API_CONTRACT of changeset 0043-08): the JDBC store versions immutably, answers
 * the stored version for the same document, enforces the 120-character contract id the VARCHAR(255) column does not,
 * admits only usable contracts, keeps versions contiguous under concurrent writers and never shows another tenant's
 * contracts; the port checks the package of the caller's tenant first.
 */
class ApiContractH2Test {

    static final String T = "dept-x";
    static final RequestContext ALICE = new RequestContext(T, "alice", Set.of(), "seat-a", null, null, null, null, "OIDC");
    static final RequestContext MALLORY = new RequestContext("dept-y", "mallory", Set.of(), "seat-m", null, null, null, null,
            "OIDC");

    static final String OPENAPI = """
            {"openapi":"3.0.3","info":{"title":"pets","version":"1"},
             "paths":{
               "/pets/{id}":{"get":{"operationId":"getPet","responses":{"200":{"description":"ok",
                 "content":{"application/json":{"schema":{"$ref":"#/components/schemas/Pet"}}}}}}},
               "/pets":{"post":{"operationId":"addPet","requestBody":{"content":{"application/json":
                 {"schema":{"$ref":"#/components/schemas/Pet"}}}},"responses":{"201":{"description":"created"}}}}},
             "components":{"schemas":{"Pet":{"type":"object","required":["name"],
               "properties":{"name":{"type":"string"}}}}}}""";

    JdbcDataSource ds;
    TxSupport tx;
    JdbcApiContractStore store;
    JdbcAuditAdapter audit;
    PackagePort packages;

    @BeforeEach
    void database() {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m068" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        SangamSchema.apply(ds);
        tx = new TxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds));
        store = new JdbcApiContractStore(tx.dialect(), ApiContractAdapter.joined(tx));   // as ApiContractWiring
        RestProperties props = new RestProperties();
        PackageSigning unsignedDev = new PackageSigning(null, AuditCheckpoints.ed25519Keys(List.of()), true, false, "dev");
        audit = new JdbcAuditAdapter(tx, null, null, props);
        packages = new JdbcPackageAdapter(tx, PackagePromotionH2Test.DEPLOYMENTS, PackagePromotionH2Test.ALL, audit, props,
                unsignedDev);
    }

    String packageOf(RequestContext who) {
        return draft(who).id();
    }

    PackagePort.Package draft(RequestContext who) {
        Map<String, String> resources = new LinkedHashMap<>();
        resources.put("pets.bpmn", "<definitions id=\"pets\"/>");
        return packages.create(who, new PackagePort.CreatePackageRequest("pets-" + UUID.randomUUID().toString().substring(0, 8),
                "Pets", resources));
    }

    ApiContractAdapter port() {
        return new ApiContractAdapter(store, packages, tx, audit);
    }

    long auditRows(String chain) {
        return Transactions.of(ds).inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE event_type=?"
                + " AND chain_id=?", rs -> rs.getLong(1), ApiContractAdapter.EVENT, chain).orElseThrow());
    }

    long contractRows() {
        return Transactions.of(ds).inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM API_CONTRACT",
                rs -> rs.getLong(1)).orElseThrow());
    }

    static String code(Runnable r) {
        return assertThrows(ApiProblem.class, r::run).code();
    }

    @Test
    void versionsAreImmutableAndTheSameDocumentAnswersTheStoredVersion() {
        ApiContractStore.ApiContract v1 = store.put(T, "p1", "pets", OPENAPI, "alice");
        assertEquals(1, v1.version());
        assertEquals(ApiContractStore.Format.OPENAPI, v1.format());
        assertEquals("3.0.3", v1.specVersion());
        assertEquals(ApiContractStore.sha256(OPENAPI), v1.sha256());

        ApiContractStore.ApiContract again = store.put(T, "p1", "pets", OPENAPI, "bob");
        assertEquals(1, again.version(), "an identical document is the stored version, not a new one");
        ApiContractStore.ApiContract v2 = store.put(T, "p1", "pets", OPENAPI.replace("\"pets\",\"version\"",
                "\"pets v2\",\"version\""), "bob");
        assertEquals(2, v2.version());

        assertEquals(2, store.find(T, "p1", "pets", null).orElseThrow().version(), "no version = the latest");
        assertEquals(v1.sha256(), store.find(T, "p1", "pets", 1).orElseThrow().sha256(), "version 1 is unchanged");
        assertEquals("pets", store.find(T, "p1", "pets", 1).orElseThrow().document().path("info").path("title").asText());
        assertTrue(store.find(T, "p1", "pets", 3).isEmpty());
        assertTrue(store.find(T, "p1", "pets", 0).isEmpty());

        List<String> createdBy = Transactions.of(ds).inTransaction(c -> Jdbc.query(c,
                "SELECT created_by FROM API_CONTRACT WHERE tenant_id=? ORDER BY version", rs -> rs.getString(1), T));
        assertEquals(List.of("alice", "bob"), createdBy, "the identical put by bob stored nothing");
    }

    @Test
    void theListHoldsTheLatestVersionOfEachContractOfThePackageOnly() {
        store.put(T, "p1", "zeta", OPENAPI);
        store.put(T, "p1", "alpha", OPENAPI);
        store.put(T, "p1", "alpha", OPENAPI.replace("\"version\":\"1\"", "\"version\":\"2\""));
        store.put(T, "p2", "other", OPENAPI);
        store.put("dept-y", "p1", "foreign", OPENAPI);

        List<ApiContractStore.ApiContract> list = store.list(T, "p1");
        assertEquals(List.of("alpha", "zeta"), list.stream().map(ApiContractStore.ApiContract::contractId).toList());
        assertEquals(List.of(2, 1), list.stream().map(ApiContractStore.ApiContract::version).toList());
        assertTrue(store.find(T, "p1", "foreign", null).isEmpty(), "another tenant's contract is invisible");
        assertTrue(store.list("dept-z", "p1").isEmpty());
    }

    @Test
    void theStoreEnforcesTheContractIdAndColumnLimitsItself() {
        String id120 = "c".repeat(120);
        assertEquals(id120, store.put(T, "p1", id120, OPENAPI).contractId());
        String id121 = "c".repeat(121);
        assertThrows(IllegalArgumentException.class, () -> store.put(T, "p1", id121, OPENAPI),
                "the column is VARCHAR(255); the store refuses more than 120");
        assertTrue(store.find(T, "p1", id121, null).isEmpty());
        for (String bad : List.of("", "has space", "slash/inside", "semi;colon")) {
            assertThrows(IllegalArgumentException.class, () -> store.put(T, "p1", bad, OPENAPI), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> store.put("t".repeat(65), "p1", "pets", OPENAPI));
        assertThrows(IllegalArgumentException.class, () -> store.put(T, "p".repeat(65), "pets", OPENAPI));
        assertThrows(IllegalArgumentException.class, () -> store.put(T, " ", "pets", OPENAPI));
        assertThrows(IllegalArgumentException.class, () -> store.put(T, "p1", "pets", OPENAPI, "u".repeat(256)));
        assertThrows(IllegalArgumentException.class, () -> store.put(T, "p1", "pets",
                OPENAPI.replace("\"3.0.3\"", "\"3.0.3-with-a-very-long-suffix\"")), "spec_version is VARCHAR(16)");
        assertEquals(1L, contractRows(), "nothing but the 120-character contract was stored");
    }

    @Test
    void onlyAUsableContractIsAdmitted() {
        assertThrows(IllegalArgumentException.class, () -> store.put(T, "p1", "s", "{\"swagger\":\"2.0\"}"));
        assertThrows(IllegalArgumentException.class, () -> store.put(T, "p1", "s", "[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> store.put(T, "p1", "s", "not json"));
        String external = OPENAPI.replaceFirst("#/components/schemas/Pet", "https://evil.example/pet.json");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> store.put(T, "p1", "ext", external)).getMessage()
                .contains("inside the document"), "an outside reference is refused before anything is stored");
        String duplicate = OPENAPI.replace("\"operationId\":\"addPet\"", "\"operationId\":\"getPet\"");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> store.put(T, "p1", "dup", duplicate)).getMessage()
                .contains("more than once"));
        String badPattern = OPENAPI.replace("\"name\":{\"type\":\"string\"}", "\"name\":{\"type\":\"string\",\"pattern\":\"(\"}");
        assertThrows(IllegalArgumentException.class, () -> store.put(T, "p1", "regex", badPattern),
                "a schema that does not compile is refused at store time, not at the first call");
        assertTrue(store.list(T, "p1").isEmpty());
    }

    @Test
    void concurrentWritersGetContiguousVersionsAndNoError() throws Exception {
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Callable<Integer>> work = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String doc = OPENAPI.replace("\"version\":\"1\"", "\"version\":\"w" + i + "\"");
                work.add(() -> store.put(T, "p1", "race", doc).version());
            }
            List<Integer> versions = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(work)) {
                versions.add(f.get());
            }
            versions.sort(Integer::compare);
            assertEquals(IntStream.rangeClosed(1, writers).boxed().toList(), versions);
            assertEquals(writers, store.find(T, "p1", "race", null).orElseThrow().version());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void thePortChecksThePackageOfTheCallersTenantFirst() {
        ApiContractAdapter port = port();
        String pkg = packageOf(ALICE);
        JsonNode doc = Json.tree(OPENAPI);

        ApiContractPort.ApiContractSummary s = port.put(ALICE, pkg, "pets", doc);
        assertEquals(1, s.version());
        assertEquals(T, s.tenantId());
        assertEquals("OPENAPI", s.format());
        assertEquals(1, port.put(ALICE, pkg, "pets", Json.tree(OPENAPI.replace("\n", "\n   "))).version(),
                "the same document with other whitespace is the same version");

        assertEquals("NOT_FOUND", code(() -> port.list(MALLORY, pkg)), "another tenant does not see the package");
        assertEquals("NOT_FOUND", code(() -> port.put(MALLORY, pkg, "pets", doc)));
        assertEquals("NOT_FOUND", code(() -> port.list(ALICE, "no-such-package")));
        assertEquals("NOT_FOUND", code(() -> port.get(ALICE, pkg, "pets", 2)));
        assertEquals("NOT_FOUND", code(() -> port.get(ALICE, pkg, "missing", null)));
        assertEquals("VALIDATION_FAILED", code(() -> port.put(ALICE, pkg, "c".repeat(121), doc)));
        assertEquals("VALIDATION_FAILED", code(() -> port.put(ALICE, pkg, "pets", Json.tree("{\"openapi\":\"2.0\"}"))));

        ApiContractPort.ApiContract got = port.get(ALICE, pkg, "pets", null);
        assertEquals("pets", got.document().path("info").path("title").asText());
        List<ApiContractPort.ContractOperation> ops = port.operations(ALICE, pkg, "pets", 1);
        assertEquals(List.of("getPet", "addPet"), ops.stream().map(ApiContractPort.ContractOperation::operationId).toList());
        assertEquals("GET", ops.get(0).method());
        assertEquals("/pets/{id}", ops.get(0).path());
        assertEquals("object", ops.get(1).requestSchema().path("type").asText(), "the local reference is inlined");
        assertNull(ops.get(0).requestSchema());
        assertFalse(ops.get(0).responseSchemas().isEmpty());
        assertEquals(List.of("pets"), port.list(ALICE, pkg).stream().map(ApiContractPort.ApiContractSummary::contractId)
                .toList());
    }

    /** Decision 115: the hash freezes at submit (R43), so a contract version is stored only into a DRAFT package. */
    @Test
    void aSubmittedOrApprovedPackageRefusesANewContractVersionWith409() {
        ApiContractAdapter port = port();
        PackagePort.Package p = draft(ALICE);
        port.put(ALICE, p.id(), "pets", Json.tree(OPENAPI));

        packages.transition(ALICE, p.id(), new PackagePort.TransitionRequest("submit", null, null, null));
        ApiProblem submitted = assertThrows(ApiProblem.class, () -> port.put(ALICE, p.id(), "pets",
                Json.tree(OPENAPI.replace("\"version\":\"1\"", "\"version\":\"2\""))));
        assertEquals(409, submitted.status());
        assertEquals("INVALID_TRANSITION", submitted.code());
        assertEquals("INVALID_TRANSITION", code(() -> port.put(ALICE, p.id(), "other", Json.tree(OPENAPI))),
                "a new contract id is refused as well");

        packages.transition(PackagePromotionH2Test.BOB, p.id(), new PackagePort.TransitionRequest("approve", "ok", null,
                null));
        ApiProblem approved = assertThrows(ApiProblem.class, () -> port.put(ALICE, p.id(), "pets",
                Json.tree(OPENAPI.replace("\"version\":\"1\"", "\"version\":\"3\""))));
        assertEquals(409, approved.status());
        assertEquals("INVALID_TRANSITION", approved.code());

        assertEquals(1L, contractRows(), "only the version stored while DRAFT exists");
        assertEquals(1, port.get(ALICE, p.id(), "pets", null).version(), "reads stay open after the freeze");
        assertEquals(1L, auditRows("pkg:" + p.key()), "a refused put writes no audit");
    }

    @Test
    void everyNewVersionIsAuditedOnThePackageChainAndAnIdenticalResendIsNot() {
        ApiContractAdapter port = port();
        PackagePort.Package p = draft(ALICE);
        String chain = "pkg:" + p.key();

        port.put(ALICE, p.id(), "pets", Json.tree(OPENAPI));
        assertEquals(1L, auditRows(chain));
        String payload = Transactions.of(ds).inTransaction(c -> Jdbc.one(c, "SELECT p.payload FROM AUDIT_EVENT e"
                + " JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id WHERE e.event_type=? AND e.chain_id=?",
                rs -> rs.getString(1), ApiContractAdapter.EVENT, chain).orElseThrow());
        JsonNode recorded = Json.tree(payload);
        assertEquals(T, recorded.path("tenantId").asText());
        assertEquals(p.id(), recorded.path("packageId").asText());
        assertEquals("pets", recorded.path("contractId").asText());
        assertEquals(1, recorded.path("version").asInt());
        assertEquals("alice", recorded.path("createdBy").asText());

        port.put(ALICE, p.id(), "pets", Json.tree(OPENAPI));
        assertEquals(1L, auditRows(chain), "an identical re-send answers the stored version and audits nothing");

        port.put(ALICE, p.id(), "pets", Json.tree(OPENAPI.replace("\"version\":\"1\"", "\"version\":\"2\"")));
        assertEquals(2L, auditRows(chain), "the next version is audited again");
    }

    @Test
    void aFailedAuditWriteLeavesNothingStored() {
        AuditPort failing = (AuditPort) java.lang.reflect.Proxy.newProxyInstance(AuditPort.class.getClassLoader(),
                new Class<?>[]{AuditPort.class}, (proxy, method, args) -> {
                    if (method.getName().equals("record")) {
                        throw new IllegalStateException("audit store unavailable");
                    }
                    return method.invoke(audit, args);
                });
        ApiContractAdapter port = new ApiContractAdapter(store, packages, tx, failing);
        String pkg = packageOf(ALICE);
        assertThrows(IllegalStateException.class, () -> port.put(ALICE, pkg, "pets", Json.tree(OPENAPI)));
        assertEquals(0L, contractRows(), "never stored without its audit");
        assertTrue(store.find(T, pkg, "pets", null).isEmpty());
    }
}
