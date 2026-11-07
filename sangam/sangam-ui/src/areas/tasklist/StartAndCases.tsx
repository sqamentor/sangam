// SPDX-License-Identifier: Apache-2.0
/**
 * R76 start catalogue (processes and cases) and case workspace (EC-01, PF-33, UP-09): CMMN stages, milestones,
 * discretionary items (manual start), documents, comments, participants and reactivation as a linked case (FC-59).
 */
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import type { CasePlanItem, FormSchema } from '../../api/types';
import { announce, useAction, useAsync, usePaged } from '../../lib/hooks';
import { fmtDate } from '../../i18n';
import { FormRenderer, validateForm, type FormValues } from '../../components/FormRenderer';
import { AddVariables } from '../../modules/operaton3';
import { ConversationPanel } from '../../modules/platform';
import { Badge, DataTable, Empty, KeyValues, Loading, Notice, PageHeader, Pagination, ProblemAlert, ReasonDialog, Section, TabPanel, Tabs, TextArea, TextField } from '../../components/ui';

export function StartCatalog() {
  const { t } = useTranslation();
  const cat = useAsync(() => api.catalogue.list(), []);
  const [chosen, setChosen] = useState<{ key: string; name: string; kind: 'PROCESS' | 'CASE'; formKey?: string } | null>(null);
  const [search, setSearch] = useState('');
  return (
    <>
      <PageHeader title={t('start.title')} description={t('start.description')} />
      <ProblemAlert error={cat.error} onRetry={cat.reload} />
      {cat.loading && <Loading />}
      {cat.data && cat.data.length === 0 && <Empty message={t('start.empty')} />}
      {!chosen && <TextField label={t('common.search')} value={search} onChange={setSearch} />}
      {!chosen && (
        <ul className="cards">
          {cat.data?.filter((c) => c.name.toLowerCase().includes(search.toLowerCase())).map((c) => (
            <li key={c.key} className="card">
              <h2>{c.name}</h2>
              <p className="muted">{c.description}</p>
              <Badge value={c.kind} tone="info" />
              <button type="button" className="primary" onClick={() => setChosen(c)}>
                {t('start.begin')}
              </button>
            </li>
          ))}
        </ul>
      )}
      {chosen && <StartForm item={chosen} onCancel={() => setChosen(null)} />}
    </>
  );
}

function StartForm({ item, onCancel }: { item: { key: string; name: string; kind: 'PROCESS' | 'CASE'; formKey?: string }; onCancel: () => void }) {
  const { t } = useTranslation();
  const nav = useNavigate();
  const form = useAsync(() => (item.formKey ? api.forms.get(item.formKey) : Promise.resolve<FormSchema>({ components: [] })), [item.formKey]);
  const [values, setValues] = useState<FormValues>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [businessKey, setBusinessKey] = useState('');
  const act = useAction();
  return (
    <Section title={item.name}>
      {form.loading && <Loading />}
      <ProblemAlert error={form.error ?? act.error} />
      <TextField label={t('start.businessKey')} hint={t('start.businessKeyHint')} value={businessKey} onChange={setBusinessKey} />
      {form.data && <FormRenderer schema={form.data} values={values} onChange={setValues} errors={errors} />}
      <AddVariables values={values} onChange={setValues} />
      <div className="toolbar">
        <button type="button" onClick={onCancel}>
          {t('common.cancel')}
        </button>
        <button
          type="button"
          className="primary"
          disabled={act.busy}
          onClick={() => {
            if (form.data) {
              const errs = validateForm(form.data, values, t);
              setErrors(errs);
              if (Object.keys(errs).length) return;
            }
            void act
              .run((k) =>
                item.kind === 'CASE'
                  ? api.cases.start({ key: item.key, businessKey: businessKey || undefined, variables: values }, k).then((c) => `/cases/${encodeURIComponent(c.id)}`)
                  : api.processes.start({ key: item.key, businessKey: businessKey || undefined, variables: values }, k).then(() => '/tasks'),
              )
              .then((to) => {
                if (to) {
                  announce(t('start.started'));
                  nav(to);
                }
              });
          }}
        >
          {t('start.submit')}
        </button>
      </div>
    </Section>
  );
}

