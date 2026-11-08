# SPDX-License-Identifier: Apache-2.0
"""BPMN / DMN / CMMN / form writer with automatic layout (BPMN DI) for the Sangam packs."""
import json
import os
import sys
from collections import defaultdict
from xml.sax.saxutils import escape

sys.setrecursionlimit(10000)

P = 'org.sangam.packs.core.'
C = dict(
    LIFE=P + 'LifecycleStateDelegate', START=P + 'PackStartListener', ACTOR=P + 'ActorRecorderListener',
    SOD=P + 'SodGuardListener', POWER=P + 'PowerLimitGuardListener', BIND=P + 'ApprovalBindingListener',
    REC=P + 'RecommendationLevelDelegate', SLA=P + 'SlaDelegate', OP=P + 'ExternalOperationDelegate',
    CALL=P + 'ConnectorCallDelegate', DEC=P + 'DecisionDelegate', DOC=P + 'DocumentDelegate',
    SIGN=P + 'SignatureDelegate', CASE=P + 'CaseRecordDelegate', SEAT=P + 'SeatResolutionDelegate',
    LEDGER=P + 'LedgerDelegate', CUM=P + 'CumulativeThresholdDelegate', BATCH=P + 'BatchDelegate',
    ITEM=P + 'BatchItemOutcomeListener', JOIN=P + 'BatchJoinListener', LINK=P + 'LinkedInstanceDelegate',
    ASSIGN=P + 'AssignmentStrategyDelegate', SET=P + 'SetVariablesDelegate', COMM=P + 'CommitteeDelegate',
    CORR=P + 'CorrelationDelegate', SUSP=P + 'SuspensionDelegate', NOTIFY=P + 'NotifyDelegate',
    MERIT='org.sangam.packs.gov.MeritListDelegate', WAIT='org.sangam.packs.gov.WaitlistDelegate',
    MATCH='org.sangam.packs.gov.ReconciliationMatchDelegate', HEAR='org.sangam.packs.gov.HearingScheduleDelegate',
    EVWIN='org.sangam.packs.universal.EventWindowDelegate',
)

SIZES = {'task': (100, 80), 'gateway': (50, 50), 'event': (36, 36)}
COLW, ROWH = 165, 125


def a(v):
    return escape(str(v), {'"': '&quot;'})


def cond(c):
    c = c.strip()
    return c if c.startswith('${') or c.startswith('#{') else '${' + c + '}'


def fld(name, val):
    v = str(val)
    if '${' in v or '#{' in v:
        return f'<operaton:field name="{name}"><operaton:expression>{escape(v)}</operaton:expression></operaton:field>'
    return f'<operaton:field name="{name}"><operaton:string>{escape(v)}</operaton:string></operaton:field>'


def fields_xml(fields):
    return ''.join(fld(k, v) for k, v in (fields or {}).items() if v is not None)


def xlistener(event, cls, fields=None):
    return f'<operaton:executionListener event="{event}" class="{cls}">{fields_xml(fields)}</operaton:executionListener>'


def tlistener(event, cls, fields=None):
    return f'<operaton:taskListener event="{event}" class="{cls}">{fields_xml(fields)}</operaton:taskListener>'


