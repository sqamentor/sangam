R = 'D:/GITHUB  Project/Custom WorkFlow/sangam/'


def sub(path, pairs):
    s = open(R + path, encoding='utf-8').read()
    for a, b in pairs:
        assert a in s, (path, a)
        s = s.replace(a, b, 1)
    open(R + path, 'w', encoding='utf-8', newline='\n').write(s)


X = 'sangam-connectors-health/src/main/java/org/sangam/connectors/health/x12/'
sub(X + 'X12Decoders.java', [
    ("""        for (Map<String, Object> c : claims) {
            // [U] claim balance""", """        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Map<String, Object> c : claims) {
            // occurrence of this patient control number in the file: the claim-scoped posting key with the trace number
            c.put("occurrence", seen.merge(String.valueOf(c.get("patientControlNumber")), 1, Integer::sum) - 1);
        }
        for (Map<String, Object> c : claims) {
            // [U] claim balance"""),
    ("""    static String clpStatus(String code) {""", """    /**
     * Scopes a decoded 835 to one claim: {@code claims} keeps only that patient control number and the top-level claim
     * fields (status, amounts, first adjustment) describe it; {@code claimStatus = NOT_IN_FILE} when it is absent.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> focusClaim(Map<String, Object> remit, String patientControlNumber) {
        Map<String, Object> m = new LinkedHashMap<>(remit);
        List<Map<String, Object>> all = m.get("claims") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        List<Map<String, Object>> mine = new ArrayList<>();
        for (Map<String, Object> c : all) {
            if (patientControlNumber.equals(String.valueOf(c.get("patientControlNumber")))) {
                mine.add(c);
            }
        }
        m.put("claims", mine);
        m.put("claimFound", !mine.isEmpty());
        for (String k : List.of("paidAmount", "chargeAmount", "patientResponsibility", "firstReasonCode", "firstAdjustmentGroup")) {
            m.remove(k);
        }
        if (mine.isEmpty()) {
            m.put("claimStatus", "NOT_IN_FILE");
            m.put("denied", false);
            return m;
        }
        Map<String, Object> c0 = mine.get(0);
        m.put("claimStatus", c0.get("status"));
        m.put("paidAmount", c0.get("paidAmount"));
        m.put("chargeAmount", c0.get("chargeAmount"));
        m.put("patientResponsibility", c0.getOrDefault("patientResponsibility", "0"));
        m.put("denied", "DENIED".equals(c0.get("status")));
        if (c0.get("adjustments") instanceof List<?> adjs && !adjs.isEmpty() && adjs.get(0) instanceof Map<?, ?> a0) {
            m.put("firstReasonCode", a0.get("reasonCode"));
            m.put("firstAdjustmentGroup", a0.get("group"));
        }
        return m;
    }

    static String clpStatus(String code) {"""),
])
sub(X + 'X12Connector.java', [
    ("""                case "parse" -> parse(r.required("x12"));""", """                case "parse" -> parse(r);"""),
    ("""    private ConnectorResult parse(String x12) {
        Map<String, Object> out = decodeOutput(null, x12);
        return ConnectorResult.Success.of(out, String.valueOf(out.getOrDefault("controlNumber", "")));
    }
""", """    private ConnectorResult parse(ConnectorRequest r) {
        Map<String, Object> out = parseFocused(r.required("x12"), r.str("eraTraceNumber"), r.str("patientControlNumber"));
        return ConnectorResult.Success.of(out, String.valueOf(out.getOrDefault("controlNumber", "")));
    }

    /**
     * Decodes an X12 file. With a trace number, the top level describes the 835 transaction of that TRN02 (a file may
     * hold several); with a patient control number, it is scoped to that claim ({@link X12Decoders#focusClaim}), so a
     * process posts and routes only its own claim of a mixed 835.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseFocused(String x12, String traceNumber, String patientControlNumber) {
        Map<String, Object> out = decodeOutput(null, x12);
        List<Map<String, Object>> decoded = (List<Map<String, Object>>) out.get("decoded");
        Map<String, Object> pick = null;
        for (Map<String, Object> d : decoded) {
            if (traceNumber != null && traceNumber.equals(d.get("traceNumber"))) {
                pick = d;
            }
        }
        if (pick == null && !decoded.isEmpty()) {
            pick = decoded.get(0);
        }
        if (pick == null) {
            return out;
        }
        if (patientControlNumber != null && !patientControlNumber.isBlank() && "835".equals(pick.get("type"))) {
            pick = X12Decoders.focusClaim(pick, patientControlNumber);
        }
        for (Map.Entry<String, Object> e : pick.entrySet()) {
            if (!(e.getValue() instanceof List<?>) || e.getKey().equals("claims")) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return HealthHttp.noNulls(out);
    }
"""),
])

