// SPDX-License-Identifier: Apache-2.0
/**
 * R63 definition and instance views (PF-01 history, PF-02 heatmap, PF-17 Operate, PF-43/44 Control/Hub):
 * definition list, diagram with runtime tokens / history heatmap / duration overlay, instance search,
 * selection → R64 batch (retry, suspend, cancel, migrate, set variables, delete, restart) with server count preview.
 */
import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import type { BatchType, ConsequencePreview, InstanceQuery, InstanceState, ProcessInstance } from '../../api/types';
import { announce, useAction, useAsync, usePaged } from '../../lib/hooks';
import { fmtDate, fmtNumber } from '../../i18n';
import { BpmnDiagram, type OverlayMode } from '../../components/BpmnDiagram';
import { Badge, Checkbox, DataTable, Empty, Loading, Notice, PageHeader, Pagination, ProblemAlert, ReasonDialog, SearchBox, Section, SelectField, Tabs, TextArea, TextField } from '../../components/ui';

export function ProcessDefinitions() {
  const { t } = useTranslation();
  const [q, setQ] = useState('');
  const [latest, setLatest] = useState(true);
  const list = usePaged((p) => api.processes.definitions({ ...p, q, latest }), [q, latest]);
  return (
    <>
      <PageHeader title={t('ops.processesTitle')} />
      <div className="filters">
        <SearchBox label={t('common.search')} value={q} onChange={setQ} />
        <Checkbox label={t('ops.latestOnly')} checked={latest} onChange={setLatest} />
      </div>
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.loading && !list.data && <Loading />}
      {list.data?.items.length === 0 && <Empty message={t('common.emptyNone')} />}
      {list.data && list.data.items.length > 0 && (
        <>
          <DataTable
            caption={t('ops.processesTitle')}
            captionHidden
            rows={list.data.items}
            rowKey={(r) => r.id}
            columns={[
              { key: 'name', header: t('ops.definition'), render: (r) => <Link to={`/ops/processes/${encodeURIComponent(r.id)}`}>{r.name ?? r.key}</Link> },
              { key: 'key', header: t('ops.key') },
              { key: 'version', header: t('ops.version'), render: (r) => `${r.version}${r.versionTag ? ` (${r.versionTag})` : ''}` },
              { key: 'packageVersion', header: t('ops.package'), render: (r) => r.packageVersion ?? '—' },
              { key: 'running', header: t('ops.running'), render: (r) => fmtNumber(r.runningInstances) },
              { key: 'incidents', header: t('ops.incidents'), render: (r) => (r.incidents ? <Badge value={`${r.incidents}`} tone="bad" /> : '0') },
              { key: 'state', header: t('admin.status'), render: (r) => <Badge value={r.suspended ? 'SUSPENDED' : 'ACTIVE'} /> },
            ]}
          />
          <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
        </>
      )}
    </>
  );
}

const STATES: InstanceState[] = ['ACTIVE', 'SUSPENDED', 'INCIDENT', 'COMPLETED', 'CANCELED', 'UNKNOWN_OUTCOME'];
const BATCHES: BatchType[] = ['RETRY', 'SUSPEND', 'ACTIVATE', 'CANCEL', 'SET_VARIABLES', 'DELETE', 'RESTART', 'MIGRATE'];