class Scope:
    def __init__(self, proc, el=None):
        self.proc = proc
        self.el = el
        self.nodes = []
        self.flows = []
        self.boundaries = []
        self.subs = []
        self.assocs = []

    # ---- element creation --------------------------------------------------------------------------------------
    def _add(self, el):
        if el['id'] in self.proc.ids:
            raise ValueError('duplicate id ' + el['id'] + ' in ' + self.proc.pid)
        self.proc.ids.add(el['id'])
        el.setdefault('attrs', {})
        el.setdefault('ext', [])
        el.setdefault('body', [])
        self.nodes.append(el)
        return el['id']

    def start(self, id, name='Start', msg=None, timer_cycle=None, timer_date=None, interrupting=None,
              listeners=None, form=None, initiator='initiator', signal=None):
        attrs = {}
        if initiator and self.el is None:
            attrs['operaton:initiator'] = initiator
        if form:
            attrs['operaton:formKey'] = 'sangam:' + form
        if interrupting is not None:
            attrs['isInterrupting'] = 'true' if interrupting else 'false'
        body = []
        if msg:
            body.append(f'<messageEventDefinition messageRef="{self.proc.msg(msg)}"/>')
        if signal:
            body.append(f'<signalEventDefinition signalRef="{self.proc.sig(signal)}"/>')
        if timer_cycle:
            body.append(f'<timerEventDefinition><timeCycle xsi:type="tFormalExpression">{escape(timer_cycle)}'
                        f'</timeCycle></timerEventDefinition>')
        if timer_date:
            body.append(f'<timerEventDefinition><timeDate xsi:type="tFormalExpression">{escape(timer_date)}'
                        f'</timeDate></timerEventDefinition>')
        return self._add(dict(tag='startEvent', id=id, name=name, kind='event', attrs=attrs,
                              ext=list(listeners or []), body=body))

    def end(self, id, name='End', error=None, terminate=False, listeners=None, escalation=None, compensate=False):
        body = []
        if error:
            body.append(f'<errorEventDefinition errorRef="{self.proc.err(error)}"/>')
        if terminate:
            body.append('<terminateEventDefinition/>')
        if escalation:
            body.append(f'<escalationEventDefinition escalationRef="{self.proc.esc(escalation)}"/>')
        if compensate:
            body.append('<compensateEventDefinition/>')
        return self._add(dict(tag='endEvent', id=id, name=name, kind='event', ext=list(listeners or []), body=body))

    def svc(self, id, name, cls, fields=None, async_=False, listeners=None, comp_for=None, async_after=False):
        attrs = {'operaton:class': cls}
        if async_:
            attrs['operaton:asyncBefore'] = 'true'
        if async_after:
            attrs['operaton:asyncAfter'] = 'true'
        el = dict(tag='serviceTask', id=id, name=name, kind='task', attrs=attrs,
                  ext=[fields_xml(fields)] + list(listeners or []))
        if comp_for:
            el['attrs']['isForCompensation'] = 'true'
            el['comp_for'] = comp_for
        return self._add(el)

    def expr(self, id, name, expression, async_=False):
        attrs = {'operaton:expression': expression}
        if async_:
            attrs['operaton:asyncBefore'] = 'true'
        return self._add(dict(tag='serviceTask', id=id, name=name, kind='task', attrs=attrs))

    def ext(self, id, name, topic, async_=False, retries=None, listeners=None):
        attrs = {'operaton:type': 'external', 'operaton:topic': topic}
        if async_:
            attrs['operaton:asyncBefore'] = 'true'
        return self._add(dict(tag='serviceTask', id=id, name=name, kind='task', attrs=attrs,
                              ext=list(listeners or [])))

    def rule(self, id, name, key, prefix=None, binding=None, collect=False, required=True, async_=False):
        f = {'decisionKey': key, 'resultPrefix': prefix, 'binding': binding}
        if collect:
            f['collect'] = 'true'
        if not required:
            f['required'] = 'false'
        attrs = {'operaton:class': C['DEC']}
        if async_:
            attrs['operaton:asyncBefore'] = 'true'
        self.proc.decisions_used.add(key)
        return self._add(dict(tag='businessRuleTask', id=id, name=name, kind='task', attrs=attrs,
                              ext=[fields_xml(f)]))

    def user(self, id, name, group, form=None, decision=None, fields=None, listeners=None, due=None, mi=None,
             priority=None, sod=False, sod_steps=None, sod_exclude=None, power=None, bind=None, step_suffix=None,
             doc=None, joint=False):
        """decision = (var, [values]); power = dict(decisionKey=.., approveValues=..); bind = approve values."""
        attrs = {'operaton:candidateGroups': group}
        if form:
            attrs['operaton:formKey'] = 'sangam:' + form
            self.proc.forms.append(dict(key=form, name=name, decision=decision, fields=fields or [], doc=doc))
        if due:
            attrs['operaton:dueDate'] = due
        if priority:
            attrs['operaton:priority'] = priority
        ext = []
        if mi:
            ext.append(f'<operaton:properties><operaton:property name="sangam.maxCardinality" value="{mi["max"]}"/>'
                       f'</operaton:properties>')
        dvar = decision[0] if decision else None
        if sod:
            ext.append(tlistener('complete', C['SOD'], {'steps': sod_steps, 'excludeVar': sod_exclude,
                                                       'stepSuffixVar': step_suffix}))
        if power:
            ext.append(tlistener('complete', C['POWER'], dict(power, decisionVar=dvar)))
        if bind:
            ext.append(tlistener('complete', C['BIND'], {'decisionVar': dvar, 'approveValues': bind,
                                                        'mode': 'JOINT' if joint else None}))
        ext.extend(listeners or [])
        ext.append(tlistener('complete', C['ACTOR'], {'decisionVar': dvar, 'stepSuffixVar': step_suffix}))
        body = []
        if mi:
            b = f'<multiInstanceLoopCharacteristics isSequential="{"true" if mi.get("seq") else "false"}">'
            b += f'<loopCardinality xsi:type="tFormalExpression">{escape(mi["count"])}</loopCardinality>'
            if mi.get('complete'):
                b += f'<completionCondition xsi:type="tFormalExpression">{escape(mi["complete"])}</completionCondition>'
            b += '</multiInstanceLoopCharacteristics>'
            body.append(b)
        return self._add(dict(tag='userTask', id=id, name=name, kind='task', attrs=attrs, ext=ext, body=body))

    def call(self, id, name, called, async_=False, ins=None, outs_all=True, mi=None, bk=None, all_in=True,
             outs=None):
        ext = [f'<operaton:in businessKey="{a(bk or "#{execution.processBusinessKey}")}"/>']
        if all_in:
            ext.append('<operaton:in variables="all"/>')
        for src, tgt in (ins or {}).items():
            src = src.split('|')[0]
            if '${' in src:
                ext.append(f'<operaton:in sourceExpression="{a(src)}" target="{tgt}"/>')
            else:
                ext.append(f'<operaton:in source="{src}" target="{tgt}"/>')
        if outs_all:
            ext.append('<operaton:out variables="all"/>')
        for src, tgt in (outs or {}).items():
            ext.append(f'<operaton:out source="{src}" target="{tgt}"/>')
        attrs = {'calledElement': called}
        if async_:
            attrs['operaton:asyncBefore'] = 'true'
        self.proc.calls.add(called)
        return self._add(dict(tag='callActivity', id=id, name=name, kind='task', attrs=attrs, ext=ext))

    def xgw(self, id, name='', policy=None):
        # ADR-D-25: strict XOR by default; policy='priority' needs flow(..., priority=n) on every conditional flow
        attrs = {'sangam:routingPolicy': policy} if policy else {}
        return self._add(dict(tag='exclusiveGateway', id=id, name=name, kind='gateway', attrs=attrs))

    def pgw(self, id, name=''):
        return self._add(dict(tag='parallelGateway', id=id, name=name, kind='gateway'))

    def egw(self, id, name=''):
        return self._add(dict(tag='eventBasedGateway', id=id, name=name, kind='gateway'))

    def catch_msg(self, id, name, msg, listeners=None):
        return self._add(dict(tag='intermediateCatchEvent', id=id, name=name, kind='event', ext=list(listeners or []),
                              body=[f'<messageEventDefinition messageRef="{self.proc.msg(msg)}"/>']))

    def catch_timer(self, id, name, date=None, duration=None, cycle=None):
        return self._add(dict(tag='intermediateCatchEvent', id=id, name=name, kind='event',
                              body=[timer_def(date, duration, cycle)]))

    def throw(self, id, name, compensate=False, escalation=None, listeners=None):
        body = []
        if compensate:
            body.append('<compensateEventDefinition/>')
        if escalation:
            body.append(f'<escalationEventDefinition escalationRef="{self.proc.esc(escalation)}"/>')
        return self._add(dict(tag='intermediateThrowEvent', id=id, name=name, kind='event', body=body,
                              ext=list(listeners or [])))

    def boundary(self, id, host, name, timer_date=None, timer_duration=None, msg=None, error=None, cancel=True,
                 compensate=False, escalation=None):
        if timer_date or timer_duration:
            body = timer_def(timer_date, timer_duration, None)
        elif msg:
            body = f'<messageEventDefinition messageRef="{self.proc.msg(msg)}"/>'
        elif error:
            body = f'<errorEventDefinition errorRef="{self.proc.err(error)}"/>'
        elif compensate:
            body = '<compensateEventDefinition/>'
        elif escalation:
            body = f'<escalationEventDefinition escalationRef="{self.proc.esc(escalation)}"/>'
        else:
            raise ValueError('boundary kind')
        if id in self.proc.ids:
            raise ValueError('duplicate id ' + id)
        self.proc.ids.add(id)
        self.boundaries.append(dict(tag='boundaryEvent', id=id, name=name, host=host, kind='event',
                                    cancel=cancel, body=[body]))
        return id

    def evsub(self, id, name):
        el = dict(tag='subProcess', id=id, name=name, kind='sub', attrs={'triggeredByEvent': 'true'})
        if id in self.proc.ids:
            raise ValueError('duplicate id ' + id)
        self.proc.ids.add(id)
        s = Scope(self.proc, el)
        el['scope'] = s
        self.subs.append(el)
        return s

    def assoc(self, src, tgt):
        self.assocs.append((self.proc.fid('a'), src, tgt))

    def flow(self, src, tgt, c=None, name=None, default=False, priority=None):
        fid = self.proc.fid('f')
        self.flows.append(dict(id=fid, src=src, tgt=tgt, cond=c, name=name, default=default, priority=priority))
        return fid

    def seq(self, *ids):
        for x, y in zip(ids, ids[1:]):
            self.flow(x, y)
        return ids[-1]

    def node(self, id):
        for n in self.nodes:
            if n['id'] == id:
                return n
        return None

    # ---- macros ----------------------------------------------------------------------------------------------
    def state(self, id, st, name=None):
        return self.svc(id, name or ('State: ' + st), C['LIFE'], {'state': st})

    def setv(self, id, name, values=None, defaults=None):
        return self.svc(id, name, C['SET'], {'values': values, 'defaults': defaults})

    def notify(self, id, name, template, channel='inapp', recipient=None, async_=False):
        return self.svc(id, name, C['NOTIFY'], {'template': template, 'channel': channel, 'recipientVar': recipient},
                        async_=async_)

    def conn(self, id, name, connector, operation='call', mode='query', inputs=None, prefix=None, step=None,
             error=None, async_=True):
        self.proc.connectors.add((connector, mode))
        return self.svc(id, name, C['CALL'], {'connector': connector, 'operation': operation, 'mode': mode,
                                              'inputs': inputs, 'resultPrefix': prefix, 'step': step,
                                              'errorOnFailure': error}, async_=async_)

    def sla_task(self, id, name, seat_var, form=None, decision=None, fields=None, spec=None, default_spec='5WD',
                 on_breach='ESCALATE', approve='approve', refuse='reject', sod=False, sod_steps=None,
                 sod_exclude=None, power=None, bind=None, step_suffix=None, listeners=None, max_esc=None,
                 doc=None, bound_by_case=None):
        """SLA-guarded human task (R31, GP-06): plan, task, 75% warning, breach with escalation or deemed outcome.
        Returns (entry, exit) ids."""
        plan = self.svc(id + '_slaPlan', 'Start SLA clock', C['SLA'],
                        {'op': 'PLAN', 'clock': id, 'spec': spec, 'defaultSpec': default_spec,
                         'boundByCase': bound_by_case})
        t = self.user(id, name, '${' + seat_var + '}', form=form, decision=decision, fields=fields,
                      due='${' + id + 'DueAt}', sod=sod, sod_steps=sod_steps, sod_exclude=sod_exclude, power=power,
                      bind=bind, step_suffix=step_suffix, listeners=listeners, doc=doc)
        warn = self.boundary(id + '_warn', t, 'SLA warning (75%)', timer_date='${' + id + 'WarnAt}', cancel=False)
        wsvc = self.svc(id + '_warnNotify', 'SLA warning', C['SLA'], {'op': 'WARN', 'clock': id,
                                                                    'seatVar': seat_var})
        wend = self.end(id + '_warnEnd', 'Warned')
        self.flow(warn, wsvc)
        self.flow(wsvc, wend)
        br = self.boundary(id + '_breach', t, 'SLA breached', timer_date='${' + id + 'DueAt}', cancel=True)
        bsvc = self.svc(id + '_breachAct', 'Escalate or deem', C['SLA'],
                        {'op': 'BREACH', 'clock': id, 'onBreach': on_breach, 'seatVar': seat_var,
                         'decisionVar': decision[0] if decision else None, 'approveValue': approve,
                         'refuseValue': refuse, 'maxEscalations': max_esc})
        bgw = self.xgw(id + '_breachGw', 'Deemed?')
        done = self.xgw(id + '_done', '')
        self.flow(br, bsvc)
        self.flow(bsvc, bgw)
        self.flow(bgw, done, "slaAction == 'DEEMED'", 'deemed')
        self.flow(bgw, plan, None, 'escalated', default=True)
        self.flow(plan, t)
        self.flow(t, done)
        return plan, done

    def side_exits(self, prefix='se'):
        """Withdrawal by the initiator and authorised cancellation at any stage (GP-14, IX.3 side exits, VI.5)."""
        w = self.evsub(prefix + '_withdraw', 'Withdrawn by initiator')
        ws = w.start(prefix + '_withdrawStart', 'Withdrawal requested', msg='sangam.withdrawCase', interrupting=True)
        wset = w.setv(prefix + '_withdrawKind', 'Kind: withdrawal', values='cancelKind=WITHDRAWN')
        wc = w.call(prefix + '_withdrawCall', 'Withdraw (core)', 'sangam-core-cancellation')
        we = w.end(prefix + '_withdrawEnd', 'Withdrawn', listeners=[self.proc.item_listener('WITHDRAWN')])
        w.seq(ws, wset, wc, we)
        c = self.evsub(prefix + '_cancel', 'Authorised cancellation')
        cs = c.start(prefix + '_cancelStart', 'Cancellation ordered', msg='sangam.cancelCase', interrupting=True)
        cset = c.setv(prefix + '_cancelKind', 'Kind: cancellation', values='cancelKind=CANCELLED')
        cc = c.call(prefix + '_cancelCall', 'Cancel (core)', 'sangam-core-cancellation')
        ce = c.end(prefix + '_cancelEnd', 'Cancelled', listeners=[self.proc.item_listener('CANCELLED')])
        c.seq(cs, cset, cc, ce)