export function CaseList() {
  const { t } = useTranslation();
  const [state, setState] = useState('ACTIVE');
  const list = usePaged((p) => api.cases.list({ ...p, state }), [state]);
  return (
    <>
      <PageHeader title={t('cases.title')} />
      <Tabs
        label={t('cases.state')}
        active={state}
        onChange={setState}
        tabs={['ACTIVE', 'COMPLETED', 'TERMINATED'].map((s) => ({ id: s, label: t(`enum.${s}`) }))}
      />
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.loading && !list.data && <Loading />}
      {list.data && list.data.items.length === 0 && <Empty message={t('cases.empty')} />}
      {list.data && list.data.items.length > 0 && (
        <>
          <DataTable
            caption={t('cases.title')}
            captionHidden
            rows={list.data.items}
            rowKey={(r) => r.id}
            columns={[
              { key: 'businessKey', header: t('cases.reference'), render: (r) => <Link to={`/cases/${encodeURIComponent(r.id)}`}>{r.businessKey ?? r.id}</Link> },
              { key: 'definitionKey', header: t('cases.type') },
              { key: 'state', header: t('cases.state'), render: (r) => <Badge value={r.state} /> },
              { key: 'created', header: t('cases.created'), render: (r) => fmtDate(r.created) },
            ]}
          />
          <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
        </>
      )}
    </>
  );
}

function PlanTree({ items, onAction }: { items: CasePlanItem[]; onAction: (p: CasePlanItem, a: 'manual-start' | 'complete' | 'disable' | 'reenable' | 'terminate') => void }) {
  const { t } = useTranslation();
  return (
    <ul className="plan-tree" role="tree">
      {items.map((p) => (
        <li key={p.id} role="treeitem" aria-expanded={p.children?.length ? true : undefined}>
          <span className={`plan-${p.type.toLowerCase()}`}>
            <strong>{p.name ?? p.definitionId}</strong> <span className="muted small">{t(`case.type.${p.type}`)}</span> <Badge value={p.state} />
            {p.required && <Badge value="REQUIRED" tone="info" />}
            {p.discretionary && <Badge value="DISCRETIONARY" tone="info" />}
            {p.repetition && <Badge value="REPEATABLE" tone="info" />}
          </span>{' '}
          <span className="toolbar inline">
            {p.state === 'ENABLED' && (
              <>
                <button type="button" className="link" onClick={() => onAction(p, 'manual-start')}>
                  {t('case.start')}
                </button>
                <button type="button" className="link" onClick={() => onAction(p, 'disable')}>
                  {t('case.disable')}
                </button>
              </>
            )}
            {p.state === 'DISABLED' && (
              <button type="button" className="link" onClick={() => onAction(p, 'reenable')}>
                {t('case.reenable')}
              </button>
            )}
            {p.state === 'ACTIVE' && p.type !== 'HUMAN_TASK' && (
              <button type="button" className="link" onClick={() => onAction(p, 'complete')}>
                {t('case.complete')}
              </button>
            )}
            {p.state === 'ACTIVE' && (
              <button type="button" className="link danger" onClick={() => onAction(p, 'terminate')}>
                {t('case.terminate')}
              </button>
            )}
          </span>
          {p.children && p.children.length > 0 && <PlanTree items={p.children} onAction={onAction} />}
        </li>
      ))}
    </ul>
  );
}

