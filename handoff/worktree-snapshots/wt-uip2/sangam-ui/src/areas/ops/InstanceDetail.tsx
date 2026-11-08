// SPDX-License-Identifier: Apache-2.0
/**
 * Process-instance view (R63, PF-01, PF-03, PF-05, PF-17, FC-15, FC-17, FC-20, FC-31/32, D-32, D-35):
 * diagram with live tokens, activity-instance tree, variables (edit with reason, masked fields), history logs
 * (audit, jobs, external tasks, variable changes, user tasks, decisions, called instances), links, and guarded
 * actions with consequence preview: suspend/resume (re-date deadlines), cancel (business vs technical, O08),
 * modify (skip/move token), move across call-activity boundary, inject a task, send a message to one branch, restart.
 */
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api, historyFilterable } from '../../api/endpoints';
import type { ActivityInstance, ActivitySelection, ActivityState, ConsequencePreview, HistoryLogKind, ModificationInstruction, ModificationKind } from '../../api/types';
import { announce, useAction, useAsync, usePaged } from '../../lib/hooks';
import { fmtDate } from '../../i18n';
import { BpmnDiagram } from '../../components/BpmnDiagram';
import { http } from '../../api/client';
import { useAuth } from '../../auth/AuthProvider';
import { PluginSlot, usePlugins } from '../../plugins/host';
import {
  Badge,
  Checkbox,
  DataTable,
  Dialog,
  Empty,
  KeyValues,
  Loading,
  Notice,
  PageHeader,
  Pagination,
  PreviewList,
  ProblemAlert,
  ReasonDialog,
  SelectField,
  TabPanel,
  Tabs,
  TextArea,
  TextField,
} from '../../components/ui';

const LOGS: HistoryLogKind[] = ['audit', 'jobs', 'external-tasks', 'variables', 'user-tasks', 'decisions', 'called'];
const STATES: ActivityState[] = ['running', 'completed', 'canceled'];

type Dlg = 'cancel' | 'suspend' | 'modify' | 'move' | 'inject' | 'message' | 'restart' | 'adhoc' | null;