def timer_def(date, duration, cycle):
    if date:
        return f'<timerEventDefinition><timeDate xsi:type="tFormalExpression">{escape(date)}</timeDate></timerEventDefinition>'
    if duration:
        return (f'<timerEventDefinition><timeDuration xsi:type="tFormalExpression">{escape(duration)}</timeDuration>'
                f'</timerEventDefinition>')
    return f'<timerEventDefinition><timeCycle xsi:type="tFormalExpression">{escape(cycle)}</timeCycle></timerEventDefinition>'


class Proc:
    def __init__(self, pid, name, doc='', ttl=3650, pack=None, version='1.0.0', start=None, callable_core=False):
        self.pid = pid
        self.name = name
        self.doc = doc
        self.ttl = ttl
        self.version = version
        self.ids = set([pid])
        self.n = 0
        self.messages = {}
        self.errors = {}
        self.signals = {}
        self.escalations = {}
        self.forms = []
        self.calls = set()
        self.decisions_used = set()
        self.connectors = set()
        self.root = Scope(self)
        self.listeners = []
        self.pack = pack
        if start:
            self.listeners.append(xlistener('start', C['START'], start))

    def fid(self, k):
        self.n += 1
        return f'{self.short()}_{k}{self.n}'

    def short(self):
        return self.pid.replace('sangam-', '').replace('-', '_')[:24]

    def msg(self, name):
        mid = 'msg_' + name.replace('.', '_').replace('-', '_')
        self.messages[mid] = name
        return mid

    def sig(self, name):
        sid = 'sig_' + name.replace('.', '_').replace('-', '_')
        self.signals[sid] = name
        return sid

    def err(self, code):
        eid = 'err_' + code
        self.errors[eid] = code
        return eid

    def esc(self, code):
        eid = 'esc_' + code
        self.escalations[eid] = code
        return eid

    def item_listener(self, outcome, reason_var=None, data_vars=None, round_var=None):
        return xlistener('end', C['ITEM'], {'outcome': outcome, 'reasonVar': reason_var, 'dataVars': data_vars,
                                            'roundVar': round_var})

    # ---- XML ---------------------------------------------------------------------------------------------------
    def xml(self):
        out = ['<?xml version="1.0" encoding="UTF-8"?>',
               '<!-- SPDX-License-Identifier: Apache-2.0 -->',
               f'<!-- {escape(self.name)}. Generated layout; edit freely in the Sangam modeller. -->',
               '<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" '
               'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
               'xmlns:operaton="http://operaton.org/schema/1.0/bpmn" '
               'xmlns:sangam="http://sangam.org/schema/1.0/bpmn" '
               'xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" '
               'xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" '
               'xmlns:di="http://www.omg.org/spec/DD/20100524/DI" '
               f'id="defs_{self.short()}" targetNamespace="http://sangam.org/packs" exporter="sangam-packs" '
               f'exporterVersion="{self.version}">']
        for mid, name in sorted(self.messages.items()):
            out.append(f'  <message id="{mid}" name="{a(name)}"/>')
        for eid, code in sorted(self.errors.items()):
            out.append(f'  <error id="{eid}" name="{a(code)}" errorCode="{a(code)}"/>')
        for sid, name in sorted(self.signals.items()):
            out.append(f'  <signal id="{sid}" name="{a(name)}"/>')
        for eid, code in sorted(self.escalations.items()):
            out.append(f'  <escalation id="{eid}" name="{a(code)}" escalationCode="{a(code)}"/>')
        out.append(f'  <process id="{self.pid}" name="{a(self.name)}" isExecutable="true" '
                   f'operaton:historyTimeToLive="{self.ttl}" operaton:versionTag="{self.version}">')
        if self.doc:
            out.append(f'    <documentation>{escape(self.doc)}</documentation>')
        if self.listeners:
            out.append('    <extensionElements>' + ''.join(self.listeners) + '</extensionElements>')
        self._scope_xml(self.root, out, '    ')
        out.append('  </process>')
        layout_proc(self)
        out.extend(di_xml(self))
        out.append('</definitions>')
        return '\n'.join(out) + '\n'

    def _scope_xml(self, s, out, ind):
        defaults = {}
        for f in s.flows:
            if f['default']:
                defaults[f['src']] = f['id']
        for n in s.nodes:
            attrs = dict(n.get('attrs', {}))
            if n['id'] in defaults:
                attrs['default'] = defaults[n['id']]
            at = ''.join(f' {k}="{a(v)}"' for k, v in attrs.items())
            ext = ''.join(x for x in n.get('ext', []) if x)
            body = ''.join(n.get('body', []))
            inner = (f'<extensionElements>{ext}</extensionElements>' if ext else '') + body
            out.append(f'{ind}<{n["tag"]} id="{n["id"]}" name="{a(n["name"])}"{at}>{inner}</{n["tag"]}>')
        for b in s.boundaries:
            out.append(f'{ind}<boundaryEvent id="{b["id"]}" name="{a(b["name"])}" attachedToRef="{b["host"]}" '
                       f'cancelActivity="{"true" if b["cancel"] else "false"}">{"".join(b["body"])}</boundaryEvent>')
        for f in s.flows:
            nm = f' name="{a(f["name"])}"' if f['name'] else ''
            if f.get('priority') is not None:
                nm += f' sangam:priority="{f["priority"]}"'
            if f['cond']:
                out.append(f'{ind}<sequenceFlow id="{f["id"]}"{nm} sourceRef="{f["src"]}" targetRef="{f["tgt"]}">'
                           f'<conditionExpression xsi:type="tFormalExpression">{escape(cond(f["cond"]))}'
                           f'</conditionExpression></sequenceFlow>')
            else:
                out.append(f'{ind}<sequenceFlow id="{f["id"]}"{nm} sourceRef="{f["src"]}" targetRef="{f["tgt"]}"/>')
        for sub in s.subs:
            at = ''.join(f' {k}="{a(v)}"' for k, v in sub['attrs'].items())
            out.append(f'{ind}<subProcess id="{sub["id"]}" name="{a(sub["name"])}"{at}>')
            self._scope_xml(sub['scope'], out, ind + '  ')
            out.append(f'{ind}</subProcess>')
        for aid, src, tgt in s.assocs:  # artifacts after all flow elements (XSD order)
            out.append(f'{ind}<association id="{aid}" associationDirection="One" sourceRef="{src}" targetRef="{tgt}"/>')