S = 'sangam-server/src/main/java/org/sangam/server/bridge/'
sub(S + 'PackGapConnectors.java', [
    ("""            List<Map<String, Object>> claims = claims(r);
            if (claims == null) {
                return ConnectorResult.BusinessFailure.of("INPUT_REQUIRED", "eraClaims or patientControlNumber is required");
            }
            List<Map<String, Object>> exceptions = new java.util.ArrayList<>();
""", """            List<Map<String, Object>> claims = claims(r);
            if (claims == null) {
                return ConnectorResult.BusinessFailure.of("INPUT_REQUIRED", "eraClaims or patientControlNumber is required");
            }
            List<Map<String, Object>> exceptions = new java.util.ArrayList<>();
            Object scope = r.input().get("patientControlNumber");
            if (scope != null && !scope.toString().isBlank() && r.input().get("eraClaims") != null) {
                // claim-scoped: a mixed 835 reaches several processes; each posts only its own claim
                claims = claims.stream().filter(c -> scope.toString().equals(String.valueOf(c.get("patientControlNumber"))))
                        .toList();
                if (claims.isEmpty()) {
                    exceptions.add(exception(scope.toString(), "claim", "CLAIM_NOT_IN_FILE"));
                }
            }
"""),
    ("""            for (int i = 0; i < claims.size(); i++) {
                posted += post(r, claims.get(i), i, exceptions);
            }""", """            java.util.Map<String, Integer> seen = new java.util.HashMap<>();
            for (Map<String, Object> c : claims) {
                // claim key = trace + PCN + occurrence of that PCN in the file (decoder field; counted here when absent)
                int counted = seen.merge(String.valueOf(c.get("patientControlNumber")), 1, Integer::sum) - 1;
                int occurrence = c.get("occurrence") == null ? counted : Integer.parseInt(String.valueOf(c.get("occurrence")));
                posted += post(r, c, occurrence, exceptions);
            }"""),
    ("""            // keyed on the 835 trace number (TRN02): a second remittance posts separately; the same 835 replayed, by a
            // retry or by another case it also mentions, finds the same references and posts nothing twice""",
     """            // keyed on trace (TRN02) + patient control number + occurrence: a claim's legs post exactly once whichever
            // process handles it (claim case, later-remittance case, retry, replay); a second remittance posts separately"""),
])
sub(S + 'Remittance835Router.java', [
    (""" * otherwise the later-remittance process starts once per trace number.""",
     """ * otherwise the later-remittance process starts once per (trace number, patient control number), and posts only that
 * claim."""),
    ("""            String trace = String.valueOf(vars.get("eraTraceNumber"));
            if (uniqueStart.claim(c, m.tenantId(), REMITTANCE_PROCESS, trace, m.source(), m.messageId())) {
                String id = rs.createProcessInstanceByKey(REMITTANCE_PROCESS).businessKey(trace).processDefinitionTenantId(m.tenantId()).setVariables(vars).execute().getId();
                uniqueStart.bind(c, m.tenantId(), REMITTANCE_PROCESS, trace, id);
            }""", """            String key = vars.get("eraTraceNumber") + ":" + m.correlationKey();
            if (uniqueStart.claim(c, m.tenantId(), REMITTANCE_PROCESS, key, m.source(), m.messageId())) {
                String id = rs.createProcessInstanceByKey(REMITTANCE_PROCESS).businessKey(key).processDefinitionTenantId(m.tenantId()).setVariables(vars).execute().getId();
                uniqueStart.bind(c, m.tenantId(), REMITTANCE_PROCESS, key, id);
            }"""),
])

G = 'sangam-packs/tools/packgen/sec_ushealth.py'
sub(G, [
    ("""    era = s.conn('cl_parse', 'Decode the 835 remittance', 'x12.remit', 'parse', 'query', 'x12', 'era')""",
     """    era = s.conn('cl_parse', 'Decode this claim of the 835', 'x12.remit', 'parse', 'query',
                 'x12,eraTraceNumber,patientControlNumber', 'era')"""),
    ("""    s.flow(qg, pq, "postStatus == 'FAILED' || postExceptionCount > 0 || !eraBalanced", 'US-HLT-106 work queue')""",
     """    s.flow(qg, pq, "postStatus == 'FAILED' || postExceptionCount > 0 || !eraBalanced || eraClaimStatus == 'NOT_IN_FILE'",
           'US-HLT-106 work queue')"""),
    ("""    # later 835s (secondary payer, take-back) arrive after the claim case has ended; business key = the 835 trace number""",
     """    # later 835s (secondary payer, take-back) arrive after the claim case has ended: one case per (trace, PCN)"""),
    ("""    r2 = sr.conn('rm_parse', 'Decode the 835 remittance', 'x12.remit', 'parse', 'query', 'x12', 'era')""",
     """    r1b = sr.setv('rm_traceIn', 'Trace from the router', values='routedTrace=@eraTraceNumber')
    r2 = sr.conn('rm_parse', 'Decode this claim of the 835', 'x12.remit', 'parse', 'query',
                 'x12,eraTraceNumber,patientControlNumber', 'era')"""),
    ("""    sr.seq(r0, r1, r2, r3)""", """    sr.seq(r0, r1, r1b, r2, r3)"""),
    ("""                 'eraClaims,eraClaimStatus,eraPaidAmount,eraPatientResponsibility,eraTraceNumber,ledgerId,line,period',""",
     """                 'patientControlNumber,eraClaims,eraClaimStatus,eraPaidAmount,eraPatientResponsibility,eraTraceNumber,'
                 'ledgerId,line,period',"""),
    ("""    sr.flow(r5, rq, "postStatus == 'FAILED' || postExceptionCount > 0 || !eraBalanced || eraClaimStatus == 'DENIED'",""",
     """    sr.flow(r5, rq, "postStatus == 'FAILED' || postExceptionCount > 0 || !eraBalanced || eraClaimStatus == 'DENIED'"
                    " || eraClaimStatus == 'NOT_IN_FILE'","""),
])
s = open(R + G, encoding='utf-8').read()
s = s.replace("""    r1b = sr.setv('rm_traceIn', 'Trace from the router', values='routedTrace=@eraTraceNumber')
""", "").replace("    sr.seq(r0, r1, r1b, r2, r3)", "    sr.seq(r0, r1, r2, r3)")
s = s.replace("""'eraBalanced=true;postExceptionCount=0;eraTraceNumber=no-trace;eraClaimStatus=PAID_PRIMARY')""",
              """'eraBalanced=true;postExceptionCount=0;eraTraceNumber=no-trace;eraClaimStatus=PAID_PRIMARY;'
                 'patientControlNumber=')""", 1)
open(R + G, 'w', encoding='utf-8', newline='\n').write(s)
print('ok')
