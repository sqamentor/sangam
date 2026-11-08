// SPDX-License-Identifier: Apache-2.0
/**
 * sangam-config screens (contract /config/**, ADR-D-20) and the onboarding wizard.
 * - Inheritance view: resolved profile for a scope / office / date, the layer chain, merged tree, locked paths,
 *   unverified placeholders and findings.
 * - Maker-checker import: draft from pasted or uploaded YAML/JSON, submit, approval bound to the reviewed hash, activate.
 * - Master codes, correction slips, classification validation, document types, crosswalks.
 * - Onboarding wizard: tenant → organisation → seats → postings → profile validation → activation, composed from the
 *   existing contract operations because no /onboarding routes exist yet (README "Integration notes").
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { http, request } from '../api/client';
import { announce, useAction, useAsync } from '../lib/hooks';
import { Badge, DataTable, JsonView, KeyValues, Notice, ProblemAlert, Section, TextArea, TextField } from '../components/ui';
import { ModulePage, c, cell, f, type Row, type ScreenDef } from './engine';

type Finding = { severity: string; code?: string; path?: string; message?: string };

function Findings({ items }: { items?: Finding[] }) {
  const { t } = useTranslation();
  if (!items?.length) return <Notice tone="ok">{t('mod.config.noFindings')}</Notice>;
  return (
    <DataTable
      caption={t('mod.config.findings')}
      rows={items.map((x, i) => ({ ...x, i }))}
      rowKey={(r) => String(r.i)}
      columns={[
        { key: 'severity', header: t('fld.severity'), render: (r) => <Badge value={r.severity} /> },
        { key: 'code', header: t('fld.code'), render: (r) => r.code ?? '—' },
        { key: 'path', header: t('fld.path'), render: (r) => r.path ?? '—' },
        { key: 'message', header: t('fld.message'), render: (r) => r.message ?? '—' },
      ]}
    />
  );
}

/** Collects declared locks: any `locked` / `locks` list in the tree, plus findings whose code mentions a lock. */
export function lockedPaths(data: unknown, findings: Finding[] = [], prefix = ''): string[] {
  const out = new Set<string>(findings.filter((x) => /LOCK/i.test(x.code ?? '') && x.path).map((x) => x.path!));
  const walk = (v: unknown, p: string) => {
    if (!v || typeof v !== 'object' || Array.isArray(v)) return;
    for (const [k, x] of Object.entries(v as Row)) {
      if ((k === 'locked' || k === 'locks') && Array.isArray(x)) x.forEach((l) => out.add(p ? `${p}.${String(l)}` : String(l)));
      else if (k === 'locked' && x === true) out.add(p);
      else walk(x, p ? `${p}.${k}` : k);
    }
  };
  walk(data, prefix);
  return [...out].filter(Boolean).sort();
}

function Tree({ value, locked, path = '' }: { value: unknown; locked: Set<string>; path?: string }) {
  const { t } = useTranslation();
  if (!value || typeof value !== 'object') return <>{cell(value)}</>;
  return (
    <ul className="plan-tree">
      {Object.entries(value as Row).map(([k, v]) => {
        const p = path ? `${path}.${k}` : k;
        return (
          <li key={k}>
            <strong>{k}</strong> {locked.has(p) && <span className="badge badge-warn">{t('mod.config.locked')}</span>} {v && typeof v === 'object' ? <Tree value={v} locked={locked} path={p} /> : cell(v)}
          </li>
        );
      })}
    </ul>
  );
}

