// SPDX-License-Identifier: Apache-2.0
/**
 * Second Operaton parity pass on /engine-rest (platform roles only): runtime jobs, variable instances, runtime batches
 * with failed jobs, deployment resource preview / download, bulk job priority, DRD page, metrics chart, operation-log
 * annotation, decision TTL, attachments by URL. Plus the /admin/plugins console. Written from the REST contract.
 */
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { download, http } from '../api/client';
import { useAction, useAsync } from '../lib/hooks';
import { fmtDate } from '../i18n';
import { BpmnDiagram } from '../components/BpmnDiagram';
import { LineChart } from '../components/Charts';
import { DmnView } from '../components/DmnView';
import { DataTable, JsonView, Notice, PageHeader, ProblemAlert, Section, TextField } from '../components/ui';
import { ModulePage, c, engineDate, f, type ScreenDef } from './engine';
import type { Row } from './engine';

const E = '/engine-rest';
const MAX = { maxResults: 200 };

export const jobsEngine: ScreenDef = {
  id: 'jobs',
  title: 'mod.op2.jobs',
  list: `${E}/job`,
  listQuery: MAX,
  unpaged: true,
  filters: [f.text('processInstanceId'), f.text('jobDefinitionId'), f.bool('withException'), f.bool('noRetriesLeft')],
  columns: [c('id'), c('dueDate'), c('retries'), c('priority'), c('suspended'), c('exceptionMessage')],
  rowKey: ['id'],
  sections: [{ id: 'trace', label: 'ops.stacktrace', path: `${E}/job/{id}/stacktrace` }, { id: 'log', label: 'ops.log.jobs', path: `${E}/history/job-log?jobId={id}` }],
  actions: [
    { id: 'retries', label: 'ops.setRetries', method: 'PUT', path: `${E}/job/{id}/retries`, fields: [f.num('retries', undefined, true)], noJson: true },
    { id: 'suspend', label: 'act.setSuspension', method: 'PUT', path: `${E}/job/{id}/suspended`, fields: [f.bool('suspended')], noJson: true },
    { id: 'due', label: 'act.reschedule', method: 'PUT', path: `${E}/job/{id}/duedate`, fields: [f.text('duedate', 'fld.dueDate', true), f.bool('cascade')], noJson: true, hint: 'mod.op2.dueHint' },
    { id: 'recalc', label: 'act.recalculateDue', path: `${E}/job/{id}/duedate/recalculate`, fields: [f.q(f.bool('creationDateBased'))], noJson: true },
    { id: 'exec', label: 'act.runNow', path: `${E}/job/{id}/execute`, noJson: true },
    { id: 'delete', label: 'common.delete', method: 'DELETE', path: `${E}/job/{id}`, noJson: true, danger: true },
  ],
};

/** Engine value types a user can type (Bytes and File go through the binary upload). */
const VAR_TYPES = ['String', 'Integer', 'Long', 'Double', 'Short', 'Boolean', 'Date', 'Null', 'Json', 'Xml', 'Object'];

export const variablesEngine: ScreenDef = {
  id: 'variables',
  title: 'mod.op2.variables',
  list: `${E}/variable-instance?deserializeValues=false`,
  listQuery: MAX,
  unpaged: true,
  filters: [f.text('processInstanceIdIn', 'fld.processInstanceId', true), f.text('variableNameLike', 'fld.variableName'), f.text('activityInstanceIdIn', 'fld.activityInstanceId')],
  requireFilters: ['processInstanceIdIn'],
  columns: [c('name'), c('type'), c('value', 'fld.value'), c('valueInfo'), c('activityInstanceId'), c('executionId')],
  rowKey: ['id'],
  // Operaton's "add variable" dialog: name, type, value on the instance chosen in the filter.
  toolbar: [{ id: 'add', label: 'ops.addVariable', method: 'PUT', path: `${E}/process-instance/{processInstanceIdIn}/variables/{name}`, fields: [f.path(f.text('name', undefined, true)), f.select('type', VAR_TYPES, undefined, true), f.json('value', 'fld.value', true), f.json('valueInfo')], noJson: true, hint: 'mod.op4.varValueHint', defaults: () => ({ type: 'String' }) }],
  actions: [
    { id: 'set', label: 'act.update', method: 'PUT', path: `${E}/process-instance/{processInstanceId}/variables/{name}`, fields: [f.select('type', VAR_TYPES, undefined, true), f.json('value', 'fld.value', true), f.json('valueInfo')], noJson: true, hint: 'mod.op4.varValueHint', defaults: (r) => ({ type: r?.type === undefined ? undefined : String(r.type), value: r?.value === undefined ? undefined : JSON.stringify(r.value), valueInfo: r?.valueInfo && Object.keys(r.valueInfo as Row).length ? JSON.stringify(r.valueInfo) : undefined }) },
    { id: 'local', label: 'act.setLocal', path: `${E}/execution/{executionId}/localVariables`, fields: [f.json('modifications', undefined, true)], noJson: true },
    { id: 'delete', label: 'common.delete', method: 'DELETE', path: `${E}/process-instance/{processInstanceId}/variables/{name}`, noJson: true, danger: true },
  ],
};