# ---- layout ------------------------------------------------------------------------------------------------------

def size(n):
    return SIZES.get(n['kind'], (100, 80))


def layout_scope(s, ox, oy):
    nodes = [n for n in s.nodes if not n.get('comp_for')]
    idx = {n['id']: n for n in nodes}
    host_of = {b['id']: b['host'] for b in s.boundaries}
    succ = defaultdict(list)
    preds = defaultdict(list)
    for f in s.flows:
        src = host_of.get(f['src'], f['src'])
        if f['tgt'] in idx and src in idx and f['tgt'] != src:
            succ[src].append(f['tgt'])
    incoming = defaultdict(int)
    for u in succ:
        for v in succ[u]:
            incoming[v] += 1
    order = []
    color = {}
    back = set()

    def dfs(u):
        color[u] = 1
        for v in succ[u]:
            if color.get(v) == 1:
                back.add((u, v))
            elif not color.get(v):
                dfs(v)
        color[u] = 2
        order.append(u)

    starts = [n['id'] for n in nodes if n['tag'] == 'startEvent'] + [n['id'] for n in nodes if incoming[n['id']] == 0]
    for st in starts + [n['id'] for n in nodes]:
        if not color.get(st):
            dfs(st)
    topo = list(reversed(order))
    rank = {u: 0 for u in topo}
    for u in topo:
        for v in succ[u]:
            if (u, v) not in back:
                rank[v] = max(rank[v], rank[u] + 1)
                preds[v].append(u)
    occupied = defaultdict(set)
    row = {}
    for u in topo:
        pr = [row[p] for p in preds[u] if p in row]
        r = min(pr) if pr else 0
        while r in occupied[rank[u]]:
            r += 1
        row[u] = r
        occupied[rank[u]].add(r)
    maxx, maxy = ox, oy
    for u in topo:
        n = idx[u]
        w, h = size(n)
        cx = ox + 70 + rank[u] * COLW
        cy = oy + 70 + row[u] * ROWH
        n['x'], n['y'], n['w'], n['h'] = cx - w // 2, cy - h // 2, w, h
        maxx = max(maxx, n['x'] + w)
        maxy = max(maxy, n['y'] + h)
    per_host = defaultdict(int)
    for b in s.boundaries:
        hst = idx.get(b['host'])
        i = per_host[b['host']]
        per_host[b['host']] += 1
        b['x'], b['y'], b['w'], b['h'] = hst['x'] + 8 + i * 30, hst['y'] + hst['h'] - 18, 36, 36
    for n in s.nodes:
        if n.get('comp_for'):
            hst = idx.get(n['comp_for'])
            n['x'], n['y'], n['w'], n['h'] = hst['x'] + 40, hst['y'] + 120, 100, 80
            maxy = max(maxy, n['y'] + 80)
    y = maxy + 50
    for sub in s.subs:
        w, h = layout_scope(sub['scope'], ox + 40, y + 10)
        sub['x'], sub['y'] = ox + 30, y
        sub['w'] = max(w - (ox + 30) + 30, 300)
        sub['h'] = max(h - y + 30, 120)
        maxx = max(maxx, sub['x'] + sub['w'])
        y = sub['y'] + sub['h'] + 40
        maxy = max(maxy, y)
    return maxx, maxy