export function DefinitionDetail() {
  const { id = '' } = useParams();
  const { t } = useTranslation();
  const nav = useNavigate();
  const def = useAsync(() => api.processes.definition(id), [id]);
  const xml = useAsync(() => api.processes.xml(id), [id]);
  const [mode, setMode] = useState<OverlayMode>('tokens');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const history = mode !== 'tokens';
  const stats = useAsync(
    () => api.processes.statistics(id, history ? 'history' : 'runtime', history ? { from: from || undefined, to: to || undefined } : {}, def.data?.key),
    [id, history, from, to, def.data?.key],
  );
  const [activity, setActivity] = useState<string>();
  const [state, setState] = useState<InstanceState | ''>(history ? '' : 'ACTIVE');
  const [bk, setBk] = useState('');
  const query: InstanceQuery = useMemo(
    () => ({ definitionId: id, definitionKey: def.data?.key, state: state || undefined, activityId: activity, businessKey: bk || undefined }),
    [id, def.data?.key, state, activity, bk],
  );
  const list = usePaged((p) => api.processes.instances({ ...p, ...query, history }), [query, history]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [batchType, setBatchType] = useState<BatchType | null>(null);
  const [preview, setPreview] = useState<ConsequencePreview>();
  const [payload, setPayload] = useState('');
  const [suspendOpen, setSuspendOpen] = useState(false);
  const [includeInstances, setIncludeInstances] = useState(true);
  const act = useAction();

  const batchQuery = (): InstanceQuery => (selected.size > 0 ? { instanceIds: [...selected] } : query);

  const openBatch = async (b: BatchType) => {
    if (b === 'MIGRATE') {
      nav(`/ops/migration?source=${encodeURIComponent(id)}${selected.size ? `&ids=${[...selected].join(',')}` : ''}`);
      return;
    }
    setBatchType(b);
    setPreview(undefined);
    setPayload(b === 'RESTART' && activity ? JSON.stringify({ startBeforeActivityId: activity, initialVariables: true }) : '');
    const p = await api.ops.batchPreview({ type: b, query: batchQuery(), reason: '' }).catch(() => undefined);
    setPreview(p);
  };

  return (
    <>
      <PageHeader
        title={def.data ? `${def.data.name ?? def.data.key} v${def.data.version}` : t('ops.definition')}
        actions={
          def.data && (
            <button type="button" onClick={() => setSuspendOpen(true)}>
              {def.data.suspended ? t('ops.activateDefinition') : t('ops.suspendDefinition')}
            </button>
          )
        }
      />
      <ProblemAlert error={def.error ?? xml.error ?? stats.error} />
      <Tabs
        label={t('diagram.mode')}
        active={mode}
        onChange={(m) => {
          setMode(m as OverlayMode);
          setState(m === 'tokens' ? 'ACTIVE' : '');
        }}
        tabs={[
          { id: 'tokens', label: t('diagram.modeTokens') },
          { id: 'heat', label: t('diagram.modeHeat') },
          { id: 'duration', label: t('diagram.modeDuration') },
        ]}
      />
      {history && (
        <div className="filters">
          <TextField label={t('common.from')} type="date" value={from} onChange={setFrom} />
          <TextField label={t('common.to')} type="date" value={to} onChange={setTo} />
        </div>
      )}
      {xml.data && (
        <BpmnDiagram
          title={def.data?.name ?? id}
          xml={xml.data.bpmn20Xml}
          stats={stats.data?.activities}
          flows={stats.data?.flows}
          mode={mode}
          selected={activity ? [activity] : []}
          onSelect={(a) => setActivity((cur) => (cur === a ? undefined : a))}
        />
      )}
      {activity && (
        <Notice>
          {t('ops.filteredByActivity', { id: activity })}{' '}
          <button type="button" className="link" onClick={() => setActivity(undefined)}>
            {t('common.clear')}
          </button>
        </Notice>
      )}
      <Section title={history ? t('ops.historicInstances') : t('ops.runningInstances')}>
        <div className="filters">
          <SearchBox label={t('ops.businessKey')} value={bk} onChange={setBk} />
          <SelectField label={t('admin.status')} value={state} onChange={(v) => setState(v as InstanceState)} placeholder={t('common.all')} options={STATES.map((s) => ({ value: s, label: t(`enum.${s}`) }))} />
        </div>
        <div className="toolbar bulk" role="group" aria-label={t('ops.batchActions')}>
          <span>{selected.size > 0 ? t('common.selectedN', { n: selected.size }) : t('ops.allMatching')}</span>
          {BATCHES.map((b) => (
            <button key={b} type="button" onClick={() => void openBatch(b)}>
              {t(`batch.type.${b}`)}
            </button>
          ))}
        </div>
        <ProblemAlert error={list.error} onRetry={list.reload} />
        {list.loading && !list.data && <Loading />}
        {list.data?.items.length === 0 && <Empty message={t('common.emptyFiltered')} />}
        {list.data && list.data.items.length > 0 && (
          <>
            <InstanceTable rows={list.data.items} selected={selected} onSelect={setSelected} />
            <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
          </>
        )}
      </Section>
      <ReasonDialog
        open={batchType !== null}
        title={batchType ? t(`batch.type.${batchType}`) : ''}
        preview={preview}
        busy={act.busy}
        error={act.error}
        danger={batchType === 'DELETE' || batchType === 'CANCEL'}
        onClose={() => setBatchType(null)}
        onConfirm={(reason) => {
          let extra: Record<string, unknown> | undefined;
          try {
            extra = payload ? (JSON.parse(payload) as Record<string, unknown>) : undefined;
          } catch {
            extra = undefined;
          }
          void act.run((k) => api.ops.createBatch({ type: batchType!, query: batchQuery(), payload: extra, reason }, k)).then((b) => {
            if (b) {
              announce(t('batch.created', { id: b.id }));
              setBatchType(null);
              nav(`/ops/batches/${encodeURIComponent(b.id)}`);
            }
          });
        }}
      >
        {(batchType === 'SET_VARIABLES' || batchType === 'RESTART') && (
          <TextArea label={t('batch.payload')} hint={t(`batch.payloadHint.${batchType}`)} value={payload} onChange={setPayload} />
        )}
      </ReasonDialog>
      <ReasonDialog
        open={suspendOpen}
        title={def.data?.suspended ? t('ops.activateDefinition') : t('ops.suspendDefinition')}
        busy={act.busy}
        error={act.error}
        onClose={() => setSuspendOpen(false)}
        onConfirm={(reason) =>
          void act.run(() => api.processes.suspendDefinition(id, !def.data!.suspended, reason, includeInstances).then(() => true)).then((ok) => {
            if (ok) {
              setSuspendOpen(false);
              def.reload();
            }
          })
        }
      >
        <Checkbox label={t('ops.includeInstances')} checked={includeInstances} onChange={setIncludeInstances} />
      </ReasonDialog>
    </>
  );
}

export function InstanceTable({ rows, selected, onSelect }: { rows: ProcessInstance[]; selected?: Set<string>; onSelect?: (s: Set<string>) => void }) {
  const { t } = useTranslation();
  return (
    <DataTable
      caption={t('ops.instances')}
      captionHidden
      rows={rows}
      rowKey={(r) => r.id}
      selectable={!!onSelect}
      selected={selected}
      onSelect={onSelect}
      columns={[
        { key: 'id', header: t('ops.instance'), render: (r) => <Link to={`/ops/instances/${encodeURIComponent(r.id)}`}>{r.id}</Link> },
        { key: 'businessKey', header: t('ops.businessKey'), render: (r) => r.businessKey ?? '—' },
        { key: 'state', header: t('admin.status'), render: (r) => <Badge value={r.state} /> },
        { key: 'lifecycleState', header: t('ops.lifecycle'), render: (r) => r.lifecycleState ?? '—' },
        { key: 'version', header: t('ops.version'), render: (r) => String(r.definitionVersion ?? '—') },
        { key: 'start', header: t('ops.started'), render: (r) => fmtDate(r.startTime) },
        { key: 'end', header: t('ops.ended'), render: (r) => fmtDate(r.endTime) },
        { key: 'incidents', header: t('ops.incidents'), render: (r) => fmtNumber(r.incidents ?? 0) },
      ]}
    />
  );
}

/** Cross-definition instance search (PF-17 Operate). */
export function InstanceSearch() {
  const { t } = useTranslation();
  const [bk, setBk] = useState('');
  const [state, setState] = useState('');
  const [history, setHistory] = useState(false);
  const list = usePaged((p) => api.processes.instances({ ...p, businessKey: bk || undefined, state: (state || undefined) as InstanceState, history }), [bk, state, history]);
  return (
    <>
      <div className="filters">
        <SearchBox label={t('ops.businessKey')} value={bk} onChange={setBk} />
        <SelectField label={t('admin.status')} value={state} onChange={setState} placeholder={t('common.all')} options={STATES.map((s) => ({ value: s, label: t(`enum.${s}`) }))} />
        <Checkbox label={t('ops.includeHistory')} checked={history} onChange={setHistory} />
      </div>
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.data && <InstanceTable rows={list.data.items} />}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
    </>
  );
}
