R = 'D:/GITHUB  Project/Custom WorkFlow/sangam/'


def sub(path, pairs):
    s = open(R + path, encoding='utf-8').read()
    for a, b in pairs:
        assert a in s, a
        s = s.replace(a, b)
    open(R + path, 'w', encoding='utf-8', newline='\n').write(s)


# (1) posting keyed on the trace number
sub('sangam-server/src/main/java/org/sangam/server/bridge/PackGapConnectors.java', [
    ("""            for (Map<String, Object> c : claims) {
                posted += post(r, c, exceptions);
            }
""", """            for (int i = 0; i < claims.size(); i++) {
                posted += post(r, claims.get(i), i, exceptions);
            }
"""),
    ("private int post(ConnectorRequest r, Map<String, Object> c, List<Map<String, Object>> exceptions) {",
     "private int post(ConnectorRequest r, Map<String, Object> c, int index, List<Map<String, Object>> exceptions) {"),
    ('''leg(r, pcn, "reverse", paid.abs(), Map.of(), exceptions)''', '''leg(r, pcn, index, "reverse", paid.abs(), Map.of(), exceptions)'''),
    ('''leg(r, pcn, "consume", paid,''', '''leg(r, pcn, index, "consume", paid,'''),
    ('''leg(r, pcn, "write_off", writeOff,''', '''leg(r, pcn, index, "write_off", writeOff,'''),
    ('''leg(r, pcn, "transfer", pr,''', '''leg(r, pcn, index, "transfer", pr,'''),
    ("private int leg(ConnectorRequest r, String pcn, String op,", "private int leg(ConnectorRequest r, String pcn, int index, String op,"),
    ("""            java.util.UUID id = Hashes.uuidV5(r.operationId(), pcn + "|" + op);
""", """            // keyed on the 835 trace number (TRN02): a second remittance posts separately; the same 835 replayed, by a
            // retry or by another case it also mentions, finds the same references and posts nothing twice
            Object trace = r.input().get("eraTraceNumber");
            java.util.UUID id = trace == null || trace.toString().isBlank()
                    ? Hashes.uuidV5(r.operationId(), pcn + "|" + index + "|" + op)
                    : Hashes.uuidV5(REMIT_NS, r.tenantId() + "|" + trace + "|" + pcn + "|" + index + "|" + op);
"""),
    ("""        private final SangamConnector ledger;

        public RemittancePosting(""", """        private static final java.util.UUID REMIT_NS = java.util.UUID.fromString("3f0c6c2e-8a51-5a0e-9b3d-2f6b1c7d8e90");
        private final SangamConnector ledger;

        public RemittancePosting("""),
    ("""                    in.put(k, r.input().get(k));
                }
            }
            in.put("holder", pcn);""", """                    in.put(k, r.input().get(k));
                }
            }
            in.put("holder", pcn);"""),
])

# simulator: key=value data on effects; mdm.consumersDue with connectionCaseKey
sub('sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java', [
    ("""    private ConnectorResult firstOutcome(ConnectorRequest r, String directive) {
""", """    private ConnectorResult firstOutcome(ConnectorRequest r, String directive) {
        if ("mdm.install".equals(r.connector()) && r.payload().get("applicantRef") != null) {
            installed.put(r.tenantId() + "|" + r.payload().get("applicantRef"), String.valueOf(r.businessKey()));
        }
        if (directive != null && directive.contains("=")) {
            Map<String, Object> data = new HashMap<>(effectData(r));
            for (String pair : directive.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    data.put(pair.substring(0, eq).trim(), coerce(pair.substring(eq + 1).trim()));
                }
            }
            return ConnectorResult.completed("SIM-" + r.operationId().substring(0, 8), data);
        }
"""),
    ("""    private final Map<String, ReconciliationRequest> reconciliations""",
     """    private final Map<String, String> installed = new ConcurrentHashMap<>();
    private final Map<String, ReconciliationRequest> reconciliations"""),
    ("""            case "digilocker.pull" -> {""", """            case "mdm.consumersDue" -> {
                // the MDM knows the connection case of a newly energised consumer, so its first bill can release the credit
                List<Map<String, Object>> items = new ArrayList<>();
                installed.forEach((k, bk) -> {
                    if (k.startsWith(r.tenantId() + "|")) {
                        Map<String, Object> it = new HashMap<>();
                        it.put("key", k.substring(k.indexOf('|') + 1));
                        it.put("connectionCaseKey", bk);
                        items.add(it);
                    }
                });
                for (int i = 1; i <= 3; i++) {
                    items.add(Map.of("key", "CONS-" + i));
                }
                d.put("items", items);
            }
            case "digilocker.pull" -> {"""),
])
sub('sangam-packs/tools/packgen/sec_other.py', [
    ("""'mdm.install', 'register', 'submit', 'meterNo,applicantName', 'mdm', step='meter')""",
     """'mdm.install', 'register', 'submit', 'meterNo,applicantName,applicantRef', 'mdm', step='meter')"""),
    ("""('mdm.install', 'a', '', 'Meter data management')""",
     """('mdm.install', 'a', '', 'Meter data management; keeps the business key as the connection case of the consumer')"""),
])
sub('sangam-packs/tools/packgen/sec_more.py', [
    ("""('mdm.consumersDue', 'a', '', 'Consumers in the cycle')""",
     """('mdm.consumersDue', 'a', '', 'Consumers in the cycle: items with key and, for a new connection, connectionCaseKey')"""),
])

