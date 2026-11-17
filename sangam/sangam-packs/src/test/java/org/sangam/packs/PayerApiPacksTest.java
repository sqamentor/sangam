// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * M-133 (owner decision 107): the CMS-0057-F payer-API packs {@code sec.ushealth.payer-to-payer} and
 * {@code sec.ushealth.provider-access}. Nothing leaves or is requested without the patient's opt-in (payer-to-payer)
 * or with an unconfirmed attribution / opt-out check (provider access), and every unconfirmed check holds the case for
 * a person (SL-115) instead of releasing data.
 */
class PayerApiPacksTest {

    static ProcessEngine engine;
    static PackDriver driver;
    static int seq;

    static final String REQUEST = "sangam-sec-ushealth-p2p-request";
    static final String RESPOND = "sangam-sec-ushealth-p2p-respond";
    static final String PROVIDER = "sangam-sec-ushealth-provider-access";

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-m133;DB_CLOSE_DELAY=-1");
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
        // R8-heap2: a test class stays loaded for the whole run; its statics must not keep a closed engine
        engine = null;
        driver = null;
    }

    /** Starts {@code key} with the generic bag plus {@code kv} (a null value removes the key) and runs to a person. */
    static String run(String key, Object... kv) {
        String bk = "M133/" + key + "/" + (++seq);
        Map<String, Object> v = AllPacksSmokeTest.bag();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] == null) {
                v.remove(kv[i].toString());
            } else {
                v.put(kv[i].toString(), kv[i + 1]);
            }
        }
        engine.getRuntimeService().createProcessInstanceByKey(key).businessKey(bk).setVariables(v)
                .processDefinitionTenantId(PackTestTenant.ID).execute();
        driver.run(bk, t -> null, 40);
        return bk;
    }

    static boolean called(String bk, String connector) {
        return ((SimulatorConnectorGateway) PackRuntime.gateway()).journal().stream()
                .anyMatch(r -> bk.equals(r.businessKey()) && connector.equals(r.connector()));
    }

    static boolean reached(String bk, String activityId) {
        String root = driver.rootId(bk);
        return root != null && engine.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(root)
                .activityId(activityId).count() > 0;
    }

    static List<String> openTasks(String bk) {
        return engine.getTaskService().createTaskQuery().processInstanceBusinessKeyLike(bk + "%").list().stream()
                .map(t -> t.getTaskDefinitionKey()).toList();
    }

    static void noIncidents() {
        assertEquals(0, engine.getRuntimeService().createIncidentQuery().count(), "incidents");
    }

    // ------------------------------------------------------------------------------------- payer-to-payer: new payer

    @Test
    void withoutOptInNothingIsRequested() {
        String bk = run(REQUEST, "p2pOptIn", false);
        assertTrue(reached(bk, "pq_endNoOptIn"), "closed as not opted in");
        assertFalse(called(bk, "payer.memberMatch"), "member match without opt-in");
        assertFalse(called(bk, "payer.p2pData"), "data requested without opt-in");
        noIncidents();
    }

    @Test
    void aMissingOptInAnswerCountsAsNoOptIn() {
        String bk = run(REQUEST, "p2pOptIn", null);
        assertTrue(reached(bk, "pq_endNoOptIn"), "the default is no opt-in (fail closed)");
        assertFalse(called(bk, "payer.memberMatch"));
    }

    @Test
    void anUnconfirmedMemberMatchHoldsTheCaseAndRequestsNothing() {
        String bk = run(REQUEST, "p2pOptIn", true, "sim_payer_memberMatch", "FAILED:PAYER_DOWN");
        assertEquals(List.of("pq_matchHold"), openTasks(bk), "member services correct the member data");
        assertFalse(called(bk, "payer.p2pData"), "data requested for an unmatched member");
        noIncidents();
    }

    @Test
    void dataWithoutABundleWaitsAndIsNeverStored() {
        String bk = run(REQUEST, "p2pOptIn", true, "sim_payer_p2pData", "UNKNOWN");
        assertTrue(reached(bk, "pq_wait"), "waits for the data or the response time");
        assertFalse(called(bk, "payer.memberRecord"), "stored without a confirmed bundle");
        assertTrue(driver.var(bk, "p2pResponseDueAt") != null, "the response clock is set");
    }

    @Test
    void optedInMatchedAndReceivedDataIsStored() {
        String bk = run(REQUEST, "p2pOptIn", true);
        assertTrue(reached(bk, "pq_end"), "received and stored with the demo answers");
        assertTrue(called(bk, "payer.memberRecord"));
        noIncidents();
    }

    // ------------------------------------------------------------------------------- payer-to-payer: previous payer

    @Test
    void aRequestWithoutOptInAttestationIsRefusedWithoutAnyExport() {
        String bk = run(RESPOND, "optInAttested", false);
        assertTrue(reached(bk, "pr_endNoOptIn"));
        assertFalse(called(bk, "payer.memberRecord"), "member data touched without attestation");
        assertFalse(called(bk, "payer.p2pData"), "data sent without attestation");
    }

    @Test
    void anUnconfirmedLocalMatchHoldsBeforeAnyDataLeaves() {
        String bk = run(RESPOND, "optInAttested", true, "sim_payer_memberRecord", "FAILED:MPI_DOWN");
        assertEquals(List.of("pr_matchHold"), openTasks(bk));
        assertFalse(called(bk, "payer.p2pData"), "data sent for an unconfirmed member");
    }

    @Test
    void anAttestedMatchedRequestIsAnswered() {
        String bk = run(RESPOND, "optInAttested", true);
        assertTrue(reached(bk, "pr_end"));
        assertTrue(called(bk, "payer.p2pData"));
        noIncidents();
    }

    // -------------------------------------------------------------------------------------------- provider access

    @Test
    void aPatientWhoOptedOutIsRefused() {
        String bk = run(PROVIDER, "sim_payer_memberRecord", "patientId=P;optedOut=true;bundleRef=B");
        assertTrue(reached(bk, "pv_endRefused"));
        assertFalse(called(bk, "payer.providerAccess"), "answered for an opted-out patient");
    }

    @Test
    void aProviderWithoutTreatmentRelationshipIsRefused() {
        String bk = run(PROVIDER, "sim_payer_attribution", "attributed=false");
        assertTrue(reached(bk, "pv_endRefused"));
        assertFalse(called(bk, "payer.providerAccess"));
    }

    @Test
    void anUnconfirmedAttributionHoldsTheRequest() {
        String bk = run(PROVIDER, "sim_payer_attribution", "FAILED:ATTRIBUTION_DOWN");
        assertEquals(List.of("pv_checkHold"), openTasks(bk), "member services confirm at the source");
        assertFalse(called(bk, "payer.providerAccess"), "answered on an unconfirmed attribution");
    }

    @Test
    void anAttributedProviderOfAPatientWhoDidNotOptOutIsAnswered() {
        String bk = run(PROVIDER);
        assertTrue(reached(bk, "pv_end"));
        assertTrue(called(bk, "payer.providerAccess"));
        noIncidents();
    }
}