export function InheritanceView() {
  const { t } = useTranslation();
  const [scope, setScope] = useState('');
  const [office, setOffice] = useState('');
  const [asOf, setAsOf] = useState('');
  const [q, setQ] = useState<Row | null>(null);
  const r = useAsync(() => http.get<{ chain?: string[]; data?: unknown; binding?: string; placeholders?: Record<string, string>; findings?: Finding[] }>('/config/profiles', q as never), [JSON.stringify(q)], !!q);
  const locks = lockedPaths(r.data?.data, r.data?.findings);
  return (
    <Section title={t('mod.config.inheritance')}>
      <p className="hint">{t('mod.config.inheritanceHint')}</p>
      <form className="filters" onSubmit={(e) => (e.preventDefault(), setQ({ scope: scope || undefined, office: office || undefined, asOf: asOf || undefined }))}>
        <TextField label={t('fld.scope')} value={scope} onChange={setScope} />
        <TextField label={t('fld.office')} value={office} onChange={setOffice} />
        <TextField label={t('fld.asOf')} type="date" value={asOf} onChange={setAsOf} />
        <button type="submit">{t('common.open')}</button>
      </form>
      <ProblemAlert error={r.error} onRetry={r.reload} />
      {r.data && (
        <>
          <KeyValues items={[[t('fld.binding'), r.data.binding]]} />
          <h3>{t('mod.config.chain')}</h3>
          <ol className="path">{(r.data.chain ?? []).map((l) => <li key={l}>{l}</li>)}</ol>
          <h3>{t('mod.config.lockedPaths')}</h3>
          {locks.length ? <ul>{locks.map((l) => <li key={l}><code>{l}</code></li>)}</ul> : <p className="muted">{t('mod.config.noLocks')}</p>}
          <h3>{t('mod.config.placeholders')}</h3>
          <KeyValues items={Object.entries(r.data.placeholders ?? {})} />
          <Findings items={r.data.findings} />
          <h3>{t('mod.config.merged')}</h3>
          <Tree value={r.data.data} locked={new Set(locks)} />
        </>
      )}
    </Section>
  );
}

/** Maker → checker lifecycle of one profile version. The checker approves the hash shown on screen. */
export function ProfileLifecycle() {
  const { t } = useTranslation();
  const [id, setId] = useState('');
  const [active, setActive] = useState('');
  const [version, setVersion] = useState('');
  const [doc, setDoc] = useState('');
  const [reason, setReason] = useState('');
  const [findings, setFindings] = useState<Finding[]>();
  const pid = encodeURIComponent(active);
  const versions = useAsync(() => http.page<Row>(`/config/profiles/${pid}/versions`, { size: 100 }), [active], !!active);
  const history = useAsync(() => http.page<Row>(`/config/profiles/${pid}/history`, { size: 100 }), [active], !!active);
  const act = useAction();
  const refresh = () => (versions.reload(), history.reload());
  const vp = (v: Row) => `/config/profiles/${pid}/versions/${encodeURIComponent(String(v.version))}`;
  const save = () =>
    void act
      .run((k) => request<{ findings?: Finding[] }>('PUT', `/config/profiles/${pid}/versions/${encodeURIComponent(version)}`, { body: doc, idempotencyKey: k }))
      .then((r) => {
        if (r) {
          setFindings(r.findings ?? []);
          announce(t('common.saved'));
          refresh();
        }
      });
  const step = (fn: (k: string) => Promise<unknown>) => void act.run(async (k) => (await fn(k), true)).then((ok) => ok && refresh());
  return (
    <Section title={t('mod.config.lifecycle')}>
      <form className="filters" onSubmit={(e) => (e.preventDefault(), setActive(id.trim()))}>
        <TextField label={t('fld.profileId')} value={id} onChange={setId} required />
        <button type="submit">{t('common.open')}</button>
      </form>
      <ProblemAlert error={versions.error ?? history.error ?? act.error} />
      {active && (
        <>
          <h3>{t('mod.config.import')}</h3>
          <TextField label={t('fld.version')} value={version} onChange={setVersion} required />
          <div className="field">
            <label htmlFor="profile-file">{t('mod.config.file')}</label>
            <input id="profile-file" type="file" accept=".yaml,.yml,.json" onChange={(e) => void e.target.files?.[0]?.text().then(setDoc)} />
          </div>
          <TextArea label={t('mod.config.document')} className="code" rows={10} value={doc} onChange={setDoc} />
          <button type="button" disabled={!version || !doc.trim() || act.busy} onClick={save}>{t('mod.config.saveDraft')}</button>
          {findings && <Findings items={findings} />}
          <TextField label={t('common.reason')} hint={t('mod.config.rejectHint')} value={reason} onChange={setReason} />
          <DataTable
            caption={t('mod.config.versions')}
            rows={versions.data?.items ?? []}
            rowKey={(r) => String(r.version)}
            columns={[
              c('version'),
              c('layer'),
              c('status'),
              { key: 'hash', label: 'fld.hash', render: (r: Row) => <code className="hash">{String(r.hash ?? '')}</code> },
              c('effectiveFrom'),
            ].map((x) => ({ key: x.key, header: t(x.label), render: (r: Row) => (x.render ? x.render(r) : cell(r[x.key], x.key)) })).concat([
              {
                key: 'a',
                header: t('common.actions'),
                render: (r: Row) => (
                  <span className="toolbar inline">
                    {r.status === 'DRAFT' && <button type="button" className="link" onClick={() => step((k) => http.post(`${vp(r)}/submit`, {}, k))}>{t('act.submit')}</button>}
                    {r.status === 'SUBMITTED' && <button type="button" className="link" onClick={() => step((k) => http.post(`${vp(r)}/approve`, { presentedHash: r.hash }, k))}>{t('act.approve')}</button>}
                    {r.status === 'SUBMITTED' && <button type="button" className="link danger" disabled={reason.trim().length < 5} onClick={() => step((k) => http.post(`${vp(r)}/reject`, { reason }, k))}>{t('act.reject')}</button>}
                    {r.status === 'APPROVED' && <button type="button" className="link" onClick={() => step((k) => http.post(`${vp(r)}/activate`, {}, k))}>{t('act.activate')}</button>}
                  </span>
                ),
              },
            ])}
          />
          <p className="hint">{t('mod.config.makerChecker')}</p>
          <h3>{t('mod.config.register')}</h3>
          <JsonView value={history.data?.items ?? []} />
        </>
      )}
    </Section>
  );
}

