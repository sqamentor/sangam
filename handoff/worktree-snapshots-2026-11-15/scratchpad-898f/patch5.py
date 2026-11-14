R = 'D:/GITHUB  Project/Custom WorkFlow/sangam/'


def sub(path, pairs):
    s = open(R + path, encoding='utf-8').read()
    for a, b in pairs:
        assert a in s, a
        s = s.replace(a, b, 1)
    open(R + path, 'w', encoding='utf-8', newline='\n').write(s)


sub('sangam-packs/tools/packgen/sec_ushealth.py', [("""    pk.dmn('sec-ushealth-claim-denials',""", """    # later 835s (secondary payer, take-back) arrive after the claim case has ended; business key = the 835 trace number
    rp = pk.proc('sangam-sec-ushealth-remittance', 'Later remittance (835)', '', start={'materialFields': 'x12'})
    sr = rp.root
    r0 = sr.start('rm_start', 'Remittance received (835)', msg='sangam.ushealth.remittance', initiator=None)
    r1 = sr.setv('rm_defaults', 'Configuration', defaults='ledgerId=RECEIVABLES;line=CLAIM;period=OPEN;billerSeat=BILLER;'
                 'eraBalanced=true;postExceptionCount=0;eraTraceNumber=no-trace;eraClaimStatus=PAID_PRIMARY')
    r2 = sr.conn('rm_parse', 'Decode the 835 remittance', 'x12.remit', 'parse', 'query', 'x12', 'era')
    r3 = sr.xgw('rm_traceGw', 'Trace number present?')
    sr.seq(r0, r1, r2, r3)
    r4 = sr.conn('rm_post', 'Post the remittance', 'ledger.post', 'post', 'submit',
                 'eraClaims,eraClaimStatus,eraPaidAmount,eraPatientResponsibility,eraTraceNumber,ledgerId,line,period',
                 'post', step='posting:${eraTraceNumber}')
    rq = sr.user('rm_exception', 'Resolve the remittance exception | Resolver la excepción de la remesa', '${billerSeat}',
                 form='sec-ushealth-claim/remittance-exception', decision=None, fields=['remitSummary', 'remarks*'])
    sr.flow(r3, rq, "eraTraceNumber == 'no-trace'", 'cannot be made idempotent')
    sr.flow(r3, r4, None, 'yes', default=True)
    r5 = sr.xgw('rm_postGw', 'Posted cleanly?')
    sr.flow(r4, r5)
    sr.flow(r5, rq, "postStatus == 'FAILED' || postExceptionCount > 0 || !eraBalanced || eraClaimStatus == 'DENIED'",
            'US-HLT-106 work queue')
    r6 = end_ok(rp, sr, 'rm_end', 'Posted', outcome='POSTED')
    sr.flow(r5, r6, None, 'clean', default=True)
    sr.flow(rq, r6)
    pk.dmn('sec-ushealth-claim-denials',""")])
sub('sangam-server/src/main/java/org/sangam/server/bridge/PackGapConnectors.java', [
    ("""trace == null || trace.toString().isBlank()
""", """trace == null || trace.toString().isBlank() || "no-trace".equals(trace)
"""),
])
sub('sangam-packs/src/test/java/org/sangam/packs/PilotScenarioTest.java', [("""    @Test
    void receivableRefusalGoesToTheWorkQueue() {""", """    static void remittance(String bk, String era, String post) {
        engine.getRuntimeService().createMessageCorrelation("sangam.ushealth.remittance").processInstanceBusinessKey(bk)
                .setVariable("x12", "835").setVariable("sim_x12_remit", era).setVariable("sim_ledger_post", post).correlate();
    }

    @Test
    void laterRemittancePostsIdempotentlyAndExceptionsQueue() {
        String ok = "REMIT/T9";
        String era = "traceNumber=T9;claimStatus=REVERSAL;paidAmount=-50;balanced=true";
        remittance(ok, era, "exceptionCount=0");
        driver.run(ok, t -> null, 10);
        assertTrue(driver.ended(ok), "clean take-back ends");
        remittance(ok, era, "exceptionCount=0"); // the same 835 delivered again
        driver.run(ok, t -> null, 10);
        assertEquals(1, sim().journal().stream().filter(r -> "ledger.post".equals(r.connector()) && ok.equals(r.businessKey()))
                .map(r -> r.operationId()).distinct().count(), "replay keeps the operation id");
        String bad = "REMIT/T10";
        remittance(bad, "traceNumber=T10;claimStatus=PAID_SECONDARY;paidAmount=5;balanced=true", "exceptionCount=1");
        assertEquals(List.of("rm_exception"), driver.run(bad, t -> asSeat(t, "remarks", "unmatched"), 10));
        String none = "REMIT/none";
        remittance(none, "claimStatus=PAID_SECONDARY;paidAmount=5;balanced=true", "exceptionCount=0");
        assertEquals(List.of("rm_exception"), driver.run(none, t -> asSeat(t, "remarks", "no trace"), 10));
        assertTrue(sim().journal().stream().noneMatch(r -> "ledger.post".equals(r.connector()) && none.equals(r.businessKey())));
    }

    @Test
    void receivableRefusalGoesToTheWorkQueue() {""")])
sub('sangam-server/src/test/java/org/sangam/server/bridge/RemittanceAndSeedsTest.java', [("""        posting.invoke(traced("T2", "30"));
""", """        posting.invoke(traced("T2", "30"));
        assertEquals(0, new BigDecimal("40").compareTo(s.available(claim("PCN3"))));
        posting.invoke(traced("no-trace", "5"));
        posting.invoke(traced("no-trace", "5"));
        assertEquals(0, new BigDecimal("30").compareTo(s.available(claim("PCN3"))), "no trace: never swallowed as a replay");
        lc.invoke(new ConnectorRequest("T", UUID.randomUUID(), "a1", 1, "root", "BK", "adjust", Map.of(
                "ledgerId", "RECEIVABLES", "line", "CLAIM", "period", "OPEN", "holder", "PCN3", "amount", "10"), null));
""")])
print('ok')
