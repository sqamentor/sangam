# SPDX-License-Identifier: Apache-2.0
"""sangam.compliance BPMN: the 12 process models of sangam-compliance (packs/compliance/*.bpmn), M-010b / SL-134.

packgen is their single source (ADR-D-20): main.py writes them into
sangam-compliance/src/main/resources/packs/compliance, and --check compares them there. The rest of that pack
(pack.json, seed.json, forms, packs.idx) stays hand-maintained in sangam-compliance.

The models are jurisdiction-neutral: every deadline arrives as a process variable that the jurisdiction pack computes
(dueAt, reportDueAt, ...), and every service step is a ComplianceStepDelegate step. Elements are written in
declaration order with their explicit, stable ids (GP-20). The diagram layout (BPMN DI) comes from the packgen writer
(lib.layout_proc, lib.di_xml).

Seat routing (M-010c): a user task is offered to the seat the tenant configured for its compliance role
(sangam.compliance.seats.<ROLE>, a role code of seed.json), through the process variable seat(role), which the
process starter binds at start time and refuses to leave unbound (ComplianceConfig.seatVariables, no fallback to the
role). The starters (DataSubjectRightsService, BreachIncidentService, IncidentReportingService and
BreachNotificationPlanner, which BreachIncidentService.register calls once per new breach) list the roles of their
processes; ComplianceSeatModelTest checks those lists against this BPMN.
"""
import os
import re
from xml.sax.saxutils import escape

import lib

STEP = 'org.sangam.compliance.bpmn.ComplianceStepDelegate'
# OJ-06 step 8 (coordinator ruling 2026-11-13): closing a security incident without the root cause and the lessons
# learned is refused when the follow-up task is completed (422 INCIDENT_CLOSURE_INCOMPLETE), not later as a failed job
INCIDENT_CLOSURE_GUARD = 'org.sangam.compliance.bpmn.IncidentClosureGuardListener'
# the task listener classes a compliance model may name on a user task (each a class of sangam-compliance)
GUARDS = (INCIDENT_CLOSURE_GUARD,)
NAMESPACE = 'http://sangam.org/packs/compliance'
EXPORTER = 'sangam-compliance'
KIND = {'startEvent': 'event', 'endEvent': 'event', 'boundaryEvent': 'event', 'userTask': 'task',
        'serviceTask': 'task', 'exclusiveGateway': 'gateway', 'parallelGateway': 'gateway'}


def seat(role):
    """M-010c: the candidate group of a task worked by compliance role `role`: the process variable that holds the
    tenant's configured seat (sangam.compliance.seats.<ROLE>), bound by the process starter at start time. The name
    follows ComplianceConfig.seatVariable: DPO -> ${dpoSeat}, CISO-HEAD -> ${cisoHeadSeat}."""
    parts = [x for x in re.split(r'[-_ ]+', role.strip().lower()) if x]
    if not parts:
        raise ValueError('role code is blank')
    return '${' + parts[0] + ''.join(x[:1].upper() + x[1:] for x in parts[1:]) + 'Seat}'