def layout_proc(p):
    layout_scope(p.root, 0, 0)


def all_shapes(s):
    for n in s.nodes:
        yield n
    for b in s.boundaries:
        yield b
    for sub in s.subs:
        yield sub
        yield from all_shapes(sub['scope'])


def all_edges(s):
    for f in s.flows:
        yield f
    for sub in s.subs:
        yield from all_edges(sub['scope'])


def all_assocs(s):
    for x in s.assocs:
        yield x
    for sub in s.subs:
        yield from all_assocs(sub['scope'])


def di_xml(p):
    shapes = {e['id']: e for e in all_shapes(p.root)}
    out = [f'  <bpmndi:BPMNDiagram id="{p.short()}_diagram">',
           f'    <bpmndi:BPMNPlane id="{p.short()}_plane" bpmnElement="{p.pid}">']
    for e in shapes.values():
        exp = ' isExpanded="true"' if e['kind'] == 'sub' else ''
        out.append(f'      <bpmndi:BPMNShape id="{e["id"]}_di" bpmnElement="{e["id"]}"{exp}>'
                   f'<dc:Bounds x="{e["x"]}" y="{e["y"]}" width="{e["w"]}" height="{e["h"]}"/></bpmndi:BPMNShape>')
    k = 0
    for f in all_edges(p.root):
        s, t = shapes[f['src']], shapes[f['tgt']]
        pts = route(s, t, k)
        k += 1
        wp = ''.join(f'<di:waypoint x="{int(x)}" y="{int(y)}"/>' for x, y in pts)
        out.append(f'      <bpmndi:BPMNEdge id="{f["id"]}_di" bpmnElement="{f["id"]}">{wp}</bpmndi:BPMNEdge>')
    for aid, src, tgt in all_assocs(p.root):
        s, t = shapes[src], shapes[tgt]
        wp = (f'<di:waypoint x="{s["x"] + 18}" y="{s["y"] + 36}"/>'
              f'<di:waypoint x="{t["x"] + 50}" y="{t["y"]}"/>')
        out.append(f'      <bpmndi:BPMNEdge id="{aid}_di" bpmnElement="{aid}">{wp}</bpmndi:BPMNEdge>')
    out.append('    </bpmndi:BPMNPlane>')
    out.append('  </bpmndi:BPMNDiagram>')
    return out