const master: ScreenDef = {
  id: 'master',
  title: 'mod.config.master',
  list: '/config/master/{scheme}/{segment}',
  unpaged: true,
  filters: [f.text('scheme', undefined, true), f.text('segment', undefined, true), f.text('parent'), f.date('asOf')],
  requireFilters: ['scheme', 'segment'],
  columns: [c('code'), c('parentCode'), c('name'), c('validFrom'), c('validTo')],
  rowKey: ['code'],
  toolbar: [
    { id: 'slip', label: 'act.correctionSlip', path: '/config/master/correction-slips', fields: [f.text('scope', undefined, true), f.text('scheme', undefined, true), f.date('effectiveFrom', undefined, true), f.json('changes', undefined, true)], showResult: true },
    { id: 'classify', label: 'act.validateCode', path: '/config/classification/validate', fields: [f.text('scheme', undefined, true), f.text('code', undefined, true), f.text('scope'), f.date('asOf')], showResult: true },
    { id: 'crosswalk', label: 'act.mapCrosswalk', path: '/config/crosswalks/{crosswalkId}/map', fields: [f.text('crosswalkId', undefined, true), f.text('code', undefined, true), f.money('amount')], showResult: true },
  ],
};

const docTypes: ScreenDef = {
  id: 'docTypes',
  title: 'mod.config.docTypes',
  list: '/config/document-types',
  unpaged: true,
  filters: [f.text('scope'), f.text('family'), f.date('asOf')],
  columns: [c('id'), c('family'), c('name')],
  rowKey: ['id', 'key'],
  actions: [{ id: 'check', label: 'act.checkDocument', path: '/config/document-types/{id}/check', fields: [f.json('document', undefined, true), f.text('scope')], showResult: true, noJson: true }],
};

export function ConfigPage() {
  return (
    <ModulePage
      title="mod.config.title"
      description="mod.config.desc"
      screens={[master, docTypes]}
      extra={[
        { id: 'inherit', label: 'mod.config.inheritance', node: <InheritanceView /> },
        { id: 'life', label: 'mod.config.lifecycle', node: <ProfileLifecycle /> },
      ]}
    />
  );
}

