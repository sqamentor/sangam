// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.packs.port.ConnectorGateway;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.port.ReconciliationRequest;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * Decision 225 (IFC-189): a procurement case learns whether its department is scientific only from its bound tenant
 * profile ({@code config.profile} {@code profile.get}, path {@code procurement.scientificDepartment}); a case whose value
 * is missing, of another type or not read is refused with {@code DEPARTMENT_CLASS_NOT_CONFIRMED} before any sanction
 * (fail closed). With a confirmed value, tender and GeM purchase evaluate {@code ifms-procurement-mode} with
 * {@code amount} and {@code scientificDepartment}. A spoofed start variable never stands in for the profile value.
 *
 * <p>The profile is answered by a test gateway with the connector's real answer shape (a {@code values} map and the
 * {@code binding}); every other connector goes to the simulator.
 */
class DepartmentClassLookupTest {

    static final String PATH = "procurement.scientificDepartment";
    static final String TENDER = "sangam-tender";
    static final String GEM = "sangam-gem-purchase";
    static final long LAKH = 100_000L * 100; // one lakh rupees in paise (pack money is minor units)

    static ProcessEngine engine;
    static PackDriver driver;
    static ProfileGateway gateway;
    static int seq;

    /** Answers config.profile from {@link #answers} (by business key); the simulator answers the rest. */
    static final class ProfileGateway implements ConnectorGateway {
        final SimulatorConnectorGateway sim = new SimulatorConnectorGateway();
        final Map<String, ConnectorResult> answers = new ConcurrentHashMap<>();
        final List<ConnectorRequest> profileCalls = new CopyOnWriteArrayList<>();

        @Override
        public ConnectorResult query(ConnectorRequest r) {
            if (!"config.profile".equals(r.connector())) {
                return sim.query(r);
            }
            profileCalls.add(r);
            String root = r.businessKey() == null ? "" : r.businessKey().split("/BID")[0];
            return answers.getOrDefault(root, ConnectorResult.failed("CONFIG_ERROR", "no profile in this test"));
        }

        @Override
        public ConnectorResult submit(ConnectorRequest r) {
            return sim.submit(r);
        }

        @Override
        public ConnectorResult lookup(String tenantId, String connector, String operationId) {
            return sim.lookup(tenantId, connector, operationId);
        }

        @Override
        public ConnectorResult cancel(String tenantId, String connector, String operationId, String reason) {
            return sim.cancel(tenantId, connector, operationId, reason);
        }

        @Override
        public String openReconciliation(ReconciliationRequest request) {
            return sim.openReconciliation(request);
        }

        @Override
        public CapabilityClass capabilityOf(String connector) {
            return sim.capabilityOf(connector);
        }
    }