class Model:
    """One compliance process: its elements in declaration order, plus the layout graph for lib.layout_scope."""

    def __init__(self, pid, name, exporter_version, doc):
        self.proc = lib.Proc(pid, name, doc)   # holds the layout graph (root scope); its own XML writer is not used
        self.exporter_version = exporter_version
        self.lines = []
        self.tags = {}
        self.defaults = {}

    def _node(self, tag, id, line, **extra):
        if id in self.tags or id == self.proc.pid:
            raise ValueError(f'duplicate id {id} in {self.proc.pid}')
        self.tags[id] = tag
        self.lines.append(line)
        if tag == 'boundaryEvent':
            self.proc.root.boundaries.append(dict(tag=tag, id=id, kind='event', **extra))
        else:
            self.proc.root.nodes.append(dict(tag=tag, id=id, kind=KIND[tag]))
        return id

    def start(self, id, name):
        return self._node('startEvent', id, f'<startEvent id="{id}" name="{lib.a(name)}"/>')

    def end(self, id, name):
        return self._node('endEvent', id, f'<endEvent id="{id}" name="{lib.a(name)}"/>')

    def pgw(self, id):
        return self._node('parallelGateway', id, f'<parallelGateway id="{id}"/>')

    def xgw(self, id, name, default):
        self.defaults[id] = default
        return self._node('exclusiveGateway', id,
                          f'<exclusiveGateway id="{id}" name="{lib.a(name)}" default="{default}"/>')

    def user(self, id, name, group, form, complete_guard=None):
        """A user task. `complete_guard` names a task listener class run when the task is completed: it refuses the
        completion in the caller's own command (the caller sees the refusal and nothing is committed), where the same
        check in a later asynchronous step could only fail a job. The class must be one of the compliance module's
        own listeners (GUARDS) and on the engine's model code allow-list (SL-160)."""
        lib.check_group_expression(id, group)
        head = (f'<userTask id="{id}" name="{lib.a(name)}" operaton:candidateGroups="{lib.a(group)}" '
                f'operaton:formKey="sangam:{lib.a(form)}"')
        if complete_guard is None:
            return self._node('userTask', id, head + '/>')
        if complete_guard not in GUARDS:
            raise ValueError(f'{self.proc.pid}/{id}: {complete_guard} is not a compliance completion guard')
        return self._node('userTask', id, head + '><extensionElements><operaton:taskListener event="complete" '
                                                 f'class="{lib.a(complete_guard)}"/></extensionElements></userTask>')

    def step(self, id, name, step):
        return self._node('serviceTask', id, f'<serviceTask id="{id}" name="{lib.a(name)}" operaton:class="{STEP}" '
                                             f'operaton:asyncBefore="true"><extensionElements><operaton:field '
                                             f'name="step"><operaton:string>{escape(step)}</operaton:string>'
                                             f'</operaton:field></extensionElements></serviceTask>')

    def timer(self, id, name, host, date):
        """A non-interrupting timer on a user task at the date in `date` (a variable computed from the pack)."""
        if self.tags.get(host) != 'userTask':
            raise ValueError(f'{self.proc.pid}/{id}: the host {host} must be a user task declared before its timer')
        return self._node('boundaryEvent', id, f'<boundaryEvent id="{id}" name="{lib.a(name)}" attachedToRef="{host}" '
                                               f'cancelActivity="false"><timerEventDefinition><timeDate '
                                               f'xsi:type="tFormalExpression">{escape(date)}</timeDate>'
                                               f'</timerEventDefinition></boundaryEvent>', host=host, name=name,
                          cancel=False)

    def flow(self, id, src, tgt, cond=None):
        if id in self.tags:
            raise ValueError(f'duplicate id {id} in {self.proc.pid}')
        self.tags[id] = 'sequenceFlow'
        self.proc.root.flows.append(dict(id=id, src=src, tgt=tgt, cond=cond, name=None, default=False, priority=None))
        if cond:
            self.lines.append(f'<sequenceFlow id="{id}" sourceRef="{src}" targetRef="{tgt}"><conditionExpression '
                              f'xsi:type="tFormalExpression">{escape(cond)}</conditionExpression></sequenceFlow>')
        else:
            self.lines.append(f'<sequenceFlow id="{id}" sourceRef="{src}" targetRef="{tgt}"/>')
        return id

    def _validate(self):
        pid = self.proc.pid
        nodes = {k for k, v in self.tags.items() if v != 'sequenceFlow'}
        for f in self.proc.root.flows:
            for end_ in (f['src'], f['tgt']):
                if end_ not in nodes:
                    raise ValueError(f'{pid}/{f["id"]}: unknown element {end_}')
        for gw, fid in self.defaults.items():
            if not any(f['id'] == fid and f['src'] == gw and not f['cond'] for f in self.proc.root.flows):
                raise ValueError(f'{pid}/{gw}: default {fid} is not an unconditional flow out of the gateway')
        if [t for t in self.tags.values()].count('startEvent') != 1:
            raise ValueError(f'{pid}: exactly one start event')

    def xml(self):
        self._validate()
        p = self.proc
        out = ['<?xml version="1.0" encoding="UTF-8"?>',
               '<!-- SPDX-License-Identifier: Apache-2.0 -->',
               f'<!-- {escape(p.name)}. Sangam compliance pack (jurisdiction-neutral): timelines arrive as process '
               'variables computed from the jurisdiction pack. Generated with its diagram layout by '
               'sangam-packs/tools/packgen (compliance.py): change the generator, then regenerate. -->',
               '<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" '
               'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
               'xmlns:operaton="http://operaton.org/schema/1.0/bpmn" '
               'xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" '
               'xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" '
               'xmlns:di="http://www.omg.org/spec/DD/20100524/DI" '
               f'id="defs_{p.pid.replace("-", "_")}" targetNamespace="{NAMESPACE}" exporter="{EXPORTER}" '
               f'exporterVersion="{self.exporter_version}">',
               f'  <process id="{p.pid}" name="{lib.a(p.name)}" isExecutable="true" '
               f'operaton:historyTimeToLive="3650" operaton:versionTag="1.0.0">',
               f'    <documentation>{escape(p.doc)}</documentation>']
        out.extend('    ' + line for line in self.lines)
        out.append('  </process>')
        lib.layout_proc(p)
        out.extend(lib.di_xml(p))
        out.append('</definitions>')
        return '\n'.join(out) + '\n'


