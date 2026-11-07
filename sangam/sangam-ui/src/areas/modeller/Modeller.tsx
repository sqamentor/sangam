// SPDX-License-Identifier: Apache-2.0
/**
 * Modeller (R69, R43, R80; PF-21/22/23/36/37; EC-04/05; A06/A07; FC-41/46; D-08/D-12/D-43):
 * - packages = hash-pinned executable bundles (BPMN, DMN, forms, CMMN) with version history;
 * - in-browser BPMN / DMN / form editors (bpmn.io, watermark kept) and a CMMN source editor with outline;
 * - validation report (closure check, unsupported constructs, latest-binding and CMMN-listener findings);
 * - diff vs the deployed version, comments, Git sync (pull / push), model tests;
 * - lifecycle draft → review → approved → deployed → retired; reviewer ≠ author; "play" deploys to the sandbox tenant.
 */
import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import { fileName } from '../../api/adapters';
import { BulkTestReport, FeelPlayground, LeaseBanner, TokenWalk } from '../../modules/platform';
import { ScanReport, hasBlocking, scanArtifacts } from '../../modules/compat';
import type { ModelArtifact, ModelPackage, PackageDiff, PackageState, ValidationIssue } from '../../api/types';
import { announce, useAction, useAsync, usePaged } from '../../lib/hooks';
import { fmtDate } from '../../i18n';
import { useAuth } from '../../auth/AuthProvider';
import { BpmnEditor, CmmnEditor, DmnEditor, EMPTY_BPMN, EMPTY_DMN, EMPTY_FORM, FormBuilder, type EditorHandle } from '../../components/Editors';
import { Badge, Checkbox, DataTable, Dialog, Empty, KeyValues, Loading, Notice, PageHeader, Pagination, ProblemAlert, ReasonDialog, Section, SelectField, TabPanel, Tabs, TextArea, TextField } from '../../components/ui';

const EMPTY_CMMN = `<?xml version="1.0" encoding="UTF-8"?>
<cmmn:definitions xmlns:cmmn="http://www.omg.org/spec/CMMN/20151109/MODEL" id="Definitions_1" targetNamespace="http://sangam.org/cmmn">
  <cmmn:case id="Case_1" name="Case">
    <cmmn:casePlanModel id="CasePlanModel_1" name="Plan">
      <cmmn:planItem id="PI_Task_1" definitionRef="Task_1" />
      <cmmn:humanTask id="Task_1" name="Review" />
    </cmmn:casePlanModel>
  </cmmn:case>
</cmmn:definitions>`;

