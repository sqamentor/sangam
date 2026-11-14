// SPDX-License-Identifier: Apache-2.0
/**
 * Screens for sangam-ai, sangam-channels, sangam-modeller and sangam-ops.
 * Every path below is a contract operation of sangam-rest/api/sangam-api.yaml (checked 2026-11-12 against the yaml
 * path list, W1-J follow-up of M-110); request fields follow the operation's request schema or, for a Map-backed body,
 * the keys the server reads (M-051). The server authorises every call (X.4.7).
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ApiError, http, request } from '../api/client';
import { mergeById, openEventStream } from '../api/sse';
import { useAction, useAsync, useInterval } from '../lib/hooks';
import { BpmnDiagram } from '../components/BpmnDiagram';
import { fmtNumber } from '../i18n';
import { Badge, DataTable, Dialog, Empty, JsonView, Notice, ProblemAlert, ReasonDialog, Section, TextArea, TextField } from '../components/ui';
import { ModulePage, c, cell, f, type Row, type ScreenDef } from './engine';

// ------------------------------------------------------------------ AI

const runs: ScreenDef = {
  id: 'runs', title: 'mod.ai.runs', description: 'mod.ai.runsDesc', list: '/ai/agent-runs',
  filters: [f.text('agentKey'), f.text('status'), f.text('businessKey')],
  columns: [c('runId'), c('agentKey'), c('status'), c('businessKey'), c('startedAt')], rowKey: ['runId', 'id'],
  detail: '/ai/agent-runs/{runId}',
  sections: [{ id: 'steps', label: 'mod.ai.steps', path: '/ai/agent-runs/{runId}/steps', columns: [c('seq'), c('kind'), c('tool'), c('status'), c('tokens'), c('at')] }],
};
const approvals: ScreenDef = {
  id: 'approvals', title: 'mod.ai.approvals', description: 'mod.ai.approvalsDesc', list: '/ai/agent-approvals',
  filters: [f.text('status')],
  columns: [c('approvalId'), c('runId'), c('tool'), c('summary'), c('status'), c('requestedAt')], rowKey: ['approvalId', 'id'],
  actions: [
    { id: 'approve', label: 'act.approve', path: '/ai/agent-approvals/{approvalId}/approve', reason: 'body', noJson: true },
    { id: 'reject', label: 'act.reject', path: '/ai/agent-approvals/{approvalId}/reject', reason: 'body', danger: true, noJson: true },
  ],
};
const kb: ScreenDef = {
  id: 'kb', title: 'mod.ai.kb', list: '/ai/knowledge-bases', unpaged: true,
  columns: [c('key'), c('version'), c('status'), c('chunks'), c('updatedAt')], rowKey: ['key', 'id'],
  create: { id: 'publish', label: 'act.publish', path: '/ai/knowledge-bases', fields: [f.text('key', undefined, true), f.area('text', 'fld.body', true), f.json('metadata')], hint: 'mod.ai.kbHint' },
  actions: [{ id: 'retire', label: 'act.retire', path: '/ai/knowledge-bases/{key}/retire', reason: 'body', danger: true, noJson: true }],
};
const drafts: ScreenDef = {
  id: 'drafts', title: 'mod.ai.drafts', description: 'mod.ai.draftsDesc', list: '/ai/drafts',
  filters: [f.text('packageId'), f.text('status')],
  columns: [c('draftId'), c('packageId'), c('kind'), c('status'), c('createdAt')], rowKey: ['draftId', 'id'],
  detail: '/ai/drafts/{draftId}',
  toolbar: [{ id: 'generate', label: 'mod.ai.generate', path: '/ai/drafts', fields: [f.text('packageId', undefined, true), f.select('kind', ['BPMN', 'DMN', 'FORM'], undefined, true), f.area('prompt', 'fld.prompt', true)], showResult: true, hint: 'mod.ai.generateHint' }],
  actions: [
    { id: 'approve', label: 'act.approve', path: '/ai/drafts/{draftId}/approve', reason: 'body', noJson: true },
    { id: 'reject', label: 'act.reject', path: '/ai/drafts/{draftId}/reject', reason: 'body', danger: true, noJson: true },
  ],
};
const classifications: ScreenDef = {
  id: 'class', title: 'mod.ai.classifications', list: '/ai/classifications',
  filters: [f.text('status')],
  columns: [c('id'), c('subjectRef'), c('label'), c('confidence'), c('status')], rowKey: ['id'],
  actions: [{ id: 'review', label: 'act.review', path: '/ai/classifications/{id}/review', fields: [f.select('decision', ['ACCEPT', 'RETURN'], undefined, true), f.text('label')] }],
};

export function FeelPlayground() {
  const { t } = useTranslation();
  const [expr, setExpr] = useState('');
  const [vars, setVars] = useState('{}');
  const [goal, setGoal] = useState('');
  const [out, setOut] = useState<unknown>();
  const [err, setErr] = useState<string>();
  const act = useAction();
  const parsed = () => {
    try {
      setErr(undefined);
      return JSON.parse(vars) as Row;
    } catch (e) {
      setErr((e as Error).message);
      return null;
    }
  };
  return (
    <Section title={t('mod.ai.feel')}>
      <TextArea label={t('mod.ai.expression')} className="code" value={expr} onChange={setExpr} />
      <TextArea label={t('ops.variablesJson')} className="code" value={vars} onChange={setVars} />
      <button type="button" disabled={!expr} onClick={() => { const v = parsed(); if (v) void act.run(() => http.post<unknown>('/ai/expressions/evaluate', { expression: expr, variables: v })).then(setOut); }}>{t('ops.evaluate')}</button>
      <TextField label={t('mod.ai.goal')} hint={t('mod.ai.goalHint')} value={goal} onChange={setGoal} />
      <button type="button" disabled={!goal} onClick={() => void act.run(() => http.post<unknown>('/ai/expressions/propose', { goal })).then(setOut)}>{t('mod.ai.propose')}</button>
      {err && <Notice tone="bad">{err}</Notice>}
      <ProblemAlert error={act.error} />
      {out !== undefined && <JsonView value={out} />}
    </Section>
  );
}

export function AiPage() {
  return <ModulePage title="mod.ai.title" description="mod.ai.desc" screens={[runs, approvals, drafts, kb, classifications]} extra={[{ id: 'feel', label: 'mod.ai.feel', node: <FeelPlayground /> }]} />;
}

// ------------------------------------------------------------------ channels

/**
 * M-040: the live stream of one conversation (GET /channels/conversations/{id}/stream, event 'message', event id =
 * message id; ChannelsController.java:109-114, ChannelsLiveHub.java:52). History is re-read after every (re)connect,
 * because the server streams only what happens while the stream is open. Returns whether the stream is live.
 */
