// SPDX-License-Identifier: Apache-2.0
/**
 * Screens for sangam-ai, sangam-channels, sangam-modeller and sangam-ops, from their README integration notes.
 * The REST routes are not in the contract yet (WS-REST wiring in progress), so every path below except
 * POST /packages/{id}/tests/run is an assumption to reconcile; README "New module routes" lists them.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { http, request } from '../api/client';
import { useAction, useAsync, useInterval } from '../lib/hooks';
import { BpmnDiagram } from '../components/BpmnDiagram';
import { Badge, DataTable, Dialog, Empty, JsonView, Notice, ProblemAlert, Section, TextArea, TextField } from '../components/ui';
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
  useInterval(() => { if (sel) msgs.reload(); }, 10000);
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

/** Edit lease for one model file: acquires on open, heartbeats, releases on close, and offers takeover. */
export function LeaseBanner({ packageId, file, onState }: { packageId: string; file: string; onState?: (mine: boolean) => void }) {
  const { t } = useTranslation();
  const [lease, setLease] = useState<{ holder?: string; mine?: boolean; expiresAt?: string }>();
  const path = `/models/${encodeURIComponent(file)}/lease`;
  const held = useRef(false);
  const act = useAction();
  const acquire = (verb: string) => void act.run(() => http.post<typeof lease>(`${path}/${verb}`, { packageId })).then((r) => {
    if (r) {
      setLease(r);
      held.current = !!r.mine;
      onState?.(!!r.mine);
    }
  });
  useEffect(() => {
    acquire('acquire');
    return () => {
      if (held.current) void http.post(`${path}/release`, { packageId }).catch(() => undefined);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path]);
  useInterval(() => { if (held.current) acquire('heartbeat'); }, 30000);
  if (act.error) return <ProblemAlert error={act.error} />;
  if (!lease || lease.mine) return null;
  return (
    <Notice tone="warn">
      {t('mod.modeller2.leased', { who: lease.holder ?? '' })}{' '}
      <button type="button" className="link" onClick={() => acquire('takeover')}>{t('mod.modeller2.takeover')}</button>
    </Notice>
  );
}

const templates: ScreenDef = {
  id: 'templates', title: 'mod.modeller2.templates', description: 'mod.modeller2.templatesDesc', list: '/templates',
  filters: [f.select('kind', ['PROCESS', 'ELEMENT', 'FORM_COMPONENT', 'DMN'])],
  columns: [c('key'), c('name'), c('kind'), c('version')], rowKey: ['key', 'id'], detail: '/templates/{key}',
  create: { id: 'save', label: 'mod.modeller2.saveTemplate', path: '/templates', fields: [f.text('key', undefined, true), f.text('name', undefined, true), f.select('kind', ['PROCESS', 'ELEMENT', 'FORM_COMPONENT', 'DMN'], undefined, true), f.area('content', 'fld.body', true)] },
};
const actionsAdmin: ScreenDef = {
  id: 'actions', title: 'mod.modeller2.actions', list: '/actions',
  filters: [f.text('scope')],
  columns: [c('key'), c('name'), c('scope'), c('permission'), c('active')], rowKey: ['key', 'id'],
  create: { id: 'save', label: 'act.create', path: '/actions', fields: [f.text('key', undefined, true), f.text('name', undefined, true), f.text('scope', undefined, true), f.text('permission'), f.text('processKey'), f.json('form')] },
  actions: [
    { id: 'run', label: 'act.runNow', path: '/actions/{key}/run', fields: [f.text('subjectRef', undefined, true), f.json('input')], reason: 'body', showResult: true },
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

/** Bulk test report: non-deterministic (judge) results are kept apart and never affect the pass count. */
export function BulkTestReport({ packageId }: { packageId: string }) {
  const { t } = useTranslation();
  type R = { name: string; status?: string; passed?: boolean; deterministic?: boolean; message?: string; durationMs?: number };
  const [rep, setRep] = useState<{ passed?: number; failed?: number; results?: R[]; reportId?: string } | R[]>();
  const act = useAction();
  const results: R[] = Array.isArray(rep) ? rep : rep?.results ?? [];
  const det = results.filter((r) => r.deterministic !== false);
  const judge = results.filter((r) => r.deterministic === false);
  const statusOf = (r: R) => r.status ?? (r.passed ? 'PASS' : 'FAIL');
  const table = (rows: R[], cap: string) => <DataTable caption={cap} rows={rows} rowKey={(r) => r.name} columns={[{ key: 'name', header: t('common.name') }, { key: 's', header: t('admin.status'), render: (r) => <Badge value={statusOf(r)} /> }, { key: 'm', header: t('ops.message'), render: (r) => r.message ?? '—' }]} />;
  return (
    <Section title={t('mod.modeller2.bulkTests')}>
      <button type="button" className="primary" disabled={act.busy} onClick={() => void act.run(() => http.post<typeof rep>(`/packages/${encodeURIComponent(packageId)}/tests/run`)).then((r) => r && setRep(r))}>{t('modeller.runTests')}</button>
      <ProblemAlert error={act.error} />
      {det.length > 0 && table(det, t('mod.modeller2.deterministic'))}
      {judge.length > 0 && <>{table(judge, t('mod.modeller2.judged'))}<Notice>{t('mod.modeller2.judgedHint')}</Notice></>}
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