export function Packages() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const [state, setState] = useState<PackageState | ''>('');
  const list = usePaged((p) => api.packages.list({ ...p, state: state || undefined }), [state]);
  const [create, setCreate] = useState<{ key: string; name: string } | null>(null);
  const act = useAction();
  return (
    <>
      <PageHeader
        title={t('modeller.packagesTitle')}
        description={t('modeller.packagesDesc')}
        actions={
          <button type="button" className="primary" onClick={() => setCreate({ key: '', name: '' })}>
            {t('modeller.newPackage')}
          </button>
        }
      />
      <SelectField
        label={t('admin.status')}
        value={state}
        onChange={(v) => setState(v as PackageState)}
        placeholder={t('common.all')}
        options={(['DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'DEPLOYED', 'ACTIVE', 'RETIRED'] as const).map((s) => ({ value: s, label: t(`enum.${s}`) }))}
      />
      <ProblemAlert error={list.error ?? act.error} onRetry={list.reload} />
      {list.data?.items.length === 0 && <Empty message={t('modeller.noPackages')} />}
      {list.data && list.data.items.length > 0 && (
        <DataTable
          caption={t('modeller.packagesTitle')}
          captionHidden
          rows={list.data.items}
          rowKey={(r) => r.id}
          columns={[
            { key: 'name', header: t('common.name'), render: (r) => <Link to={`/modeller/${encodeURIComponent(r.id)}`}>{r.name}</Link> },
            { key: 'key', header: t('ops.key') },
            { key: 'version', header: t('ops.version') },
            { key: 'state', header: t('admin.status'), render: (r) => <Badge value={r.state} /> },
            { key: 'author', header: t('admin.author') },
            { key: 'updated', header: t('modeller.updated'), render: (r) => fmtDate(r.updatedAt) },
            { key: 'artifacts', header: t('modeller.artifacts'), render: (r) => String(r.artifacts?.length ?? 0) },
          ]}
        />
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
      <Dialog
        open={create !== null}
        title={t('modeller.newPackage')}
        onClose={() => setCreate(null)}
        footer={
          <button
            type="button"
            className="primary"
            disabled={!create?.key || !create?.name}
            onClick={() => void act.run(() => api.packages.create(create!)).then((p) => p && nav(`/modeller/${encodeURIComponent(p.id)}`))}
          >
            {t('common.save')}
          </button>
        }
      >
        {create && (
          <>
            <TextField label={t('ops.key')} required value={create.key} onChange={(v) => setCreate({ ...create, key: v })} />
            <TextField label={t('common.name')} required value={create.name} onChange={(v) => setCreate({ ...create, name: v })} />
          </>
        )}
      </Dialog>
    </>
  );
}

function ArtifactEditor({ artifact, onReady, onChange }: { artifact: ModelArtifact; onReady: (h: EditorHandle) => void; onChange: () => void }) {
  switch (artifact.kind) {
    case 'BPMN':
      return <BpmnEditor xml={artifact.content} onReady={onReady} onChange={onChange} />;
    case 'DMN':
      return <DmnEditor xml={artifact.content} onReady={onReady} onChange={onChange} />;
    case 'FORM':
      return <FormBuilder schema={artifact.content} onReady={onReady} onChange={onChange} />;
    case 'CMMN':
      return <CmmnEditor xml={artifact.content} onReady={onReady} onChange={onChange} />;
    default:
      return null;
  }
}

export function PackageWorkspace() {
  const { id = '' } = useParams();
  const { t } = useTranslation();
  const { me } = useAuth();
  const pkg = useAsync(() => api.packages.get(id), [id]);
  const [current, setCurrent] = useState<string>();
  const handle = useRef<EditorHandle>();
  const [dirty, setDirty] = useState(false);
  const [tab, setTab] = useState('edit');
  const [issues, setIssues] = useState<ValidationIssue[]>();
  const [adding, setAdding] = useState<{ name: string; kind: ModelArtifact['kind'] } | null>(null);
  const [transition, setTransition] = useState<'submit' | 'deploy' | 'retire' | null>(null);
  const [tenant, setTenant] = useState('');
  const [ack, setAck] = useState(false);
  const act = useAction();

  useEffect(() => {
    if (pkg.data && !current && pkg.data.artifacts.length) setCurrent(pkg.data.artifacts[0].id);
  }, [pkg.data, current]);

  if (pkg.loading && !pkg.data) return <Loading />;
  if (!pkg.data) return <ProblemAlert error={pkg.error} onRetry={pkg.reload} />;
  const p = pkg.data;
  const editable = p.state === 'DRAFT' || p.state === 'REJECTED';
  const art = p.artifacts.find((a) => a.id === current);
  const scan = scanArtifacts(p.artifacts);

  const save = async (): Promise<boolean> => {
    if (!art || !handle.current) return true;
    const content = await handle.current.getContent();
    const next = p.artifacts.map((a) => (a.id === art.id ? { ...a, content } : a));
    const r = await act.run(() => api.packages.saveResources(id, next, p.name));
    if (r) {
      setDirty(false);
      pkg.setData({ ...r, artifacts: r.artifacts.length ? r.artifacts : next });
      announce(t('common.saved'));
      return true;
    }
    return false;
  };

  const switchTo = async (aid: string) => {
    if (dirty && !(await save())) return;
    handle.current = undefined;
    setCurrent(aid);
  };

  return (
    <>
      <PageHeader
        title={`${p.name} · v${p.version}`}
        description={`${p.key} · ${t('admin.author')}: ${p.author}`}
        actions={
          <>
            <Badge value={p.state} />
            {editable && (
              <>
                <button type="button" disabled={!dirty || act.busy} onClick={() => void save()}>
                  {t('common.save')}
                </button>
                <button
                  type="button"
                  onClick={() =>
                    void (async () => {
                      if (dirty) await save();
                      const r = await act.run(() => api.packages.validate(id));
                      if (r) {
                        setIssues(r);
                        setTab('validation');
                      }
                    })()
                  }
                >
                  {t('modeller.validate')}
                </button>
                <button type="button" className="primary" disabled={dirty} onClick={() => setTransition('submit')}>
                  {t('modeller.submitReview')}
                </button>
              </>
            )}
            {p.state === 'APPROVED' && p.author !== me?.userId && (
              <button type="button" className="primary" onClick={() => setTransition('deploy')}>
                {t('modeller.deploy')}
              </button>
            )}
            {(p.state === 'ACTIVE' || p.state === 'DEPLOYED') && (
              <button type="button" className="danger" onClick={() => setTransition('retire')}>
                {t('modeller.retire')}
              </button>
            )}
          </>
        }
      />
      <ProblemAlert error={act.error} />
      {p.hash && (
        <p className="small muted">
          {t('modeller.hash')}: <code className="hash">{p.hash}</code> · {t('modeller.binding')}: {p.bindingMode ?? 'DEPLOYMENT'}
        </p>
      )}
      <Tabs
        label={t('modeller.workspace')}
        active={tab}
        onChange={setTab}
        tabs={[
          { id: 'edit', label: t('modeller.edit') },
          { id: 'validation', label: t('modeller.validation'), badge: issues ? String(issues.filter((i) => i.severity === 'ERROR').length) : undefined },
          { id: 'compat', label: t('mod.compat.scan'), badge: scan.length ? String(scan.length) : undefined },
          { id: 'diff', label: t('modeller.diff') },
          { id: 'history', label: t('modeller.history') },
          { id: 'tests', label: t('modeller.tests') },
          { id: 'play', label: t('modeller.play') },
          { id: 'walk', label: t('mod.modeller2.tokenWalk') },
          { id: 'feel', label: t('mod.ai.feel') },
          { id: 'git', label: t('modeller.git') },
          { id: 'comments', label: t('task.tab.comments') },
        ]}
      />
      <TabPanel id="edit" active={tab}>
        <div className="workspace">
          <nav aria-label={t('modeller.artifacts')} className="artifact-list">
            <ul>
              {p.artifacts.map((a) => (
                <li key={a.id}>
                  <button type="button" aria-current={a.id === current ? 'true' : undefined} onClick={() => void switchTo(a.id)}>
                    <span className="badge badge-info">{a.kind}</span> {a.name}
                  </button>
                </li>
              ))}
            </ul>
            {editable && (
              <button type="button" onClick={() => setAdding({ name: '', kind: 'BPMN' })}>
                {t('modeller.addArtifact')}
              </button>
            )}
          </nav>
          <div className="artifact-editor">
            {art ? (
              editable ? (
                <>
                <LeaseBanner key={art.id} packageId={id} file={art.name} />
                <ArtifactEditor
                  key={art.id}
                  artifact={art}
                  onReady={(h) => {
                    handle.current = h;
                  }}
                  onChange={() => setDirty(true)}
                />
                </>
              ) : (
                <>
                  <Notice>{t('modeller.readOnly')}</Notice>
                  <ArtifactEditor key={art.id} artifact={art} onReady={() => undefined} onChange={() => undefined} />
                </>
              )
            ) : (
              <Empty message={t('modeller.noArtifacts')} />
            )}
            {dirty && <p className="small muted" aria-live="polite">{t('modeller.unsaved')}</p>}
          </div>
        </div>
      </TabPanel>
      <TabPanel id="validation" active={tab}>
        <ValidationReport issues={issues} />
      </TabPanel>
      <TabPanel id="compat" active={tab}>
        <ScanReport findings={scan} />
        <Link to="/modeller/compatibility">{t('mod.compat.title')}</Link>
      </TabPanel>
      <TabPanel id="diff" active={tab}>
        <DiffPanel packageId={id} />
      </TabPanel>
      <TabPanel id="history" active={tab}>
        <HistoryPanel packageId={id} />
      </TabPanel>
      <TabPanel id="tests" active={tab}>
        <BulkTestReport packageId={id} />
      </TabPanel>
      <TabPanel id="walk" active={tab}>
        {art?.kind === 'BPMN' ? <TokenWalk key={art.id} file={art.name} bpmnXml={art.content} /> : <Empty message={t('modeller.noArtifacts')} />}
      </TabPanel>
      <TabPanel id="feel" active={tab}>
        <FeelPlayground />
      </TabPanel>
      <TabPanel id="play" active={tab}>
        <PlayPanel pkg={p} />
      </TabPanel>
      <TabPanel id="git" active={tab}>
        <GitPanel packageId={id} />
      </TabPanel>
      <TabPanel id="comments" active={tab}>
        <CommentsPanel packageId={id} />
      </TabPanel>
      <Dialog
        open={adding !== null}
        title={t('modeller.addArtifact')}
        onClose={() => setAdding(null)}
        footer={
          <button
            type="button"
            className="primary"
            disabled={!adding?.name}
            onClick={() => {
              const content = adding!.kind === 'BPMN' ? EMPTY_BPMN : adding!.kind === 'DMN' ? EMPTY_DMN : adding!.kind === 'FORM' ? EMPTY_FORM : EMPTY_CMMN;
              const added = { id: fileName({ name: adding!.name, kind: adding!.kind }), name: fileName({ name: adding!.name, kind: adding!.kind }), kind: adding!.kind, content };
              const next = [...p.artifacts, added];
              void act.run(() => api.packages.saveResources(id, next, p.name)).then((r) => {
                if (r) {
                  setAdding(null);
                  pkg.setData({ ...r, artifacts: r.artifacts.length ? r.artifacts : next });
                  setCurrent(added.id);
                }
              });
            }}
          >
            {t('common.save')}
          </button>
        }
      >
        {adding && (
          <>
            <TextField label={t('common.name')} required value={adding.name} onChange={(v) => setAdding({ ...adding, name: v })} hint={t('modeller.artifactNameHint')} />
            <SelectField label={t('admin.kind')} value={adding.kind} onChange={(v) => setAdding({ ...adding, kind: v as ModelArtifact['kind'] })} options={['BPMN', 'DMN', 'FORM', 'CMMN'].map((k) => ({ value: k, label: t(`modeller.kind.${k}`) }))} />
          </>
        )}
      </Dialog>
      <ReasonDialog
        open={transition !== null}
        title={transition ? t(`modeller.tr.${transition}`) : ''}
        preview={(transition === 'submit' || transition === 'deploy') && hasBlocking(scan) && !ack ? { summary: [], blocked: true, blockedReason: t('mod.compat.ackRequired') } : undefined}
        busy={act.busy}
        error={act.error}
        danger={transition === 'retire'}
        onClose={() => setTransition(null)}
        onConfirm={(reason) =>
          void act.run((k) => api.packages.transition(id, transition!, { reason, tenantId: tenant || undefined }, k)).then((r) => {
            if (r) {
              setTransition(null);
              pkg.setData(r);
              announce(t('modeller.transitioned', { state: t(`enum.${r.state}`) }));
            }
          })
        }
      >
        {(transition === 'submit' || transition === 'deploy') && <ScanReport findings={scan} />}
        {(transition === 'submit' || transition === 'deploy') && hasBlocking(scan) && <Checkbox label={t('mod.compat.ack')} checked={ack} onChange={setAck} />}
        {transition === 'submit' && <Notice>{t('modeller.submitHint')}</Notice>}
        {transition === 'deploy' && <TextField label={t('modeller.targetTenant')} value={tenant} onChange={setTenant} hint={t('modeller.targetTenantHint')} />}
        {transition === 'retire' && <Notice tone="warn">{t('modeller.retireHint')}</Notice>}
      </ReasonDialog>
    </>
  );
}

function ValidationReport({ issues }: { issues?: ValidationIssue[] }) {
  const { t } = useTranslation();
  if (!issues) return <Empty message={t('modeller.runValidation')} />;
  if (issues.length === 0) return <Notice tone="ok">{t('modeller.noIssues')}</Notice>;
  return (
    <DataTable
      caption={t('modeller.validation')}
      captionHidden
      rows={issues.map((i, n) => ({ ...i, n }))}
      rowKey={(r) => String(r.n)}
      columns={[
        { key: 'severity', header: t('modeller.severity'), render: (r) => <Badge value={r.severity} /> },
        { key: 'artifact', header: t('modeller.artifact'), render: (r) => r.artifact ?? '—' },
        { key: 'elementId', header: t('modeller.element'), render: (r) => r.elementId ?? '—' },
        { key: 'code', header: t('modeller.code'), render: (r) => r.code ?? '—' },
        { key: 'message', header: t('ops.message') },
      ]}
    />
  );
}

function DiffPanel({ packageId }: { packageId: string }) {
  const { t } = useTranslation();
  const d = useAsync<PackageDiff>(() => api.packages.diff(packageId), [packageId]);
  return (
    <>
      <ProblemAlert error={d.error} onRetry={d.reload} />
      {d.loading && <Loading />}
      {d.data && (
        <>
          <p>
            {t('modeller.diffFromTo', { from: d.data.fromVersion ?? '—', to: d.data.toVersion })}
            {d.data.inFlightCases !== undefined && ` · ${t('modeller.inFlight', { n: d.data.inFlightCases })}`}
          </p>
          <DataTable
            caption={t('modeller.diff')}
            captionHidden
            rows={d.data.artifacts}
            rowKey={(r) => r.name}
            columns={[
              { key: 'name', header: t('modeller.artifact') },
              { key: 'change', header: t('ops.change'), render: (r) => <Badge value={r.change} tone={r.change === 'UNCHANGED' ? 'neutral' : 'info'} /> },
              { key: 'summary', header: t('modeller.summary'), render: (r) => (r.summary?.length ? <ul>{r.summary.map((s, i) => <li key={i}>{s}</li>)}</ul> : '—') },
            ]}
          />
        </>
      )}
    </>
  );
}

function HistoryPanel({ packageId }: { packageId: string }) {
  const h = useAsync(() => api.packages.history(packageId), [packageId]);
  return (
    <>
      <ProblemAlert error={h.error} />
      <ol className="timeline">
        {h.data?.map((x, i) => (
          <li key={i}>
            <time dateTime={x.at}>{fmtDate(x.at)}</time> v{x.version} <Badge value={x.state} /> {x.by} {x.hash && <code className="hash small">{x.hash.slice(0, 12)}</code>}
          </li>
        ))}
      </ol>
    </>
  );
}

/** PF-23 play mode: deploy the current draft to the sandbox tenant and start an instance there. */
function PlayPanel({ pkg }: { pkg: ModelPackage }) {
  const { t } = useTranslation();
  const act = useAction();
  const [key, setKey] = useState('');
  const [vars, setVars] = useState('{}');
  const [msg, setMsg] = useState<string>();
  return (
    <>
      <Notice tone="warn">{t('modeller.playHint')}</Notice>
      <div className="toolbar">
        <button
          type="button"
          onClick={() =>
            void act
              .run((k) =>
                api.deployments.create(
                  `${pkg.key}-sandbox`,
                  pkg.artifacts.map((a) => ({
                    name: a.kind === 'FORM' ? `${a.name}.form` : a.kind === 'DMN' ? `${a.name}.dmn` : a.kind === 'CMMN' ? `${a.name}.cmmn` : `${a.name}.bpmn`,
                    content: a.content,
                    type: a.kind === 'FORM' ? 'application/json' : 'application/xml',
                  })),
                  k,
                ),
              )
              .then((d) => d && setMsg(t('modeller.sandboxDeployed', { id: d.id })))
          }
        >
          {t('modeller.deploySandbox')}
        </button>
      </div>
      <TextField label={t('modeller.processKey')} value={key} onChange={setKey} />
      <TextArea label={t('ops.variablesJson')} value={vars} onChange={setVars} />
      <button
        type="button"
        disabled={!key}
        onClick={() => {
          let v: Record<string, unknown> = {};
          try {
            v = JSON.parse(vars) as Record<string, unknown>;
          } catch {
            /* empty */
          }
          void act.run((k) => api.processes.start({ key, variables: v }, k)).then((i) => i && setMsg(t('modeller.sandboxStarted', { id: i.id })));
        }}
      >
        {t('modeller.startSandbox')}
      </button>
      {msg && <Notice tone="ok">{msg}</Notice>}
      <ProblemAlert error={act.error} />
      <p>
        <Link to="/ops/debugger">{t('nav.debugger')}</Link>
      </p>
    </>
  );
}

function GitPanel({ packageId }: { packageId: string }) {
  const { t } = useTranslation();
  const st = useAsync(() => api.packages.gitStatus(packageId), [packageId]);
  const act = useAction();
  const [cfg, setCfg] = useState({ provider: 'GITEA', remote: '', branch: 'main', credentialRef: '' });
  const [message, setMessage] = useState('');
  return (
    <>
      <ProblemAlert error={st.error ?? act.error} />
      {st.data && (
        <KeyValues
          items={[
            [t('modeller.remote'), st.data.remote],
            [t('modeller.branch'), st.data.branch],
            [t('modeller.ahead'), String(st.data.ahead)],
            [t('modeller.behind'), String(st.data.behind)],
            [t('modeller.lastSync'), fmtDate(st.data.lastSync)],
          ]}
        />
      )}
      {st.data?.conflicts.length ? (
        <Notice tone="bad">
          {t('modeller.conflicts')}
          <ul>
            {st.data.conflicts.map((c) => (
              <li key={c}>{c}</li>
            ))}
          </ul>
        </Notice>
      ) : null}
      {st.data?.remote && (
        <div className="toolbar">
          <button type="button" onClick={() => void act.run(() => api.packages.gitSync(packageId, 'pull')).then(() => st.reload())}>
            {t('modeller.pull')}
          </button>
          <TextField label={t('modeller.commitMessage')} value={message} onChange={setMessage} />
          <button type="button" disabled={!message} onClick={() => void act.run(() => api.packages.gitSync(packageId, 'push', message)).then(() => st.reload())}>
            {t('modeller.push')}
          </button>
        </div>
      )}
      <Section title={t('modeller.gitConfigure')}>
        <SelectField label={t('modeller.provider')} value={cfg.provider} onChange={(v) => setCfg({ ...cfg, provider: v })} options={['GITHUB', 'GITLAB', 'GITEA', 'BITBUCKET', 'AZURE', 'GENERIC'].map((v) => ({ value: v, label: v }))} />
        <TextField label={t('modeller.remote')} value={cfg.remote} onChange={(v) => setCfg({ ...cfg, remote: v })} />
        <TextField label={t('modeller.branch')} value={cfg.branch} onChange={(v) => setCfg({ ...cfg, branch: v })} />
        <TextField label={t('admin.authRef')} hint={t('admin.authRefHint')} value={cfg.credentialRef} onChange={(v) => setCfg({ ...cfg, credentialRef: v })} />
        <button type="button" disabled={!cfg.remote} onClick={() => void act.run(() => api.packages.gitConfigure(packageId, cfg).then(() => true)).then(() => st.reload())}>
          {t('common.save')}
        </button>
      </Section>
    </>
  );
}

function CommentsPanel({ packageId }: { packageId: string }) {
  const { t } = useTranslation();
  const list = useAsync(() => api.packages.comments(packageId), [packageId]);
  const [text, setText] = useState('');
  const [el, setEl] = useState('');
  const act = useAction();
  return (
    <>
      <ProblemAlert error={list.error ?? act.error} />
      <ol className="comments">
        {list.data?.map((c) => (
          <li key={c.id}>
            <div className="small muted">
              {c.author} · {fmtDate(c.createdAt)}
            </div>
            <p>{c.text}</p>
          </li>
        ))}
      </ol>
      <TextField label={t('modeller.element')} value={el} onChange={setEl} hint={t('modeller.elementHint')} />
      <TextArea label={t('task.addComment')} value={text} onChange={setText} />
      <button
        type="button"
        disabled={!text.trim()}
        onClick={() =>
          void act.run(() => api.packages.addComment(packageId, text, el || undefined)).then((r) => {
            if (r) {
              setText('');
              list.reload();
            }
          })
        }
      >
        {t('task.postComment')}
      </button>
    </>
  );
}

/** A07: review queue; the reviewer cannot be the author (K5). */
export function ReviewQueue() {
  const { t } = useTranslation();
  const { me } = useAuth();
  const list = usePaged((p) => api.packages.list({ ...p, state: 'IN_REVIEW' }), []);
  const [sel, setSel] = useState<ModelPackage | null>(null);
  const [decision, setDecision] = useState<'approve' | 'reject' | null>(null);
  const [eff, setEff] = useState('');
  const act = useAction();
  return (
    <>
      <PageHeader title={t('modeller.reviewTitle')} description={t('modeller.reviewDesc')} />
      <ProblemAlert error={list.error ?? act.error} onRetry={list.reload} />
      {list.data?.items.length === 0 && <Empty message={t('modeller.reviewEmpty')} />}
      {list.data && list.data.items.length > 0 && (
        <DataTable
          caption={t('modeller.reviewTitle')}
          captionHidden
          rows={list.data.items}
          rowKey={(r) => r.id}
          columns={[
            {
              key: 'name',
              header: t('common.name'),
              render: (r) => (
                <button type="button" className="link" onClick={() => setSel(r)}>
                  {r.name} v{r.version}
                </button>
              ),
            },
            { key: 'author', header: t('admin.author') },
            { key: 'updated', header: t('modeller.updated'), render: (r) => fmtDate(r.updatedAt) },
          ]}
        />
      )}
      {sel && (
        <Section
          title={`${sel.name} v${sel.version}`}
          actions={
            <>
              <Link to={`/modeller/${encodeURIComponent(sel.id)}`}>{t('common.open')}</Link>
              <button type="button" className="primary" disabled={sel.author === me?.userId} onClick={() => setDecision('approve')}>
                {t('admin.approve')}
              </button>
              <button type="button" disabled={sel.author === me?.userId} onClick={() => setDecision('reject')}>
                {t('admin.reject')}
              </button>
            </>
          }
        >
          {sel.author === me?.userId && <Notice tone="warn">{t('modeller.selfReview')}</Notice>}
          <DiffPanel packageId={sel.id} />
        </Section>
      )}
      <ReasonDialog
        open={decision !== null}
        title={decision ? t(`modeller.tr.${decision}`) : ''}
        busy={act.busy}
        error={act.error}
        onClose={() => setDecision(null)}
        onConfirm={(reason) =>
          void act.run((k) => api.packages.transition(sel!.id, decision!, { reason, effectiveFrom: eff ? new Date(eff).toISOString() : undefined }, k)).then((r) => {
            if (r) {
              setDecision(null);
              setSel(null);
              list.reload();
            }
          })
        }
      >
        {decision === 'approve' && <TextField label={t('admin.effectiveFrom')} type="datetime-local" value={eff} onChange={setEff} />}
      </ReasonDialog>
    </>
  );
}

/** EC-21: import assessment (LEAP-equivalent) for BPMN / CMMN / DMN from other engines. */
export function ImportAssessment() {
  const { t } = useTranslation();
  const act = useAction();
  const [rows, setRows] = useState<Awaited<ReturnType<typeof api.packages.assess>>>();
  return (
    <>
      <PageHeader title={t('modeller.assessTitle')} description={t('modeller.assessDesc')} />
      <div className="field">
        <label htmlFor="assess-files">{t('modeller.assessFiles')}</label>
        <input
          id="assess-files"
          type="file"
          multiple
          accept=".bpmn,.bpmn20.xml,.cmmn,.dmn,.xml"
          onChange={(e) => {
            const files = Array.from(e.target.files ?? []);
            e.target.value = '';
            if (files.length) void act.run(() => api.packages.assess(files)).then((r) => r && setRows(r));
          }}
        />
      </div>
      <ProblemAlert error={act.error} />
      {act.busy && <Loading />}
      {rows && (
        <>
          <Notice>
            {(['SUPPORTED', 'MAPPED', 'PATTERN', 'REJECTED'] as const).map((c) => `${t(`enum.${c}`)}: ${rows.filter((r) => r.classification === c).length}`).join(' · ')}
          </Notice>
          <DataTable
            caption={t('modeller.assessTitle')}
            captionHidden
            rows={rows.map((r, i) => ({ ...r, i }))}
            rowKey={(r) => String(r.i)}
            columns={[
              { key: 'file', header: t('task.fileName') },
              { key: 'element', header: t('modeller.element'), render: (r) => r.element ?? '—' },
              { key: 'construct', header: t('modeller.construct') },
              { key: 'classification', header: t('modeller.classification'), render: (r) => <Badge value={r.classification} tone={r.classification === 'REJECTED' ? 'bad' : r.classification === 'SUPPORTED' ? 'ok' : 'warn'} /> },
              { key: 'note', header: t('admin.note'), render: (r) => r.note ?? '—' },
            ]}
          />
        </>
      )}
    </>
  );
}
