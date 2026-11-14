import re
M = 'D:/GITHUB  Project/Custom WorkFlow/sangam/sangam-packs/'
J = M + 'src/main/java/org/sangam/packs/'
G = M + 'tools/packgen/'


def sub(path, pairs):
    s = open(path, encoding='utf-8').read()
    for a, b in pairs:
        assert a in s, a
        s = s.replace(a, b)
    open(path, 'w', encoding='utf-8', newline='\n').write(s)


# (1) tenant in operation ids
sub(J + 'core/Vars.java', [("""    public static String operationId(String businessKey, String step, int attempt) {
        return Hashes.uuidV5(OPERATION_NS, businessKey + "|" + step + "|" + attempt).toString();""",
"""    /**
     * The stable operation id: a UUIDv5 string, which IntegrationConnectorGateway uses directly as the ledger id. The
     * tenant is part of the name, so two tenants with the same business key never collide in the global ledger. A case
     * without a tenant keeps the name {@code businessKey|step|attempt}.
     */
    public static String operationId(String tenantId, String businessKey, String step, int attempt) {
        String name = businessKey + "|" + step + "|" + attempt;
        if (tenantId != null && !tenantId.isBlank()) {
            name = "tenant=" + tenantId.length() + ":" + tenantId + "|" + name;
        }
        return Hashes.uuidV5(OPERATION_NS, name).toString();""")])
sub(J + 'core/ConnectorCallDelegate.java', [('Vars.operationId(bk, s, attempt)', 'Vars.operationId(Vars.tenant(ex), bk, s, attempt)')])
sub(J + 'core/DocumentDelegate.java', [('Vars.operationId(bk, "doc:"', 'Vars.operationId(Vars.tenant(ex), bk, "doc:"')])
sub(J + 'core/ExternalOperationDelegate.java', [('Vars.operationId(bk(ex), s, attempt)', 'Vars.operationId(Vars.tenant(ex), bk(ex), s, attempt)')])
sub(J + 'core/NotifyDelegate.java', [('Vars.operationId(ex.getProcessBusinessKey()', 'Vars.operationId(Vars.tenant(ex), ex.getProcessBusinessKey()')])
sub(J + 'core/SignatureDelegate.java', [('Vars.operationId(bk, "sign:"', 'Vars.operationId(Vars.tenant(ex), bk, "sign:"')])
sub(J + 'worker/EmbeddedWorkerRunner.java', [('Vars.operationId(bk, t.getActivityId()', 'Vars.operationId(t.getTenantId(), bk, t.getActivityId()')])

# (2) billing.revise and billing.generate
sub(G + 'sec_more.py', [
    ("""'billingSeat=BILLING-CLERK;dueSpec=15D')
    rd = s.conn('bl_read'""", """'billingSeat=BILLING-CLERK;dueSpec=15D;consumer=@key;tariffId=DEFAULT;cycle=@batchId;head=ENERGY')
    rd = s.conn('bl_read'"""),
    ("""'billing.generate', 'bill', 'submit', 'key,units,billAmount', 'bill',""",
     """'billing.generate', 'bill', 'submit',
                'key,consumer,units,billAmount,tariffId,cycle', 'bill',"""),
    ("""    rb = s.conn('bl_reviseBill', 'Issue revised bill', 'billing.revise', 'revise', 'submit', 'key,units', 'rev', step='revise')
    s.flow(rg, rb, "disputeDecision == 'customer'", 'upheld')
""", """    sp = s.setv('bl_superseded', 'Amount of the bill being replaced', values='supersededAmount=@billAmount')
    rc = s.expr('bl_recompute', 'Revised bill amount', "${execution.setVariable('billAmount', fixedCharge + units * energyRate)}")
    rb = s.conn('bl_reviseBill', 'Issue revised bill', 'billing.revise', 'revise', 'submit',
                'key,consumer,units,tariffId,cycle,head,supersededAmount', 'rev', step='revise')
    s.flow(rg, sp, "disputeDecision == 'customer'", 'upheld')
    s.seq(sp, rc, rb)
"""),
])

