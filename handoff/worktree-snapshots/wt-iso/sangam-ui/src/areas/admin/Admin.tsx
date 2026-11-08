// SPDX-License-Identifier: Apache-2.0
/**
 * Admin (A01–A11, IX.5, VI.4, R67, R71, R79, R81, R82):
 * tenants (A01, PF-49, EC-17), org hierarchy (A02), seats, effective-dated postings / additional charge / leave
 * substitute with impact preview (A03), delegation-of-powers DMN tables with gap/overlap check, tests and four-eyes
 * approval (A04), allow/deny policies with an "explain" tool (A08, deny wins), SoD exceptions, users and groups,
 * identity providers incl. LDAP sync (A05, R67), service principals (I03), and registry screens: service registry
 * (R71, EC-07), SLA models (R79, EC-08), routing strategies (R82), notification and document templates (A09, EC-18),
 * conversational channels (R81, EC-15), AI-agent definitions (R72, EC-14), retention classes and legal hold (A11).
 */
import { useState, type KeyboardEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import type { Assignment, AssignmentImpact, DelegationCheckResult, OrgUnit, Policy, SeatHoldingKind, Tenant } from '../../api/types';
import { announce, useAction, useAsync, usePaged } from '../../lib/hooks';
import { fmtDate } from '../../i18n';
import { CrudPage } from '../../components/CrudPage';
import { useAuth } from '../../auth/AuthProvider';
import { DmnEditor, EMPTY_DMN, type EditorHandle } from '../../components/Editors';
import {
  Badge,
  Checkbox,
  CopyText,
  DataTable,
  Dialog,
  Empty,
  KeyValues,
  Loading,
  Notice,
  PageHeader,
  Pagination,
  ProblemAlert,
  ReasonDialog,
  SearchBox,
  Section,
  SelectField,
  TextArea,
  TextField,
} from '../../components/ui';

// ------------------------------------------------------------------ A01 tenants

export function Tenants() {
  const { t } = useTranslation();
  return (
    <CrudPage<Tenant>
      title={t('admin.tenantsTitle')}
      description={t('admin.tenantsDesc')}
      path="/admin/tenants"
      newDefaults={{ status: 'DRAFT', languages: ['en', 'hi'] }}
      columns={[
        { key: 'id', label: 'admin.tenantId' },
        { key: 'name', label: 'common.name' },
        { key: 'status', label: 'admin.status', render: (r) => <Badge value={r.status} /> },
        { key: 'hostingProfile', label: 'admin.hosting' },
        { key: 'languages', label: 'admin.languages', render: (r) => r.languages?.join(', ') },
      ]}
      fields={[
        { key: 'id', type: 'textfield', label: 'admin.tenantId', validate: { required: true, pattern: '[a-z0-9-]{2,40}' } },
        { key: 'name', type: 'textfield', label: 'common.name', validate: { required: true } },
        { key: 'hostingProfile', type: 'select', label: 'admin.hosting', values: ['SHARED', 'DEDICATED_SCHEMA', 'DEDICATED_DB'].map((v) => ({ value: v, label: `enum.${v}` })) },
        { key: 'database', type: 'select', label: 'admin.database', values: ['POSTGRES', 'ORACLE', 'SQLSERVER', 'MYSQL'].map((v) => ({ value: v, label: `enum.${v}` })) },
        { key: 'languages', type: 'checklist', label: 'admin.languages', values: [{ value: 'en', label: 'lang.en' }, { value: 'hi', label: 'lang.hi' }] },
        { key: 'calendar', type: 'textfield', label: 'admin.calendar' },
        { key: 'admins', type: 'textarea', label: 'admin.tenantAdmins', description: 'admin.tenantAdminsHint' },
      ]}
      rowActions={[
        { id: 'validate', label: 'admin.validate' },
        { id: 'activate', label: 'admin.activate', reason: true, visible: (r) => r.status !== 'ACTIVE' },
        { id: 'suspend', label: 'admin.suspendTenant', reason: true, danger: true, visible: (r) => r.status === 'ACTIVE' },
      ]}
    />
  );
}

// ------------------------------------------------------------------ A02 org hierarchy

const LEVELS = ['MINISTRY', 'DEPARTMENT', 'DIRECTORATE', 'REGION', 'DISTRICT', 'OFFICE', 'SECTION'] as const;

function OrgTreeNode({
  node,
  level,
  selected,
  onSelect,
  expanded,
  toggle,
}: {
  node: OrgUnit;
  level: number;
  selected?: string;
  onSelect: (n: OrgUnit) => void;
  expanded: Set<string>;
  toggle: (id: string) => void;
}) {
  const { i18n } = useTranslation();
  const has = (node.children?.length ?? 0) > 0;
  const open = expanded.has(node.id);
  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'ArrowRight' && has && !open) toggle(node.id);
    else if (e.key === 'ArrowLeft' && open) toggle(node.id);
    else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      onSelect(node);
    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const items = Array.from(document.querySelectorAll<HTMLElement>('[role="tree"] [role="treeitem"] > .tree-label'));
      const idx = items.indexOf(e.currentTarget as HTMLElement);
      items[e.key === 'ArrowDown' ? Math.min(items.length - 1, idx + 1) : Math.max(0, idx - 1)]?.focus();
    }
  };
  return (
    <li role="treeitem" aria-expanded={has ? open : undefined} aria-selected={selected === node.id} aria-level={level}>
      <span className={`tree-label${node.active ? '' : ' muted'}`} tabIndex={selected === node.id || (!selected && level === 1) ? 0 : -1} onKeyDown={onKey} onClick={() => onSelect(node)}>
        {has && (
          <button type="button" className="icon" tabIndex={-1} aria-hidden="true" onClick={(e) => (e.stopPropagation(), toggle(node.id))}>
            {open ? '▾' : '▸'}
          </button>
        )}
        {i18n.language === 'hi' && node.nameHi ? node.nameHi : node.name} <span className="small muted">{node.level}</span>
      </span>
      {has && open && (
        <ul role="group">
          {node.children!.map((c) => (
            <OrgTreeNode key={c.id} node={c} level={level + 1} selected={selected} onSelect={onSelect} expanded={expanded} toggle={toggle} />
          ))}
        </ul>
      )}
    </li>
  );
}