export function BinaryVariable() {
  const { t } = useTranslation();
  const [id, setId] = useState('');
  const [pi, setPi] = useState('');
  const [name, setName] = useState('');
  const act = useAction();
  return (
    <Section title={t('mod.op2.binary')}>
      <TextField label={t('fld.variableInstanceId')} value={id} onChange={setId} />
      <button type="button" disabled={!id} onClick={() => void act.run(() => download(`${E}/variable-instance/${encodeURIComponent(id)}/data`, undefined, 'variable.bin').then(() => true))}>{t('common.download')}</button>
      <TextField label={t('fld.processInstanceId')} value={pi} onChange={setPi} />
      <TextField label={t('fld.name')} value={name} onChange={setName} />
      <div className="field">
        <label htmlFor="var-up">{t('mod.op2.upload')}</label>
        <input
          id="var-up"
          type="file"
          disabled={!pi || !name}
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (!file) return;
            const fd = new FormData();
            fd.append('data', file, file.name);
            fd.append('valueType', 'Bytes');
            void act.run((k) => http.upload(`${E}/process-instance/${encodeURIComponent(pi)}/variables/${encodeURIComponent(name)}/data`, fd, k));
          }}
        />
      </div>
      <ProblemAlert error={act.error} />
    </Section>
  );
}

export const batchesEngine: ScreenDef = {
  id: 'batches',
  title: 'mod.op2.batches',
  list: `${E}/batch/statistics`,
  listQuery: MAX,
  unpaged: true,
  filters: [f.text('batchId'), f.text('type'), f.bool('suspended')],
  columns: [c('id'), c('type'), c('totalJobs'), c('remainingJobs'), c('failedJobs'), c('suspended')],
  rowKey: ['id'],
  sections: [{ id: 'failed', label: 'mod.op2.failedJobs', path: `${E}/job?jobDefinitionId={batchJobDefinitionId}&noRetriesLeft=true` }],
  actions: [
    { id: 'retryAll', label: 'act.retryAll', method: 'PUT', path: `${E}/job-definition/{batchJobDefinitionId}/retries`, fields: [f.num('retries', undefined, true)], noJson: true, defaults: () => ({ retries: 1 }) },
    { id: 'suspend', label: 'act.setSuspension', method: 'PUT', path: `${E}/batch/{id}/suspended`, fields: [f.bool('suspended')], noJson: true },
    { id: 'delete', label: 'common.delete', method: 'DELETE', path: `${E}/batch/{id}`, fields: [f.q(f.bool('cascade'))], noJson: true, danger: true },
  ],
};

export const opLog: ScreenDef = {
  id: 'opLog',
  title: 'mod.op2.opLog',
  list: `${E}/history/user-operation`,
  listQuery: { ...MAX, sortBy: 'timestamp', sortOrder: 'desc' },
  unpaged: true,
  filters: [f.text('processInstanceId'), f.text('userId'), f.text('operationType')],
  columns: [c('timestamp'), c('userId'), c('operationType'), c('entityType'), c('property'), c('annotation')],
  rowKey: ['id'],
  actions: [
    { id: 'annotate', label: 'act.annotate', method: 'PUT', path: `${E}/history/user-operation/{operationId}/set-annotation`, fields: [f.area('annotation', undefined, true)], noJson: true },
    { id: 'clear', label: 'common.clear', method: 'PUT', path: `${E}/history/user-operation/{operationId}/clear-annotation`, noJson: true },
  ],
};

export const decisionDefs: ScreenDef = {
  id: 'decisionDefs',
  title: 'mod.op2.decisionDefs',
  list: `${E}/decision-definition`,
  listQuery: { ...MAX, latestVersion: true },
  unpaged: true,
  columns: [c('name'), c('key'), c('version'), c('historyTimeToLive'), c('decisionRequirementsDefinitionId')],
  rowKey: ['id'],
  actions: [{ id: 'ttl', label: 'act.setTtl', method: 'PUT', path: `${E}/decision-definition/{id}/history-time-to-live`, fields: [f.num('historyTimeToLive')], noJson: true, hint: 'mod.op2.ttlClear', sendNulls: ['historyTimeToLive'] }],
};