# (2) billing.credit
sub(G + 'sec_other.py', [
    ("""    cp = s.conn('uc_comp', 'Credit compensation in the next bill', 'billing.credit', 'credit', 'submit', 'applicantName',
                'comp', step='compensation')
""", """    cc = s.setv('uc_compCfg', 'Compensation credit inputs', values='amount=@compensationAmount',
                defaults='consumer=@applicantRef;head=ENERGY;cycle=first-bill')
    cp = s.conn('uc_comp', 'Credit compensation in the next bill', 'billing.credit', 'credit', 'submit',
                'consumer,amount,head,cycle', 'comp', step='compensation')
    s.flow(cc, cp)
    cr = s.xgw('uc_compRes', 'Credited?')
    s.flow(cp, cr)
    cm = s.user('uc_compManual', 'Compensation not credited: credit by hand | क्षतिपूर्ति जमा नहीं हुई: स्वयं जमा करें', 'AE-SUBDIV',
                form='sec-util-new-connection/compensation-manual', decision=None, fields=['remarks*'])
    cj = s.xgw('uc_compJoin')
    s.flow(cr, cm, "compStatus == 'FAILED'", 'refused (no demand in that cycle yet)')
    s.flow(cr, cj, None, 'credited', default=True)
    s.flow(cm, cj)
"""),
    ("""maybe(s, 'uc_compOpt', 'compensationDue', cp, cp, 'Delayed?')""", """maybe(s, 'uc_compOpt', 'compensationDue', cc, cj, 'Delayed?')"""),
    ("""[('Area', 'areaType', 'string')], [('connSpec', 'Connect within', 'string')],
           [([q('METRO')], [q('7D')], ''), ([q('MUNICIPAL')], [q('15D')], ''), (['-'], [q('30D')], 'rural / hilly')])""",
     """[('Area', 'areaType', 'string')],
           [('connSpec', 'Connect within', 'string'), ('compensationAmount', 'Compensation (SYNTHETIC, SERC notifies)', 'double')],
           [([q('METRO')], [q('7D'), '1000'], ''), ([q('MUNICIPAL')], [q('15D'), '1000'], ''),
            (['-'], [q('30D'), '500'], 'rural / hilly')])"""),
])

# (4) party identifier types
sub(G + 'packs.py', [
    ("        self.seed = None\n", "        self.seed = None\n        self.identifier_types = None\n"),
    ("        conns = {}\n", """        if pk.identifier_types:
            rpath = f'packs/{pk.dir}/registry/identifier-types.json'
            write(os.path.join(root, rpath), json.dumps({'identifierTypes': pk.identifier_types}, ensure_ascii=False,
                                                        indent=2) + '\\n')
            resources.append({'kind': 'OTHER', 'path': rpath})
        conns = {}
"""),
])
sub(G + 'core.py', [("""    core.accept('CORE-1',""", """    # read by RegistrySeedLoader (packs/*/registry/*.json) for every tenant; administrators may override or retire them
    core.identifier_types = [
        {'typeKey': 'AADHAAR', 'label': 'Aadhaar number', 'pattern': '[2-9][0-9]{11}', 'normalise': ['DIGITS_ONLY'],
         'checksum': 'VERHOEFF', 'unique': True, 'sensitive': True, 'matchWeight': 12, 'appliesTo': 'PERSON'},
        {'typeKey': 'PAN', 'label': 'Permanent account number', 'pattern': '[A-Z]{5}[0-9]{4}[A-Z]',
         'normalise': ['UPPER', 'STRIP_SPACES'], 'unique': True, 'sensitive': True, 'matchWeight': 12},
        {'typeKey': 'GSTIN', 'label': 'GST identification number', 'pattern': '[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z]',
         'normalise': ['UPPER', 'STRIP_SPACES'], 'unique': True, 'matchWeight': 12, 'appliesTo': 'ORGANISATION'},
        {'typeKey': 'BANK_ACCOUNT', 'label': 'Bank account', 'pattern': '[0-9A-Z]{6,34}',
         'normalise': ['UPPER', 'STRIP_SPACES', 'STRIP_PUNCT'], 'sensitive': True, 'matchWeight': 6},
        {'typeKey': 'MOBILE', 'label': 'Mobile number', 'normalise': ['DIGITS_ONLY'], 'pattern': '[0-9]{10,15}',
         'sensitive': True, 'matchWeight': 4, 'appliesTo': 'PERSON'},
        {'typeKey': 'BENEFICIARY_ID', 'label': 'Scheme beneficiary id (vault token)', 'normalise': ['TRIM'], 'unique': True,
         'matchWeight': 10}]
    core.accept('CORE-1',""")])