export function OrgHierarchy() {
  const { t } = useTranslation();
  const [asOf, setAsOf] = useState('');
  const tree = useAsync(() => api.admin.orgTree(asOf || undefined), [asOf]);
  const [sel, setSel] = useState<OrgUnit | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [edit, setEdit] = useState<Partial<OrgUnit> | null>(null);
  const [move, setMove] = useState<{ parentId: string; from: string } | null>(null);
  const [imp, setImp] = useState<{ created: number; updated: number; errors: string[] }>();
  const act = useAction();
  const seats = usePaged((p) => api.admin.seats({ ...p, orgUnitId: sel?.id }), [sel?.id]);
  const toggle = (id: string) => {
    const n = new Set(expanded);
    if (n.has(id)) n.delete(id);
    else n.add(id);
    setExpanded(n);
  };
  return (
    <>
      <PageHeader
        title={t('admin.orgTitle')}
        actions={
          <>
            <button type="button" onClick={() => setEdit({ level: 'DEPARTMENT', active: true, validFrom: new Date().toISOString().slice(0, 10) })}>
              {t('admin.addRoot')}
            </button>
            <label className="button">
              {t('admin.importCsv')}
              <input
                type="file"
                accept=".csv,text/csv"
                className="sr-only"
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  e.target.value = '';
                  if (f) void act.run(() => api.admin.importOrg(f)).then((r) => r && (setImp(r), tree.reload()));
                }}
              />
            </label>
          </>
        }
      />
      <TextField label={t('admin.asOf')} type="date" value={asOf} onChange={setAsOf} hint={t('admin.asOfHint')} />
      <ProblemAlert error={tree.error ?? act.error} onRetry={tree.reload} />
      {imp && (
        <Notice tone={imp.errors.length ? 'warn' : 'ok'}>
          {t('admin.importResult', { c: imp.created, u: imp.updated })}
          {imp.errors.length > 0 && (
            <ul>
              {imp.errors.map((e, i) => (
                <li key={i}>{e}</li>
              ))}
            </ul>
          )}
        </Notice>
      )}
      <div className="grid-2">
        <Section title={t('admin.hierarchy')}>
          {tree.loading && <Loading />}
          {tree.data && tree.data.length === 0 && <Empty message={t('admin.orgEmpty')} />}
          <ul role="tree" aria-label={t('admin.hierarchy')} className="tree">
            {tree.data?.map((n) => <OrgTreeNode key={n.id} node={n} level={1} selected={sel?.id} onSelect={setSel} expanded={expanded} toggle={toggle} />)}
          </ul>
        </Section>
        <Section title={sel ? sel.name : t('admin.selectUnit')}>
          {sel && (
            <>
              <KeyValues
                items={[
                  [t('admin.level'), sel.level],
                  [t('admin.validFrom'), fmtDate(sel.validFrom, false)],
                  [t('admin.validTo'), sel.validTo ? fmtDate(sel.validTo, false) : '—'],
                  [t('admin.status'), <Badge key="a" value={sel.active ? 'ACTIVE' : 'INACTIVE'} />],
                ]}
              />
              <div className="toolbar">
                <button type="button" onClick={() => setEdit({ ...sel, children: undefined })}>
                  {t('common.edit')}
                </button>
                <button
                  type="button"
                  onClick={() =>
                    setEdit({ parentId: sel.id, level: LEVELS[Math.min(LEVELS.length - 1, LEVELS.indexOf(sel.level) + 1)], active: true, validFrom: new Date().toISOString().slice(0, 10) })
                  }
                >
                  {t('admin.addChild')}
                </button>
                <button type="button" onClick={() => setMove({ parentId: '', from: new Date().toISOString().slice(0, 10) })}>
                  {t('admin.move')}
                </button>
              </div>
              <h3>{t('admin.seatsHere')}</h3>
              {seats.data && (
                <ul>
                  {seats.data.items.map((s) => (
                    <li key={s.id}>
                      {s.designation} — {s.holderName ?? t('admin.vacant')} {!s.active && <Badge value="INACTIVE" />}
                    </li>
                  ))}
                </ul>
              )}
              <Link to="/admin/seats">{t('nav.seats')}</Link>
            </>
          )}
        </Section>
      </div>
      <Dialog
        open={edit !== null}
        title={edit?.id ? t('common.edit') : t('admin.addUnit')}
        onClose={() => setEdit(null)}
        footer={
          <button
            type="button"
            className="primary"
            disabled={!edit?.name || act.busy}
            onClick={() =>
              void act.run(() => api.admin.saveOrgUnit(edit!)).then((r) => {
                if (r) {
                  setEdit(null);
                  tree.reload();
                }
              })
            }
          >
            {t('common.save')}
          </button>
        }
      >
        {edit && (
          <>
            <TextField label={t('common.name')} required value={edit.name ?? ''} onChange={(v) => setEdit({ ...edit, name: v })} />
            <TextField label={t('admin.nameHi')} lang="hi" value={edit.nameHi ?? ''} onChange={(v) => setEdit({ ...edit, nameHi: v })} />
            <SelectField label={t('admin.level')} value={edit.level ?? 'OFFICE'} onChange={(v) => setEdit({ ...edit, level: v as OrgUnit['level'] })} options={LEVELS.map((l) => ({ value: l, label: t(`enum.${l}`) }))} />
            <TextField label={t('admin.validFrom')} type="date" required value={(edit.validFrom ?? '').slice(0, 10)} onChange={(v) => setEdit({ ...edit, validFrom: v })} />
            <TextField label={t('admin.validTo')} type="date" value={(edit.validTo ?? '').slice(0, 10)} onChange={(v) => setEdit({ ...edit, validTo: v || undefined })} />
            <Checkbox label={t('admin.activeFlag')} hint={t('admin.noDeleteHint')} checked={edit.active ?? true} onChange={(v) => setEdit({ ...edit, active: v })} />
          </>
        )}
      </Dialog>
      <ReasonDialog
        open={move !== null}
        title={t('admin.move')}
        busy={act.busy}
        error={act.error}
        onClose={() => setMove(null)}
        onConfirm={(reason) =>
          void act.run(() => api.admin.moveOrgUnit(sel!.id, move!.parentId, move!.from, reason).then(() => true)).then((ok) => {
            if (ok) {
              setMove(null);
              tree.reload();
            }
          })
        }
      >
        {move && (
          <>
            <TextField label={t('admin.newParent')} required value={move.parentId} onChange={(v) => setMove({ ...move, parentId: v })} />
            <TextField label={t('admin.effectiveFrom')} type="date" required value={move.from} onChange={(v) => setMove({ ...move, from: v })} />
          </>
        )}
      </ReasonDialog>
    </>
  );
}

// ------------------------------------------------------------------ seats

export function Seats() {
  const { t } = useTranslation();
  return (
    <CrudPage
      title={t('admin.seatsTitle')}
      description={t('admin.seatsDesc')}
      path="/admin/seats"
      newDefaults={{ active: true }}
      columns={[
        { key: 'designation', label: 'admin.designation' },
        { key: 'orgUnitName', label: 'admin.orgUnit' },
        { key: 'holderName', label: 'admin.holder', render: (r) => r.holderName ?? t('admin.vacant') },
        { key: 'active', label: 'admin.status', render: (r) => <Badge value={r.active ? 'ACTIVE' : 'INACTIVE'} /> },
      ]}
      fields={[
        { key: 'designation', type: 'textfield', label: 'admin.designation', validate: { required: true } },
        { key: 'designationHi', type: 'textfield', label: 'admin.designationHi' },
        { key: 'orgUnitId', type: 'textfield', label: 'admin.orgUnitId', validate: { required: true } },
        { key: 'validFrom', type: 'datetime', subtype: 'date', label: 'admin.validFrom', validate: { required: true } },
        { key: 'validTo', type: 'datetime', subtype: 'date', label: 'admin.validTo' },
      ]}
      rowActions={[{ id: 'deactivate', label: 'admin.deactivate', reason: true, danger: true, visible: (r) => !!r.active }]}
    />
  );
}

// ------------------------------------------------------------------ A03 assignments