export function useConversationStream(conversationId: string | undefined, onOpen: () => void, onMessage: (m: Row) => void): boolean {
  const [live, setLive] = useState(false);
  const open = useRef(onOpen);
  const msg = useRef(onMessage);
  open.current = onOpen;
  msg.current = onMessage;
  useEffect(() => {
    setLive(false);
    if (!conversationId) return;
    return openEventStream(`/channels/conversations/${encodeURIComponent(conversationId)}/stream`, {
      onOpen: () => open.current(),
      onLive: setLive,
      onEvent: (e) => {
        if (e.event !== 'message') return;
        try {
          const m = JSON.parse(e.data) as Row;
          if (m && typeof m === 'object' && m.id !== undefined) msg.current(m);
        } catch {
          open.current(); // not JSON: read the history instead of guessing
        }
      },
    });
  }, [conversationId]);
  return live;
}

/** Conversation against the live /channels routes. The server has no list route, so a conversation is reached by message search, by id, or by creating it. */
export function ConversationPanel({ caseId }: { caseId?: string }) {
  const { t } = useTranslation();
  const [q, setQ] = useState('');
  const [term, setTerm] = useState('');
  const [idText, setIdText] = useState('');
  const [title, setTitle] = useState('');
  const [sel, setSel] = useState<string>();
  const [text, setText] = useState('');
  const base = '/channels/conversations';
  const hits = useAsync(() => http.get<Row[]>(`${base}/search`, { text: term, limit: 50 }), [term], !!term);
  const msgs = useAsync(() => http.get<Row[]>(`${base}/${encodeURIComponent(sel!)}/messages`, { limit: 200 }), [sel], !!sel);
  const live = useConversationStream(sel, msgs.reload, (m) => msgs.setData((prev) => mergeById(prev, m)));
  // M-040: the 10 s poll stays as the fallback while the stream is not live.
  useInterval(() => { if (sel) msgs.reload(); }, sel && !live ? 10000 : null);
  const act = useAction();
  const path = () => `${base}/${encodeURIComponent(sel!)}`;
  return (
    <Section title={t('mod.channels.conversations')}>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setTerm(q.trim()); }}>
        <TextField label={t('common.search')} value={q} onChange={setQ} />
        <button type="submit">{t('common.search')}</button>
      </form>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setSel(idText.trim() || undefined); }}>
        <TextField label={t('fld.conversationId')} value={idText} onChange={setIdText} />
        <button type="submit">{t('common.open')}</button>
      </form>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); void act.run((k) => http.post<Row>(base, { title, caseRef: caseId, participants: [] }, k)).then((x) => { if (x) { setSel(String(x.id)); setTitle(''); } }); }}>
        <TextField label={t('fld.title')} value={title} onChange={setTitle} required />
        <button type="submit" disabled={!title.trim() || act.busy}>{t('act.create')}</button>
      </form>
      {caseId && <p className="hint">{t('mod.channels.caseHint')}</p>}
      <ProblemAlert error={hits.error ?? msgs.error ?? act.error} />
      {term && hits.data?.length === 0 && <Empty message={t('common.emptyNone')} />}
      <ul>{hits.data?.map((m) => <li key={String(m.id)}><button type="button" className="link" onClick={() => setSel(String(m.conversationId))}>{String(m.body ?? m.id)}</button></li>)}</ul>
      {sel && (
        <div>
          <p className="small muted" role="status">{live ? t('mod.channels.live') : t('mod.channels.polling')}</p>
          <ol className="comments" aria-live="polite">
            {msgs.data?.map((m) => (
              <li key={String(m.id)}>
                <div className="small muted">{String(m.sender ?? '')} · {cell(m.at)}</div>
                <p>{String(m.body ?? '')}</p>
                {((m.attachments ?? []) as Row[]).map((a) => <div key={String(a.key)} className="small">{String(a.name)}</div>)}
              </li>
            ))}
          </ol>
          <TextArea label={t('task.addComment')} value={text} onChange={setText} />
          <div className="toolbar">
            <button type="button" disabled={!text.trim() || act.busy} onClick={() => void act.run((k) => http.post(`${path()}/messages`, { body: text, attachments: [], clientKey: k }, k).then(() => true)).then((ok) => { if (ok) { setText(''); msgs.reload(); } })}>{t('common.send')}</button>
            <label className="button">{t('task.upload')}<input type="file" className="sr-only" onChange={(e) => { const file = e.target.files?.[0]; if (file) void act.run((k) => request(`POST`, `${path()}/attachments`, { form: file, query: { name: file.name }, idempotencyKey: k, headers: { 'Content-Type': 'application/octet-stream', 'X-Content-Type': file.type || 'application/octet-stream' } })); }} /></label>
          </div>
          <p className="hint">{t('mod.channels.attachHint')}</p>
        </div>
      )}
    </Section>
  );
}

