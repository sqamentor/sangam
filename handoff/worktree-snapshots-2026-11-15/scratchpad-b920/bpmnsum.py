import sys, re, xml.etree.ElementTree as ET
NS = {'b': 'http://www.omg.org/spec/BPMN/20100524/MODEL'}
def local(t): return t.split('}')[-1]
for path in sys.argv[1:]:
    root = ET.parse(path).getroot()
    print('=====', path.split('packs')[-1])
    msgs = {m.get('id'): m.get('name') for m in root.iter('{%s}message' % NS['b'])}
    for proc in root.iter('{%s}process' % NS['b']):
        print('PROCESS', proc.get('id'))
        for el in proc.iter():
            tag = local(el.tag)
            a = {local(k): v for k, v in el.attrib.items()}
            if tag in ('userTask',):
                print('  USER', a.get('id'), a.get('candidateGroups'), a.get('formKey'), '|', a.get('name'))
            elif tag == 'callActivity':
                ins = [ (local(c.tag), {local(k): v for k,v in c.attrib.items()}) for c in el.iter() if local(c.tag) in ('in','out')]
                print('  CALL', a.get('id'), a.get('calledElement'), a.get('name'))
            elif tag in ('timerEventDefinition',):
                pass
            elif tag in ('boundaryEvent', 'intermediateCatchEvent', 'startEvent'):
                det = []
                for c in el:
                    ct = local(c.tag)
                    if ct == 'timerEventDefinition':
                        det.append('timer ' + ' '.join(local(x.tag) + '=' + (x.text or '') for x in c))
                    elif ct == 'messageEventDefinition':
                        det.append('msg ' + str(msgs.get(c.get('messageRef'))))
                    elif ct == 'signalEventDefinition':
                        det.append('signal')
                    elif ct == 'errorEventDefinition':
                        det.append('error ' + str(c.get('errorRef')))
                if det:
                    print('  EVT', tag, a.get('id'), a.get('attachedToRef', ''), a.get('cancelActivity', ''), det, '|', a.get('name'))
            elif tag == 'sequenceFlow':
                cond = [x.text for x in el if local(x.tag) == 'conditionExpression']
                if cond:
                    print('  FLOW', a.get('sourceRef'), '->', a.get('targetRef'), cond[0].strip())
            elif tag in ('serviceTask', 'scriptTask', 'businessRuleTask', 'sendTask'):
                print('  SVC', a.get('id'), a.get('name'))