const runs: ScreenDef = {
  id: 'runs',
  title: 'mod.onboarding.runs',
  list: '/onboarding/runs',
  filters: [f.text('tenant')],
  columns: [c('id'), c('tenantId'), c('status'), c('maker'), c('checker'), c('revision'), c('updatedAt')],
  rowKey: ['id'],
  detail: '/onboarding/runs/{id}',
  actions: [
    { id: 'recheck', label: 'mod.onboarding.recheck', path: '/onboarding/runs/{id}/recheck', noJson: true },
    { id: 'amend', label: 'mod.onboarding.amend', method: 'PUT', path: '/onboarding/runs/{id}/request', fields: [f.json('tenant', 'fld.tenantJson', true), f.json('profile', 'fld.profileJson'), f.json('packs', 'fld.packsJson'), f.json('security', 'fld.securityJson')], defaults: (r) => { const q = (r?.request ?? {}) as Row; return { tenant: JSON.stringify(q.tenant ?? {}), profile: JSON.stringify(q.profile ?? {}), packs: JSON.stringify(q.packs ?? {}), security: JSON.stringify(q.security ?? {}) }; } },
    { id: 'approve', label: 'act.approve', path: '/onboarding/runs/{id}/approve', fields: [f.text('presentedHash', undefined, true)], noJson: true, hint: 'mod.onboarding.approveHint', defaults: (r) => ({ presentedHash: ((r?.readiness ?? {}) as Row).hash ?? r?.requestHash }), visible: (r) => r.status === 'AWAITING_APPROVAL' },
    { id: 'reject', label: 'act.reject', path: '/onboarding/runs/{id}/reject', reason: 'body', danger: true, noJson: true, visible: (r) => r.status === 'AWAITING_APPROVAL' },
    { id: 'resume', label: 'mod.onboarding.resume', path: '/onboarding/runs/{id}/resume', noJson: true, visible: (r) => r.status === 'FAILED' || r.status === 'WAITING_FOR_FIX' },
    { id: 'cancel', label: 'act.cancel', path: '/onboarding/runs/{id}/cancel', reason: 'body', danger: true, noJson: true },
  ],
  toolbar: [
    { id: 'packs', label: 'mod.onboarding.resolvePacks', path: '/onboarding/packs/resolve', fields: [f.json('ids', 'fld.packIds', true)], noJson: true, showResult: true },
    { id: 'export', label: 'mod.onboarding.exportBundle', method: 'GET', path: '/onboarding/tenants/{tenant}/bundle', fields: [f.text('tenant', undefined, true)], noJson: true, showResult: true },
    { id: 'import', label: 'mod.onboarding.importBundle', path: '/onboarding/bundles/import', showResult: true },
    { id: 'diff', label: 'mod.onboarding.diffBundles', path: '/onboarding/bundles/diff', showResult: true },
  ],
};

/** Onboarding (contract /onboarding/**): compose a request, dry-run it, start a run; runs then go through maker-checker. */
function NewRun({ onStarted }: { onStarted: () => void }) {
  const { t } = useTranslation();
  const [v, setV] = useState<Record<string, string>>({ tenant: '{\n  "id": "",\n  "name": ""\n}', profile: '{}', packs: '{}', security: '{}', seeds: '{}', compliance: '{}', goLive: '{}' });
  const [dry, setDry] = useState<{ ok?: boolean; problems?: { severity: string; code?: string; step?: string; subject?: string; message?: string }[]; plan?: unknown; requestHash?: string }>();
  const [err, setErr] = useState<string>();
  const act = useAction();
  const body = (): Row | null => {
    try {
      setErr(undefined);
      return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, JSON.parse(x || '{}')]));
    } catch (e) {
      setErr((e as Error).message);
      return null;
    }
  };
  return (
    <Section title={t('mod.onboarding.title')}>
      <p className="hint">{t('mod.onboarding.hint')}</p>
      {Object.keys(v).map((k) => (
        <TextArea key={k} label={t(`mod.onboarding.part_${k}`)} className="code" rows={k === 'tenant' ? 5 : 3} value={v[k]} onChange={(x) => (setV({ ...v, [k]: x }), setDry(undefined))} />
      ))}
      {err && <Notice tone="bad">{err}</Notice>}
      <div className="toolbar">
        <button type="button" disabled={act.busy} onClick={() => { const b = body(); if (b) void act.run(() => http.post<typeof dry>('/onboarding/dry-run', b)).then((r) => r && setDry(r)); }}>{t('mod.onboarding.dryRun')}</button>
        <button type="button" className="primary" disabled={act.busy || !dry?.ok} onClick={() => { const b = body(); if (b) void act.run((k) => http.post<Row>('/onboarding/runs', b, k)).then((r) => { if (r) { announce(t('mod.common.done')); onStarted(); } }); }}>{t('mod.onboarding.start')}</button>
      </div>
      <ProblemAlert error={act.error} />
      {dry && (
        <>
          <Notice tone={dry.ok ? 'ok' : 'bad'}>{dry.ok ? t('mod.onboarding.dryOk') : t('mod.onboarding.dryBad')}</Notice>
          <Findings items={(dry.problems ?? []).map((p) => ({ severity: p.severity, code: p.code, path: [p.step, p.subject].filter(Boolean).join(' / '), message: p.message }))} />
          <JsonView value={dry.plan ?? {}} />
        </>
      )}
    </Section>
  );
}

export function OnboardingWizard() {
  const [n, setN] = useState(0);
  return <ModulePage key={n} title="mod.onboarding.title" screens={[runs]} extra={[{ id: 'new', label: 'mod.onboarding.new', node: <NewRun onStarted={() => setN(n + 1)} /> }]} />;
}