def build():
    """The 12 models, in file-name order."""
    return sorted((f() for f in MODELS), key=lambda m: m.proc.pid)


def write_all(directory):
    """Writes every model as <directory>/<process id>.bpmn (LF, SL-56). Returns the file names."""
    os.makedirs(directory, exist_ok=True)
    names = []
    for m in build():
        name = m.proc.pid + '.bpmn'
        with open(os.path.join(directory, name), 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(m.xml())
        names.append(name)
    return names


def p_breach_incident():
    p = Model('sangam-breach-incident', 'Personal data breach', '0.1.0',
              'Breach handling: initial notification to the supervisory authority and notice to each affected '
              'data subject without delay (parallel), detailed report by the pack deadline (IN: DPDP r.7, 72 '
              'hours) with warning and overdue timers. The linked incident report runs in '
              'sangam-incident-report.')
    p.start('start', 'Breach registered')
    p.pgw('fork')
    p.user('u_facts', 'Confirm facts for the authority notification', seat('DPO'), 'compliance/breach-facts')
    p.step('s_authority', 'Notify supervisory authority', 'breach.notifyAuthority')
    p.user('u_notice', 'Approve notice to affected data subjects', seat('DPO'), 'compliance/breach-subject-notice')
    p.step('s_subjects', 'Notify affected data subjects', 'breach.notifySubjects')
    p.pgw('join')
    p.user('u_report', 'Prepare detailed report', seat('DPO'), 'compliance/breach-report')
    p.timer('t_warn', 'Report deadline approaching', 'u_report', '${reportWarnAt}')
    p.user('u_warn', 'Escalation: detailed report deadline approaching', seat('CISO'), 'compliance/breach-escalation')
    p.end('end_warn', 'Escalated')
    p.timer('t_due', 'Report deadline', 'u_report', '${reportDueAt}')
    p.step('s_overdue', 'Record overdue report', 'breach.overdue')
    p.end('end_due', 'Overdue recorded')
    p.step('s_report', 'Submit detailed report', 'breach.submitReport')
    p.user('u_close', 'Close breach after remediation', seat('CISO'), 'compliance/breach-close')
    p.step('s_close', 'Close breach and release evidence holds', 'breach.close')
    p.end('end', 'Closed')
    p.flow('f1', 'start', 'fork')
    p.flow('f2', 'fork', 'u_facts')
    p.flow('f3', 'u_facts', 's_authority')
    p.flow('f4', 's_authority', 'join')
    p.flow('f5', 'fork', 'u_notice')
    p.flow('f6', 'u_notice', 's_subjects')
    p.flow('f7', 's_subjects', 'join')
    p.flow('f8', 'join', 'u_report')
    p.flow('f9', 'u_report', 's_report')
    p.flow('f10', 's_report', 'u_close')
    p.flow('f11', 'u_close', 's_close')
    p.flow('f12', 's_close', 'end')
    p.flow('f_w1', 't_warn', 'u_warn')
    p.flow('f_w2', 'u_warn', 'end_warn')
    p.flow('f_d1', 't_due', 's_overdue')
    p.flow('f_d2', 's_overdue', 'end_due')
    return p


def p_breach_notification_plan():
    p = Model('sangam-breach-notification-plan', 'Breach risk assessment and notification plan', '0.2.0',
              'Threshold-driven notifications (US-HIPAA 164.402-414: four-factor assessment, individuals 60 '
              'days, media more than 500 in a State, HHS contemporaneous or annual; EU-GDPR Arts 33-34: '
              'authority 72 hours, subjects if high risk). Start with breachId after '
              'BreachIncidentService.register.')
    p.start('start', 'Breach registered')
    p.user('u_assess', 'Document the risk assessment', seat('PRIVACY-OFFICER'), 'compliance/breach-risk-assessment')
    p.step('s_assess', 'Record risk assessment', 'breach.assessRisk')
    p.xgw('g_n', 'Notifiable?', default='f_yes')
    p.end('end_no', 'Not notifiable (documented)')
    p.step('s_plan', 'Build notification plan', 'breach.plan')
    p.user('u_send', 'Send planned notifications and record each', seat('PRIVACY-OFFICER'),
           'compliance/breach-plan-send')
    p.timer('t_first', 'First notification due', 'u_send', '${firstNotificationDueAt}')
    p.step('s_overdue', 'Record overdue notification', 'breach.planOverdue')
    p.end('end_due', 'Overdue recorded')
    p.end('end', 'Notifications sent')
    p.flow('f1', 'start', 'u_assess')
    p.flow('f2', 'u_assess', 's_assess')
    p.flow('f3', 's_assess', 'g_n')
    p.flow('f_no', 'g_n', 'end_no', "${riskOutcome == 'NOT_NOTIFIABLE'}")
    p.flow('f_yes', 'g_n', 's_plan')
    p.flow('f4', 's_plan', 'u_send')
    p.flow('f5', 'u_send', 'end')
    p.flow('f_d1', 't_first', 's_overdue')
    p.flow('f_d2', 's_overdue', 'end_due')
    return p


def p_dsr_access():
    p = Model('sangam-dsr-access', 'Data-subject access request', '0.1.0',
              'Access right (IN: DPDP s.11). Due date from the pack SLA; non-interrupting escalation at the due date.')
    p.start('start', 'Request received')
    p.step('s_start', 'Mark in progress', 'rights.start')
    p.user('u_compile', 'Verify identity and compile data summary', seat('DPO'), 'compliance/access-compile')
    p.step('s_summary', 'Build access summary', 'rights.accessSummary')
    p.user('u_release', 'Review and release summary', seat('DPO'), 'compliance/access-release')
    p.timer('t_due', 'Due date', 'u_release', '${dueAt}')
    p.step('s_close', 'Close request', 'rights.closeAccess')
    p.end('end', 'Answered')
    p.flow('f1', 'start', 's_start')
    p.flow('f2', 's_start', 'u_compile')
    p.flow('f3', 'u_compile', 's_summary')
    p.flow('f4', 's_summary', 'u_release')
    p.flow('f5', 'u_release', 's_close')
    p.flow('f6', 's_close', 'end')
    p.step('esc', 'Escalate overdue request', 'rights.escalate')
    p.end('end_esc', 'Escalated')
    p.flow('f_esc1', 't_due', 'esc')
    p.flow('f_esc2', 'esc', 'end_esc')
    return p


def p_dsr_accounting():
    p = Model('sangam-dsr-accounting', 'Accounting of disclosures', '0.2.0',
              'Accounting right (US-HIPAA 45 CFR 164.528: 60 days, one 30-day extension, six-year look-back, TPO '
              'excluded). Optional extension before the due date.')
    p.start('start', 'Request received')
    p.step('s_start', 'Mark in progress', 'rights.start')
    p.user('u_review', 'Review accounting (extend if needed)', seat('PRIVACY-OFFICER'), 'compliance/accounting-review')
    p.timer('t_due', 'Due date', 'u_review', '${dueAt}')
    p.xgw('g_ext', 'Extension?', default='f_go')
    p.step('s_extend', 'Extend once with written notice', 'rights.extend')
    p.user('u_review2', 'Complete accounting', seat('PRIVACY-OFFICER'), 'compliance/accounting-review')
    p.step('s_acc', 'Build and send accounting', 'rights.accounting')
    p.end('end', 'Provided')
    p.flow('f1', 'start', 's_start')
    p.flow('f2', 's_start', 'u_review')
    p.flow('f3', 'u_review', 'g_ext')
    p.flow('f_x', 'g_ext', 's_extend', '${extensionNeeded == true}')
    p.flow('f_x2', 's_extend', 'u_review2')
    p.flow('f_x3', 'u_review2', 's_acc')
    p.flow('f_go', 'g_ext', 's_acc')
    p.flow('f4', 's_acc', 'end')
    p.step('esc', 'Escalate overdue request', 'rights.escalate')
    p.end('end_esc', 'Escalated')
    p.flow('f_esc1', 't_due', 'esc')
    p.flow('f_esc2', 'esc', 'end_esc')
    return p


def p_dsr_automated_decision():
    p = Model('sangam-dsr-automated-decision', 'Human review of an automated decision', '0.2.0',
              'Right not to be subject to a solely automated decision (EU-GDPR Art. 22(3)): human intervention '
              'and contest.')
    p.start('start', 'Request received')
    p.step('s_start', 'Mark in progress', 'rights.start')
    p.user('u_review', 'Human review of the decision', seat('DPO'), 'compliance/automated-decision-review')
    p.timer('t_due', 'Due date', 'u_review', '${dueAt}')
    p.step('s_record', 'Record review outcome', 'rights.humanReview')
    p.end('end', 'Reviewed')
    p.flow('f1', 'start', 's_start')
    p.flow('f2', 's_start', 'u_review')
    p.flow('f3', 'u_review', 's_record')
    p.flow('f4', 's_record', 'end')
    p.step('esc', 'Escalate overdue request', 'rights.escalate')
    p.end('end_esc', 'Escalated')
    p.flow('f_esc1', 't_due', 'esc')
    p.flow('f_esc2', 'esc', 'end_esc')
    return p


def p_dsr_erasure():
    p = Model('sangam-dsr-erasure', 'Data-subject erasure request', '0.1.0',
              'Erasure right (IN: DPDP s.12(3)). Erasure runs through sangam-integration ErasureService; legal '
              'holds and retention required by law are respected; the records officer approves.')
    p.start('start', 'Request received')
    p.step('s_start', 'Mark in progress', 'rights.start')
    p.user('u_approve', 'Check retention obligations and approve erasure', seat('RECORDS-OFFICER'),
           'compliance/erasure-approve')
    p.timer('t_due', 'Due date', 'u_approve', '${dueAt}')
    p.xgw('g_decision', 'Approved?', default='f_reject')
    p.step('s_erase', 'Erase (holds and legal retention respected)', 'rights.erase')
    p.step('s_reject', 'Reject with reason', 'rights.reject')
    p.end('end', 'Erased')
    p.end('end_rej', 'Rejected')
    p.flow('f1', 'start', 's_start')
    p.flow('f2', 's_start', 'u_approve')
    p.flow('f3', 'u_approve', 'g_decision')
    p.flow('f_ok', 'g_decision', 's_erase', "${decision == 'APPROVE'}")
    p.flow('f_reject', 'g_decision', 's_reject')
    p.flow('f4', 's_erase', 'end')
    p.flow('f5', 's_reject', 'end_rej')
    p.step('esc', 'Escalate overdue request', 'rights.escalate')
    p.end('end_esc', 'Escalated')
    p.flow('f_esc1', 't_due', 'esc')
    p.flow('f_esc2', 'esc', 'end_esc')
    return p


def p_dsr_grievance():
    p = Model('sangam-dsr-grievance', 'Data-subject grievance redressal', '0.1.0',
              'Grievance (IN: DPDP s.13, r.14(3) at most 90 days; SPDI r.5(9) one month for body corporates). '
              'Warning at three quarters of the period, escalation at the due date; the response names the '
              'authority complaint route.')
    p.start('start', 'Grievance lodged')
    p.step('s_start', 'Mark in progress', 'rights.start')
    p.user('u_examine', 'Examine and resolve grievance', seat('GRIEVANCE-OFFICER'), 'compliance/grievance-examine')
    p.timer('t_warn', 'Three quarters of the period', 'u_examine', '${warnAt}')
    p.user('u_warn', 'Senior review of a delayed grievance', seat('DPO'), 'compliance/grievance-delayed')
    p.end('end_warn', 'Reviewed')
    p.timer('t_due', 'Due date', 'u_examine', '${dueAt}')
    p.step('s_resolve', 'Send resolution', 'rights.resolveGrievance')
    p.end('end', 'Resolved')
    p.flow('f1', 'start', 's_start')
    p.flow('f2', 's_start', 'u_examine')
    p.flow('f3', 'u_examine', 's_resolve')
    p.flow('f4', 's_resolve', 'end')
    p.flow('f_w1', 't_warn', 'u_warn')
    p.flow('f_w2', 'u_warn', 'end_warn')
    p.step('esc', 'Escalate overdue request', 'rights.escalate')
    p.end('end_esc', 'Escalated')
    p.flow('f_esc1', 't_due', 'esc')
    p.flow('f_esc2', 'esc', 'end_esc')
    return p


def p_dsr_nomination():
    p = Model('sangam-dsr-nomination', 'Nomination by a data subject', '0.1.0',
              'Nomination (IN: DPDP s.14, r.14(4)). Invocation on death or incapacity is a separate officer '
              'action (NominationService.invoke).')
    p.start('start', 'Nomination requested')
    p.step('s_start', 'Mark in progress', 'rights.start')
    p.user('u_verify', 'Verify nominee particulars', seat('DPO'), 'compliance/nomination-verify')
    p.timer('t_due', 'Due date', 'u_verify', '${dueAt}')
    p.step('s_register', 'Register nomination', 'nomination.register')
    p.end('end', 'Registered')
    p.flow('f1', 'start', 's_start')
    p.flow('f2', 's_start', 'u_verify')
    p.flow('f3', 'u_verify', 's_register')
    p.flow('f4', 's_register', 'end')
    p.step('esc', 'Escalate overdue request', 'rights.escalate')
    p.end('end_esc', 'Escalated')
    p.flow('f_esc1', 't_due', 'esc')
    p.flow('f_esc2', 'esc', 'end_esc')
    return p


def p_dsr_objection():
    p = Model('sangam-dsr-objection', 'Objection to processing', '0.2.0',
              'Objection right (EU-GDPR Art. 21): stop unless compelling legitimate grounds are recorded; '
              'consent purposes are withdrawn.')
    p.start('start', 'Objection received')
    p.step('s_start', 'Mark in progress', 'rights.start')
    p.user('u_decide', 'Assess objection', seat('DPO'), 'compliance/objection-decide')
    p.timer('t_due', 'Due date', 'u_decide', '${dueAt}')
    p.step('s_apply', 'Apply objection', 'rights.applyObjection')
    p.end('end', 'Decided')
    p.flow('f1', 'start', 's_start')
    p.flow('f2', 's_start', 'u_decide')
    p.flow('f3', 'u_decide', 's_apply')
    p.flow('f4', 's_apply', 'end')
    p.step('esc', 'Escalate overdue request', 'rights.escalate')
    p.end('end_esc', 'Escalated')
    p.flow('f_esc1', 't_due', 'esc')
    p.flow('f_esc2', 'esc', 'end_esc')
    return p


def p_dsr_rectification():
    p = Model('sangam-dsr-rectification', 'Data-subject correction, completion or updating', '0.1.0',
              'Rectification rights (IN: DPDP s.12(2)). The officer sets correctionAccepted, corrections and '
              'decisionReason.')
    p.start('start', 'Request received')
    p.step('s_start', 'Mark in progress', 'rights.start')
    p.user('u_examine', 'Examine correction and evidence', seat('DPO'), 'compliance/rectification-examine')
    p.timer('t_due', 'Due date', 'u_examine', '${dueAt}')
    p.step('s_apply', 'Apply or decline correction', 'rights.applyCorrection')
    p.end('end', 'Decided')
    p.flow('f1', 'start', 's_start')
    p.flow('f2', 's_start', 'u_examine')
    p.flow('f3', 'u_examine', 's_apply')
    p.flow('f4', 's_apply', 'end')
    p.step('esc', 'Escalate overdue request', 'rights.escalate')
    p.end('end_esc', 'Escalated')
    p.flow('f_esc1', 't_due', 'esc')
    p.flow('f_esc2', 'esc', 'end_esc')
    return p


def p_dsr_restriction():
    p = Model('sangam-dsr-restriction', 'Restriction of processing or disclosure', '0.2.0',
              'Restriction right (US-HIPAA 164.522(a)(1)(vi) mandatory restriction to a health plan when paid in '
              'full; EU-GDPR Art. 18).')
    p.start('start', 'Request received')
    p.step('s_start', 'Mark in progress', 'rights.start')
    p.user('u_decide', 'Decide restriction', seat('PRIVACY-OFFICER'), 'compliance/restriction-decide')
    p.timer('t_due', 'Due date', 'u_decide', '${dueAt}')
    p.step('s_apply', 'Record restriction', 'rights.applyRestriction')
    p.end('end', 'Decided')
    p.flow('f1', 'start', 's_start')
    p.flow('f2', 's_start', 'u_decide')
    p.flow('f3', 'u_decide', 's_apply')
    p.flow('f4', 's_apply', 'end')
    p.step('esc', 'Escalate overdue request', 'rights.escalate')
    p.end('end_esc', 'Escalated')
    p.flow('f_esc1', 't_due', 'esc')
    p.flow('f_esc2', 'esc', 'end_esc')
    return p


def p_incident_report():
    p = Model('sangam-incident-report', 'Cyber incident report to an incident authority', '0.1.0',
              'Incident reporting with the authority deadline from the pack (IN: CERT-In 6 hours from noticing, '
              'Annexure I types). Unknown details are reported as such and followed up.')
    p.start('start', 'Incident registered')
    p.user('u_prepare', 'Prepare and approve incident report', seat('CISO'), 'compliance/incident-prepare')
    p.timer('t_warn', 'Deadline approaching', 'u_prepare', '${incidentWarnAt}')
    p.user('u_warn', 'Escalation: incident report deadline approaching', seat('CISO-HEAD'),
           'compliance/incident-escalation')
    p.end('end_warn', 'Escalated')
    p.timer('t_due', 'Deadline', 'u_prepare', '${incidentDueAt}')
    p.step('s_overdue', 'Record overdue report', 'incident.overdue')
    p.end('end_due', 'Overdue recorded')
    p.step('s_submit', 'Submit to the authority and secondary recipients', 'incident.submit')
    p.user('u_follow', 'Follow up with remaining details or close', seat('CISO'), 'compliance/incident-follow-up',
           complete_guard=INCIDENT_CLOSURE_GUARD)
    p.xgw('g_follow', 'Follow-up needed?', default='f_close')
    p.step('s_follow', 'Send follow-up', 'incident.followUp')
    p.step('s_close', 'Close incident', 'incident.close')
    p.end('end', 'Closed')
    p.flow('f1', 'start', 'u_prepare')
    p.flow('f2', 'u_prepare', 's_submit')
    p.flow('f3', 's_submit', 'u_follow')
    p.flow('f4', 'u_follow', 'g_follow')
    p.flow('f_more', 'g_follow', 's_follow', '${followUpNeeded == true}')
    p.flow('f5', 's_follow', 'u_follow')
    p.flow('f_close', 'g_follow', 's_close')
    p.flow('f6', 's_close', 'end')
    p.flow('f_w1', 't_warn', 'u_warn')
    p.flow('f_w2', 'u_warn', 'end_warn')
    p.flow('f_d1', 't_due', 's_overdue')
    p.flow('f_d2', 's_overdue', 'end_due')
    return p


MODELS = (p_breach_incident, p_breach_notification_plan, p_dsr_access, p_dsr_accounting, p_dsr_automated_decision,
          p_dsr_erasure, p_dsr_grievance, p_dsr_nomination, p_dsr_objection, p_dsr_rectification, p_dsr_restriction,
          p_incident_report)
