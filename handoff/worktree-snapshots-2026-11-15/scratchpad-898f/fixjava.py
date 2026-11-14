import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
def r(a,b):
    global s
    assert s.count(a)==1,a[:60]; s=s.replace(a,b)
r('''    void p00EngineReadsRfc822OffsetDatesAndRejectsIsoZAndDatetimeLocal() throws Exception {''','''    void p00EngineReadsOffsetDatesAndRejectsBareDatetimeLocal() throws Exception {''')
r('''        assertEquals(400, call("PUT", "/engine-rest/process-definition/" + definitionId + "/suspended",
                body.formatted(when.toInstant().toString())).statusCode(), "ISO 'Z' must be rejected (why the UI converts)");
''','''        assertEquals(400, call("PUT", "/engine-rest/process-definition/" + definitionId + "/suspended",
                body.formatted("2027-12-01T09:00")).statusCode(), "a bare datetime-local value is rejected (why the UI converts)");
''')
r('''        ok("POST", "/engine-rest/execution/" + pi + "/create-incident",''','''        ok("POST", "/engine-rest/execution/" + reviewExecution(pi) + "/create-incident",''')
r('''        ok("POST", "/engine-rest/execution/" + other + "/create-incident",''','''        ok("POST", "/engine-rest/execution/" + reviewExecution(other) + "/create-incident",''')
r('''    @BeforeAll''','''    String reviewExecution(String pi) throws Exception {
        return ok("GET", "/engine-rest/execution?processInstanceId=" + pi + "&activityId=review", null).get(0).get("id").asText();
    }

    @BeforeAll''')
r('''        ok("POST", "/engine-rest/external-task/fetchAndLock", fetch);
        ok("POST", "/engine-rest/external-task/" + xid + "/failure",''','''        assertTrue(ok("POST", "/engine-rest/external-task/fetchAndLock", fetch).toString().contains(xid), "w1 locks the task");
        ok("POST", "/engine-rest/external-task/" + xid + "/failure",''')
r('''              </decision>
            </definitions>''','''              </decision>
              <decision id="uipAge" name="Age band" operaton:historyTimeToLive="30">
                <decisionTable id="t2">
                  <input id="i2"><inputExpression id="e2" typeRef="integer"><text>age</text></inputExpression></input>
                  <output id="o2" name="band" typeRef="string"/>
                  <rule id="r2"><inputEntry id="ie2"><text>&gt;= 0</text></inputEntry><outputEntry id="oe2"><text>"any"</text></outputEntry></rule>
                </decisionTable>
              </decision>
            </definitions>''')
open(p,'w',encoding='utf-8',newline='').write(s); print('ok')