    static ConnectorResult profileValue(Object value) {
        Map<String, Object> values = new HashMap<>();
        values.put(PATH, value); // ConfigConnector answers a path the profile does not hold with a null value
        return ConnectorResult.completed(null, Map.of("values", values, "binding", "IN@2026.11.0>GOI@2026.11.0#test"));
    }

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        gateway = new ProfileGateway();
        PackRuntime.install(PackRuntime.defaults().withGateway(gateway));
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-d225;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel): as SangamProcessEnginePlugin does
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
        driver = new PackDriver(engine);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        PackRuntime.reset();
    }

    /** Starts {@code key} with the smoke bag plus {@code kv}, the profile answering {@code profile}; returns the key. */
    static String start(String key, ConnectorResult profile, Object... kv) {
        String bk = "D225/" + key + "/" + (++seq);
        if (profile != null) {
            gateway.answers.put(bk, profile);
        }
        Map<String, Object> v = AllPacksSmokeTest.bag();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            v.put(kv[i].toString(), kv[i + 1]);
        }
        engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(bk).setVariables(v)
                .processDefinitionTenantId(PackTestTenant.ID).execute();
        return bk;
    }

    static List<PackAuditEvent> audit(String bk) {
        return ((LoggingAuditSink) PackRuntime.audit()).timeline(bk);
    }

    static void assertRefused(String bk) {
        driver.run(bk, new FormAnswers(engine)::answer, 80);
        assertTrue(driver.ended(bk), bk + ": case still running");
        assertEquals(0, engine.getRuntimeService().createIncidentQuery().count(), "incidents");
        assertTrue(String.valueOf(driver.var(bk, "rejectionReasonCodes")).contains("DEPARTMENT_CLASS_NOT_CONFIRMED"),
                bk + ": coded reason " + driver.var(bk, "rejectionReasonCodes"));
        assertTrue(audit(bk).stream().noneMatch(e -> "CASE_APPROVED".equals(e.type())), bk + ": approved");
        assertTrue(audit(bk).stream().noneMatch(e -> "RULE_EVALUATED".equals(e.type())
                && "ifms-procurement-mode".equals(e.details().get("decision"))), bk + ": mode evaluated without a class");
        assertEquals(null, driver.var(bk, "scientificDepartment"), bk + ": a value stood in for the profile");
    }

    // ------------------------------------------------------------------ fail closed

    @Test
    void aProfileWithoutTheValueRefusesTheTenderEvenWithASpoofedStartVariable() {
        assertRefused(start(TENDER, profileValue(null), "scientificDepartment", true, "estimateAmount", 30 * LAKH,
                "amount", 30 * LAKH));
    }

    /**
     * Coordinator round 6 (decisions 244 and 257): the exact answer ConfigConnector gives for a State or the GOI base,
     * whose {@code procurement.scientificDepartment} is a [U] placeholder without a value (sangam-config
     * ProfileDataTest asserts that shape on the shipped profiles): a null value and the path listed under
     * {@code placeholders}. Tender and GeM purchase refuse it at run time.
     */
    @Test
    void anUndeclaredPlaceholderAnswerRefusesTenderAndGemPurchase() {
        for (String binding : List.of("IN@2026.11.0>AP@2026.11.1#test", "IN@2026.11.0>GOI@2026.11.1#test")) {
            Map<String, Object> values = new HashMap<>();
            values.put(PATH, null);
            ConnectorResult undeclared = ConnectorResult.completed(null,
                    Map.of("values", values, "binding", binding, "placeholders", List.of(PATH)));
            assertRefused(start(TENDER, undeclared, "scientificDepartment", true, "estimateAmount", 30 * LAKH,
                    "amount", 30 * LAKH));
            assertRefused(start(GEM, undeclared, "scientificDepartment", false, "gemAvailable", true,
                    "quantity", 1, "unitPrice", 20_000L * 100));
        }
    }

    @Test
    void aFailedProfileReadRefusesTheTender() {
        assertRefused(start(TENDER, ConnectorResult.failed("BINDING_UNRESOLVABLE", "binding gone"),
                "estimateAmount", 30 * LAKH, "amount", 30 * LAKH));
        assertRefused(start(TENDER, ConnectorResult.pending(null), "estimateAmount", 30 * LAKH, "amount", 30 * LAKH));
    }

    @Test
    void aValueThatIsNotABooleanRefusesTheTender() {
        assertRefused(start(TENDER, profileValue("true"), "estimateAmount", 30 * LAKH, "amount", 30 * LAKH));
    }

    @Test
    void aProfileWithoutTheValueRefusesTheGemPurchaseBeforeTheBudgetCheck() {
        String bk = start(GEM, profileValue(null), "scientificDepartment", false, "gemAvailable", true,
                "quantity", 1, "unitPrice", 20_000L * 100);
        assertRefused(bk);
        assertFalse(gateway.sim.journal().stream().anyMatch(r -> bk.equals(r.businessKey())
                && "ifms.budget".equals(r.connector())), bk + ": budget checked after a refusal");
    }

    // ------------------------------------------------------------------ the profile's mode

    @Test
    void theTenderTakesTheModeOfItsValueAndTheProfileClass() {
        String open = start(TENDER, profileValue(false), "scientificDepartment", true, "estimateAmount", 80 * LAKH,
                "amount", 80 * LAKH);
        driver.runJobs();
        assertEquals(Boolean.FALSE, driver.var(open, "scientificDepartment"), "the profile wins over the start variable");
        assertEquals("OPEN_TENDER", driver.var(open, "procurementMode"));
        assertNotNull(driver.var(open, "ruleRef"));
        assertEquals("IN@2026.11.0>GOI@2026.11.0#test", driver.var(open, "scientificDepartmentBinding"));
        String limited = start(TENDER, profileValue(true), "scientificDepartment", false, "estimateAmount", 80 * LAKH,
                "amount", 80 * LAKH);
        driver.runJobs();
        assertEquals(Boolean.TRUE, driver.var(limited, "scientificDepartment"));
        assertEquals("LIMITED_TENDER", driver.var(limited, "procurementMode"), "scientific: limited tender up to 1 crore");
        String limitedAll = start(TENDER, profileValue(false), "estimateAmount", 30 * LAKH, "amount", 30 * LAKH);
        driver.runJobs();
        assertEquals("LIMITED_TENDER", driver.var(limitedAll, "procurementMode"));
    }

    @Test
    void theProfileIsAskedForTheFixedPathOnly() {
        String bk = start(TENDER, profileValue(false), "estimateAmount", 30 * LAKH, "amount", 30 * LAKH,
                "paths", "procurement.gemMandatory");
        driver.runJobs();
        List<ConnectorRequest> calls = gateway.profileCalls.stream().filter(r -> bk.equals(r.businessKey())).toList();
        assertFalse(calls.isEmpty(), "profile read");
        for (ConnectorRequest r : calls) {
            assertEquals("profile.get", r.operation());
            assertEquals(PATH, r.payload().get("paths"), "the path is the model's literal, never a variable");
            assertTrue(r.operationId() == null, "a read, not an effect");
        }
    }

    @Test
    void theGemPurchaseRoutesOnTheProfileMode() {
        String direct = start(GEM, profileValue(false), "gemAvailable", true, "quantity", 1, "unitPrice", 20_000L * 100,
                "gemRoute", "BID", "autoApprove", false);
        driver.runJobs();
        assertEquals("DIRECT", driver.var(direct, "procurementMode"));
        assertEquals("DIRECT", driver.var(direct, "gemRoute"));
        assertEquals(Boolean.TRUE, driver.var(direct, "autoApprove"), "GP-29 auto-approval below 25,000");
        String l1 = start(GEM, profileValue(false), "gemAvailable", true, "quantity", 1, "unitPrice", 5 * LAKH);
        driver.runJobs();
        assertEquals("GEM_L1", driver.var(l1, "procurementMode"));
        assertEquals("L1", driver.var(l1, "gemRoute"));
        String bid = start(GEM, profileValue(false), "gemAvailable", true, "quantity", 1, "unitPrice", 30 * LAKH);
        driver.runJobs();
        assertEquals("LIMITED_TENDER", driver.var(bid, "procurementMode"));
        assertEquals("BID", driver.var(bid, "gemRoute"));
        String outside = start(GEM, profileValue(false), "gemAvailable", false, "quantity", 1, "unitPrice", 5 * LAKH);
        driver.runJobs();
        assertEquals("OUTSIDE_GEM", driver.var(outside, "gemRoute"));
    }

    @Test
    void aScientificDepartmentBuysSmallValuesDirectOrOnGemL1() {
        // decision 225 round 4: the DIRECT and GEM_L1 bands match every department class; before the fix a scientific
        // department's small purchase skipped them (scientificDepartment=false) and fell to LIMITED_TENDER
        String direct = start(GEM, profileValue(true), "gemAvailable", true, "quantity", 1, "unitPrice", 20_000L * 100);
        driver.runJobs();
        assertEquals(Boolean.TRUE, driver.var(direct, "scientificDepartment"));
        assertEquals("DIRECT", driver.var(direct, "procurementMode"));
        String l1 = start(GEM, profileValue(true), "gemAvailable", true, "quantity", 1, "unitPrice", 5 * LAKH);
        driver.runJobs();
        assertEquals("GEM_L1", driver.var(l1, "procurementMode"));
        String bid = start(GEM, profileValue(true), "gemAvailable", true, "quantity", 1, "unitPrice", 30 * LAKH);
        driver.runJobs();
        assertEquals("LIMITED_TENDER", driver.var(bid, "procurementMode"), "above 10 lakh the class bands still apply");
    }
}
