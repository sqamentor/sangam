import sys
f=sys.argv[1]
c=open(f,encoding='utf-8').read()
R=[
('"seatId tenantId created:dt due:dt followUp:dt lastUpdated:dt claimTime:dt state priority:int formKey delegationState")',
 '"seatId tenantId created:dt due:dt followUp:dt lastUpdated:dt claimTime:dt state priority:int formKey delegationState variables:map",\n       descs={"variables": "P45 task-card variables: only when the list was asked for them with variableNames; the same values GET /tasks/{id}/variables returns to the caller."})'),
('q("taskDefinitionKey"), q("state"), q("sortBy", desc="created, due, priority or name; a comma-separated list sorts by several keys"), q("sortOrder", desc="asc or desc per key, comma-separated")])',
 'q("taskDefinitionKey"), q("state"), q("sortBy", desc="created, due, priority or name; a comma-separated list sorts by several keys"), q("sortOrder", desc="asc or desc per key, comma-separated"),\n             q("variableNames", "list:s", "P45 task-card variables: each task carries these variables (when set), read as GET /tasks/{id}/variables reads them; at most 20 names, repeated or comma-separated")])'),
('"Variables with field masks (K11).",\n   ["VI.4"], params=[ID], resp=(200, "object"), perm="process.read"',
 '"Variables with field masks (K11).",\n   ["VI.4"], params=[ID, q("activityInstanceId", desc="P13: only the variables whose scope is this activity instance of the instance (the instance id itself is the root scope)")], resp=(200, "object"), perm="process.read"'),
('list_=True, params=HQ, resp=(200, "array:" + sch), perm="history.read", errors=STD_R)',
 'list_=True, params=HQ + HQ_EXTRA.get(path, []), resp=(200, "array:" + sch), perm="history.read", errors=STD_R)'),
('      q("assignee"), q("finished", "bool"), q("from", "dt"), q("to", "dt")]\n',
 '      q("assignee"), q("finished", "bool"), q("from", "dt"), q("to", "dt")]\nHQ_AI = q("activityInstanceId", desc="P13: only rows of this activity instance")\nHQ_EXTRA = {\n    "activity-instances": [HQ_AI, q("state", desc="P14: running, completed (ended, not canceled) or canceled", enum=["running", "completed", "canceled"])],\n    "variables": [HQ_AI], "decisions": [HQ_AI],\n}\n'),
]
for a,b in R:
    n=c.count(a)
    if n!=1: sys.exit('n=%d %s'%(n,a[:70]))
    c=c.replace(a,b)
open(f,'w',encoding='utf-8',newline='\n').write(c)
print('ok')