export function InstanceDetail() {
  const { id = '' } = useParams();
  const { t } = useTranslation();
  const inst = useAsync(() => api.processes.instance(id), [id]);
  const xml = useAsync(() => api.processes.xml(inst.data!.definitionId), [inst.data?.definitionId], !!inst.data);
  const stats = useAsync(() => api.processes.instanceStatistics(id), [id]);
  const tree = useAsync(() => api.processes.activityTree(id), [id]);
  const links = useAsync(() => api.processes.links(id), [id]);
  const { hasRole } = useAuth();
  const called = useAsync(() => http.get<{ activityId: string; calledProcessInstanceId?: string }[]>('/engine-rest/history/activity-instance', { processInstanceId: id, activityType: 'callActivity' }), [id], hasRole('PLATFORM_OPERATOR'));
  const piActions = usePlugins('process-instance.action');
  const [tab, setTab] = useState('tree');
  const [treeQ, setTreeQ] = useState('');
  const [treeState, setTreeState] = useState<ActivityState | ''>('');
  const [sel, setSel] = useState<ActivitySelection>();
  const [dlg, setDlg] = useState<Dlg>(null);
  const [picked, setPicked] = useState<string[]>([]);
  const reloadAll = () => {
    inst.reload();
    stats.reload();
    tree.reload();
  };
  const i = inst.data;
  if (inst.loading && !i) return <Loading />;
  if (!i) return <ProblemAlert error={inst.error} onRetry={inst.reload} />;
  const running = i.state === 'ACTIVE' || i.state === 'SUSPENDED' || i.state === 'INCIDENT';
  return (
    <>
      <PageHeader
        title={`${t('ops.instance')} ${i.businessKey ?? i.id}`}
        actions={
          <>
            {running && (
              <>
                <button type="button" onClick={() => setDlg('suspend')}>
                  {i.state === 'SUSPENDED' ? t('ops.resume') : t('ops.suspend')}
                </button>
                <button type="button" onClick={() => setDlg('modify')}>
                  {t('ops.modify')}
                </button>
                <button type="button" onClick={() => setDlg('move')}>
                  {t('ops.moveAcross')}
                </button>
                <button type="button" onClick={() => setDlg('inject')}>
                  {t('ops.inject')}
                </button>
                <button type="button" onClick={() => setDlg('adhoc')}>
                  {t('ops.adHoc')}
                </button>
                <button type="button" onClick={() => setDlg('message')}>
                  {t('ops.sendMessage')}
                </button>
                <button type="button" className="danger" onClick={() => setDlg('cancel')}>
                  {t('ops.cancelInstance')}
                </button>
              </>
            )}
            {!running && (
              <button type="button" onClick={() => setDlg('restart')}>
                {t('ops.restart')}
              </button>
            )}
            <Link to={`/ops/incidents?processInstanceId=${encodeURIComponent(id)}`}>{t('nav.incidents')}</Link>
            {piActions.map((p) => (
              <button key={p.pluginId + p.contribution.id} type="button" onClick={() => void p.contribution.run?.({ language: '', api: http, processInstanceId: id })}>
                {p.contribution.label.en}
              </button>
            ))}
            {i.businessKey && <Link to={`/audit/timeline/${encodeURIComponent(i.businessKey)}`}>{t('task.viewTimeline')}</Link>}
          </>
        }
      />
      <KeyValues
        items={[
          [t('admin.status'), <Badge key="s" value={i.state} />],
          [t('ops.lifecycle'), i.lifecycleState],
          [t('ops.definition'), <Link key="d" to={`/ops/processes/${encodeURIComponent(i.definitionId)}`}>{`${i.definitionKey} v${i.definitionVersion ?? ''}`}</Link>],
          [t('ops.started'), fmtDate(i.startTime)],
          [t('ops.ended'), fmtDate(i.endTime)],
          [t('ops.parent'), i.superInstanceId ? <Link key="p" to={`/ops/instances/${encodeURIComponent(i.superInstanceId)}`}>{i.superInstanceId}</Link> : undefined],
        ]}
      />
      {xml.data && (
        <BpmnDiagram
          title={i.definitionKey}
          xml={xml.data.bpmn20Xml}
          stats={stats.data?.activities}
          mode="tokens"
          badges={(called.data ?? []).filter((x) => x.calledProcessInstanceId).map((x) => ({ activityId: x.activityId, text: '→', href: `/ops/instances/${encodeURIComponent(x.calledProcessInstanceId!)}` }))}
          selected={picked}
          onSelect={(a) => setPicked((p) => (p.includes(a) ? p.filter((x) => x !== a) : [...p, a]))}
        />
      )}
      {picked.length > 0 && (
        <Notice>
          {t('ops.pickedActivities', { list: picked.join(', ') })}{' '}
          <button type="button" className="link" onClick={() => setPicked([])}>
            {t('common.clear')}
          </button>
        </Notice>
      )}
      <Tabs
        label={t('ops.instanceTabs')}
        active={tab}
        onChange={setTab}
        tabs={[
          { id: 'tree', label: t('ops.activityTree') },
          { id: 'vars', label: t('ops.variables') },
          { id: 'incidents', label: t('nav.incidents') },
          ...LOGS.map((l) => ({ id: `log-${l}`, label: t(`ops.log.${l}`) })),
          { id: 'links', label: t('ops.links') },
        ]}
      />
      {sel && !sel.root && (
        <Notice>
          {t('ops.selectedActivity', { name: sel.label, id: sel.activityInstanceId })}{' '}
          <button type="button" className="link" onClick={() => setSel(undefined)}>
            {t('common.clear')}
          </button>
        </Notice>
      )}
      <TabPanel id="tree" active={tab}>
        <ProblemAlert error={tree.error} />
        <div className="filters">
          <TextField label={t('common.search')} value={treeQ} onChange={setTreeQ} />
          <SelectField
            label={t('ops.treeState')}
            value={treeState}
            onChange={(v) => setTreeState(v as ActivityState | '')}
            placeholder={t('ops.treeCurrent')}
            options={STATES.map((s) => ({ value: s, label: t(`ops.actState.${s}`) }))}
          />
        </div>
        {treeState ? (
          <ActivityStateList instanceId={id} state={treeState} q={treeQ} selected={sel} onSelect={setSel} />
        ) : (
          tree.data && <ActivityTree node={tree.data} q={treeQ} selected={sel} onSelect={setSel} />
        )}
      </TabPanel>
      <TabPanel id="vars" active={tab}>
        <Variables instanceId={id} editable={running} at={sel} />
      </TabPanel>
      <TabPanel id="incidents" active={tab}>
        <InstanceIncidents instanceId={id} at={sel} />
      </TabPanel>
      {LOGS.map((l) => (
        <TabPanel key={l} id={`log-${l}`} active={tab}>
          <HistoryLog instanceId={id} kind={l} at={sel} />
        </TabPanel>
      ))}
      <TabPanel id="links" active={tab}>
        <ProblemAlert error={links.error} />
        {links.data?.length === 0 && <Empty message={t('ops.noLinks')} />}
        <ul>
          {links.data?.map((l, idx) => (
            <li key={idx}>
              <Badge value={l.type} tone="info" /> {l.targetType}:{' '}
              {l.targetType === 'PROCESS' ? (
                <Link to={`/ops/instances/${encodeURIComponent(l.targetId)}`}>{l.label ?? l.targetId}</Link>
              ) : l.targetType === 'CASE' ? (
                <Link to={`/cases/${encodeURIComponent(l.targetId)}`}>{l.label ?? l.targetId}</Link>
              ) : (
                (l.label ?? l.targetId)
              )}
            </li>
          ))}
        </ul>
      </TabPanel>

      <PluginSlot point="process-instance.tab" ctx={{ processInstanceId: id }} />
      {dlg === 'cancel' && <CancelDialog instanceId={id} onClose={() => setDlg(null)} onDone={reloadAll} />}
      {dlg === 'suspend' && <SuspendDialog instanceId={id} suspended={i.state === 'SUSPENDED'} onClose={() => setDlg(null)} onDone={reloadAll} />}
      {dlg === 'modify' && <ModifyDialog instanceId={id} tree={tree.data} picked={picked} onClose={() => setDlg(null)} onDone={reloadAll} />}
      {dlg === 'move' && <MoveAcrossDialog instanceId={id} tree={tree.data} onClose={() => setDlg(null)} onDone={reloadAll} />}
      {dlg === 'inject' && <InjectDialog instanceId={id} picked={picked} onClose={() => setDlg(null)} />}
      {dlg === 'message' && <MessageDialog instanceId={id} businessKey={i.businessKey} onClose={() => setDlg(null)} onDone={reloadAll} />}
      {dlg === 'adhoc' && <AdHocDialog instanceId={id} picked={picked} onClose={() => setDlg(null)} onDone={reloadAll} />}
      {dlg === 'restart' && <RestartDialog definitionId={i.definitionId} instanceId={id} picked={picked} onClose={() => setDlg(null)} />}
    </>
  );
}