s = open(G + 'sec_ushealth.py', encoding='utf-8').read()
m = re.search(r"\('ledger\.post', 'a', '', 'Payment posting'\)\]\)\n", s)
assert m
s = s[:m.end()] + """    # NPI: no LUHN here, its check digit is computed with the 80840 prefix (validated by the npi.registry connector)
    pk.identifier_types = [
        {'typeKey': 'NPI', 'label': 'National Provider Identifier', 'pattern': '[0-9]{10}', 'normalise': ['DIGITS_ONLY'],
         'unique': True, 'matchWeight': 12},
        {'typeKey': 'EIN', 'label': 'Employer Identification Number', 'pattern': '[0-9]{9}', 'normalise': ['DIGITS_ONLY'],
         'unique': True, 'matchWeight': 12, 'appliesTo': 'ORGANISATION'},
        {'typeKey': 'MEMBER_ID', 'label': 'Health plan member id', 'normalise': ['UPPER', 'STRIP_SPACES'], 'sensitive': True,
         'matchWeight': 8, 'appliesTo': 'PERSON'},
        {'typeKey': 'MRN', 'label': 'Medical record number', 'normalise': ['UPPER', 'STRIP_SPACES'], 'sensitive': True,
         'matchWeight': 8, 'appliesTo': 'PERSON'}]
""" + s[m.end():]
open(G + 'sec_ushealth.py', 'w', encoding='utf-8', newline='\n').write(s)

# tests
T = M + 'src/test/java/org/sangam/packs/'
open(T + 'OperationIdAndSeedsTest.java', 'w', encoding='utf-8', newline='\n').write('''// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import org.sangam.core.layer.Hashes;
import org.sangam.packs.core.Vars;

class OperationIdAndSeedsTest {

    @Test
    void operationIdIsAStableUuidPerTenant() {
        String a = Vars.operationId("state-a", "BILL/1", "payment", 1);
        assertEquals(a, Vars.operationId("state-a", "BILL/1", "payment", 1));
        assertEquals(5, UUID.fromString(a).version());
        assertEquals(a, UUID.fromString(a).toString());
        assertNotEquals(a, Vars.operationId("state-b", "BILL/1", "payment", 1));
        assertNotEquals(a, Vars.operationId("state-a", "BILL/1", "payment", 2));
        assertEquals(Hashes.uuidV5(Vars.OPERATION_NS, "BILL/1|payment|1").toString(),
                Vars.operationId(null, "BILL/1", "payment", 1), "no tenant keeps the earlier id");
    }

    @Test
    void identifierTypeSeedsAreReadable() throws Exception {
        for (String path : new String[] {"packs/core/registry/identifier-types.json",
                "packs/sec-ushealth-claim/registry/identifier-types.json"}) {
            try (var in = getClass().getClassLoader().getResourceAsStream(path)) {
                JsonNode types = new ObjectMapper().readTree(in).path("identifierTypes");
                assertTrue(types.size() > 0, path);
                for (JsonNode t : types) {
                    assertTrue(t.hasNonNull("typeKey"));
                    if (t.hasNonNull("pattern")) {
                        Pattern.compile(t.get("pattern").asText());
                    }
                }
            }
        }
    }
}
''')
sub(T + 'PilotScenarioTest.java', [("""    @Test
    void p1UnknownPaymentOutcomeIsReconciledWithoutRepeat() {""", """    @Test
    void billRevisionPassesFullInputs() {
        String bk = "UTIL/BILL/1";
        start("sangam-sec-util-bill", bk, vars("key", "CONS-1", "batchId", "CYCLE-2026-11", "units", 100));
        driver.run(bk, t -> null, 10);
        engine.getRuntimeService().createMessageCorrelation("sangam.util.billDispute").processInstanceBusinessKey(bk)
                .correlate();
        driver.run(bk, t -> "bl_revise".equals(t.getTaskDefinitionKey())
                ? asSeat(t, "disputeDecision", "customer", "units", 80) : null, 10);
        var sim = (org.sangam.packs.sim.SimulatorConnectorGateway) PackRuntime.gateway();
        var in = sim.journal().stream().filter(r -> "billing.revise".equals(r.connector())).findFirst().orElseThrow().payload();
        for (String k : List.of("consumer", "units", "tariffId", "cycle", "head", "supersededAmount")) {
            assertTrue(in.get(k) != null, k);
        }
        assertEquals("CYCLE-2026-11", in.get("cycle"));
    }

    @Test
    void p1UnknownPaymentOutcomeIsReconciledWithoutRepeat() {""")])
print('ok')