export const attachments: ScreenDef = {
  id: 'attachments',
  title: 'mod.op2.attachments',
  list: `${E}/task/{taskId}/attachment`,
  unpaged: true,
  filters: [f.text('taskId', undefined, true)],
  requireFilters: ['taskId'],
  columns: [c('name'), c('type'), c('url'), c('createTime')],
  rowKey: ['id'],
  actions: [{ id: 'delete', label: 'common.delete', method: 'DELETE', path: `${E}/task/{taskId}/attachment/{id}`, noJson: true, danger: true }],
};

/** Operaton's bulk job retry dialog: pick failed jobs of an instance and choose what happens to the due date. */
export function BulkRetry() {
  const { t } = useTranslation();
  const [pi, setPi] = useState('');
  const [active, setActive] = useState('');
  const jobs = useAsync(() => http.get<Row[]>(`${E}/job`, { processInstanceId: active, withException: true, maxResults: 200 }), [active], !!active);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [retries, setRetries] = useState('1');
  const [due, setDue] = useState<'keep' | 'now' | 'custom'>('keep');
  const [when, setWhen] = useState('');
  const [res, setRes] = useState<{ ok: number; failed: string[] }>();
  const act = useAction();
  const dueValue = () => engineDate(due === 'now' ? new Date() : when);
  const run = () =>
    void act
      .run(async () => {
        const ids = [...picked];
        const r = await Promise.allSettled(
          ids.map(async (id) => {
            const p = `${E}/job/${encodeURIComponent(id)}`;
            if (due !== 'keep') await http.put(`${p}/duedate`, { duedate: dueValue() });
            await http.put(`${p}/retries`, { retries: Number(retries) });
          }),
        );
        return { ok: r.filter((x) => x.status === 'fulfilled').length, failed: ids.filter((_, i) => r[i].status === 'rejected') };
      })
      .then((x) => {
        if (x) {
          setRes(x);
          jobs.reload();
        }
      });
  return (
    <Section title={t('mod.op3.bulkRetry')}>
      <form className="filters" onSubmit={(e) => (e.preventDefault(), setActive(pi.trim()), setPicked(new Set()))}>
        <TextField label={t('fld.processInstanceId')} value={pi} onChange={setPi} required />
        <button type="submit">{t('common.open')}</button>
      </form>
      <ProblemAlert error={jobs.error ?? act.error} />
      {jobs.data && (
        <DataTable caption={t('mod.op2.failedJobs')} rows={jobs.data} rowKey={(r) => String(r.id)} selectable selected={picked} onSelect={setPicked} columns={[{ key: 'id', header: t('ops.job'), render: (r: Row) => String(r.id) }, { key: 'due', header: t('fld.dueDate'), render: (r: Row) => String(r.dueDate ?? '—') }, { key: 'msg', header: t('ops.message'), render: (r: Row) => String(r.exceptionMessage ?? '—') }]} />
      )}
      <TextField label={t('ops.retries')} type="number" min={1} value={retries} onChange={setRetries} />
      <fieldset className="field">
        <legend>{t('fld.dueDate')}</legend>
        {(['keep', 'now', 'custom'] as const).map((d) => (
          <label key={d} className="choice"><input type="radio" name="retry-due" checked={due === d} onChange={() => setDue(d)} /> {t(`mod.op3.due_${d}`)}</label>
        ))}
      </fieldset>
      {due === 'custom' && <TextField label={t('fld.dueDate')} type="datetime-local" value={when} onChange={setWhen} />}
      <button type="button" className="primary" disabled={picked.size === 0 || act.busy || (due === 'custom' && !when)} onClick={run}>{t('common.confirm')}</button>
      {res && <Notice tone={res.failed.length ? 'warn' : 'ok'}>{t('mod.op2.bulkResult', { ok: res.ok, failed: res.failed.join(', ') || '—' })}</Notice>}
    </Section>
  );
}

/** Bulk override: the engine has one call per job definition, so this applies it per definition and lists failures. */
export function BulkPriority() {
  const { t } = useTranslation();
  const [pd, setPd] = useState('');
  const [prio, setPrio] = useState('');
  const [inc, setInc] = useState(false);
  const [res, setRes] = useState<{ ok: number; failed: string[] }>();
  const act = useAction();
  const run = () =>
    void act
      .run(async () => {
        const defs = await http.get<Row[]>(`${E}/job-definition`, { processDefinitionId: pd });
        const r = await Promise.allSettled(defs.map((d) => http.put(`${E}/job-definition/${encodeURIComponent(String(d.id))}/jobPriority`, { priority: prio === '' ? null : Number(prio), includeJobs: inc })));
        return { ok: r.filter((x) => x.status === 'fulfilled').length, failed: defs.filter((_, i) => r[i].status === 'rejected').map((d) => String(d.id)) };
      })
      .then((x) => x && setRes(x));
  return (
    <Section title={t('mod.op2.bulkPriority')}>
      <TextField label={t('fld.processDefinitionId')} value={pd} onChange={setPd} required />
      <TextField label={t('fld.priority')} type="number" hint={t('mod.op2.priorityClear')} value={prio} onChange={setPrio} />
      <label className="choice"><input type="checkbox" checked={inc} onChange={(e) => setInc(e.target.checked)} /> {t('fld.includeJobs')}</label>
      <button type="button" disabled={!pd || act.busy} onClick={run}>{t('common.confirm')}</button>
      <ProblemAlert error={act.error} />
      {res && <Notice tone={res.failed.length ? 'warn' : 'ok'}>{t('mod.op2.bulkResult', { ok: res.ok, failed: res.failed.join(', ') || '—' })}</Notice>}
    </Section>
  );
}