type Select = (s: ActivitySelection | undefined) => void;

/** Picks a node; picking the selected one again clears the selection (P13). */
function toggle(cur: ActivitySelection | undefined, next: ActivitySelection, onSelect: Select) {
  onSelect(cur?.activityInstanceId === next.activityInstanceId ? undefined : next);
}

function ActivityTree({ node, q = '', selected, onSelect, depth = 0 }: { node: ActivityInstance; q?: string; selected?: ActivitySelection; onSelect?: Select; depth?: number }) {
  const { t } = useTranslation();
  const hit = (n: ActivityInstance): boolean => `${n.activityName ?? ''} ${n.activityId}`.toLowerCase().includes(q.toLowerCase()) || (n.children ?? []).some(hit);
  if (q && !hit(node)) return null;
  const label = node.activityName ?? node.activityId;
  const pick: ActivitySelection = { activityInstanceId: node.id, activityId: node.activityId, activityType: node.activityType, label, root: depth === 0 };
  const pressed = selected?.activityInstanceId === node.id;
  return (
    <ul className="plan-tree" role="tree">
      <li role="treeitem" aria-expanded={node.children?.length ? true : undefined} aria-selected={pressed}>
        {onSelect ? (
          <button type="button" className="link" aria-pressed={pressed} onClick={() => toggle(selected, pick, onSelect)}>
            <strong>{label}</strong>
          </button>
        ) : (
          <strong>{label}</strong>
        )}{' '}
        <span className="muted small">{node.activityType}</span> <code className="small">{node.id}</code>{' '}
        {node.incidentIds?.length ? <Badge value={t('ops.incidentN', { n: node.incidentIds.length })} tone="bad" /> : null}
        {node.startTime && <span className="small muted"> · {fmtDate(node.startTime)}</span>}
        {node.children && node.children.length > 0 && node.children.map((c) => <ActivityTree key={c.id} node={c} q={q} selected={selected} onSelect={onSelect} depth={depth + 1} />)}
      </li>
    </ul>
  );
}

