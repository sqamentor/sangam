// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.ProcessInstance;

import org.sangam.packs.port.PackRuntime;

/**
 * Item 11 (owner E1, E3, M5): the tender refunds every EMD to its source, one child per bidder, released only against
 * that bidder's EMD capture recorded when the bids were received: at most the captured amount, once, to the same
 * source. No bidder register: held. The awarded bidder is left out of the unsuccessful bidders' refunds.
 */
class TenderEmdRefundTest {

    static ProcessEngine engine;
    private static final String TENANT = "t-emd";
    private static final Path PACK = Path.of("src/main/resources/packs/tender");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String f(String n, String v) {
        return "<operaton:field name=\"" + n + "\"><operaton:string>" + v + "</operaton:string></operaton:field>";
    }

    private static String items(String id, String extra) {
        return "<serviceTask id=\"" + id + "\" operaton:class=\"" + BatchItemsDelegate.class.getName()
                + "\"><extensionElements>" + f("source", "emdRegister") + f("keyField", "bidderId")
                + f("amount", "emdAmount") + f("captureField", "emdCaptureRef") + extra
                + "</extensionElements></serviceTask>";
    }

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-tender-emd;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        org.sangam.packs.PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        org.sangam.packs.port.TenantCurrencies.install(t -> TENANT.equals(t) ? "INR" : null);
        String ns = "xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" targetNamespace=\"t\"";
        // bids received: record the captures; then the unsuccessful bidders' refund items (the awarded one left out)
        String record = "<definitions " + ns + "><process id=\"record\" isExecutable=\"true\""
                + " operaton:historyTimeToLive=\"30\"><startEvent id=\"s\"/>"
                + items("rec", f("recordCapture", "true") + f("itemsVar", "emdItems"))
                + items("uns", f("excludeKeyVar", "contractorId") + f("itemsVar", "emdRefundItems"))
                + "<userTask id=\"w\"/><endEvent id=\"e\"/><sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"rec\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"rec\" targetRef=\"uns\"/>"
                + "<sequenceFlow id=\"f3\" sourceRef=\"uns\" targetRef=\"w\"/>"
                + "<sequenceFlow id=\"f4\" sourceRef=\"w\" targetRef=\"e\"/></process></definitions>";
        // one bidder's refund: CaptureRefunds.Release as the start listener of the refund step
        String refund = "<definitions " + ns + "><process id=\"refund\" isExecutable=\"true\""
                + " operaton:historyTimeToLive=\"30\"><startEvent id=\"s\"/><userTask id=\"pay\"><extensionElements>"
                + "<operaton:executionListener event=\"start\" class=\"" + CaptureRefunds.Release.class.getName()
                + "\">" + f("captureVar", "emdCaptureRef") + f("purpose", "EMD refund")
                + "</operaton:executionListener></extensionElements></userTask><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"pay\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"pay\" targetRef=\"e\"/></process></definitions>";
        engine.getRepositoryService().createDeployment().tenantId(TENANT).addString("record.bpmn", record)
                .addString("refund.bpmn", refund).deploy();
    }

    @AfterAll
    static void tearDown() {
        org.sangam.packs.port.TenantCurrencies.install(null);
        engine.close();
        engine = null;
        PackRuntime.reset();
    }

    private static Map<String, Object> bid(String id, long emd, String ref) {
        Map<String, Object> m = new HashMap<>();
        m.put("bidderId", id);
        m.put("emdAmount", emd);
        if (ref != null) {
            m.put("emdCaptureRef", ref);
        }
        return m;
    }

    private static ProcessInstance record(List<Map<String, Object>> register, String contractor) {
        Map<String, Object> v = new HashMap<>();
        if (register != null) {
            v.put("emdRegister", Vars.toJson(register));
        }
        v.put("contractorId", contractor);
        v.put("currency", "INR");
        return engine.getRuntimeService().createProcessInstanceByKey("record").processDefinitionTenantId(TENANT)
                .setVariables(v).execute();
    }

    private static ProcessInstance refund(String bidder, long amount, String ref) {
        Map<String, Object> v = new HashMap<>();
        v.put("bidderId", bidder);
        v.put("amount", amount);
        v.put("emdCaptureRef", ref);
        v.put("currency", "INR");
        return engine.getRuntimeService().createProcessInstanceByKey("refund").processDefinitionTenantId(TENANT)
                .setVariables(v).execute();
    }

    private static List<Map<String, Object>> list(ProcessInstance pi, String name) throws IOException {
        Object raw = engine.getRuntimeService().getVariable(pi.getId(), name);
        String json = raw instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : String.valueOf(raw);
        return JSON.readValue(json, new TypeReference<>() { });
    }

    private static String held(Runnable r) {
        String m = assertThrows(ProcessEngineException.class, r::run).getMessage();
        assertTrue(m.startsWith(MoneyOutApproval.HELD), m);
        return m;
    }

    @Test
    void eachBidderIsRefundedOnceAgainstItsOwnCapture() throws IOException {
        ProcessInstance pi = record(List.of(bid("B1", 2_000_000, "CAP-A1"), bid("B2", 2_000_000, "CAP-A2"),
                bid("B3", 1_500_000, "CAP-A3")), "B1");
        List<Map<String, Object>> refunds = list(pi, "emdRefundItems");
        assertEquals(List.of("B2", "B3"), refunds.stream().map(m -> m.get("key")).toList(),
                "the awarded bidder B1 is not refunded with the unsuccessful bidders");
        assertEquals("capture:CAP-A2", refunds.get(0).get("payeeAccount"), "to the EMD's source, never typed");
        assertEquals(3, list(pi, "emdItems").size());
        // B2 refunded its captured EMD: released by the capture record
        ProcessInstance ok = refund("B2", 2_000_000, "CAP-A2");
        assertEquals(CaptureRefunds.RELEASED_BY, engine.getRuntimeService().getVariable(ok.getId(), "approvedBy"));
        // more than captured, an unknown capture, or a second refund of the same capture: held
        assertTrue(held(() -> refund("B3", 1_500_001, "CAP-A3")).contains("refused"));
        assertTrue(held(() -> refund("B9", 100, "CAP-UNKNOWN")).contains("no capture record"));
        String again = held(() -> refund("B2", 1_000_000, "CAP-A2"));
        assertTrue(again.contains("refused") || again.contains("no capture record"), again);
    }

    @Test
    void noRegisterOrNoCaptureIsHeld() {
        assertTrue(held(() -> record(null, "B1")).contains("absent or empty"));
        assertTrue(held(() -> record(List.of(bid("B1", 2_000_000, null)), "B1")).contains("capture reference"));
        assertTrue(held(() -> record(List.of(bid("B1", 2_000_000, "CAP-B1")), null)).contains("contractorId"));
    }

    @Test
    void theShippedTenderRefundsPerBidderUnderTheCaptureRecord() throws IOException {
        String parent = Files.readString(PACK.resolve("sangam-tender.bpmn"), StandardCharsets.UTF_8);
        assertFalse(parent.contains("<operaton:string>emd.refund</operaton:string>"),
                "the tender no longer refunds all EMDs in one portal call");
        assertFalse(parent.contains("isForCompensation=\"true\""));
        assertTrue(parent.contains("sourceRef=\"td_bidsGw\" targetRef=\"td_emdRegister\""));
        assertTrue(parent.contains("<operaton:string>recordCapture</operaton:string>") || parent.contains(
                "name=\"recordCapture\"><operaton:string>true</operaton:string>"), "captures recorded at bids");
        assertTrue(parent.contains("name=\"excludeKeyVar\"><operaton:string>contractorId</operaton:string>"));
        assertTrue(parent.contains("<operaton:string>sangam-tender-emd-refund</operaton:string>"));
        String child = Files.readString(PACK.resolve("sangam-tender-emd-refund.bpmn"), StandardCharsets.UTF_8);
        assertTrue(child.contains("org.sangam.packs.core.CaptureRefunds$Release"));
        assertTrue(child.contains("<operaton:string>emd.refund</operaton:string>"));
        String pack = Files.readString(PACK.resolve("pack.json"));
        assertTrue(pack.contains("sangam-tender-emd-refund.bpmn") && pack.contains("eproc.emdRegister"));
    }
}