# (1)(2) claim pack
sub('sangam-packs/tools/packgen/sec_ushealth.py', [
    ("""'ledgerId=RECEIVABLES;line=CLAIM;period=OPEN;eraBalanced=true;postExceptionCount=0')""",
     """'ledgerId=RECEIVABLES;line=CLAIM;period=OPEN;eraBalanced=true;postExceptionCount=0;'
                        'receivableRaised=null;correctionNo=0;eraTraceNumber=no-trace')"""),
    ("""fields=['codingNotes*', 'priorAuthNumber'])""", """fields=['codingNotes*', 'priorAuthNumber', 'totalCharge'])"""),
    ("""    s.flow(ag, ra, None, 'accepted', default=True)
    s.seq(ra, al, w)
""", """    fg = s.xgw('cl_firstGw', 'Receivable raised already?')
    s.flow(ag, fg, None, 'accepted', default=True)
    s.flow(fg, ra, 'receivableRaised == null', 'first submission')
    s.flow(ra, al)
    dl = s.expr('cl_delta', 'Change in the charge (corrected claim)',
                "${execution.setVariable('receivableDelta', totalCharge - receivableRaised)}")
    s.flow(fg, dl, None, 'corrected claim', default=True)
    dg2 = s.xgw('cl_deltaGw', 'Charge changed?')
    s.flow(dl, dg2)
    s.flow(dg2, w, 'receivableDelta == 0', 'no')
    da = s.setv('cl_adjustCfg', 'Adjustment leg', values='holder=@patientControlNumber;amount=@receivableDelta')
    s.flow(dg2, da, None, 'yes', default=True)
    aj = s.conn('cl_adjust', 'Adjust the claim receivable', 'ledger.adjust', 'adjust', 'submit',
                'ledgerId,line,period,holder,amount', 'recv', step='receivable-adjust:${correctionNo}')
    s.flow(da, aj)
    rg0 = s.xgw('cl_recvGw', 'Receivable posted?')
    s.flow(al, rg0)
    s.flow(aj, rg0)
    rx = s.user('cl_recvException', 'Receivable not posted: resolve | Cuenta por cobrar no registrada: resolver', '${billerSeat}',
                form='sec-ushealth-claim/receivable-exception', decision=None, fields=['remarks*'])
    s.flow(rg0, rx, "recvStatus == 'FAILED'", 'refused')
    s.flow(rx, w)
    rok = s.setv('cl_recvOk', 'Receivable now stands at the charge', values='receivableRaised=@totalCharge')
    s.flow(rg0, rok, None, 'posted', default=True)
    s.flow(rok, w)
"""),
    ("""'patientControlNumber,eraClaims,eraClaimStatus,eraPaidAmount,eraPatientResponsibility,ledgerId,line,period',
                  'post', step='posting')""",
     """'patientControlNumber,eraClaims,eraClaimStatus,eraPaidAmount,eraPatientResponsibility,eraTraceNumber,'
                  'ledgerId,line,period', 'post', step='posting:${eraTraceNumber}')"""),
    ("""    corr = s.setv('cl_corrected', 'Corrected claim (frequency 7)', values='frequency=7')
    s.flow(dg, corr, "denialAction == 'resubmit'", 'correct')
    s.flow(corr, scrub)
""", """    corr = s.setv('cl_corrected', 'Corrected claim (frequency 7)', values='frequency=7')
    cn = s.expr('cl_correctionNo', 'Count the correction', "${execution.setVariable('correctionNo', correctionNo + 1)}")
    s.flow(dg, corr, "denialAction == 'resubmit'", 'correct')
    s.seq(corr, cn, scrub)
"""),
])

