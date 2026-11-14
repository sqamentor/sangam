import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
def r(a,b):
    global s
    assert s.count(a)==1,a[:60]; s=s.replace(a,b)
r('''map("topicName", "uip-pay", "lockDuration", 60000)});''','''map("topicName", "uip-pay", "lockDuration", 60000, "processInstanceId", pi)});''')
r('''        ok("DELETE", "/engine-rest/batch/" + id + "?cascade=true", null);
''','''        // The job executor may finish a one-instance batch first: then it is history only, deleted from there.
        HttpResponse<String> del = call("DELETE", "/engine-rest/batch/" + id + "?cascade=true", null);
        if (del.statusCode() != 204) {
            assertEquals(404, call("GET", "/engine-rest/batch/" + id, null).statusCode(), "delete refused only for a finished batch: " + del.body());
            ok("DELETE", "/engine-rest/history/batch/" + id, null);
        }
''')
open(p,'w',encoding='utf-8',newline='').write(s); print('ok')