def route(s, t, k):
    boundary = s['tag'] == 'boundaryEvent'
    if boundary:
        sx, sy = s['x'] + 18, s['y'] + 36
    else:
        sx, sy = s['x'] + s['w'], s['y'] + s['h'] / 2
    tx, ty = t['x'], t['y'] + t['h'] / 2
    if tx > sx + 5:
        if boundary:
            return [(sx, sy), (sx, ty), (tx, ty)]
        if abs(sy - ty) < 1:
            return [(sx, sy), (tx, ty)]
        mx = (sx + tx) / 2
        return [(sx, sy), (mx, sy), (mx, ty), (tx, ty)]
    low = max(s['y'] + s['h'], t['y'] + t['h']) + 18 + (k % 4) * 7
    scx, scy = s['x'] + s['w'] / 2, s['y'] + s['h']
    tcx, tcy = t['x'] + t['w'] / 2, t['y'] + t['h']
    return [(scx, scy), (scx, low), (tcx, low), (tcx, tcy)]


# ---- DMN -------------------------------------------------------------------------------------------------------

def dmn_type(typ):
    """ADR-D-26 / SL-05: shipped DMN never uses binary floating point; 'double'/'float' become the exact 'number'.
    The single place every generated DMN type goes through, so a pack definition cannot reintroduce a double."""
    return 'number' if str(typ).lower() in ('double', 'float') else typ