# tests
sub('sangam-server/src/test/java/org/sangam/server/bridge/RemittanceAndSeedsTest.java', [
    ("""    @Test
    void identifierSeedsApplyOnlyToTheirJurisdiction() {""", """    static ConnectorRequest traced(String trace, String paid) {
        return new ConnectorRequest("T", UUID.randomUUID(), "a1", 1, "root", "BK", "post", Map.of("eraTraceNumber", trace,
                "ledgerId", "RECEIVABLES", "line", "CLAIM", "period", "OPEN", "eraClaims",
                "[{\\"patientControlNumber\\":\\"PCN3\\",\\"status\\":\\"PAID_PRIMARY\\",\\"paidAmount\\":\\"" + paid + "\\"}]"), null);
    }

    @Test
    void secondRemittancePostsSeparatelyAndReplayDoesNot() throws Exception {
        LedgerService s = ledger("remit3");
        LedgerConnector lc = new LedgerConnector(s, (t, d) -> "OPEN", Clock.systemUTC());
        assertTrue(s.apply(LedgerRequest.of("ALLOCATE", claim("PCN3"), new BigDecimal("100"), "a")).applied());
        var posting = new PackGapConnectors.RemittancePosting(lc);
        posting.invoke(traced("T1", "30"));
        posting.invoke(traced("T1", "30")); // new operation id, same 835
        assertEquals(0, new BigDecimal("70").compareTo(s.available(claim("PCN3"))), "replay posts nothing");
        posting.invoke(traced("T2", "30"));
        assertEquals(0, new BigDecimal("40").compareTo(s.available(claim("PCN3"))), "second remittance posts");
        var adj = lc.invoke(new ConnectorRequest("T", UUID.randomUUID(), "a1", 1, "root", "BK", "adjust", Map.of(
                "ledgerId", "RECEIVABLES", "line", "CLAIM", "period", "OPEN", "holder", "PCN3", "amount", "-15"), null));
        assertTrue(adj instanceof ConnectorResult.Success, String.valueOf(adj));
        assertEquals(0, new BigDecimal("25").compareTo(s.available(claim("PCN3"))), "corrected charge lowers the receivable");
    }

    @Test
    void identifierSeedsApplyOnlyToTheirJurisdiction() {"""),
])
sub('sangam-packs/src/test/java/org/sangam/packs/PilotScenarioTest.java', [
    ("""    @Test
    void p1UnknownPaymentOutcomeIsReconciledWithoutRepeat() {""", """    static org.sangam.packs.sim.SimulatorConnectorGateway sim() {
        return (org.sangam.packs.sim.SimulatorConnectorGateway) PackRuntime.gateway();
    }

    @Test
    void claimPostingExceptionCorrectedClaimAndSecondRemittance() {
        String bk = "CLAIM/1";
        start("sangam-sec-ushealth-claim", bk, vars("customerId", "P1", "patientControlNumber", "PCN-9", "totalCharge", 100,
                "serviceDate", "2026-11-01", "statusFollowUp", "P30D", "sim_ledger_post", "exceptionCount=1"));
        driver.run(bk, t -> "cl_scrub".equals(t.getTaskDefinitionKey()) ? asSeat(t, "codingNotes", "ok") : null, 20);
        engine.getRuntimeService().createMessageCorrelation("sangam.ushealth.remit").processInstanceBusinessKey(bk)
                .setVariable("x12", "835").setVariable("sim_x12_remit",
                        "traceNumber=T1;claimStatus=DENIED;paidAmount=0;patientResponsibility=0;balanced=true;firstReasonCode=16")
                .correlate();
        List<String> first = driver.run(bk, t -> switch (t.getTaskDefinitionKey()) {
            case "cl_postException" -> asSeat(t, "remarks", "seen");
            case "cl_denial" -> asSeat(t, "denialAction", "resubmit");
            case "cl_scrub" -> asSeat(t, "codingNotes", "fixed", "totalCharge", 80);
            default -> null;
        }, 30);
        assertEquals(List.of("cl_postException", "cl_denial", "cl_scrub"), first);
        var adjust = sim().journal().stream().filter(r -> "ledger.adjust".equals(r.connector())
                && bk.equals(r.businessKey())).findFirst().orElseThrow();
        assertEquals(-20.0, Double.parseDouble(String.valueOf(adjust.payload().get("amount"))), "adjustment leg");
        engine.getRuntimeService().createMessageCorrelation("sangam.ushealth.remit").processInstanceBusinessKey(bk)
                .setVariable("sim_x12_remit", "traceNumber=T2;claimStatus=PAID_PRIMARY;paidAmount=80;patientResponsibility=0;balanced=true")
                .setVariable("sim_ledger_post", "exceptionCount=0").correlate();
        driver.run(bk, t -> null, 20);
        assertTrue(driver.ended(bk), "still running");
        var posts = sim().journal().stream().filter(r -> "ledger.post".equals(r.connector()) && bk.equals(r.businessKey()))
                .map(r -> r.operationId()).distinct().toList();
        assertEquals(2, posts.size(), "each remittance has its own operation");
    }

    @Test
    void receivableRefusalGoesToTheWorkQueue() {
        String bk = "CLAIM/2";
        start("sangam-sec-ushealth-claim", bk, vars("customerId", "P2", "patientControlNumber", "PCN-10", "totalCharge", 100,
                "serviceDate", "2026-11-01", "statusFollowUp", "P30D", "sim_ledger_allocate", "FAILED:LEDGER_REQUEST_INVALID"));
        List<String> done = driver.run(bk, t -> switch (t.getTaskDefinitionKey()) {
            case "cl_scrub" -> asSeat(t, "codingNotes", "ok");
            case "cl_recvException" -> asSeat(t, "remarks", "seen");
            default -> null;
        }, 20);
        assertEquals(List.of("cl_scrub", "cl_recvException"), done);
    }

    @Test
    void compensationCreditWaitsForTheFirstBill() {
        String bk = "CONN/1";
        start("sangam-sec-util-connection", bk, vars("applicantName", "A", "applicantRef", "CONS-77", "premisesAddress", "X",
                "areaType", "METRO", "loadKw", 5, "compensationDue", true, "initiator", "citizen.a", "meterNo", "M1"));
        driver.run(bk, t -> switch (t.getTaskDefinitionKey()) {
            case "sc_scrutiny" -> asSeat(t, "scrutinyDecision", "accept");
            case "uc_inspect" -> asSeat(t, "feasibility", "feasible", "findings", "ok");
            case "uc_install" -> asSeat(t, "meterNo", "M1");
            default -> null;
        }, 30);
        assertTrue(!driver.ended(bk), "held for the first bill");
        assertTrue(sim().journal().stream().noneMatch(r -> "billing.credit".equals(r.connector()) && bk.equals(r.businessKey())));
        var due = sim().query(new org.sangam.packs.port.ConnectorRequest(null, "mdm.consumersDue", "list", null, null, null, Map.of(), null));
        assertTrue(String.valueOf(due.data().get("items")).contains("connectionCaseKey=" + bk), String.valueOf(due.data()));
        String bill = "UTIL/BILL/77";
        start("sangam-sec-util-bill", bill, vars("key", "CONS-77", "batchId", "CYCLE-1", "units", 100, "connectionCaseKey", bk));
        driver.run(bill, t -> null, 10);
        driver.run(bk, t -> null, 10);
        assertTrue(driver.ended(bk), "credit done");
        var in = sim().journal().stream().filter(r -> "billing.credit".equals(r.connector()) && bk.equals(r.businessKey()))
                .findFirst().orElseThrow().payload();
        assertEquals("CYCLE-1", in.get("cycle"));
        assertEquals("ENERGY", in.get("head"));
        assertEquals("CONS-77", in.get("consumer"));
    }

    @Test
    void p1UnknownPaymentOutcomeIsReconciledWithoutRepeat() {"""),
])
import os
os.makedirs(R + 'sangam-connectors-health/src/test/java/org/sangam/connectors/health/x12', exist_ok=True)
open(R + 'sangam-connectors-health/src/test/java/org/sangam/connectors/health/x12/Remittance835FieldsTest.java', 'w', encoding='utf-8', newline='\n').write('''// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.x12;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.connectors.health.sim.HealthSimulator;

/** The fields claim posting relies on. The claim balance rule itself is [U]: not read from a public TR3 text. */
class Remittance835FieldsTest {

    @Test
    @SuppressWarnings("unchecked")
    void perClaimTotalsAndBalanceFlag() {
        String x12 = HealthSimulator.remittance835(ARGS);
        Map<String, Object> m = HealthSimulator.decode835(x12);
        assertEquals("100.00", String.valueOf(m.get("chargeAmount")));
        Map<String, Object> c = ((List<Map<String, Object>>) m.get("claims")).get(0);
        Map<String, String> totals = (Map<String, String>) c.get("adjustmentTotals");
        assertEquals(0, new java.math.BigDecimal("10").compareTo(new java.math.BigDecimal(totals.get("PR"))), totals.toString());
        assertEquals(0, new java.math.BigDecimal("20").compareTo(new java.math.BigDecimal(totals.get("CO"))), totals.toString());
        assertEquals(true, c.get("claimBalanced"));
    }
}
''')
print('ok')