function OptIns() {
  const { t } = useTranslation();
  const [v, setV] = useState({ channel: '', externalId: '', principalId: '', evidence: '' });
  const [done, setDone] = useState(false);
  const act = useAction();
  const fld = (k: keyof typeof v) => <TextField label={t(`fld.${k}`)} value={v[k]} required onChange={(x) => { setDone(false); setV({ ...v, [k]: x }); }} />;
  const send = (p: string) => void act.run((key) => http.post(p, v, key).then(() => true)).then((ok) => setDone(!!ok));
  return (
    <Section title={t('mod.channels.optIns')}>
      <p className="hint">{t('mod.channels.optInHint')}</p>
      {fld('channel')}{fld('externalId')}{fld('principalId')}{fld('evidence')}
      <div className="toolbar">
        <button type="button" disabled={act.busy || !v.channel || !v.externalId || !v.principalId} onClick={() => send('/channels/opt-ins')}>{t('act.register')}</button>
        <button type="button" className="danger" disabled={act.busy || !v.channel || !v.externalId} onClick={() => send('/channels/opt-ins/withdraw')}>{t('act.revoke')}</button>
      </div>
      <ProblemAlert error={act.error} />
      {done && <Notice tone="ok">{t('common.saved')}</Notice>}
    </Section>
  );
}
const inbound: ScreenDef = {
  id: 'inbound', title: 'mod.channels.inbound', description: 'mod.channels.inboundDesc', list: '/channels/status', unpaged: true,
  columns: [c('key'), c('channel'), c('state'), c('detail')], rowKey: ['key'],
};
export function ChannelsPage() {
  return <ModulePage title="mod.channels.title" description="mod.channels.desc" screens={[inbound]} extra={[{ id: 'conv', label: 'mod.channels.conversations', node: <ConversationPanel /> }, { id: 'opt', label: 'mod.channels.optIns', node: <OptIns /> }]} />;
}

// ------------------------------------------------------------------ modeller

type Edge = { from: string; to: string; kind?: string };

export function Landscape() {
  const { t } = useTranslation();
  const d = useAsync(() => http.get<{ nodes?: { id: string; name?: string; kind?: string }[]; edges?: Edge[] }>('/landscape'), []);
  const nodes = d.data?.nodes ?? [];
  const edges = d.data?.edges ?? [];
  const pos = new Map(nodes.map((n, i) => [n.id, { x: 20 + (i % 5) * 190, y: 20 + Math.floor(i / 5) * 90 }]));
  return (
    <Section title={t('mod.modeller2.landscape')}>
      <ProblemAlert error={d.error} onRetry={d.reload} />
      {nodes.length > 0 && (
        <figure className="erd">
          <svg viewBox={`0 0 980 ${40 + Math.ceil(nodes.length / 5) * 90}`} role="img" aria-label={t('mod.modeller2.landscape')}>
            {edges.map((e, i) => { const a = pos.get(e.from); const b = pos.get(e.to); return a && b ? <line key={i} x1={a.x + 160} y1={a.y + 25} x2={b.x} y2={b.y + 25} className="erd-link" /> : null; })}
            {nodes.map((n) => { const p = pos.get(n.id)!; return <g key={n.id} transform={`translate(${p.x},${p.y})`}><rect width={160} height={50} rx={4} className="erd-box" /><text x={8} y={22} className="erd-title">{(n.name ?? n.id).slice(0, 22)}</text><text x={8} y={40} className="erd-field">{n.kind}</text></g>; })}
          </svg>
          <details><summary>{t('diagram.tableAlternative')}</summary><ul>{edges.map((e, i) => <li key={i}>{e.from} → {e.to} {e.kind}</li>)}</ul></details>
        </figure>
      )}
    </Section>
  );
}

