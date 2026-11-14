import json
import os
import sys

os.chdir(sys.argv[1])
Q = '\\"'


def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for o, n in pairs:
        assert s.count(o) == 1, (p, o)
        s = s.replace(o, n)
    open(p, 'w', encoding='utf-8', newline='').write(s)


def bpmn(process, process_ext, review_listener):
    def a(k, v):
        return ' ' + k + '=' + Q + v + Q
    return ('<?xml' + a('version', '1.0') + a('encoding', 'UTF-8') + '?><definitions'
            + a('xmlns', 'http://www.omg.org/spec/BPMN/20100524/MODEL') + a('xmlns:flowable', 'http://flowable.org/bpmn')
            + a('targetNamespace', 'urn:sangam:test:flowable-source') + '><process' + a('id', process)
            + a('isExecutable', 'true') + '>'
            + ('<extensionElements>' + process_ext + '</extensionElements>' if process_ext else '')
            + '<userTask' + a('id', 'review' if process == 'order' else 'legacyTask') + '><extensionElements>'
            + '<flowable:taskListener' + a('event', review_listener) + a('class', 'org.example.Listener')
            + '/></extensionElements></userTask></process></definitions>')


def ex(i, d, act):
    return ('      {"ID_": "%s", "PROC_INST_ID_": "%s", "PROC_DEF_ID_": "%s", "TENANT_ID_": "acme", "IS_ACTIVE_": '
            'false, "IS_SCOPE_": true, "SUSPENSION_STATE_": 1},\n      {"ID_": "%s-e1", "PARENT_ID_": "%s", '
            '"PROC_INST_ID_": "%s", "PROC_DEF_ID_": "%s", "TENANT_ID_": "acme", "ACT_ID_": "%s", "IS_ACTIVE_": '
            'true, "SUSPENSION_STATE_": 1}') % (i, i, d, i, i, i, d, act)


def task(i):
    return ('      {"ID_": "t-%s", "EXECUTION_ID_": "%s-e1", "PROC_INST_ID_": "%s", "TENANT_ID_": "acme", "TASK_DEF_KEY_": '
            '"review", "NAME_": "Review order", "PRIORITY_": 50}') % (i, i, i)


p = 'corpus-rejected.json'
edit(p, [
    ('No deployed resource is given, so every instance also carries the WARN SOURCE_MODEL_UNREAD.",',
     'Decision 231: r21 to r23 belong to definitions whose deployed model declares a delete task listener, a process '
     'event listener, or cannot be read (no resource); the other definitions have a model without such listeners, so '
     'each instance keeps one reason.",'),
    ('{"ID_": "order:1:100", "KEY_": "order", "VERSION_": 1, "TENANT_ID_": "acme"},\n'
     '      {"ID_": "legacy:3:900", "KEY_": "legacy", "VERSION_": 3, "TENANT_ID_": "acme"}',
     '{"ID_": "order:1:100", "KEY_": "order", "VERSION_": 1, "TENANT_ID_": "acme", "DEPLOYMENT_ID_": "dep-100", "RESOURCE_NAME_": "order.bpmn20.xml"},\n'
     '      {"ID_": "legacy:3:900", "KEY_": "legacy", "VERSION_": 3, "TENANT_ID_": "acme", "DEPLOYMENT_ID_": "dep-900", "RESOURCE_NAME_": "legacy.bpmn20.xml"},\n'
     '      {"ID_": "order:1:101", "KEY_": "order", "VERSION_": 1, "TENANT_ID_": "acme", "DEPLOYMENT_ID_": "dep-101", "RESOURCE_NAME_": "order.bpmn20.xml"},\n'
     '      {"ID_": "order:1:102", "KEY_": "order", "VERSION_": 1, "TENANT_ID_": "acme", "DEPLOYMENT_ID_": "dep-102", "RESOURCE_NAME_": "order.bpmn20.xml"},\n'
     '      {"ID_": "order:1:103", "KEY_": "order", "VERSION_": 1, "TENANT_ID_": "acme", "DEPLOYMENT_ID_": "dep-103", "RESOURCE_NAME_": "order.bpmn20.xml"}'),
    ('"ACT_ID_": "cooling", "IS_ACTIVE_": true, "SUSPENSION_STATE_": 1}\n    ],',
     '"ACT_ID_": "cooling", "IS_ACTIVE_": true, "SUSPENSION_STATE_": 1},\n\n' + ex('r21', 'order:1:101', 'review')
     + ',\n\n' + ex('r22', 'order:1:102', 'review') + ',\n\n' + ex('r23', 'order:1:103', 'review') + '\n    ],'),
    ('"PROC_INST_ID_": "r19", "TENANT_ID_": "acme", "TASK_DEF_KEY_": "review", "NAME_": "Review order", "PRIORITY_": 50}\n    ],',
     '"PROC_INST_ID_": "r19", "TENANT_ID_": "acme", "TASK_DEF_KEY_": "review", "NAME_": "Review order", "PRIORITY_": 50},\n'
     + task('r21') + ',\n' + task('r22') + ',\n' + task('r23') + '\n    ],'),
])
s = open(p, encoding='utf-8').read()
assert s.rstrip().endswith('}\n  }\n}') or s.rstrip().endswith('}')
idx = s.rstrip().rfind('}')
idx2 = s[:idx].rstrip().rfind('}')
res = ('  "resources": {\n'
       '    "dep-100/order.bpmn20.xml": "' + bpmn('order', '', 'create') + '",\n'
       '    "dep-900/legacy.bpmn20.xml": "' + bpmn('legacy', '', 'create') + '",\n'
       '    "dep-101/order.bpmn20.xml": "' + bpmn('order', '', 'delete') + '",\n'
       '    "dep-102/order.bpmn20.xml": "' + bpmn('order', '<flowable:eventListener' + ' class=' + Q + 'org.example.Audit' + Q + '/>', 'create') + '"\n'
       '  }\n')
s = s[:idx2 + 1] + ',\n' + res + '}\n'
open(p, 'w', encoding='utf-8', newline='').write(s)
d = json.load(open(p, encoding='utf-8'))
print(sorted(d['resources']))
print(len(d['tables']['ACT_RU_EXECUTION']), len(d['tables']['ACT_RE_PROCDEF']))
