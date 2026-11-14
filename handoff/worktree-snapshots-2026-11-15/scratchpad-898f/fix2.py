import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
def r(a,b):
    global s
    assert s.count(a)==1,a[:60]; s=s.replace(a,b)
r('''    void p00EngineReadsOffsetDatesAndRejectsBareDatetimeLocal() throws Exception {''','''    void p00EngineReadsTheUiDateFormatInBodiesAndQueries() throws Exception {''')
r('''        assertEquals(400, call("PUT", "/engine-rest/process-definition/" + definitionId + "/suspended",
                body.formatted("2027-12-01T09:00")).statusCode(), "a bare datetime-local value is rejected (why the UI converts)");
''','')
r('''        assertEquals(400, call("GET", "/engine-rest/task?dueBefore=" + q("2026-12-01T09:00"), null).statusCode());
''','')
r('''        assertTrue(ok("POST", "/engine-rest/external-task/fetchAndLock", fetch).toString().contains(xid), "w1 locks the task");''','''        JsonNode locked = ok("POST", "/engine-rest/external-task/fetchAndLock", fetch);
        assertTrue(locked.toString().contains(xid), "w1 locks the task: " + locked + " task " + ok("GET", "/engine-rest/external-task/" + xid, null));''')
r('''        ok("PUT", "/engine-rest/batch/" + id + "/suspended", map("suspended", true));
''','')
open(p,'w',encoding='utf-8',newline='').write(s); print('ok')