/** Token walk: the server computes each step; the user picks the branch at a gateway. */
export function TokenWalk({ bpmnXml, file }: { bpmnXml: string; file: string }) {
  const { t } = useTranslation();
  const [walk, setWalk] = useState<{ current?: string[]; choices?: { flowId: string; name?: string }[]; done?: boolean; state?: unknown }>();
  const [playing, setPlaying] = useState(false);
  const act = useAction();
  const step = (choice?: string) => void act.run(() => http.post<typeof walk>('/models/token-walk', { file, xml: bpmnXml, state: walk?.state, choice })).then((r) => r && setWalk(r));
  const needChoice = (walk?.choices?.length ?? 0) > 0;
  useInterval(() => { if (playing && !needChoice && !walk?.done && !act.busy) step(); }, playing ? 1000 : null);
  return (
    <Section title={t('mod.modeller2.tokenWalk')}>
      <div className="toolbar">
        <button type="button" disabled={act.busy || walk?.done || needChoice} onClick={() => step()}>{t('act.step')}</button>
        <button type="button" aria-pressed={playing} onClick={() => setPlaying(!playing)}>{playing ? t('act.suspend') : t('act.start')}</button>
        <button type="button" onClick={() => { setWalk(undefined); setPlaying(false); }}>{t('common.clear')}</button>
      </div>
      <ProblemAlert error={act.error} />
      {needChoice && <div className="toolbar" role="group" aria-label={t('mod.modeller2.gateway')}><span>{t('mod.modeller2.gateway')}</span>{walk!.choices!.map((x) => <button key={x.flowId} type="button" onClick={() => step(x.flowId)}>{x.name ?? x.flowId}</button>)}</div>}
      <p aria-live="polite" className="small">{(walk?.current ?? []).join(', ')}</p>
      <BpmnDiagram title={file} xml={bpmnXml} selected={walk?.current ?? []} compact />
    </Section>
  );
}

/**
 * The console's view of an edit lease (ModellerController.LeaseView): the lease row plus whether the caller holds it
 * (mine) and may edit (editable). A free file has no holder and no fence.
 */
export type LeaseView = { fileId?: string; holder?: string; fence?: number; expiresAt?: string; requestedBy?: string; mine: boolean; editable: boolean };
/** What the workspace needs from the lease: may this user save, and with which fence (X-Sangam-Edit-Fence). */
export type LeaseState = { mine: boolean; fence?: number };

/** The lease path of a package. The lease, the save fence (ModellerWiring.fenced, PUT /packages/{id}) and presence all key a model file by its package id. */
export function leasePath(packageId: string): string {
  return `/models/${encodeURIComponent(packageId)}/lease`;
}

/**
 * Edit lease for one package (M-101 and the R18 save fence): acquires on open, renews every 30 s with the fence,
 * releases with the fence on close, and offers "ask for the lease" and a takeover with a reason (audited
 * MODEL_LEASE_TAKEOVER; the server requires the reason). A renewal refused as EDIT_LEASE_LOST re-reads the lease, so the
 * banner shows the new holder and the workspace stops saving with the old fence.
 */
