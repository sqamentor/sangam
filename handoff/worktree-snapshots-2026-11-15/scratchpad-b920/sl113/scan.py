"""SL-113 scan: in every generated BPMN, defaults (SetVariables 'defaults') that pre-seed a variable a connector query
or submit writes as its result (<resultPrefix><Key>), i.e. a value that stands in when the check fails or never runs.
usage: scan.py <packs dir>"""
import os
import re
import sys

root = sys.argv[1]
FIELD = re.compile(r'<operaton:field name="(\w+)"><operaton:string>([^<]*)</operaton:string></operaton:field>')
TASK = re.compile(r'<serviceTask id="([^"]+)"[^>]*operaton:class="org\.sangam\.packs\.core\.(\w+)"[^>]*>(.*?)</serviceTask>',
                  re.S)
for dp, _, fs in os.walk(root):
    for f in sorted(fs):
        if not f.endswith('.bpmn'):
            continue
        xml = open(os.path.join(dp, f), encoding='utf-8').read()
        prefixes = {}
        defaults = []
        for tid, cls, body in TASK.findall(xml):
            fields = dict(FIELD.findall(body))
            if cls == 'ConnectorCallDelegate':
                c = fields.get('connector', '')
                pre = fields.get('resultPrefix') or c.rsplit('.', 1)[-1]
                prefixes.setdefault(pre, []).append((tid, c, fields.get('mode', 'query')))
            if cls == 'SetVariablesDelegate' and fields.get('defaults'):
                for pair in fields['defaults'].split(';'):
                    k, _, v = pair.partition('=')
                    defaults.append((tid, k.strip(), v.strip()))
        for tid, k, v in defaults:
            for pre, calls in prefixes.items():
                if k.startswith(pre) and len(k) > len(pre) and k[len(pre)].isupper():
                    print(f'{os.path.relpath(os.path.join(dp, f), root)}  {tid}: {k}={v}  <- written by '
                          + ', '.join(f'{t} {c} ({m})' for t, c, m in calls))
