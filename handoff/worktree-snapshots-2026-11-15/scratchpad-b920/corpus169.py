import json
import os
import sys

os.chdir(sys.argv[1])


def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for o, n in pairs:
        assert s.count(o) == 1, (p, o)
        s = s.replace(o, n)
    open(p, 'w', encoding='utf-8', newline='').write(s)


Q = '\\"'
bpmn = ('<?xml version=' + Q + '1.0' + Q + ' encoding=' + Q + 'UTF-8' + Q + '?><definitions xmlns=' + Q
        + 'http://www.omg.org/spec/BPMN/20100524/MODEL' + Q + ' xmlns:flowable=' + Q + 'http://flowable.org/bpmn' + Q
        + ' targetNamespace=' + Q + 'urn:sangam:test:flowable-source' + Q + '>'
        + '<process id=' + Q + 'order' + Q + ' isExecutable=' + Q + 'true' + Q + '><startEvent id=' + Q + 'start' + Q + '/>'
        + '<userTask id=' + Q + 'review' + Q + ' name=' + Q + 'Review order' + Q + '><extensionElements>'
        + '<flowable:taskListener event=' + Q + 'create' + Q + ' class=' + Q + 'org.example.NotifyReviewer' + Q
        + '/></extensionElements></userTask>'
        + '<userTask id=' + Q + 'approve' + Q + ' name=' + Q + 'Approve order' + Q + '><extensionElements>'
        + '<flowable:taskListener event=' + Q + 'assignment' + Q + ' class=' + Q + 'org.example.NotifyApprover' + Q
        + '/></extensionElements></userTask>'
        + '<endEvent id=' + Q + 'end' + Q + '/></process></definitions>')

edit('corpus-supported.json', [
    ('Times are wall-clock in Europe/Berlin.",',
     'Times are wall-clock in Europe/Berlin. The deployed source model declares only create and assignment task '
     'listeners (nothing that ending an instance fires). j3 and j7 are timers written before Flowable 6.5.0.1 (no '
     'ELEMENT_ID_): j3 with the JSON handler configuration, j7 with the legacy plain activity id.",'),
    ('{"ID_": "order:1:100", "KEY_": "order", "VERSION_": 1, "TENANT_ID_": "acme"},',
     '{"ID_": "order:1:100", "KEY_": "order", "VERSION_": 1, "TENANT_ID_": "acme", "DEPLOYMENT_ID_": "dep-100", '
     '"RESOURCE_NAME_": "order.bpmn20.xml"},'),
    ('"ELEMENT_ID_": "cooling", "RETRIES_": 3, "DUEDATE_": "2026-12-01T10:00:00", "HANDLER_TYPE_": "trigger-timer",',
     '"ELEMENT_ID_": null, "RETRIES_": 3, "DUEDATE_": "2026-12-01T10:00:00", "HANDLER_TYPE_": "trigger-timer", '
     '"HANDLER_CFG_": "{' + Q + 'activityId' + Q + ':' + Q + 'cooling' + Q + '}",'),
    ('"ELEMENT_ID_": "chooseTimer", "RETRIES_": 3, "DUEDATE_": "2026-12-02T10:00:00", "HANDLER_TYPE_": "trigger-timer",',
     '"RETRIES_": 3, "DUEDATE_": "2026-12-02T10:00:00", "HANDLER_TYPE_": "trigger-timer", "HANDLER_CFG_": "chooseTimer",'),
    ('"HANDLER_TYPE_": "external-worker-complete", "TENANT_ID_": "acme"}\n    ]\n  }\n}',
     '"HANDLER_TYPE_": "external-worker-complete", "TENANT_ID_": "acme"}\n    ]\n  },\n  "resources": {\n'
     '    "dep-100/order.bpmn20.xml": "' + bpmn + '"\n  }\n}'),
])


def ex(i, act):
    return ('      {"ID_": "%s", "PROC_INST_ID_": "%s", "PROC_DEF_ID_": "order:1:100", "TENANT_ID_": "acme", "IS_ACTIVE_": '
            'false, "IS_SCOPE_": true, "SUSPENSION_STATE_": 1},\n      {"ID_": "%s-e1", "PARENT_ID_": "%s", '
            '"PROC_INST_ID_": "%s", "PROC_DEF_ID_": "order:1:100", "TENANT_ID_": "acme", "ACT_ID_": "%s", "IS_ACTIVE_": '
            'true, "SUSPENSION_STATE_": 1}') % (i, i, i, i, i, act)