export function LeaseBanner({ packageId, onState }: { packageId: string; onState?: (s: LeaseState) => void }) {
  const { t } = useTranslation();
  const [lease, setLease] = useState<LeaseView>();
  const [taking, setTaking] = useState(false);
  const [asked, setAsked] = useState(false);
  const path = leasePath(packageId);
  const held = useRef<number | undefined>(undefined);
  const stateRef = useRef(onState);
  stateRef.current = onState;
  const act = useAction();
  const apply = (r: LeaseView) => {
    setLease(r);
    held.current = r.mine && typeof r.fence === 'number' ? r.fence : undefined;
    stateRef.current?.({ mine: r.mine, fence: held.current });
  };
  const acquire = () => act.run(() => http.post<LeaseView>(`${path}/acquire`, {})).then((r) => { if (r) apply(r); });
  useEffect(() => {
    void acquire();
    return () => {
      const fence = held.current;
      held.current = undefined;
      if (fence !== undefined) void http.post(`${path}/release`, { fence }).catch(() => undefined);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path]);
  useInterval(() => {
    const fence = held.current;
    if (fence === undefined) return;
    // the contract answers 204; ModellerController answers the LeaseView: keep the lease as it is on an empty answer
    void http.post<LeaseView | undefined>(`${path}/heartbeat`, { fence }).then((r) => { if (r) apply(r); }, () => {
      held.current = undefined;
      stateRef.current?.({ mine: false });
      void acquire();
    });
  }, 30000);
  return (
    <>
      {/* a reader without package.author (403) just stays read-only; the dialog shows its own errors */}
      {!taking && !(act.error instanceof ApiError && act.error.status === 403) && <ProblemAlert error={act.error} />}
      {lease?.mine && lease.requestedBy && <Notice>{t('mod.modeller2.leaseRequested', { who: lease.requestedBy })}</Notice>}
      {lease && !lease.mine && lease.holder && (
        <Notice tone="warn">
          {t('mod.modeller2.leased', { who: lease.holder })}{' '}
          <button type="button" className="link" disabled={asked || act.busy} onClick={() => void act.run(() => http.post(`${path}/request-takeover`, {}).then(() => true)).then((ok) => ok && setAsked(true))}>
            {asked ? t('mod.modeller2.leaseAsked') : t('mod.modeller2.leaseAsk')}
          </button>{' '}
          <button type="button" className="link" onClick={() => setTaking(true)}>{t('mod.modeller2.takeover')}</button>
        </Notice>
      )}
      <ReasonDialog
        open={taking}
        title={t('mod.modeller2.takeover')}
        busy={act.busy}
        error={act.error}
        danger
        onClose={() => setTaking(false)}
        onConfirm={(reason) =>
          void act.run(() => http.post(`${path}/takeover`, { reason }).then(() => true)).then((ok) => {
            if (!ok) return;
            setTaking(false);
            void acquire(); // the takeover answer is the bare lease; acquire renews it and says mine and the fence
          })
        }
      >
        <Notice tone="warn">{t('mod.modeller2.takeoverHint', { who: lease?.holder ?? '' })}</Notice>
      </ReasonDialog>
    </>
  );
}

const templates: ScreenDef = {
  id: 'templates', title: 'mod.modeller2.templates', description: 'mod.modeller2.templatesDesc', list: '/templates',
  // Kind: ELEMENT, PALETTE, FORM_COMPONENT, PROCESS, DMN (ElementTemplates.java:27); body TemplateRequest(kind, document,
  // key, name, content) (ModellerController.java:112, :261-279)
  filters: [f.select('kind', ['PROCESS', 'ELEMENT', 'PALETTE', 'FORM_COMPONENT', 'DMN'])],
  columns: [c('key'), c('name'), c('kind'), c('version'), c('appliesTo', 'tpl.appliesTo')], rowKey: ['key', 'id'], detail: '/templates/{key}',
  create: { id: 'save', label: 'mod.modeller2.saveTemplate', path: '/templates', fields: [f.text('key', undefined, true), f.text('name', undefined, true), f.select('kind', ['PROCESS', 'ELEMENT', 'PALETTE', 'FORM_COMPONENT', 'DMN'], undefined, true), f.area('content', 'fld.body', true)], hint: 'tpl.saveHint' },
  actions: [{ id: 'apply', label: 'tpl.tryApply', path: '/templates/{key}/apply', fields: [f.text('elementType', 'tpl.elementType', true), f.json('values', 'tpl.values')], noJson: true, showResult: true, hint: 'tpl.tryApplyHint' }],
};
const actionsAdmin: ScreenDef = {
  id: 'actions', title: 'mod.modeller2.actions', list: '/actions',
  filters: [f.select('scope', ['CASE', 'PROCESS', 'TASK'])],
  columns: [c('key'), c('label', 'cf.label'), c('scope'), c('scopeType'), c('permission'), c('targetType', 'actions.targetType'), c('target', 'actions.target')], rowKey: ['key', 'id'],
  // M-051: ActionModelPublish / PublishActionRequest (ModellerController.java:118-119, :310-324); required key, label,
  // scope, permission, targetType, target (sangam-api.yaml); targetType BPMN | REST | SCRIPT (ActionModels.java:27)
  create: { id: 'save', label: 'act.create', path: '/actions', noJson: true, fields: [f.text('key', undefined, true), f.text('label', 'cf.label', true), f.select('scope', ['CASE', 'PROCESS', 'TASK'], undefined, true), f.text('scopeType'), f.text('permission', undefined, true), f.select('targetType', ['BPMN', 'REST', 'SCRIPT'], 'actions.targetType', true), f.text('target', 'actions.target', true, { description: 'actions.targetHint' }), f.text('formKey', 'actions.formKey')] },
  actions: [
    // RunActionRequest.reason is not read by the server (ModellerController.java:326-334), so no reason is asked here
    { id: 'run', label: 'act.runNow', path: '/actions/{key}/run', fields: [f.text('subjectRef', undefined, true), f.json('input')], noJson: true, showResult: true },
    { id: 'delete', label: 'common.delete', method: 'DELETE', path: '/actions/{key}', reason: 'query', danger: true, noJson: true },
  ],
};
const mocks: ScreenDef = {
  id: 'mocks', title: 'mod.modeller2.mocks', description: 'mod.modeller2.mocksDesc', list: '/service-mocks',
  columns: [c('service'), c('operation'), c('enabled'), c('scope')], rowKey: ['id', 'service'],
  create: { id: 'save', label: 'act.create', path: '/service-mocks', fields: [f.text('service', undefined, true), f.text('operation'), f.json('response', 'fld.body', true), f.num('delayMs'), f.bool('enabled')] },
  toolbar: [{ id: 'global', label: 'mod.modeller2.mocksGlobal', method: 'PUT', path: '/service-mocks/global', fields: [f.bool('enabled')], noJson: true }],
  actions: [{ id: 'toggle', label: 'act.update', method: 'PATCH', path: '/service-mocks/{id}', fields: [f.bool('enabled')], noJson: true }],
};
const catalogue: ScreenDef = {
  id: 'catalogue', title: 'mod.modeller2.catalogue', list: '/catalogue',
  filters: [f.text('q', 'fld.search'), f.text('kind')],
  columns: [c('key'), c('name'), c('kind'), c('version'), c('owner')], rowKey: ['key', 'id'],
};

export function ModellerToolsPage() {
  return <ModulePage title="mod.modeller2.title" screens={[templates, actionsAdmin, mocks, catalogue]} extra={[{ id: 'land', label: 'mod.modeller2.landscape', node: <Landscape /> }]} />;
}

/** One case of a run (PackageToolsPort.TestRun) or of a stored report (BulkModelTests.CaseResult, :30). */
export type TestCaseRow = { name: string; status: string; deterministic: boolean; message?: string; millis?: number };
/** A stored report (BulkModelTests.Report, BulkModelTests.java:33), newest first from GET .../tests/reports (:74-76). */
export type TestReport = { runId: string; packageId?: string; passed: number; failed: number; notJudged: number; cases: { caseId: string; status: string; deterministic: boolean; millis?: number; message?: string }[] };

/**
 * A run's results as rows. A case whose name starts with "judge:" is not deterministic: the server stores it so
 * (ModellerWiring.java:225) and never counts it as passed or failed.
 */
export function runRows(runs: { name: string; passed?: boolean; message?: string; durationMs?: number; deterministic?: boolean; status?: string }[]): TestCaseRow[] {
  return runs.map((r) => ({
    name: r.name,
    status: r.status ?? (r.passed ? 'PASSED' : 'FAILED'),
    deterministic: r.deterministic ?? !r.name.startsWith('judge:'),
    message: r.message,
    millis: r.durationMs,
  }));
}

export function reportRows(r: TestReport): TestCaseRow[] {
  return (r.cases ?? []).map((x) => ({ name: x.caseId, status: x.status, deterministic: x.deterministic, message: x.message, millis: x.millis }));
}

function CaseTables({ rows }: { rows: TestCaseRow[] }) {
  const { t } = useTranslation();
  const det = rows.filter((r) => r.deterministic);
  const judge = rows.filter((r) => !r.deterministic);
  const table = (rs: TestCaseRow[], cap: string) => (
    <DataTable
      caption={cap}
      rows={rs}
      rowKey={(r) => r.name}
      columns={[
        { key: 'name', header: t('common.name') },
        { key: 's', header: t('admin.status'), render: (r) => <Badge value={r.status} /> },
        { key: 'ms', header: t('tests.millis'), className: 'num', render: (r) => (r.millis !== undefined ? fmtNumber(r.millis) : '—') },
        { key: 'm', header: t('ops.message'), render: (r) => r.message ?? '—' },
      ]}
    />
  );
  return (
    <>
      {det.length > 0 && table(det, t('mod.modeller2.deterministic'))}
      {judge.length > 0 && (
        <>
          {table(judge, t('mod.modeller2.judged'))}
          <Notice>{t('mod.modeller2.judgedHint')}</Notice>
        </>
      )}
    </>
  );
}

/**
 * M-038 (NG-32, R80): bulk model tests of a package. Run them (POST /packages/{id}/tests/run, package.author,
 * PackageToolsController.java:49-52), list the stored reports (GET /packages/{id}/tests/reports, package.read,
 * ModellerController.java:368-372) with a report's cases, judge results kept apart and outside the counts, export a
 * sealed bundle of test cases and mocks (POST /packages/{id}/tests/export, package.author, :374-380; format
 * sangam-test-bundle-v1 with sha256, BulkModelTests.java:79-89) and verify one (POST /packages/tests/verify-bundle,
 * package.read, :382-386; a changed bundle is refused with TEST_BUNDLE_INVALID, BulkModelTests.java:92-97).
 */
export function BulkTestReport({ packageId }: { packageId: string }) {
  const { t } = useTranslation();
  const pid = encodeURIComponent(packageId);
  const [run, setRun] = useState<TestCaseRow[]>();
  const [open, setOpen] = useState<string | null>(null);
  const [cases, setCases] = useState('[]');
  const [mocks, setMocks] = useState('[]');
  const [bundleText, setBundleText] = useState('');
  const [verified, setVerified] = useState<Row>();
  const [jsonErr, setJsonErr] = useState(false);
  const act = useAction();
  const reports = useAsync((signal) => http.get<TestReport[]>(`/packages/${pid}/tests/reports`, undefined, signal), [pid]);
  const shown = (reports.data ?? []).find((r) => r.runId === open);
  const exportBundle = () => {
    let body: { cases: unknown; mocks: unknown };
    try {
      body = { cases: JSON.parse(cases), mocks: JSON.parse(mocks) };
      if (!Array.isArray(body.cases) || !Array.isArray(body.mocks)) throw new Error('arrays');
    } catch {
      setJsonErr(true);
      return;
    }
    setJsonErr(false);
    void act.run(() => http.post<{ bundle: string }>(`/packages/${pid}/tests/export`, body)).then((r) => {
      if (!r) return;
      setBundleText(r.bundle);
      const url = URL.createObjectURL(new Blob([r.bundle], { type: 'application/json' }));
      const a = document.createElement('a');
      a.href = url;
      a.download = `${packageId}-tests.json`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
  };
  return (
    <Section title={t('mod.modeller2.bulkTests')}>
      <button
        type="button"
        className="primary"
        disabled={act.busy}
        onClick={() => void act.run(() => http.post<Parameters<typeof runRows>[0]>(`/packages/${pid}/tests/run`)).then((r) => { if (r) { setRun(runRows(r)); reports.reload(); } })}
      >
        {t('modeller.runTests')}
      </button>
      <ProblemAlert error={act.error} />
      {run && <CaseTables rows={run} />}

      <h3>{t('tests.reports')}</h3>
      <ProblemAlert error={reports.error} onRetry={reports.reload} />
      {reports.data && reports.data.length === 0 && <Empty message={t('tests.noReports')} />}
      {reports.data && reports.data.length > 0 && (
        <DataTable
          caption={t('tests.reports')}
          captionHidden
          rows={reports.data}
          rowKey={(r) => r.runId}
          columns={[
            { key: 'runId', header: t('fld.runId'), render: (r) => <button type="button" className="link" aria-pressed={open === r.runId} onClick={() => setOpen(open === r.runId ? null : r.runId)}>{r.runId}</button> },
            { key: 'passed', header: t('tests.passed'), className: 'num', render: (r) => fmtNumber(r.passed) },
            { key: 'failed', header: t('tests.failed'), className: 'num', render: (r) => fmtNumber(r.failed) },
            { key: 'notJudged', header: t('tests.notJudged'), className: 'num', render: (r) => fmtNumber(r.notJudged) },
          ]}
        />
      )}
      {shown && (
        <>
          <h4>{t('tests.report', { id: shown.runId })}</h4>
          <CaseTables rows={reportRows(shown)} />
          <button type="button" onClick={() => setCases(JSON.stringify(shown.cases.map((x) => ({ id: x.caseId, processKey: packageId, definition: {} })), null, 2))}>
            {t('tests.casesFromReport')}
          </button>
        </>
      )}

      <h3>{t('tests.export')}</h3>
      <p className="hint">{t('tests.exportHint')}</p>
      <TextArea label={t('tests.cases')} className="code" rows={4} value={cases} onChange={setCases} />
      <TextArea label={t('tests.mocks')} className="code" rows={3} value={mocks} onChange={setMocks} />
      {jsonErr && <Notice tone="bad">{t('mod.common.invalidJson')}</Notice>}
      <button type="button" disabled={act.busy} onClick={exportBundle}>
        {t('tests.exportButton')}
      </button>

      <h3>{t('tests.verify')}</h3>
      <div className="field">
        <label htmlFor="bundle-file">{t('tests.bundleFile')}</label>
        <input id="bundle-file" type="file" accept=".json,application/json" onChange={(e) => void e.target.files?.[0]?.text().then((x) => (setBundleText(x), setVerified(undefined)))} />
      </div>
      <TextArea label={t('tests.bundle')} className="code" rows={3} value={bundleText} onChange={(x) => (setBundleText(x), setVerified(undefined))} />
      <button type="button" disabled={act.busy || !bundleText.trim()} onClick={() => void act.run(() => http.post<Row>('/packages/tests/verify-bundle', { bundle: bundleText })).then((r) => r && setVerified(r))}>
        {t('tests.verifyButton')}
      </button>
      {verified && (
        <>
          <Notice tone="ok">{t('tests.verified', { pkg: String(verified.packageId ?? ''), rev: String(verified.packageRevision ?? '') })}</Notice>
          <JsonView value={verified} />
        </>
      )}
    </Section>
  );
}

// ------------------------------------------------------------------ ops

const usage: ScreenDef = {
  id: 'usage', title: 'mod.ops2.usage', description: 'mod.ops2.usageDesc', list: '/ops/usage', unpaged: true,
  filters: [f.date('from'), f.date('to'), f.text('tenant')],
  columns: [c('day'), c('tenantId'), c('metric'), c('value', 'fld.value')], rowKey: ['day', 'metric'],
};
const outliers: ScreenDef = {
  id: 'outliers', title: 'mod.ops2.outliers', description: 'mod.ops2.outliersDesc', list: '/ops/outliers', unpaged: true,
  filters: [f.text('processDefinitionKey')],
  columns: [c('subject'), c('metric'), c('value', 'fld.value'), c('baseline'), c('correlatedWith')], rowKey: ['subject', 'metric'],
};
const variables: ScreenDef = {
  id: 'vars', title: 'mod.ops2.variables', description: 'mod.ops2.variablesDesc', list: '/ops/variables',
  filters: [f.select('scope', ['GLOBAL', 'TENANT', 'PACKAGE', 'PROCESS']), f.text('name')],
  columns: [c('scope'), c('name'), c('value', 'fld.value'), c('version')], rowKey: ['id', 'name'],
  create: { id: 'save', label: 'act.create', method: 'PUT', path: '/ops/variables', fields: [f.select('scope', ['GLOBAL', 'TENANT', 'PACKAGE', 'PROCESS'], undefined, true), f.text('name', undefined, true), f.text('value', 'fld.value', true)], reason: 'body' },
  actions: [{ id: 'delete', label: 'common.delete', method: 'DELETE', path: '/ops/variables/{id}', reason: 'query', danger: true, noJson: true }],
};
const secrets: ScreenDef = {
  id: 'secrets', title: 'mod.ops2.secrets', description: 'mod.ops2.secretsDesc', list: '/ops/secrets',
  columns: [c('name'), c('scope'), c('version'), c('updatedAt'), c('updatedBy')], rowKey: ['name', 'id'],
  create: { id: 'set', label: 'act.create', method: 'PUT', path: '/ops/secrets/{name}', fields: [f.text('name', undefined, true), f.text('scope'), f.text('value', 'fld.secretValue', true, { sensitive: true })], reason: 'body', noJson: true },
  actions: [
    { id: 'rotate', label: 'admin.rotate', method: 'PUT', path: '/ops/secrets/{name}', fields: [f.text('value', 'fld.secretValue', true, { sensitive: true })], reason: 'body', noJson: true },
    { id: 'delete', label: 'common.delete', method: 'DELETE', path: '/ops/secrets/{name}', reason: 'query', danger: true, noJson: true },
  ],
};
const sequences: ScreenDef = {
  id: 'seq', title: 'mod.ops2.sequences', list: '/ops/sequences', unpaged: true,
  columns: [c('name'), c('current'), c('table')], rowKey: ['name'],
  actions: [{ id: 'advance', label: 'act.update', path: '/ops/sequences/{name}/advance', fields: [f.num('to', 'fld.value', true)], reason: 'body', noJson: true, hint: 'mod.ops2.sequenceHint' }],
};
const indexJobs: ScreenDef = {
  id: 'index', title: 'mod.ops2.index', list: '/ops/index-jobs',
  columns: [c('id'), c('kind'), c('status'), c('progress'), c('startedAt')], rowKey: ['id'],
  toolbar: [{ id: 'rebuild', label: 'mod.ops2.rebuild', path: '/ops/index-jobs', fields: [f.text('alias'), f.select('kind', ['REBUILD', 'DRIFT_CHECK'], undefined, true)], reason: 'body', noJson: true, showResult: true }],
};
const migration: ScreenDef = {
  id: 'mig', title: 'mod.ops2.migration', description: 'mod.ops2.migrationDesc', list: '/ops/migration-status',
  filters: [f.text('source'), f.text('status')],
  columns: [c('checkpoint'), c('source'), c('migrated'), c('failed'), c('mismatched'), c('status')], rowKey: ['checkpoint', 'id'],
};

export function RetentionPlan() {
  const { t } = useTranslation();
  const [plan, setPlan] = useState<{ planId?: string; hash?: string; items?: Row[]; blocked?: unknown }>();
  const [reason, setReason] = useState('');
  const act = useAction();
  return (
    <Section title={t('mod.ops2.retention')}>
      <p className="hint">{t('mod.ops2.retentionHint')}</p>
      <button type="button" disabled={act.busy} onClick={() => void act.run(() => http.get<typeof plan>('/ops/retention/plan')).then((r) => r && setPlan(r))}>{t('mod.ops2.preview')}</button>
      <ProblemAlert error={act.error} />
      {plan && (
        <>
          <JsonView value={plan.items ?? plan} />
          <TextField label={t('common.reason')} value={reason} onChange={setReason} required />
          <button type="button" className="danger" disabled={reason.trim().length < 5 || act.busy} onClick={() => void act.run((k) => http.post<unknown>('/ops/retention/execute', { planId: plan.planId, presentedHash: plan.hash, reason }, k)).then((r) => r !== undefined && setPlan(undefined))}>{t('mod.ops2.execute')}</button>
        </>
      )}
    </Section>
  );
}

export function OpsToolsPage() {
  return <ModulePage title="mod.ops2.title" screens={[usage, outliers, variables, secrets, sequences, indexJobs, migration]} extra={[{ id: 'ret', label: 'mod.ops2.retention', node: <RetentionPlan /> }]} />;
}

/** Ctrl+K global search; results come from the server, so they are already authorised. */
export function GlobalSearch() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const [term, setTerm] = useState('');
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setOpen(true);
      }
    };
    window.addEventListener('keydown', on);
    return () => window.removeEventListener('keydown', on);
  }, []);
  const r = useAsync(() => http.page<Row>('/search', { q: term, size: 30 }), [term], open && !!term);
  const target = useMemo(() => (x: Row) => {
    const id = encodeURIComponent(String(x.id ?? x.resourceId ?? ''));
    const type = String(x.type ?? x.resourceType ?? '').toUpperCase();
    return type.includes('TASK') ? `/tasks/${id}` : type.includes('PROCESS') ? `/ops/instances/${id}` : type.includes('CASE') ? `/cases/${id}` : null;
  }, []);
  return (
    <Dialog open={open} wide title={t('mod.ops2.search')} onClose={() => setOpen(false)}>
      <form onSubmit={(e) => { e.preventDefault(); setTerm(q); }}>
        <TextField label={t('common.search')} autoFocus value={q} onChange={setQ} />
      </form>
      <ProblemAlert error={r.error} />
      {term && r.data?.items.length === 0 && <Empty message={t('common.emptyNone')} />}
      <ul>
        {r.data?.items.map((x, i) => {
          const to = target(x);
          const label = String(x.title ?? x.name ?? x.id);
          return <li key={i}><Badge value={String(x.type ?? x.resourceType ?? '')} tone="info" /> {to ? <button type="button" className="link" onClick={() => { setOpen(false); nav(to); }}>{label}</button> : label}</li>;
        })}
      </ul>
    </Dialog>
  );
}