export function DeploymentResources() {
  const { t } = useTranslation();
  const [id, setId] = useState('');
  const [dep, setDep] = useState('');
  const res = useAsync(() => http.get<Row[]>(`${E}/deployment/${encodeURIComponent(dep)}/resources`), [dep], !!dep);
  const [open, setOpen] = useState<{ name: string; text: string } | null>(null);
  const act = useAction();
  const data = (r: Row) => `${E}/deployment/${encodeURIComponent(dep)}/resources/${encodeURIComponent(String(r.id))}/data`;
  return (
    <Section title={t('mod.op2.resources')}>
      <form className="filters" onSubmit={(e) => (e.preventDefault(), setDep(id.trim()))}>
        <TextField label={t('fld.deploymentId')} value={id} onChange={setId} required />
        <button type="submit">{t('common.open')}</button>
      </form>
      <ProblemAlert error={res.error ?? act.error} />
      <ul>
        {res.data?.map((r) => (
          <li key={String(r.id)}>
            {String(r.name)}{' '}
            <button type="button" className="link" onClick={() => void act.run(() => http.get<unknown>(data(r), undefined)).then((x) => x !== undefined && setOpen({ name: String(r.name), text: typeof x === 'string' ? x : JSON.stringify(x, null, 2) }))}>{t('mod.op2.preview')}</button>{' '}
            <button type="button" className="link" onClick={() => void act.run(() => download(data(r), undefined, String(r.name)).then(() => true))}>{t('common.download')}</button>
          </li>
        ))}
      </ul>
      {open && (/\.bpmn/i.test(open.name) ? <BpmnDiagram title={open.name} xml={open.text} /> : /\.dmn/i.test(open.name) ? <DmnView title={open.name} xml={open.text} /> : <pre className="json">{open.text}</pre>)}
    </Section>
  );
}

export function DrdPage() {
  const { id = '' } = useParams();
  const { t } = useTranslation();
  const x = useAsync(() => http.get<{ dmnXml: string }>(`${E}/decision-requirements-definition/${encodeURIComponent(id)}/xml`), [id]);
  return (
    <>
      <PageHeader title={t('mod.op.drd')} />
      <ProblemAlert error={x.error} />
      {x.data && <DmnView title={id} xml={x.data.dmnXml} />}
    </>
  );
}

export function MetricsChart() {
  const { t } = useTranslation();
  const [name, setName] = useState('activity-instance-start');
  const m = useAsync(() => http.get<{ timestamp: string; value: number }[]>(`${E}/metrics`, { name, interval: 3600, aggregateByReporter: true, maxResults: 168 }), [name]);
  const rows = [...(m.data ?? [])].reverse();
  return (
    <Section title={t('mod.op2.metricsChart')}>
      <TextField label={t('fld.name')} value={name} onChange={setName} />
      <ProblemAlert error={m.error} />
      {rows.length > 0 && <LineChart title={name} labels={rows.map((r) => fmtDate(r.timestamp))} series={[{ name, values: rows.map((r) => Number(r.value)), className: 'series-1' }]} />}
    </Section>
  );
}

const plugins: ScreenDef = {
  id: 'plugins',
  title: 'mod.plugins.title',
  description: 'mod.plugins.desc',
  list: '/admin/plugins',
  unpaged: true,
  columns: [c('id'), c('version'), c('kind'), c('status')],
  rowKey: ['id'],
  actions: [
    { id: 'enable', label: 'act.activate', path: '/admin/plugins/{id}/enable', reason: 'body' },
    { id: 'disable', label: 'act.deactivate', path: '/admin/plugins/{id}/disable', reason: 'body', danger: true },
  ],
};

export function PluginsPage() {
  const { t } = useTranslation();
  const audit = useAsync(() => http.get<unknown>('/admin/plugins/audit'), []);
  return <ModulePage title="mod.plugins.title" screens={[plugins]} extra={[{ id: 'audit', label: 'nav.audit', node: <Section title={t('nav.audit')}><ProblemAlert error={audit.error} /><JsonView value={audit.data ?? []} /></Section> }]} />;
}
