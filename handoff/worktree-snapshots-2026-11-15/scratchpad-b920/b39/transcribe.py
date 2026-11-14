"""One-off: transcribe the 12 hand-written compliance BPMN files into compliance.py declarations (M-010b).
The digest comparison after generation is the proof that the transcription is exact."""
import glob
import os
import sys
import xml.etree.ElementTree as ET

M = '{http://www.omg.org/spec/BPMN/20100524/MODEL}'
OP = '{http://operaton.org/schema/1.0/bpmn}'
XSI = '{http://www.w3.org/2001/XMLSchema-instance}'
STEP = 'org.sangam.compliance.bpmn.ComplianceStepDelegate'


def r(v):
    return repr(v)


def main(src):
    out = []
    for f in sorted(glob.glob(os.path.join(src, '*.bpmn'))):
        root = ET.parse(f).getroot()
        assert set(root.attrib) == {'id', 'targetNamespace', 'exporter', 'exporterVersion'}, f
        assert root.get('targetNamespace') == 'http://sangam.org/packs/compliance' and root.get('exporter') == 'sangam-compliance'
        kids = list(root)
        assert len(kids) == 1 and kids[0].tag == M + 'process', f
        p = kids[0]
        assert p.get('isExecutable') == 'true' and p.get(OP + 'historyTimeToLive') == '3650' and p.get(OP + 'versionTag') == '1.0.0'
        assert root.get('id') == 'defs_' + p.get('id').replace('-', '_'), f
        doc = p.find(M + 'documentation').text
        fn = 'p_' + p.get('id').replace('sangam-', '').replace('-', '_')
        out.append('')
        out.append('')
        out.append(f'def {fn}():')
        out.append(f'    p = Model({r(p.get("id"))}, {r(p.get("name"))}, {r(root.get("exporterVersion"))},')
        out.append(f'              {r(doc)})')
        for el in p:
            t = el.tag.replace(M, '')
            a = el.attrib
            if t == 'documentation':
                continue
            if t == 'startEvent':
                assert set(a) == {'id', 'name'} and len(el) == 0
                out.append(f'    p.start({r(a["id"])}, {r(a["name"])})')
            elif t == 'endEvent':
                assert set(a) == {'id', 'name'} and len(el) == 0
                out.append(f'    p.end({r(a["id"])}, {r(a["name"])})')
            elif t == 'parallelGateway':
                assert set(a) == {'id'} and len(el) == 0
                out.append(f'    p.pgw({r(a["id"])})')
            elif t == 'exclusiveGateway':
                assert set(a) == {'id', 'name', 'default'} and len(el) == 0
                out.append(f'    p.xgw({r(a["id"])}, {r(a["name"])}, default={r(a["default"])})')
            elif t == 'userTask':
                assert set(a) == {'id', 'name', OP + 'candidateGroups', OP + 'formKey'} and len(el) == 0
                fk = a[OP + 'formKey']
                assert fk.startswith('sangam:')
                out.append(f'    p.user({r(a["id"])}, {r(a["name"])}, {r(a[OP + "candidateGroups"])}, {r(fk[7:])})')
            elif t == 'serviceTask':
                assert set(a) == {'id', 'name', OP + 'class', OP + 'asyncBefore'} and a[OP + 'class'] == STEP
                assert a[OP + 'asyncBefore'] == 'true'
                (ext,) = list(el)
                (fld,) = list(ext)
                (s,) = list(fld)
                assert fld.get('name') == 'step' and s.tag == OP + 'string' and len(s) == 0
                out.append(f'    p.step({r(a["id"])}, {r(a["name"])}, {r(s.text)})')
            elif t == 'boundaryEvent':
                assert set(a) == {'id', 'name', 'attachedToRef', 'cancelActivity'} and a['cancelActivity'] == 'false'
                (ted,) = list(el)
                (td,) = list(ted)
                assert td.tag == M + 'timeDate' and td.get(XSI + 'type') == 'tFormalExpression'
                out.append(f'    p.timer({r(a["id"])}, {r(a["name"])}, {r(a["attachedToRef"])}, {r(td.text)})')
            elif t == 'sequenceFlow':
                assert set(a) == {'id', 'sourceRef', 'targetRef'}
                if len(el):
                    (ce,) = list(el)
                    assert ce.tag == M + 'conditionExpression' and ce.get(XSI + 'type') == 'tFormalExpression'
                    out.append(f'    p.flow({r(a["id"])}, {r(a["sourceRef"])}, {r(a["targetRef"])}, {r(ce.text)})')
                else:
                    out.append(f'    p.flow({r(a["id"])}, {r(a["sourceRef"])}, {r(a["targetRef"])})')
            else:
                raise ValueError(t)
        out.append('    return p')
    print('\n'.join(out))


if __name__ == '__main__':
    main(sys.argv[1])