def task(i):
    return ('      {"ID_": "t-%s", "EXECUTION_ID_": "%s-e1", "PROC_INST_ID_": "%s", "TENANT_ID_": "acme", "TASK_DEF_KEY_": '
            '"review", "NAME_": "Review order", "PRIORITY_": 50}') % (i, i, i)


r16e = ('"ACT_ID_": "oldStep", "IS_ACTIVE_": true, "SUSPENSION_STATE_": 1}\n    ],')
r16t = ('"NAME_": "Removed step", "PRIORITY_": 50}\n    ],')
edit('corpus-rejected.json', [
    ('one reason per instance (r<n>). Not real data. Times are wall-clock in Europe/Berlin.",',
     'one reason per instance (r<n>). Not real data. Times are wall-clock in Europe/Berlin. r17 to r20 come from the '
     'Flowable source check: an async-after job (the activity already ran), a longString and an oversized json kept as '
     'bytes, a pre-6.5.0.1 timer whose JSON configuration names no activityId. No deployed resource is given, so every '
     'instance also carries the WARN SOURCE_MODEL_UNREAD.",'),
    (r16e, r16e[:-3] + ',\n\n' + ex('r17', 'dispatch') + ',\n\n' + ex('r18', 'review') + ',\n\n' + ex('r19', 'review')
     + ',\n\n' + ex('r20', 'cooling') + '\n    ],'),
    (r16t, r16t[:-3] + ',\n' + task('r18') + ',\n' + task('r19') + '\n    ],'),
    ('"PROC_INST_ID_": "r15", "LONG_": 2}',
     '"PROC_INST_ID_": "r15", "LONG_": 2},\n      {"ID_": "vr18", "NAME_": "remarks", "TYPE_": "longString", '
     '"EXECUTION_ID_": "r18", "PROC_INST_ID_": "r18", "BYTEARRAY_ID_": "ba-r18"},\n      {"ID_": "vr19", "NAME_": '
     '"payload", "TYPE_": "json", "EXECUTION_ID_": "r19", "PROC_INST_ID_": "r19", "BYTEARRAY_ID_": "ba-r19"}'),
    ('"EXCEPTION_MSG_": "connection refused", "HANDLER_TYPE_": "async-continuation", "TENANT_ID_": "acme"}',
     '"EXCEPTION_MSG_": "connection refused", "HANDLER_TYPE_": "async-continuation", "TENANT_ID_": "acme"},\n'
     '      {"ID_": "j-r17", "TYPE_": "message", "PROCESS_INSTANCE_ID_": "r17", "EXECUTION_ID_": "r17-e1", "ELEMENT_ID_": '
     '"dispatch", "RETRIES_": 3, "HANDLER_TYPE_": "async-after", "TENANT_ID_": "acme"}'),
    ('"DUEDATE_": "2026-03-29T02:30:00", "HANDLER_TYPE_": "trigger-timer", "TENANT_ID_": "acme"}',
     '"DUEDATE_": "2026-03-29T02:30:00", "HANDLER_TYPE_": "trigger-timer", "TENANT_ID_": "acme"},\n'
     '      {"ID_": "j-r20", "TYPE_": "timer", "PROCESS_INSTANCE_ID_": "r20", "EXECUTION_ID_": "r20-e1", "RETRIES_": 3, '
     '"DUEDATE_": "2026-12-01T10:00:00", "HANDLER_TYPE_": "trigger-timer", "HANDLER_CFG_": "{' + Q + 'timerEndDate' + Q
     + ':' + Q + '2027-01-01' + Q + '}", "TENANT_ID_": "acme"}'),
])

for f in ('corpus-supported.json', 'corpus-rejected.json'):
    d = json.load(open(f, encoding='utf-8'))
    print(f, sorted(d.keys()), list(d.get('resources', {}).keys()))
d = json.load(open('corpus-supported.json', encoding='utf-8'))
print(d['resources']['dep-100/order.bpmn20.xml'][:160])
print([j.get('HANDLER_CFG_') for j in d['tables']['ACT_RU_TIMER_JOB']])
r = json.load(open('corpus-rejected.json', encoding='utf-8'))
print(len(r['tables']['ACT_RU_EXECUTION']), [j.get('HANDLER_CFG_') for j in r['tables']['ACT_RU_TIMER_JOB']])