export function CaseWorkspace() {
  const { id = '' } = useParams();
  const { t } = useTranslation();
  const nav = useNavigate();
  const c = useAsync(() => api.cases.get(id), [id]);
  const docs = useAsync(() => api.cases.documents(id), [id]);
  const comments = useAsync(() => api.cases.comments(id), [id]);
  const people = useAsync(() => api.cases.participants(id), [id]);
  const [tab, setTab] = useState('plan');
  const [pending, setPending] = useState<{ p?: CasePlanItem; a: string } | null>(null);
  const [text, setText] = useState('');
  const act = useAction();
  if (c.loading && !c.data) return <Loading />;
  if (!c.data) return <ProblemAlert error={c.error} onRetry={c.reload} />;
  const milestones: CasePlanItem[] = [];
  const walk = (xs: CasePlanItem[]): void =>
    xs.forEach((x) => {
      if (x.type === 'MILESTONE') milestones.push(x);
      walk(x.children ?? []);
    });
  walk(c.data.planItems ?? []);
  return (
    <>
      <PageHeader
        title={`${t('cases.workspace')}: ${c.data.businessKey ?? c.data.id}`}
        actions={
          <>
            {c.data.businessKey && <Link to={`/audit/timeline/${encodeURIComponent(c.data.businessKey)}`}>{t('task.viewTimeline')}</Link>}
            {(c.data.state === 'COMPLETED' || c.data.state === 'CLOSED' || c.data.state === 'TERMINATED') && (
              <button type="button" onClick={() => setPending({ a: 'reactivate' })}>
                {t('case.reactivate')}
              </button>
            )}
          </>
        }
      />
      <KeyValues items={[[t('cases.type'), c.data.definitionKey], [t('cases.state'), <Badge key="s" value={c.data.state} />], [t('cases.created'), fmtDate(c.data.created)]]} />
      {milestones.length > 0 && (
        <ol className="milestones" aria-label={t('case.milestones')}>
          {milestones.map((m) => (
            <li key={m.id} className={m.state === 'COMPLETED' ? 'done' : ''}>
              {m.name ?? m.definitionId} <Badge value={m.state} />
            </li>
          ))}
        </ol>
      )}
      <Tabs
        label={t('cases.workspace')}
        active={tab}
        onChange={setTab}
        tabs={[
          { id: 'plan', label: t('case.plan') },
          { id: 'docs', label: t('case.documents') },
          { id: 'comments', label: t('task.tab.comments') },
          { id: 'people', label: t('case.participants') },
          { id: 'conv', label: t('mod.channels.conversations') },
        ]}
      />
      <ProblemAlert error={act.error} />
      <TabPanel id="plan" active={tab}>
        <PlanTree items={c.data.planItems ?? []} onAction={(p, a) => setPending({ p, a })} />
      </TabPanel>
      <TabPanel id="docs" active={tab}>
        <ProblemAlert error={docs.error} />
        {docs.data?.length === 0 && <Empty message={t('task.noAttachments')} />}
        <ul>
          {docs.data?.map((d) => (
            <li key={d.id}>
              <button type="button" className="link" onClick={() => void api.documents.content(d.id, d.name)}>
                {d.name}
              </button>{' '}
              <span className="muted small">
                v{d.version} · {fmtDate(d.uploadedAt)} · <code className="hash">{d.sha256.slice(0, 10)}</code>
              </span>
            </li>
          ))}
        </ul>
      </TabPanel>
      <TabPanel id="comments" active={tab}>
        <ol className="comments">
          {comments.data?.map((x) => (
            <li key={x.id}>
              <div className="small muted">
                {x.author} · {fmtDate(x.createdAt)}
              </div>
              <p>{x.text}</p>
            </li>
          ))}
        </ol>
        <TextArea label={t('task.addComment')} value={text} onChange={setText} />
        <button
          type="button"
          disabled={!text.trim() || act.busy}
          onClick={() =>
            void act.run(() => api.cases.addComment(id, text)).then((r) => {
              if (r) {
                setText('');
                comments.reload();
              }
            })
          }
        >
          {t('task.postComment')}
        </button>
      </TabPanel>
      <TabPanel id="conv" active={tab}>
        <ConversationPanel caseId={id} />
      </TabPanel>
      <TabPanel id="people" active={tab}>
        <ul>
          {people.data?.map((p) => (
            <li key={p.id}>
              {p.name} — {p.role} {p.external && <Badge value="EXTERNAL" tone="info" />}
            </li>
          ))}
        </ul>
      </TabPanel>
      <ReasonDialog
        open={pending !== null}
        title={pending ? (pending.a === 'reactivate' ? t('case.reactivate') : t(`case.actionTitle.${pending.a}`)) : ''}
        busy={act.busy}
        error={act.error}
        onClose={() => setPending(null)}
        onConfirm={(reason) => {
          if (!pending) return;
          if (pending.a === 'reactivate') {
            void act.run((k) => api.cases.reactivate(id, reason, k)).then((n) => {
              if (n) {
                setPending(null);
                nav(`/cases/${encodeURIComponent(n.id)}`);
              }
            });
          } else {
            void act
              .run(() => api.cases.planItemAction(id, pending.p!.id, pending.a as 'manual-start', reason).then(() => true))
              .then((ok) => {
                if (ok) {
                  setPending(null);
                  c.reload();
                }
              });
          }
        }}
      >
        {pending?.a === 'reactivate' && <Notice>{t('case.reactivateHint')}</Notice>}
      </ReasonDialog>
    </>
  );
}