/** P14: activity instances of this instance in one state, from the history API (the runtime tree has running only). */
function ActivityStateList({ instanceId, state, q, selected, onSelect }: { instanceId: string; state: ActivityState; q: string; selected?: ActivitySelection; onSelect: Select }) {
  const { t } = useTranslation();
  const list = usePaged((p) => api.processes.activityHistory(instanceId, { ...p, state }), [instanceId, state]);
  const rows = (list.data?.items ?? []).filter((a) => `${a.activityName ?? ''} ${a.activityId}`.toLowerCase().includes(q.toLowerCase()));
  return (
    <>
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.loading && !list.data && <Loading />}
      {list.data && rows.length === 0 && <Empty message={t('ops.noActivities')} />}
      {rows.length > 0 && (
        <ul className="plan-tree" role="tree" aria-label={t(`ops.actState.${state}`)}>
          {rows.map((a) => {
            const label = a.activityName ?? a.activityId;
            const pressed = selected?.activityInstanceId === a.id;
            return (
              <li key={a.id} role="treeitem" aria-selected={pressed}>
                <button type="button" className="link" aria-pressed={pressed} onClick={() => toggle(selected, { activityInstanceId: a.id, activityId: a.activityId, activityType: a.activityType, label }, onSelect)}>
                  <strong>{label}</strong>
                </button>{' '}
                <span className="muted small">{a.activityType}</span> <Badge value={t(`ops.actState.${state}`)} tone={state === 'canceled' ? 'warn' : state === 'completed' ? 'ok' : 'info'} />{' '}
                <code className="small">{a.id}</code>
                {a.startTime && <span className="small muted"> · {fmtDate(a.startTime)}</span>}
                {a.endTime && <span className="small muted"> → {fmtDate(a.endTime)}</span>}
              </li>
            );
          })}
        </ul>
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
    </>
  );
}

/** Incidents of this instance (open and resolved); a tree selection narrows them to its activity (P13). */
function InstanceIncidents({ instanceId, at }: { instanceId: string; at?: ActivitySelection }) {
  const { t } = useTranslation();
  const activityId = at && !at.root ? at.activityId : undefined;
  const list = usePaged((p) => api.processes.incidentHistory(instanceId, { ...p, activityId }), [instanceId, activityId]);
  return (
    <>
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.loading && !list.data && <Loading />}
      {list.data?.items.length === 0 && <Empty message={t('ops.noInstanceIncidents')} />}
      {list.data && list.data.items.length > 0 && (
        <DataTable
          caption={t('nav.incidents')}
          captionHidden
          rows={list.data.items}
          rowKey={(r) => r.id}
          columns={[
            { key: 'createTime', header: t('task.when'), render: (r) => fmtDate(r.createTime) },
            { key: 'activityId', header: t('diagram.activity'), render: (r) => r.activityId ?? '—' },
            { key: 'type', header: t('fld.type'), render: (r) => r.type ?? '—' },
            { key: 'message', header: t('ops.change'), render: (r) => r.message ?? '—' },
            { key: 'state', header: t('admin.status'), render: (r) => (r.state ? <Badge value={r.state} /> : '—') },
          ]}
        />
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
    </>
  );
}

function flattenTree(n?: ActivityInstance): ActivityInstance[] {
  if (!n) return [];
  return [n, ...(n.children ?? []).flatMap(flattenTree)];
}

function Variables({ instanceId, editable, at }: { instanceId: string; editable: boolean; at?: ActivitySelection }) {
  const { t } = useTranslation();
  const scope = at && !at.root ? at.activityInstanceId : undefined;
  const vars = useAsync(() => api.processes.variables(instanceId, scope), [instanceId, scope]);
  const [edit, setEdit] = useState<{ name: string; type: string; value: string } | null>(null);
  const act = useAction();
  return (
    <>
      <ProblemAlert error={vars.error} onRetry={vars.reload} />
      {editable && (
        <button type="button" onClick={() => setEdit({ name: '', type: 'String', value: '' })}>
          {t('ops.addVariable')}
        </button>
      )}
      {vars.data && (
        <DataTable
          caption={t('ops.variables')}
          captionHidden
          rows={vars.data}
          rowKey={(r) => `${r.scope ?? ''}:${r.name}`}
          columns={[
            { key: 'name', header: t('ops.varName') },
            { key: 'type', header: t('ops.varType') },
            { key: 'value', header: t('ops.varValue'), render: (r) => (r.masked ? <span className="muted">{t('ops.masked')}</span> : <code>{JSON.stringify(r.value)}</code>) },
            { key: 'scope', header: t('ops.scope'), render: (r) => r.scope ?? '—' },
            {
              key: 'act',
              header: t('common.actions'),
              render: (r) =>
                editable && !r.masked ? (
                  <button type="button" className="link" onClick={() => setEdit({ name: r.name, type: r.type, value: JSON.stringify(r.value) })}>
                    {t('common.edit')}
                  </button>
                ) : null,
            },
          ]}
        />
      )}
      <ReasonDialog
        open={edit !== null}
        title={t('ops.editVariable')}
        busy={act.busy}
        error={act.error}
        onClose={() => setEdit(null)}
        onConfirm={(reason) => {
          if (!edit) return;
          let value: unknown = edit.value;
          try {
            value = JSON.parse(edit.value);
          } catch {
            /* keep as string */
          }
          void act.run((k) => api.processes.setVariable(instanceId, { name: edit.name, type: edit.type, value, reason }, k).then(() => true)).then((ok) => {
            if (ok) {
              setEdit(null);
              vars.reload();
            }
          });
        }}
      >
        {edit && (
          <>
            <TextField label={t('ops.varName')} value={edit.name} onChange={(v) => setEdit({ ...edit, name: v })} required />
            <SelectField
              label={t('ops.varType')}
              value={edit.type}
              onChange={(v) => setEdit({ ...edit, type: v })}
              options={['String', 'Integer', 'Long', 'Double', 'Boolean', 'Date', 'Json'].map((x) => ({ value: x, label: x }))}
            />
            <TextArea label={t('ops.varValue')} hint={t('ops.varValueHint')} value={edit.value} onChange={(v) => setEdit({ ...edit, value: v })} />
          </>
        )}
      </ReasonDialog>
    </>
  );
}

function HistoryLog({ instanceId, kind, at }: { instanceId: string; kind: HistoryLogKind; at?: ActivitySelection }) {
  const { t } = useTranslation();
  const narrowed = at && !at.root && historyFilterable(kind) ? at : undefined;
  const list = usePaged((p) => api.processes.history(instanceId, kind, p, narrowed), [instanceId, kind, narrowed?.activityInstanceId]);
  return (
    <>
      {at && !at.root && !narrowed && <Notice>{t('ops.logNotFiltered')}</Notice>}
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.loading && !list.data && <Loading />}
      {list.data?.items.length === 0 && <Empty message={t('ops.noHistory')} />}
      {list.data && list.data.items.length > 0 && (
        <DataTable
          caption={t(`ops.log.${kind}`)}
          captionHidden
          rows={list.data.items}
          rowKey={(r) => r.id}
          columns={[
            { key: 'time', header: t('task.when'), render: (r) => fmtDate(r.time) },
            { key: 'activity', header: t('diagram.activity'), render: (r) => r.activityName ?? r.activityId ?? '—' },
            { key: 'actor', header: t('audit.actor'), render: (r) => r.actor ?? '—' },
            { key: 'operation', header: t('ops.operation'), render: (r) => r.operation ?? '—' },
            {
              key: 'change',
              header: t('ops.change'),
              render: (r) =>
                r.property ? (
                  <>
                    {r.property}: <del>{JSON.stringify(r.oldValue)}</del> → <ins>{JSON.stringify(r.newValue)}</ins>
                  </>
                ) : (
                  (r.message ?? '—')
                ),
            },
            {
              key: 'link',
              header: '',
              render: (r) => (kind === 'called' && r.linkId ? <Link to={`/ops/instances/${encodeURIComponent(r.linkId)}`}>{t('common.open')}</Link> : null),
            },
          ]}
        />
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
    </>
  );
}

function CancelDialog({ instanceId, onClose, onDone }: { instanceId: string; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const [mode, setMode] = useState<'BUSINESS' | 'TECHNICAL'>('BUSINESS');
  const { hasRole } = useAuth();
  const [skipL, setSkipL] = useState(false);
  const [skipIo, setSkipIo] = useState(false);
  const preview = useAsync(() => api.processes.cancelPreview(instanceId, mode), [instanceId, mode]);
  const act = useAction();
  return (
    <ReasonDialog
      open
      danger
      title={t('ops.cancelInstance')}
      preview={preview.data}
      busy={act.busy}
      error={act.error ?? preview.error}
      onClose={onClose}
      onConfirm={(reason) =>
        void act.run((k) => (mode === 'TECHNICAL' && (skipL || skipIo) ? http.del(`/engine-rest/process-instance/${encodeURIComponent(instanceId)}`, { skipCustomListeners: skipL, skipIoMappings: skipIo, deleteReason: reason }, k) : api.processes.cancel(instanceId, mode, reason, k)).then(() => true)).then((ok) => {
          if (ok) {
            announce(t('ops.cancelled'));
            onClose();
            onDone();
          }
        })
      }
    >
      <fieldset className="field">
        <legend>{t('ops.cancelMode')}</legend>
        {(['BUSINESS', 'TECHNICAL'] as const).map((m) => (
          <label key={m} className="choice">
            <input type="radio" name="cmode" checked={mode === m} onChange={() => setMode(m)} /> {t(`ops.cancelMode${m}`)}
          </label>
        ))}
      </fieldset>
      {mode === 'TECHNICAL' && hasRole('PLATFORM_OPERATOR') && (
        <>
          <Checkbox label={t('fld.skipCustomListeners')} checked={skipL} onChange={setSkipL} />
          <Checkbox label={t('fld.skipIoMappings')} checked={skipIo} onChange={setSkipIo} />
        </>
      )}
    </ReasonDialog>
  );
}

function SuspendDialog({ instanceId, suspended, onClose, onDone }: { instanceId: string; suspended: boolean; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const [redate, setRedate] = useState(true);
  const act = useAction();
  return (
    <ReasonDialog
      open
      title={suspended ? t('ops.resume') : t('ops.suspend')}
      busy={act.busy}
      error={act.error}
      onClose={onClose}
      onConfirm={(reason) =>
        void act.run(() => api.processes.suspend(instanceId, !suspended, reason, suspended ? redate : undefined).then(() => true)).then((ok) => {
          if (ok) {
            onClose();
            onDone();
          }
        })
      }
    >
      {suspended && <Checkbox label={t('ops.redateDeadlines')} hint={t('ops.redateHint')} checked={redate} onChange={setRedate} />}
    </ReasonDialog>
  );
}

function ModifyDialog({ instanceId, tree, picked, onClose, onDone }: { instanceId: string; tree?: ActivityInstance; picked: string[]; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const acts = flattenTree(tree).filter((a) => a.activityType !== 'processDefinition');
  const [instr, setInstr] = useState<ModificationInstruction[]>(() => [
    ...acts.filter((a) => picked.includes(a.activityId)).map((a) => ({ type: 'cancel' as ModificationKind, activityInstanceId: a.id, activityId: a.activityId })),
    ...picked.filter((p) => !acts.some((a) => a.activityId === p)).map((p) => ({ type: 'startBeforeActivity' as ModificationKind, activityId: p })),
  ]);
  const [preview, setPreview] = useState<ConsequencePreview>();
  const [reason, setReason] = useState('');
  const act = useAction();
  const upd = (i: number, p: Partial<ModificationInstruction>) => setInstr(instr.map((x, j) => (j === i ? { ...x, ...p } : x)));
  return (
    <Dialog
      open
      wide
      title={t('ops.modify')}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button type="button" disabled={instr.length === 0} onClick={() => void act.run(() => api.processes.modifyPreview(instanceId, instr)).then((p) => p && setPreview(p))}>
            {t('ops.previewConsequences')}
          </button>
          <button
            type="button"
            className="primary"
            disabled={!preview || preview.blocked || reason.trim().length < 5 || act.busy}
            onClick={() =>
              void act.run((k) => api.processes.modify(instanceId, instr, reason, k).then(() => true)).then((ok) => {
                if (ok) {
                  onClose();
                  onDone();
                }
              })
            }
          >
            {t('ops.applyModification')}
          </button>
        </>
      }
    >
      <p className="hint">{t('ops.modifyHint')}</p>
      <ol className="instructions">
        {instr.map((x, i) => (
          <li key={i}>
            <SelectField
              label={t('ops.instructionType')}
              value={x.type}
              onChange={(v) => {
                upd(i, { type: v as ModificationKind });
                setPreview(undefined);
              }}
              options={(['startBeforeActivity', 'startAfterActivity', 'startTransition', 'cancel'] as const).map((k) => ({ value: k, label: t(`ops.instr.${k}`) }))}
            />
            {x.type === 'cancel' ? (
              <SelectField
                label={t('ops.activityInstance')}
                value={x.activityInstanceId ?? ''}
                onChange={(v) => upd(i, { activityInstanceId: v })}
                placeholder={t('form.choose')}
                options={acts.map((a) => ({ value: a.id, label: `${a.activityName ?? a.activityId} (${a.id})` }))}
              />
            ) : x.type === 'startTransition' ? (
              <TextField label={t('ops.transitionId')} value={x.transitionId ?? ''} onChange={(v) => upd(i, { transitionId: v })} />
            ) : (
              <TextField label={t('ops.activityId')} value={x.activityId ?? ''} onChange={(v) => upd(i, { activityId: v })} />
            )}
            <button type="button" className="link danger" onClick={() => setInstr(instr.filter((_, j) => j !== i))}>
              {t('common.remove')}
            </button>
          </li>
        ))}
      </ol>
      <button type="button" onClick={() => setInstr([...instr, { type: 'startBeforeActivity', activityId: '' }])}>
        {t('ops.addInstruction')}
      </button>
      <PreviewList preview={preview} />
      <TextArea label={t('common.reason')} required value={reason} onChange={setReason} hint={t('common.reasonHint')} />
      <ProblemAlert error={act.error} />
    </Dialog>
  );
}

function MoveAcrossDialog({ instanceId, tree, onClose, onDone }: { instanceId: string; tree?: ActivityInstance; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const acts = flattenTree(tree).filter((a) => a.activityType !== 'processDefinition');
  const [from, setFrom] = useState('');
  const [target, setTarget] = useState('');
  const [targetAct, setTargetAct] = useState('');
  const act = useAction();
  return (
    <ReasonDialog
      open
      title={t('ops.moveAcross')}
      busy={act.busy}
      error={act.error}
      onClose={onClose}
      onConfirm={(reason) =>
        void act
          .run((k) => api.processes.moveAcross(instanceId, { fromActivityInstanceId: from, targetInstanceId: target, targetActivityId: targetAct, reason }, k).then(() => true))
          .then((ok) => {
            if (ok) {
              onClose();
              onDone();
            }
          })
      }
    >
      <p className="hint">{t('ops.moveAcrossHint')}</p>
      <SelectField label={t('ops.activityInstance')} value={from} onChange={setFrom} placeholder={t('form.choose')} options={acts.map((a) => ({ value: a.id, label: a.activityName ?? a.activityId }))} />
      <TextField label={t('ops.targetInstance')} value={target} onChange={setTarget} required />
      <TextField label={t('ops.activityId')} value={targetAct} onChange={setTargetAct} required />
    </ReasonDialog>
  );
}

function InjectDialog({ instanceId, picked, onClose }: { instanceId: string; picked: string[]; onClose: () => void }) {
  const { t } = useTranslation();
  const [kind, setKind] = useState<'USER_TASK' | 'EMBEDDED_SUBPROCESS' | 'PARALLEL_USER_TASK'>('USER_TASK');
  const [name, setName] = useState('');
  const [after, setAfter] = useState(picked[0] ?? '');
  const [seat, setSeat] = useState('');
  const act = useAction();
  const [done, setDone] = useState<string>();
  return (
    <ReasonDialog
      open
      title={t('ops.inject')}
      busy={act.busy}
      error={act.error}
      onClose={onClose}
      onConfirm={(reason) =>
        void act.run((k) => api.processes.inject(instanceId, { kind, name, afterActivityId: after, assigneeSeatId: seat || undefined, reason }, k)).then((p) => p && setDone(p.packageId ?? p.derivedDefinitionId ?? p.state ?? 'OK'))
      }
    >
      <Notice>{t('ops.injectHint')}</Notice>
      {done && (
        <Notice tone="ok">
          {t('ops.injectCreated')} <Link to={`/modeller/${encodeURIComponent(done)}`}>{t('common.open')}</Link>
        </Notice>
      )}
      <SelectField
        label={t('ops.injectKind')}
        value={kind}
        onChange={(v) => setKind(v as typeof kind)}
        options={(['USER_TASK', 'EMBEDDED_SUBPROCESS', 'PARALLEL_USER_TASK'] as const).map((k) => ({ value: k, label: t(`ops.injectKind${k}`) }))}
      />
      <TextField label={t('common.name')} value={name} onChange={setName} required />
      <TextField label={t('ops.afterActivity')} value={after} onChange={setAfter} required />
      <TextField label={t('ops.assigneeSeat')} value={seat} onChange={setSeat} />
    </ReasonDialog>
  );
}

function MessageDialog({ instanceId, businessKey, onClose, onDone }: { instanceId: string; businessKey?: string; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [branch, setBranch] = useState('');
  const [vars, setVars] = useState('{}');
  const [kind, setKind] = useState<'message' | 'signal'>('message');
  const act = useAction();
  return (
    <Dialog
      open
      title={t('ops.sendMessage')}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="primary"
            disabled={!name || act.busy}
            onClick={() => {
              let v: Record<string, unknown> = {};
              try {
                v = JSON.parse(vars) as Record<string, unknown>;
              } catch {
                /* invalid JSON → empty */
              }
              void act
                .run((k) =>
                  (kind === 'message'
                    ? api.processes.message({ name, processInstanceId: instanceId, businessKey, branchKey: branch || undefined, variables: v }, k)
                    : api.processes.signal({ name, variables: v, tenantOnly: true }, k)
                  ).then(() => true),
                )
                .then((ok) => {
                  if (ok) {
                    onClose();
                    onDone();
                  }
                });
            }}
          >
            {t('common.send')}
          </button>
        </>
      }
    >
      <SelectField label={t('ops.eventKind')} value={kind} onChange={(v) => setKind(v as 'message' | 'signal')} options={[{ value: 'message', label: t('ops.message') }, { value: 'signal', label: t('ops.signal') }]} />
      <TextField label={t('common.name')} value={name} onChange={setName} required />
      {kind === 'message' && <TextField label={t('ops.branchKey')} hint={t('ops.branchKeyHint')} value={branch} onChange={setBranch} />}
      <TextArea label={t('ops.variablesJson')} value={vars} onChange={setVars} />
      <ProblemAlert error={act.error} />
    </Dialog>
  );
}

function RestartDialog({ definitionId, instanceId, picked, onClose }: { definitionId: string; instanceId: string; picked: string[]; onClose: () => void }) {
  const { t } = useTranslation();
  const [activity, setActivity] = useState(picked[0] ?? '');
  const [initial, setInitial] = useState(true);
  const act = useAction();
  const [batch, setBatch] = useState<string>();
  return (
    <ReasonDialog
      open
      title={t('ops.restart')}
      busy={act.busy}
      error={act.error}
      onClose={onClose}
      onConfirm={(reason) =>
        void act
          .run((k) => api.processes.restart({ definitionId, instanceIds: [instanceId], startBeforeActivityId: activity, initialVariables: initial, reason }, k))
          .then((b) => b && setBatch(b.id))
      }
    >
      {batch && (
        <Notice tone="ok">
          <Link to={`/ops/batches/${encodeURIComponent(batch)}`}>{t('batch.created', { id: batch })}</Link>
        </Notice>
      )}
      <TextField label={t('ops.restartFrom')} hint={t('ops.restartHint')} value={activity} onChange={setActivity} required />
      <Checkbox label={t('ops.initialVariables')} checked={initial} onChange={setInitial} />
    </ReasonDialog>
  );
}


/** FC-01 ad-hoc sub-process: show enabled activities and start one, or complete the sub-process (contract /ad-hoc). */
function AdHocDialog({ instanceId, picked, onClose, onDone }: { instanceId: string; picked: string[]; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const [adHocId, setAdHocId] = useState(picked[0] ?? '');
  const [loaded, setLoaded] = useState<Record<string, unknown>>();
  const [activity, setActivity] = useState('');
  const [reason, setReason] = useState('');
  const act = useAction();
  const enabled = ((loaded?.enabledActivityIds ?? loaded?.enabledActivities ?? []) as unknown[]).map((x) =>
    typeof x === 'string' ? x : String((x as Record<string, unknown>).activityId ?? (x as Record<string, unknown>).id),
  );
  return (
    <Dialog
      open
      wide
      title={t('ops.adHoc')}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button
            type="button"
            disabled={!loaded || reason.trim().length < 5 || act.busy}
            onClick={() =>
              void act.run((k) => api.processes.adHocComplete(instanceId, adHocId, reason, k)).then((r) => {
                if (r) {
                  onClose();
                  onDone();
                }
              })
            }
          >
            {t('ops.adHocComplete')}
          </button>
          <button
            type="button"
            className="primary"
            disabled={!activity || act.busy}
            onClick={() =>
              void act.run((k) => api.processes.adHocStart(instanceId, adHocId, activity, k)).then((r) => {
                if (r) {
                  setLoaded(r);
                  announce(t('ops.adHocStarted'));
                  onDone();
                }
              })
            }
          >
            {t('ops.adHocStart')}
          </button>
        </>
      }
    >
      <p className="hint">{t('ops.adHocHint')}</p>
      <div className="filters">
        <TextField label={t('ops.adHocId')} value={adHocId} onChange={setAdHocId} required />
        <button type="button" disabled={!adHocId} onClick={() => void act.run(() => api.processes.adHoc(instanceId, adHocId)).then((r) => r && setLoaded(r))}>
          {t('common.open')}
        </button>
      </div>
      {loaded && (
        <SelectField
          label={t('ops.adHocActivity')}
          value={activity}
          onChange={setActivity}
          placeholder={t('form.choose')}
          options={enabled.map((a) => ({ value: a, label: a }))}
        />
      )}
      <TextArea label={t('common.reason')} hint={t('ops.adHocReasonHint')} value={reason} onChange={setReason} />
      <ProblemAlert error={act.error} />
    </Dialog>
  );
}