export function Assignments() {
  const { t } = useTranslation();
  const [kind, setKind] = useState<SeatHoldingKind | ''>('');
  const [asOf, setAsOf] = useState('');
  const [q, setQ] = useState('');
  const list = usePaged((p) => api.admin.assignments({ ...p, kind: kind || undefined, asOf: asOf || undefined, q }), [kind, asOf, q]);
  const [form, setForm] = useState<Partial<Assignment> | null>(null);
  const [impact, setImpact] = useState<AssignmentImpact>();
  const [end, setEnd] = useState<Assignment | null>(null);
  const [endDate, setEndDate] = useState('');
  const [decide, setDecide] = useState<{ a: Assignment; approve: boolean } | null>(null);
  const act = useAction();
  return (
    <>
      <PageHeader
        title={t('admin.assignmentsTitle')}
        description={t('admin.assignmentsDesc')}
        actions={
          <button
            type="button"
            className="primary"
            onClick={() => {
              setForm({ kind: 'SUBSTANTIVE', validFrom: new Date().toISOString().slice(0, 10) });
              setImpact(undefined);
            }}
          >
            {t('admin.newAssignment')}
          </button>
        }
      />
      <div className="filters">
        <SearchBox label={t('common.search')} value={q} onChange={setQ} />
        <SelectField label={t('admin.kind')} value={kind} onChange={(v) => setKind(v as SeatHoldingKind)} placeholder={t('common.all')} options={(['SUBSTANTIVE', 'ADDITIONAL_CHARGE', 'LEAVE_SUBSTITUTE'] as const).map((k) => ({ value: k, label: t(`seat.kind.${k}`) }))} />
        <TextField label={t('admin.asOf')} type="date" value={asOf} onChange={setAsOf} />
      </div>
      <ProblemAlert error={list.error ?? act.error} onRetry={list.reload} />
      {list.data && list.data.items.length === 0 && <Empty message={t('common.emptyFiltered')} />}
      {list.data && list.data.items.length > 0 && (
        <DataTable
          caption={t('admin.assignmentsTitle')}
          captionHidden
          rows={list.data.items}
          rowKey={(r) => r.id}
          columns={[
            { key: 'personName', header: t('admin.person'), render: (r) => r.personName ?? r.personId },
            { key: 'seatName', header: t('admin.seat'), render: (r) => r.seatName ?? r.seatId },
            { key: 'kind', header: t('admin.kind'), render: (r) => t(`seat.kind.${r.kind}`) },
            { key: 'actingFor', header: t('admin.actingFor'), render: (r) => r.actingForPersonName ?? '—' },
            { key: 'from', header: t('admin.validFrom'), render: (r) => fmtDate(r.validFrom, false) },
            { key: 'to', header: t('admin.validTo'), render: (r) => (r.validTo ? fmtDate(r.validTo, false) : '—') },
            { key: 'status', header: t('admin.status'), render: (r) => <Badge value={r.status} /> },
            {
              key: 'a',
              header: t('common.actions'),
              render: (r) => (
                <span className="toolbar inline">
                  {r.status === 'PENDING' && (
                    <>
                      <button type="button" className="link" onClick={() => setDecide({ a: r, approve: true })}>
                        {t('admin.approve')}
                      </button>
                      <button type="button" className="link danger" onClick={() => setDecide({ a: r, approve: false })}>
                        {t('admin.reject')}
                      </button>
                    </>
                  )}
                  {r.status === 'ACTIVE' && (
                    <button type="button" className="link" onClick={() => (setEnd(r), setEndDate(new Date().toISOString().slice(0, 10)))}>
                      {t('admin.relieve')}
                    </button>
                  )}
                </span>
              ),
            },
          ]}
        />
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
      <Dialog
        open={form !== null}
        wide
        title={t('admin.newAssignment')}
        onClose={() => setForm(null)}
        footer={
          <>
            <button type="button" disabled={!form?.personId || !form?.seatId} onClick={() => void act.run(() => api.admin.assignmentImpact(form!)).then((i) => i && setImpact(i))}>
              {t('admin.previewImpact')}
            </button>
            <button
              type="button"
              className="primary"
              disabled={!impact || act.busy || !form?.reason}
              onClick={() =>
                void act.run((k) => api.admin.saveAssignment(form!, k)).then((r) => {
                  if (r) {
                    setForm(null);
                    announce(t('common.saved'));
                    list.reload();
                  }
                })
              }
            >
              {t('common.save')}
            </button>
          </>
        }
      >
        {form && (
          <>
            <SelectField
              label={t('admin.kind')}
              value={form.kind ?? 'SUBSTANTIVE'}
              onChange={(v) => setForm({ ...form, kind: v as SeatHoldingKind })}
              options={(['SUBSTANTIVE', 'ADDITIONAL_CHARGE', 'LEAVE_SUBSTITUTE'] as const).map((k) => ({ value: k, label: t(`seat.kind.${k}`) }))}
            />
            <TextField label={t('admin.personId')} required value={form.personId ?? ''} onChange={(v) => (setForm({ ...form, personId: v }), setImpact(undefined))} />
            <TextField label={t('admin.seatId')} required value={form.seatId ?? ''} onChange={(v) => (setForm({ ...form, seatId: v }), setImpact(undefined))} />
            <TextField label={t('admin.validFrom')} type="date" required value={(form.validFrom ?? '').slice(0, 10)} onChange={(v) => setForm({ ...form, validFrom: v })} />
            <TextField label={t('admin.validTo')} type="date" required={form.kind !== 'SUBSTANTIVE'} value={(form.validTo ?? '').slice(0, 10)} onChange={(v) => setForm({ ...form, validTo: v || undefined })} />
            <TextArea label={t('common.reason')} required value={form.reason ?? ''} onChange={(v) => setForm({ ...form, reason: v })} />
            {form.kind !== 'SUBSTANTIVE' && <Notice>{t('admin.actedForHint')}</Notice>}
            {impact && (
              <Section title={t('admin.impact')}>
                <ul>
                  {impact.summary.map((s, i) => (
                    <li key={i}>{s}</li>
                  ))}
                  <li>{t('admin.openTasksAffected', { n: impact.openTasks })}</li>
                </ul>
                {impact.sodConflicts.length > 0 && (
                  <Notice tone="warn">
                    {t('admin.sodConflicts', { n: impact.sodConflicts.length })}
                    <ul>
                      {impact.sodConflicts.map((c) => (
                        <li key={c.taskId}>
                          <Link to={`/tasks/${encodeURIComponent(c.taskId)}`}>{c.taskId}</Link>: {c.reason}
                        </li>
                      ))}
                    </ul>
                  </Notice>
                )}
              </Section>
            )}
          </>
        )}
        <ProblemAlert error={act.error} />
      </Dialog>
      <ReasonDialog
        open={end !== null}
        title={t('admin.relieve')}
        busy={act.busy}
        error={act.error}
        onClose={() => setEnd(null)}
        onConfirm={(reason) =>
          void act.run(() => api.admin.endAssignment(end!.id, endDate, reason).then(() => true)).then((ok) => {
            if (ok) {
              setEnd(null);
              list.reload();
            }
          })
        }
      >
        <TextField label={t('admin.validTo')} type="date" required value={endDate} onChange={setEndDate} />
      </ReasonDialog>
      <ReasonDialog
        open={decide !== null}
        title={decide?.approve ? t('admin.approve') : t('admin.reject')}
        busy={act.busy}
        error={act.error}
        onClose={() => setDecide(null)}
        onConfirm={(reason) =>
          void act.run(() => api.admin.approveAssignment(decide!.a.id, decide!.approve, reason).then(() => true)).then((ok) => {
            if (ok) {
              setDecide(null);
              list.reload();
            }
          })
        }
      >
        <Notice>{t('admin.noSelfApproval')}</Notice>
      </ReasonDialog>
    </>
  );
}

// ------------------------------------------------------------------ A04 delegation-of-powers tables

export function DelegationTables() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const list = usePaged((p) => api.admin.delegationTables(p), []);
  const act = useAction();
  return (
    <>
      <PageHeader
        title={t('admin.delegTitle')}
        description={t('admin.delegDesc')}
        actions={
          <button
            type="button"
            className="primary"
            onClick={() =>
              void act.run(() => api.admin.saveDelegationTable({ key: `powers-${Date.now()}`, name: t('admin.newTable'), dmnXml: EMPTY_DMN })).then((d) => d && nav(`/admin/delegations/${encodeURIComponent(d.id)}`))
            }
          >
            {t('common.new')}
          </button>
        }
      />
      <ProblemAlert error={list.error ?? act.error} onRetry={list.reload} />
      {list.data && (
        <DataTable
          caption={t('admin.delegTitle')}
          captionHidden
          rows={list.data.items}
          rowKey={(r) => r.id}
          columns={[
            { key: 'name', header: t('common.name'), render: (r) => <Link to={`/admin/delegations/${encodeURIComponent(r.id)}`}>{r.name}</Link> },
            { key: 'key', header: t('ops.key') },
            { key: 'version', header: t('ops.version') },
            { key: 'state', header: t('admin.status'), render: (r) => <Badge value={r.state} /> },
            { key: 'eff', header: t('admin.effectiveFrom'), render: (r) => fmtDate(r.effectiveFrom, false) },
            { key: 'author', header: t('admin.author') },
            { key: 'approver', header: t('admin.approver'), render: (r) => r.approver ?? '—' },
          ]}
        />
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
    </>
  );
}

export function DelegationTableEditor() {
  const { id = '' } = useParams();
  const { t } = useTranslation();
  const { me } = useAuth();
  const d = useAsync(() => api.admin.delegationTable(id), [id]);
  const [handle, setHandle] = useState<EditorHandle>();
  const [dirty, setDirty] = useState(false);
  const [check, setCheck] = useState<DelegationCheckResult>();
  const [tests, setTests] = useState('');
  const [tr, setTr] = useState<'submit' | 'approve' | 'reject' | null>(null);
  const [eff, setEff] = useState('');
  const act = useAction();
  if (d.loading && !d.data) return <Loading />;
  if (!d.data) return <ProblemAlert error={d.error} onRetry={d.reload} />;
  const x = d.data;
  const editable = x.state === 'DRAFT' || x.state === 'REJECTED';
  const save = async () => {
    const xml = await handle!.getContent();
    let tc = x.testCases;
    try {
      if (tests.trim()) tc = JSON.parse(tests) as typeof tc;
    } catch {
      /* keep previous */
    }
    const r = await act.run(() => api.admin.saveDelegationTable({ ...x, dmnXml: xml, testCases: tc }));
    if (r) {
      setDirty(false);
      d.setData(r);
      announce(t('common.saved'));
    }
  };
  return (
    <>
      <PageHeader
        title={`${x.name} v${x.version}`}
        description={t('admin.delegEditorDesc')}
        actions={
          <>
            {editable && (
              <button type="button" disabled={!dirty || act.busy} onClick={() => void save()}>
                {t('common.save')}
              </button>
            )}
            <button type="button" onClick={() => void act.run(() => api.admin.checkDelegationTable(id)).then((c) => c && setCheck(c))}>
              {t('admin.checkTable')}
            </button>
            {editable && (
              <button type="button" className="primary" disabled={dirty} onClick={() => setTr('submit')}>
                {t('admin.submitForApproval')}
              </button>
            )}
            {x.state === 'IN_REVIEW' && x.author !== me?.userId && (
              <>
                <button type="button" className="primary" onClick={() => setTr('approve')}>
                  {t('admin.approve')}
                </button>
                <button type="button" onClick={() => setTr('reject')}>
                  {t('admin.reject')}
                </button>
              </>
            )}
          </>
        }
      />
      <KeyValues items={[[t('admin.status'), <Badge key="s" value={x.state} />], [t('admin.author'), x.author], [t('admin.approver'), x.approver], [t('admin.effectiveFrom'), fmtDate(x.effectiveFrom)]]} />
      {x.state === 'IN_REVIEW' && x.author === me?.userId && <Notice tone="warn">{t('admin.authorCannotApprove')}</Notice>}
      <ProblemAlert error={act.error} />
      <DmnEditor xml={x.dmnXml} onReady={setHandle} onChange={() => setDirty(true)} />
      {check && (
        <Section title={t('admin.checkResult')}>
          <KeyValues items={[[t('admin.hitPolicy'), check.hitPolicy]]} />
          {check.gaps.length === 0 && check.overlaps.length === 0 ? <Notice tone="ok">{t('admin.noGaps')}</Notice> : null}
          {check.gaps.length > 0 && (
            <Notice tone="bad">
              {t('admin.gaps')}
              <ul>
                {check.gaps.map((g, i) => (
                  <li key={i}>{g}</li>
                ))}
              </ul>
            </Notice>
          )}
          {check.overlaps.length > 0 && (
            <Notice tone="warn">
              {t('admin.overlaps')}
              <ul>
                {check.overlaps.map((g, i) => (
                  <li key={i}>{g}</li>
                ))}
              </ul>
            </Notice>
          )}
          <ul>
            {check.tests.map((tc) => (
              <li key={tc.id}>
                {tc.id}: <Badge value={tc.passed ? 'PASS' : 'FAIL'} /> {tc.actual && <code>{JSON.stringify(tc.actual)}</code>}
              </li>
            ))}
          </ul>
        </Section>
      )}
      <Section title={t('admin.testCases')}>
        <TextArea label={t('admin.testCasesJson')} hint={t('admin.testCasesHint')} value={tests || JSON.stringify(x.testCases ?? [], null, 2)} onChange={(v) => (setTests(v), setDirty(true))} disabled={!editable} />
      </Section>
      <ReasonDialog
        open={tr !== null}
        title={tr ? t(`admin.tr.${tr}`) : ''}
        busy={act.busy}
        error={act.error}
        onClose={() => setTr(null)}
        onConfirm={(reason) =>
          void act.run(() => api.admin.transitionDelegationTable(id, tr!, { reason, effectiveFrom: eff ? new Date(eff).toISOString() : undefined })).then((r) => {
            if (r) {
              setTr(null);
              d.setData(r);
            }
          })
        }
      >
        {tr === 'approve' && (
          <>
            <TextField label={t('admin.effectiveFrom')} type="datetime-local" hint={t('admin.effectiveHint')} value={eff} onChange={setEff} />
          </>
        )}
      </ReasonDialog>
    </>
  );
}


// ------------------------------------------------------------------ A08 policies

const PERMS = ['READ', 'READ_TASK', 'READ_HISTORY', 'READ_VARIABLE', 'READ_SENSITIVE', 'COMPLETE', 'CLAIM', 'START', 'CANCEL_INSTANCE', 'DELETE_INSTANCE', 'MODIFY', 'MIGRATE', 'RETRY', 'RESOLVE_RECON', 'REPAIR_OUTBOX', 'DEPLOY', 'ERASE_DATA', 'ADMIN'];

export function Policies() {
  const { t } = useTranslation();
  const [effect, setEffect] = useState('');
  const [rt, setRt] = useState('');
  const list = usePaged((p) => api.admin.policies({ ...p, effect: effect || undefined, resourceType: rt || undefined }), [effect, rt]);
  const [edit, setEdit] = useState<Partial<Policy> | null>(null);
  const [del, setDel] = useState<Policy | null>(null);
  const [ex, setEx] = useState({ principalId: '', action: 'READ', resourceType: 'PROCESS_INSTANCE', resourceId: '*' });
  const [exRes, setExRes] = useState<{ allowed: boolean; matched: { policyId: string; effect: 'ALLOW' | 'DENY'; scope: string }[]; policyVersion: number }>();
  const act = useAction();
  return (
    <>
      <PageHeader
        title={t('admin.policiesTitle')}
        description={t('admin.policiesDesc')}
        actions={
          <button type="button" className="primary" onClick={() => setEdit({ effect: 'ALLOW', principalType: 'GROUP', resourceId: '*', permissions: ['READ'] })}>
            {t('common.new')}
          </button>
        }
      />
      <Notice>{t('admin.denyWins')}</Notice>
      <div className="filters">
        <SelectField label={t('admin.effect')} value={effect} onChange={setEffect} placeholder={t('common.all')} options={['ALLOW', 'DENY'].map((v) => ({ value: v, label: t(`enum.${v}`) }))} />
        <TextField label={t('admin.resourceType')} value={rt} onChange={setRt} />
      </div>
      <ProblemAlert error={list.error ?? act.error} onRetry={list.reload} />
      {list.data && (
        <DataTable
          caption={t('admin.policiesTitle')}
          captionHidden
          rows={list.data.items}
          rowKey={(r) => r.id}
          columns={[
            { key: 'effect', header: t('admin.effect'), render: (r) => <Badge value={r.effect} /> },
            { key: 'principal', header: t('admin.principal'), render: (r) => `${r.principalType}:${r.principalId}` },
            { key: 'resource', header: t('admin.resource'), render: (r) => `${r.resourceType}/${r.resourceId}` },
            { key: 'perms', header: t('admin.permissions'), render: (r) => r.permissions.join(', ') },
            { key: 'condition', header: t('admin.condition'), render: (r) => (r.condition ? <code>{r.condition}</code> : '—') },
            { key: 'validTo', header: t('admin.validTo'), render: (r) => fmtDate(r.validTo, false) },
            { key: 'v', header: t('admin.policyVersion'), render: (r) => String(r.policyVersion ?? '—') },
            {
              key: 'a',
              header: t('common.actions'),
              render: (r) => (
                <span className="toolbar inline">
                  <button type="button" className="link" onClick={() => setEdit(r)}>
                    {t('common.edit')}
                  </button>
                  <button type="button" className="link danger" onClick={() => setDel(r)}>
                    {t('common.delete')}
                  </button>
                </span>
              ),
            },
          ]}
        />
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
      <Section title={t('admin.explain')}>
        <p className="hint">{t('admin.explainHint')}</p>
        <div className="filters">
          <TextField label={t('admin.principal')} value={ex.principalId} onChange={(v) => setEx({ ...ex, principalId: v })} />
          <SelectField label={t('admin.action')} value={ex.action} onChange={(v) => setEx({ ...ex, action: v })} options={PERMS.map((p) => ({ value: p, label: p }))} />
          <TextField label={t('admin.resourceType')} value={ex.resourceType} onChange={(v) => setEx({ ...ex, resourceType: v })} />
          <TextField label={t('admin.resourceId')} value={ex.resourceId} onChange={(v) => setEx({ ...ex, resourceId: v })} />
          <button type="button" disabled={!ex.principalId} onClick={() => void act.run(() => api.admin.explain(ex)).then((r) => r && setExRes(r))}>
            {t('admin.explainRun')}
          </button>
        </div>
        {exRes && (
          <Notice tone={exRes.allowed ? 'ok' : 'bad'}>
            <strong>{exRes.allowed ? t('admin.allowed') : t('admin.denied')}</strong> ({t('admin.policyVersion')} {exRes.policyVersion})
            <ul>
              {exRes.matched.map((m) => (
                <li key={m.policyId}>
                  <Badge value={m.effect} /> {m.policyId} · {m.scope}
                </li>
              ))}
            </ul>
          </Notice>
        )}
      </Section>
      <Dialog
        open={edit !== null}
        wide
        title={t('admin.policy')}
        onClose={() => setEdit(null)}
        footer={
          <button
            type="button"
            className="primary"
            disabled={!edit?.principalId || !edit?.resourceType || act.busy}
            onClick={() =>
              void act.run(() => api.admin.savePolicy(edit!)).then((r) => {
                if (r) {
                  setEdit(null);
                  list.reload();
                }
              })
            }
          >
            {t('common.save')}
          </button>
        }
      >
        {edit && (
          <>
            <SelectField label={t('admin.effect')} value={edit.effect ?? 'ALLOW'} onChange={(v) => setEdit({ ...edit, effect: v as Policy['effect'] })} options={['ALLOW', 'DENY'].map((v) => ({ value: v, label: t(`enum.${v}`) }))} />
            <SelectField
              label={t('admin.principalType')}
              value={edit.principalType ?? 'GROUP'}
              onChange={(v) => setEdit({ ...edit, principalType: v as Policy['principalType'] })}
              options={['USER', 'GROUP', 'ROLE', 'SEAT', 'SERVICE'].map((v) => ({ value: v, label: t(`enum.${v}`) }))}
            />
            <TextField label={t('admin.principal')} required value={edit.principalId ?? ''} onChange={(v) => setEdit({ ...edit, principalId: v })} />
            <TextField label={t('admin.resourceType')} required value={edit.resourceType ?? ''} onChange={(v) => setEdit({ ...edit, resourceType: v })} />
            <TextField label={t('admin.resourceId')} hint={t('admin.resourceIdHint')} value={edit.resourceId ?? '*'} onChange={(v) => setEdit({ ...edit, resourceId: v })} />
            <fieldset className="field">
              <legend>{t('admin.permissions')}</legend>
              <div className="choices cols">
                {PERMS.map((p) => (
                  <Checkbox
                    key={p}
                    label={p}
                    checked={edit.permissions?.includes(p) ?? false}
                    onChange={(v) => setEdit({ ...edit, permissions: v ? [...(edit.permissions ?? []), p] : (edit.permissions ?? []).filter((x) => x !== p) })}
                  />
                ))}
              </div>
            </fieldset>
            <TextField label={t('admin.condition')} hint={t('admin.conditionHint')} value={edit.condition ?? ''} onChange={(v) => setEdit({ ...edit, condition: v || undefined })} />
            <TextField label={t('admin.validTo')} type="date" value={(edit.validTo ?? '').slice(0, 10)} onChange={(v) => setEdit({ ...edit, validTo: v || undefined })} />
            <TextArea label={t('admin.note')} value={edit.note ?? ''} onChange={(v) => setEdit({ ...edit, note: v })} />
          </>
        )}
        <ProblemAlert error={act.error} />
      </Dialog>
      <ReasonDialog
        open={del !== null}
        danger
        title={t('common.delete')}
        busy={act.busy}
        error={act.error}
        onClose={() => setDel(null)}
        onConfirm={(reason) =>
          void act.run(() => api.admin.deletePolicy(del!.id, reason).then(() => true)).then((ok) => {
            if (ok) {
              setDel(null);
              list.reload();
            }
          })
        }
      />
    </>
  );
}

export function SodExceptions() {
  const { t } = useTranslation();
  const list = usePaged((p) => api.admin.sodExceptions(p), []);
  const [form, setForm] = useState<{ rule: string; personId: string; reason: string; expiresAt: string } | null>(null);
  const [decide, setDecide] = useState<{ id: string; approve: boolean } | null>(null);
  const act = useAction();
  return (
    <>
      <PageHeader
        title={t('admin.sodTitle')}
        description={t('admin.sodDesc')}
        actions={
          <button type="button" className="primary" onClick={() => setForm({ rule: 'MAKER_CHECKER', personId: '', reason: '', expiresAt: '' })}>
            {t('admin.requestException')}
          </button>
        }
      />
      <ProblemAlert error={list.error ?? act.error} onRetry={list.reload} />
      {list.data && (
        <DataTable
          caption={t('admin.sodTitle')}
          captionHidden
          rows={list.data.items}
          rowKey={(r) => r.id}
          columns={[
            { key: 'rule', header: t('admin.rule'), render: (r) => t(`sod.rule.${r.rule}`, { defaultValue: r.rule }) },
            { key: 'person', header: t('admin.person'), render: (r) => r.personName ?? r.personId },
            { key: 'reason', header: t('common.reason') },
            { key: 'expires', header: t('admin.expires'), render: (r) => fmtDate(r.expiresAt) },
            { key: 'status', header: t('admin.status'), render: (r) => <Badge value={r.status} /> },
            {
              key: 'a',
              header: t('common.actions'),
              render: (r) =>
                r.status === 'PENDING' ? (
                  <span className="toolbar inline">
                    <button type="button" className="link" onClick={() => setDecide({ id: r.id, approve: true })}>
                      {t('admin.approve')}
                    </button>
                    <button type="button" className="link danger" onClick={() => setDecide({ id: r.id, approve: false })}>
                      {t('admin.reject')}
                    </button>
                  </span>
                ) : null,
            },
          ]}
        />
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
      <Dialog
        open={form !== null}
        title={t('admin.requestException')}
        onClose={() => setForm(null)}
        footer={
          <button
            type="button"
            className="primary"
            disabled={!form?.personId || !form?.reason || !form?.expiresAt}
            onClick={() =>
              void act.run(() => api.admin.saveSodException({ ...form!, expiresAt: new Date(form!.expiresAt).toISOString() })).then((r) => {
                if (r) {
                  setForm(null);
                  list.reload();
                }
              })
            }
          >
            {t('common.submit')}
          </button>
        }
      >
        {form && (
          <>
            <SelectField label={t('admin.rule')} value={form.rule} onChange={(v) => setForm({ ...form, rule: v })} options={['MAKER_CHECKER', 'CHECKER_APPROVER', 'SAME_SEAT', 'RECON_RESOLVER'].map((r) => ({ value: r, label: t(`sod.rule.${r}`) }))} />
            <TextField label={t('admin.personId')} required value={form.personId} onChange={(v) => setForm({ ...form, personId: v })} />
            <TextField label={t('admin.expires')} type="datetime-local" required value={form.expiresAt} onChange={(v) => setForm({ ...form, expiresAt: v })} />
            <TextArea label={t('common.reason')} required value={form.reason} onChange={(v) => setForm({ ...form, reason: v })} />
            <Notice>{t('admin.higherAuthority')}</Notice>
          </>
        )}
      </Dialog>
      <ReasonDialog
        open={decide !== null}
        title={decide?.approve ? t('admin.approve') : t('admin.reject')}
        busy={act.busy}
        error={act.error}
        onClose={() => setDecide(null)}
        onConfirm={(reason) =>
          void act.run(() => api.admin.decideSodException(decide!.id, decide!.approve, reason).then(() => true)).then((ok) => {
            if (ok) {
              setDecide(null);
              list.reload();
            }
          })
        }
      />
    </>
  );
}

// ------------------------------------------------------------------ users, groups

export function Users() {
  const { t } = useTranslation();
  return (
    <CrudPage
      title={t('admin.usersTitle')}
      path="/admin/users"
      newDefaults={{ active: true, source: 'LOCAL' }}
      columns={[
        { key: 'username', label: 'admin.username' },
        { key: 'displayName', label: 'common.name' },
        { key: 'email', label: 'admin.email' },
        { key: 'source', label: 'admin.source' },
        { key: 'active', label: 'admin.status', render: (r) => <Badge value={r.active ? 'ACTIVE' : 'INACTIVE'} /> },
        { key: 'lastLogin', label: 'admin.lastLogin', render: (r) => fmtDate(r.lastLogin) },
      ]}
      fields={[
        { key: 'username', type: 'textfield', label: 'admin.username', validate: { required: true } },
        { key: 'displayName', type: 'textfield', label: 'common.name', validate: { required: true } },
        { key: 'email', type: 'textfield', label: 'admin.email' },
        { key: 'active', type: 'checkbox', label: 'admin.activeFlag' },
      ]}
      rowActions={[{ id: 'disable', label: 'admin.disable', reason: true, danger: true, visible: (r) => !!r.active }]}
    />
  );
}

export function Groups() {
  const { t } = useTranslation();
  const [sel, setSel] = useState<string>('');
  const members = useAsync(() => api.admin.groupMembers(sel), [sel], !!sel);
  const [userId, setUserId] = useState('');
  const act = useAction();
  return (
    <>
      <CrudPage
        title={t('admin.groupsTitle')}
        path="/admin/groups"
        columns={[
          { key: 'name', label: 'common.name' },
          { key: 'type', label: 'admin.kind' },
          { key: 'members', label: 'admin.members' },
          { key: 'source', label: 'admin.source' },
        ]}
        fields={[
          { key: 'id', type: 'textfield', label: 'admin.groupId', validate: { required: true } },
          { key: 'name', type: 'textfield', label: 'common.name', validate: { required: true } },
          { key: 'type', type: 'textfield', label: 'admin.kind' },
        ]}
      />
      <Section title={t('admin.membership')}>
        <div className="filters">
          <TextField label={t('admin.groupId')} value={sel} onChange={setSel} />
          <TextField label={t('admin.personId')} value={userId} onChange={setUserId} />
          <button type="button" disabled={!sel || !userId} onClick={() => void act.run(() => api.admin.setMembership(sel, userId, true).then(() => true)).then(() => members.reload())}>
            {t('admin.addMember')}
          </button>
        </div>
        <ProblemAlert error={members.error ?? act.error} />
        <ul>
          {members.data?.map((u) => (
            <li key={u.id}>
              {u.displayName} ({u.username}){' '}
              <button type="button" className="link danger" onClick={() => void act.run(() => api.admin.setMembership(sel, u.id, false).then(() => true)).then(() => members.reload())}>
                {t('common.remove')}
              </button>
            </li>
          ))}
        </ul>
      </Section>
    </>
  );
}

// ------------------------------------------------------------------ A05 identity providers, I03 principals

export function IdentityProviders() {
  const { t } = useTranslation();
  const list = useAsync(() => api.admin.identityProviders(), []);
  const [edit, setEdit] = useState<Record<string, unknown> | null>(null);
  const [msg, setMsg] = useState<string>();
  const act = useAction();
  return (
    <>
      <PageHeader
        title={t('admin.idpTitle')}
        description={t('admin.idpDesc')}
        actions={
          <button type="button" className="primary" onClick={() => setEdit({ protocol: 'OIDC', mfaRequired: true, sessionTimeoutMin: 30, attributeMapping: '{"username":"preferred_username","tenant":"tenant"}' })}>
            {t('common.new')}
          </button>
        }
      />
      <ProblemAlert error={list.error ?? act.error} onRetry={list.reload} />
      {msg && <Notice>{msg}</Notice>}
      {list.data && (
        <DataTable
          caption={t('admin.idpTitle')}
          captionHidden
          rows={list.data}
          rowKey={(r) => r.id}
          columns={[
            { key: 'name', header: t('common.name') },
            { key: 'protocol', header: t('admin.protocol') },
            { key: 'issuer', header: t('admin.issuer'), render: (r) => r.issuer ?? r.metadataUrl ?? '—' },
            { key: 'mfa', header: t('admin.mfa'), render: (r) => (r.mfaRequired ? t('common.yes') : t('common.no')) },
            { key: 'status', header: t('admin.status'), render: (r) => <Badge value={r.status} /> },
            { key: 'test', header: t('admin.lastTest'), render: (r) => fmtDate(r.lastTestAt) },
            {
              key: 'a',
              header: t('common.actions'),
              render: (r) => (
                <span className="toolbar inline">
                  <button type="button" className="link" onClick={() => setEdit({ ...r, attributeMapping: JSON.stringify(r.attributeMapping) })}>
                    {t('common.edit')}
                  </button>
                  <button type="button" className="link" onClick={() => void act.run(() => api.admin.testIdentityProvider(r.id)).then((x) => x && (setMsg(x.message), list.reload()))}>
                    {t('admin.testLogin')}
                  </button>
                  {r.protocol === 'LDAP' && (
                    <button type="button" className="link" onClick={() => void act.run(() => api.admin.syncIdentityProvider(r.id)).then((x) => x && setMsg(t('admin.synced', { u: x.users, g: x.groups })))}>
                      {t('admin.syncNow')}
                    </button>
                  )}
                </span>
              ),
            },
          ]}
        />
      )}
      <Dialog
        open={edit !== null}
        wide
        title={t('admin.idp')}
        onClose={() => setEdit(null)}
        footer={
          <button
            type="button"
            className="primary"
            onClick={() => {
              let mapping: Record<string, string> = {};
              try {
                mapping = JSON.parse(String(edit!.attributeMapping ?? '{}')) as Record<string, string>;
              } catch {
                /* invalid JSON */
              }
              void act.run(() => api.admin.saveIdentityProvider({ ...edit!, attributeMapping: mapping } as never)).then((r) => {
                if (r) {
                  setEdit(null);
                  list.reload();
                }
              });
            }}
          >
            {t('common.save')}
          </button>
        }
      >
        {edit && (
          <>
            <TextField label={t('common.name')} required value={String(edit.name ?? '')} onChange={(v) => setEdit({ ...edit, name: v })} />
            <SelectField label={t('admin.protocol')} value={String(edit.protocol)} onChange={(v) => setEdit({ ...edit, protocol: v })} options={['OIDC', 'SAML2', 'LDAP'].map((v) => ({ value: v, label: v }))} />
            <TextField label={t('admin.issuer')} value={String(edit.issuer ?? '')} onChange={(v) => setEdit({ ...edit, issuer: v })} />
            <TextField label={t('admin.metadataUrl')} value={String(edit.metadataUrl ?? '')} onChange={(v) => setEdit({ ...edit, metadataUrl: v })} />
            <TextArea label={t('admin.attributeMapping')} value={String(edit.attributeMapping ?? '')} onChange={(v) => setEdit({ ...edit, attributeMapping: v })} />
            <Checkbox label={t('admin.mfa')} checked={Boolean(edit.mfaRequired)} onChange={(v) => setEdit({ ...edit, mfaRequired: v })} />
            <TextField label={t('admin.sessionTimeout')} type="number" value={String(edit.sessionTimeoutMin ?? 30)} onChange={(v) => setEdit({ ...edit, sessionTimeoutMin: Number(v) })} />
            <Notice>{t('admin.idpActivationRule')}</Notice>
          </>
        )}
      </Dialog>
    </>
  );
}

export function ServicePrincipals() {
  const { t } = useTranslation();
  const list = useAsync(() => api.admin.servicePrincipals(), []);
  const [form, setForm] = useState<{ name: string; scopes: string; topics: string; expiresAt: string } | null>(null);
  const [secret, setSecret] = useState<string>();
  const [revoke, setRevoke] = useState<string | null>(null);
  const act = useAction();
  return (
    <>
      <PageHeader
        title={t('admin.principalsTitle')}
        description={t('admin.principalsDesc')}
        actions={
          <button type="button" className="primary" onClick={() => setForm({ name: '', scopes: '', topics: '', expiresAt: '' })}>
            {t('common.new')}
          </button>
        }
      />
      {secret && (
        <Notice tone="warn">
          <strong>{t('admin.secretOnce')}</strong> <CopyText text={secret} />{' '}
          <button type="button" className="link" onClick={() => setSecret(undefined)}>
            {t('common.close')}
          </button>
        </Notice>
      )}
      <ProblemAlert error={list.error ?? act.error} onRetry={list.reload} />
      {list.data && (
        <DataTable
          caption={t('admin.principalsTitle')}
          captionHidden
          rows={list.data}
          rowKey={(r) => r.id}
          columns={[
            { key: 'name', header: t('common.name') },
            { key: 'scopes', header: t('admin.scopes'), render: (r) => r.scopes.join(', ') },
            { key: 'topics', header: t('ops.topics'), render: (r) => r.topics.join(', ') },
            { key: 'expires', header: t('admin.expires'), render: (r) => fmtDate(r.expiresAt) },
            { key: 'last', header: t('admin.lastUsed'), render: (r) => fmtDate(r.lastUsedAt) },
            { key: 'status', header: t('admin.status'), render: (r) => <Badge value={r.status} /> },
            {
              key: 'a',
              header: t('common.actions'),
              render: (r) =>
                r.status === 'ACTIVE' ? (
                  <span className="toolbar inline">
                    <button type="button" className="link" onClick={() => void act.run(() => api.admin.rotateServicePrincipal(r.id)).then((x) => x && setSecret(x.secret))}>
                      {t('admin.rotate')}
                    </button>
                    <button type="button" className="link danger" onClick={() => setRevoke(r.id)}>
                      {t('admin.revoke')}
                    </button>
                  </span>
                ) : null,
            },
          ]}
        />
      )}
      <Dialog
        open={form !== null}
        title={t('admin.newPrincipal')}
        onClose={() => setForm(null)}
        footer={
          <button
            type="button"
            className="primary"
            disabled={!form?.name}
            onClick={() =>
              void act
                .run(() =>
                  api.admin.saveServicePrincipal({
                    name: form!.name,
                    scopes: form!.scopes.split(',').map((s) => s.trim()).filter(Boolean),
                    topics: form!.topics.split(',').map((s) => s.trim()).filter(Boolean),
                    expiresAt: form!.expiresAt ? new Date(form!.expiresAt).toISOString() : undefined,
                  }),
                )
                .then((r) => {
                  if (r) {
                    setForm(null);
                    if (r.secret) setSecret(r.secret);
                    list.reload();
                  }
                })
            }
          >
            {t('common.save')}
          </button>
        }
      >
        {form && (
          <>
            <TextField label={t('common.name')} required value={form.name} onChange={(v) => setForm({ ...form, name: v })} />
            <TextField label={t('admin.scopes')} hint={t('events.commaList')} value={form.scopes} onChange={(v) => setForm({ ...form, scopes: v })} />
            <TextField label={t('ops.topics')} hint={t('events.commaList')} value={form.topics} onChange={(v) => setForm({ ...form, topics: v })} />
            <TextField label={t('admin.expires')} type="date" value={form.expiresAt} onChange={(v) => setForm({ ...form, expiresAt: v })} />
            <Notice>{t('admin.principalApproval')}</Notice>
          </>
        )}
      </Dialog>
      <ReasonDialog
        open={revoke !== null}
        danger
        title={t('admin.revoke')}
        busy={act.busy}
        error={act.error}
        onClose={() => setRevoke(null)}
        onConfirm={(reason) =>
          void act.run(() => api.admin.revokeServicePrincipal(revoke!, reason).then(() => true)).then((ok) => {
            if (ok) {
              setRevoke(null);
              list.reload();
            }
          })
        }
      />
    </>
  );
}

// ------------------------------------------------------------------ registry screens (declarative)

export function ServiceRegistry() {
  const { t } = useTranslation();
  return (
    <CrudPage
      title={t('admin.servicesTitle')}
      description={t('admin.servicesDesc')}
      path="/admin/services"
      canDelete
      columns={[
        { key: 'key', label: 'ops.key' },
        { key: 'name', label: 'common.name' },
        { key: 'connector', label: 'admin.connector' },
        { key: 'capabilityClass', label: 'recon.class' },
        { key: 'endpoint', label: 'admin.endpoint' },
      ]}
      fields={[
        { key: 'key', type: 'textfield', label: 'ops.key', validate: { required: true } },
        { key: 'name', type: 'textfield', label: 'common.name', validate: { required: true } },
        {
          key: 'connector',
          type: 'select',
          label: 'admin.connector',
          values: ['REST', 'SOAP', 'GRAPHQL', 'EMAIL', 'SMS_DLT', 'SFTP', 'WEBHOOK', 'KAFKA', 'AMQP', 'JDBC', 'S3', 'CHAT', 'ESIGN', 'DIGILOCKER', 'PAYMENT', 'PFMS', 'RPA'].map((v) => ({ value: v, label: `connector.${v}` })),
          validate: { required: true },
        },
        { key: 'endpoint', type: 'textfield', label: 'admin.endpoint', description: 'admin.egressHint', validate: { required: true } },
        { key: 'authRef', type: 'textfield', label: 'admin.authRef', description: 'admin.authRefHint' },
        { key: 'capabilityClass', type: 'radio', label: 'recon.class', values: ['a', 'b', 'c'].map((v) => ({ value: v, label: `recon.classLabel.${v}` })), validate: { required: true } },
        { key: 'operations', type: 'textarea', label: 'admin.operations', description: 'admin.operationsHint' },
        { key: 'simulator', type: 'checkbox', label: 'admin.simulator' },
      ]}
    />
  );
}

export function SlaModels() {
  const { t } = useTranslation();
  return (
    <CrudPage
      title={t('admin.slaTitle')}
      description={t('admin.slaDesc')}
      path="/admin/sla-models"
      canDelete
      columns={[
        { key: 'key', label: 'ops.key' },
        { key: 'scope', label: 'admin.slaScope' },
        { key: 'target', label: 'admin.slaTarget' },
        { key: 'calendar', label: 'admin.calendar' },
      ]}
      fields={[
        { key: 'key', type: 'textfield', label: 'ops.key', validate: { required: true } },
        { key: 'scope', type: 'select', label: 'admin.slaScope', values: ['TASK', 'PROCESS', 'CASE'].map((v) => ({ value: v, label: `enum.${v}` })), validate: { required: true } },
        { key: 'appliesTo', type: 'textfield', label: 'admin.slaAppliesTo', description: 'admin.slaAppliesToHint' },
        { key: 'target', type: 'textfield', label: 'admin.slaTarget', description: 'admin.durationHint', validate: { required: true } },
        { key: 'warning', type: 'textfield', label: 'admin.slaWarning', description: 'admin.durationHint' },
        { key: 'breach', type: 'textfield', label: 'admin.slaBreach', description: 'admin.durationHint' },
        { key: 'calendar', type: 'textfield', label: 'admin.calendar' },
        { key: 'pauseWhen', type: 'textfield', label: 'admin.slaPause', description: 'admin.slaPauseHint' },
        { key: 'escalation', type: 'checklist', label: 'admin.slaEscalation', values: ['REASSIGN', 'NOTIFY', 'RAISE_PRIORITY', 'ESCALATE_UP'].map((v) => ({ value: v, label: `admin.esc.${v}` })) },
        { key: 'notifyTemplate', type: 'textfield', label: 'admin.slaTemplate' },
      ]}
    />
  );
}

export function RoutingStrategies() {
  const { t } = useTranslation();
  return (
    <CrudPage
      title={t('admin.routingTitle')}
      description={t('admin.routingDesc')}
      path="/admin/routing-strategies"
      canDelete
      columns={[
        { key: 'key', label: 'ops.key' },
        { key: 'strategy', label: 'admin.strategy' },
        { key: 'claimLimit', label: 'admin.claimLimit' },
      ]}
      fields={[
        { key: 'key', type: 'textfield', label: 'ops.key', validate: { required: true } },
        { key: 'strategy', type: 'select', label: 'admin.strategy', values: ['ROUND_ROBIN', 'LEAST_LOADED', 'SKILLS_MATCH', 'SLA_PRIORITY', 'PULL_QUEUE'].map((v) => ({ value: v, label: `admin.strat.${v}` })), validate: { required: true } },
        { key: 'skills', type: 'textfield', label: 'admin.skills', description: 'events.commaList' },
        { key: 'claimLimit', type: 'number', label: 'admin.claimLimit', validate: { min: 1 } },
        { key: 'candidateSeats', type: 'textarea', label: 'admin.candidateSeats' },
      ]}
    />
  );
}

export function Templates() {
  const { t } = useTranslation();
  return (
    <CrudPage
      title={t('admin.templatesTitle')}
      description={t('admin.templatesDesc')}
      path="/admin/templates"
      canDelete
      columns={[
        { key: 'key', label: 'ops.key' },
        { key: 'kind', label: 'admin.kind' },
        { key: 'language', label: 'admin.languages' },
        { key: 'dltTemplateId', label: 'admin.dltTemplateId' },
        { key: 'version', label: 'ops.version' },
      ]}
      fields={[
        { key: 'key', type: 'textfield', label: 'ops.key', validate: { required: true } },
        { key: 'kind', type: 'select', label: 'admin.kind', values: ['SMS', 'EMAIL', 'DOCUMENT_PDFA', 'DOCUMENT_DOCX', 'IN_APP'].map((v) => ({ value: v, label: `admin.tplKind.${v}` })), validate: { required: true } },
        { key: 'language', type: 'select', label: 'admin.languages', values: [{ value: 'en', label: 'lang.en' }, { value: 'hi', label: 'lang.hi' }, { value: 'bi', label: 'lang.bilingual' }] },
        { key: 'peId', type: 'textfield', label: 'admin.peId' },
        { key: 'header', type: 'textfield', label: 'admin.dltHeader' },
        { key: 'dltTemplateId', type: 'textfield', label: 'admin.dltTemplateId' },
        { key: 'variables', type: 'textfield', label: 'admin.tplVariables', description: 'admin.tplVariablesHint' },
        { key: 'body', type: 'textarea', label: 'admin.tplBody', validate: { required: true } },
        { key: 'urls', type: 'textarea', label: 'admin.whitelistedUrls' },
      ]}
      rowActions={[
        { id: 'test-send', label: 'admin.testSend' },
        { id: 'retire', label: 'admin.retire', reason: true, danger: true },
      ]}
    />
  );
}

export function ConversationalChannels() {
  const { t } = useTranslation();
  return (
    <CrudPage
      title={t('admin.channelsTitle')}
      description={t('admin.channelsDesc')}
      path="/admin/conversation-channels"
      canDelete
      columns={[
        { key: 'key', label: 'ops.key' },
        { key: 'kind', label: 'events.kind' },
        { key: 'allowedActions', label: 'admin.allowedActions' },
        { key: 'active', label: 'admin.status', render: (r) => <Badge value={r.active ? 'ACTIVE' : 'PAUSED'} /> },
      ]}
      fields={[
        { key: 'key', type: 'textfield', label: 'ops.key', validate: { required: true } },
        { key: 'kind', type: 'select', label: 'events.kind', values: ['MATRIX', 'TEAMS', 'SLACK', 'WHATSAPP', 'SMS', 'IVR'].map((v) => ({ value: v, label: `connector.${v}` })), validate: { required: true } },
        { key: 'endpoint', type: 'textfield', label: 'admin.endpoint' },
        { key: 'identityMapping', type: 'textfield', label: 'admin.identityMapping', description: 'admin.identityMappingHint' },
        { key: 'allowedActions', type: 'checklist', label: 'admin.allowedActions', values: ['START_PROCESS', 'COMPLETE_SIMPLE_TASK', 'STATUS_QUERY'].map((v) => ({ value: v, label: `admin.chanAct.${v}` })) },
        { key: 'active', type: 'checkbox', label: 'events.active' },
      ]}
    />
  );
}

export function AgentDefinitions() {
  const { t } = useTranslation();
  return (
    <CrudPage
      title={t('admin.agentsTitle')}
      description={t('admin.agentsDesc')}
      path="/admin/agents"
      canDelete
      columns={[
        { key: 'key', label: 'ops.key' },
        { key: 'provider', label: 'admin.modelProvider' },
        { key: 'tokenBudget', label: 'admin.tokenBudget' },
        { key: 'humanReview', label: 'admin.humanReview', render: (r) => (r.humanReview ? t('common.yes') : t('common.no')) },
      ]}
      fields={[
        { key: 'key', type: 'textfield', label: 'ops.key', validate: { required: true } },
        { key: 'provider', type: 'select', label: 'admin.modelProvider', values: ['SELF_HOSTED', 'OFFLINE', 'OPENAI_COMPATIBLE', 'OTHER'].map((v) => ({ value: v, label: `admin.prov.${v}` })), validate: { required: true } },
        { key: 'model', type: 'textfield', label: 'admin.model' },
        { key: 'tools', type: 'textarea', label: 'admin.tools', description: 'admin.toolsHint' },
        { key: 'inputGuardrails', type: 'textarea', label: 'admin.inputGuardrails' },
        { key: 'outputGuardrails', type: 'textarea', label: 'admin.outputGuardrails' },
        { key: 'tokenBudget', type: 'number', label: 'admin.tokenBudget', validate: { min: 0 } },
        { key: 'confidenceThreshold', type: 'number', label: 'admin.confidence', validate: { min: 0, max: 1 } },
        { key: 'humanReview', type: 'checkbox', label: 'admin.humanReview', description: 'admin.humanReviewHint', defaultValue: true },
      ]}
    />
  );
}

export function RetentionClasses() {
  const { t } = useTranslation();
  return (
    <CrudPage
      title={t('admin.retentionTitle')}
      description={t('admin.retentionDesc')}
      path="/admin/retention-classes"
      columns={[
        { key: 'key', label: 'ops.key' },
        { key: 'category', label: 'admin.recordCategory' },
        { key: 'retainYears', label: 'admin.retainYears' },
        { key: 'reviewRequired', label: 'admin.reviewRequired', render: (r) => (r.reviewRequired ? t('common.yes') : t('common.no')) },
      ]}
      fields={[
        { key: 'key', type: 'textfield', label: 'ops.key', validate: { required: true } },
        { key: 'category', type: 'select', label: 'admin.recordCategory', values: ['A', 'B', 'C', 'TRANSIENT'].map((v) => ({ value: v, label: `admin.cat.${v}` })), validate: { required: true } },
        { key: 'retainYears', type: 'number', label: 'admin.retainYears', validate: { min: 0 } },
        { key: 'reviewRequired', type: 'checkbox', label: 'admin.reviewRequired' },
        { key: 'transferToArchive', type: 'checkbox', label: 'admin.transferArchive' },
      ]}
    />
  );
}