def dmn(did, name, hit, inputs, outputs, rules, doc='', ttl=3650, version='1.0.0'):
    """inputs: [(label, expression, typeRef)], outputs: [(name, label, typeRef)], rules: [(ins, outs, note)]."""
    inputs = [(label, expr, dmn_type(typ)) for (label, expr, typ) in inputs]
    outputs = [(nm, label, dmn_type(typ)) for (nm, label, typ) in outputs]
    o = ['<?xml version="1.0" encoding="UTF-8"?>',
         '<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" '
         'xmlns:operaton="http://operaton.org/schema/1.0/dmn" '
         f'id="defs_{did.replace("-", "_")}" name="{a(name)}" namespace="http://sangam.org/packs">',
         '  <!-- SPDX-License-Identifier: Apache-2.0 -->']
    if doc:
        o.append(f'  <!-- {escape(doc)} -->')
    o.append(f'  <decision id="{did}" name="{a(name)}" operaton:historyTimeToLive="{ttl}" '
             f'operaton:versionTag="{version}">')
    o.append(f'    <decisionTable id="{did}_t" hitPolicy="{hit}">')
    for i, (label, expr, typ) in enumerate(inputs):
        o.append(f'      <input id="{did}_i{i}" label="{a(label)}"><inputExpression id="{did}_ie{i}" typeRef="{typ}">'
                 f'<text>{escape(expr)}</text></inputExpression></input>')
    for i, (nm, label, typ) in enumerate(outputs):
        o.append(f'      <output id="{did}_o{i}" label="{a(label)}" name="{nm}" typeRef="{typ}"/>')
    for r, (ins, outs, note) in enumerate(rules):
        o.append(f'      <rule id="{did}_r{r}">' + (f'<description>{escape(note)}</description>' if note else ''))
        for i, v in enumerate(ins):
            o.append(f'        <inputEntry id="{did}_r{r}_i{i}"><text>{escape(v)}</text></inputEntry>')
        for i, v in enumerate(outs):
            o.append(f'        <outputEntry id="{did}_r{r}_o{i}"><text>{escape(v)}</text></outputEntry>')
        o.append('      </rule>')
    o.append('    </decisionTable>')
    o.append('  </decision>')
    o.append('</definitions>')
    return '\n'.join(o) + '\n'


def q(s):
    return '"' + s + '"'
