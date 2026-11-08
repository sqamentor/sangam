// SPDX-License-Identifier: Apache-2.0
/**
 * Operaton Tasklist parity on /engine-rest (platform roles only): guided filter editor (general, criteria, card
 * variables, global read), filter task lists with card variables, ad-hoc criteria search with sort by variable,
 * task date editing and attachment by URL. Written from the REST contract. Also AddVariables, used by Sangam forms.
 */
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { http, request } from '../api/client';
import { announce, useAction, useAsync } from '../lib/hooks';
import { DataTable, Dialog, Empty, Notice, ProblemAlert, Section, SelectField, TextField, Checkbox } from '../components/ui';
import { cell, engineDate, type Row } from './engine';

const E = '/engine-rest';
const CRITERIA = ['assignee', 'assigneeLike', 'owner', 'candidateGroup', 'candidateUser', 'involvedUser', 'unassigned', 'name', 'nameLike', 'description', 'descriptionLike', 'priority', 'minPriority', 'maxPriority', 'dueBefore', 'dueAfter', 'followUpBefore', 'followUpAfter', 'createdBefore', 'createdAfter', 'processDefinitionKey', 'processInstanceBusinessKey', 'taskDefinitionKey', 'tenantIdIn'];
const BOOL = new Set(['unassigned']);
const NUM = new Set(['priority', 'minPriority', 'maxPriority']);
const LIST = new Set(['tenantIdIn']);

type Crit = { key: string; value: string };

function toQuery(rows: Crit[]): Row {
  const out: Row = {};
  for (const r of rows) {
    if (!r.key) continue;
    out[r.key] = BOOL.has(r.key) ? r.value !== 'false' : NUM.has(r.key) ? Number(r.value) : LIST.has(r.key) ? r.value.split(',').map((s) => s.trim()) : r.value;
  }
  return out;
}

function CriteriaRows({ rows, onChange }: { rows: Crit[]; onChange: (r: Crit[]) => void }) {
  const { t } = useTranslation();
  return (
    <fieldset className="field">
      <legend>{t('mod.op3.criteria')}</legend>
      {rows.map((r, i) => (
        <div key={i} className="filters">
          <SelectField label={t('mod.op3.criterion')} value={r.key} onChange={(v) => onChange(rows.map((x, j) => (j === i ? { ...x, key: v } : x)))} placeholder={t('form.choose')} options={CRITERIA.map((c) => ({ value: c, label: c }))} />
          <TextField label={t('fld.value')} value={r.value} onChange={(v) => onChange(rows.map((x, j) => (j === i ? { ...x, value: v } : x)))} />
          <button type="button" className="link danger" onClick={() => onChange(rows.filter((_, j) => j !== i))}>{t('common.remove')}</button>
        </div>
      ))}
      <button type="button" onClick={() => onChange([...rows, { key: '', value: '' }])}>{t('mod.op3.addCriterion')}</button>
    </fieldset>
  );
}

type FilterRow = { id?: string; name?: string; owner?: string; query?: Row; properties?: Row };

/** Operaton's filter modal: general, criteria, card variables, and "visible to everyone". */
function FilterEditor({ filter, onClose, onDone }: { filter: FilterRow; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const p = filter.properties ?? {};
  const [name, setName] = useState(filter.name ?? '');
  const [owner, setOwner] = useState(filter.owner ?? '');
  const [priority, setPriority] = useState(String(p.priority ?? 0));
  const [description, setDescription] = useState(String(p.description ?? ''));
  const [refresh, setRefresh] = useState(Boolean(p.refresh ?? true));
  const [crit, setCrit] = useState<Crit[]>(Object.entries(filter.query ?? {}).map(([key, v]) => ({ key, value: Array.isArray(v) ? v.join(',') : String(v) })));
  const [vars, setVars] = useState(((p.variables ?? []) as { name: string; label: string }[]).map((x) => `${x.name}:${x.label}`).join(', '));
  // "Visible to everyone" is the filter's GLOBAL (type 0) READ authorization: show it as it is, create it once, revoke it on untick.
  const global = useAsync(() => http.get<Row[]>(`${E}/authorization`, { type: 0, resourceType: 5, resourceId: filter.id }), [filter.id], !!filter.id);
  const [everyoneEdit, setEveryone] = useState<boolean | null>(null);
  const everyone = everyoneEdit ?? (global.data?.length ?? 0) > 0;
  const act = useAction();
  const save = () =>
    void act
      .run(async (k) => {
        const body = {
          resourceType: 'Task',
          name,
          owner: owner || undefined,
          query: toQuery(crit),
          properties: { priority: Number(priority), description, refresh, variables: vars.split(',').map((s) => s.trim()).filter(Boolean).map((s) => ({ name: s.split(':')[0], label: s.split(':')[1] ?? s.split(':')[0] })) },
        };
        let id = filter.id;
        if (id) await http.put(`${E}/filter/${encodeURIComponent(id)}`, body, k);
        else id = (await http.post<{ id: string }>(`${E}/filter/create`, body, k)).id;
        const existing = global.data ?? [];
        if (everyone && existing.length === 0) await http.post(`${E}/authorization/create`, { type: 0, resourceType: 5, resourceId: id, userId: '*', permissions: ['READ'] });
        if (!everyone) for (const a of existing) await http.del(`${E}/authorization/${encodeURIComponent(String(a.id))}`);
        return true;
      })
      .then((ok) => ok && onDone());
  return (
    <Dialog open wide title={t('mod.op.filters')} onClose={onClose} footer={<><button type="button" onClick={onClose}>{t('common.cancel')}</button><button type="button" className="primary" disabled={!name || act.busy} onClick={save}>{t('common.save')}</button></>}>
      <TextField label={t('common.name')} required value={name} onChange={setName} />
      <TextField label={t('fld.owner')} value={owner} onChange={setOwner} />
      <TextField label={t('fld.priority')} type="number" value={priority} onChange={setPriority} />
      <TextField label={t('fld.description')} value={description} onChange={setDescription} />
      <Checkbox label={t('mod.op3.autoRefresh')} checked={refresh} onChange={setRefresh} />
      <CriteriaRows rows={crit} onChange={setCrit} />
      <TextField label={t('mod.op3.cardVariables')} hint={t('mod.op3.cardVariablesHint')} value={vars} onChange={setVars} />
      <Checkbox label={t('mod.op3.everyone')} checked={everyone} onChange={setEveryone} />
      <ProblemAlert error={act.error ?? global.error} />
    </Dialog>
  );
}

/** Saved filters as a sidebar over their tasks, with card variables from the HAL response. */
export function FilterBoard() {
  const { t } = useTranslation();
  const list = useAsync(() => http.get<FilterRow[]>(`${E}/filter`, { resourceType: 'Task', itemCount: false }), []);
  const [sel, setSel] = useState<FilterRow | null>(null);
  const [edit, setEdit] = useState<FilterRow | null>(null);
  const tasks = useAsync(async () => {
    const r = await request<{ _embedded?: { task?: Row[]; variable?: Row[] } }>('GET', `${E}/filter/${encodeURIComponent(sel!.id!)}/list`, { query: { maxResults: 100 }, headers: { Accept: 'application/hal+json' } });
    return r._embedded?.task ?? [];
  }, [sel?.id], !!sel);
  const cards = ((sel?.properties?.variables ?? []) as { name: string; label: string }[]);
  const varOf = (task: Row, name: string) => {
    const all = ((task._embedded as Row | undefined)?.variable ?? []) as Row[];
    return all.find((v) => v.name === name)?.value;
  };
  return (
    <Section title={t('mod.op3.board')} actions={<button type="button" className="primary" onClick={() => setEdit({})}>{t('common.new')}</button>}>
      <ProblemAlert error={list.error ?? tasks.error} />
      <div className="workspace">
        <nav aria-label={t('mod.op.filters')} className="artifact-list">
          <ul>
            {list.data?.map((x) => (
              <li key={x.id}>
                <button type="button" aria-current={sel?.id === x.id ? 'true' : undefined} onClick={() => setSel(x)}>{x.name}</button>
              </li>
            ))}
          </ul>
        </nav>
        <div>
          {sel && <button type="button" onClick={() => setEdit(sel)}>{t('common.edit')}</button>}
          {sel && tasks.data?.length === 0 && <Empty message={t('common.emptyNone')} />}
          {sel && !!tasks.data?.length && (
            <DataTable
              caption={sel.name ?? ''}
              rows={tasks.data}
              rowKey={(r) => String(r.id)}
              columns={[
                { key: 'name', header: t('common.name'), render: (r: Row) => <Link to={`/tasks/${encodeURIComponent(String(r.id))}`}>{String(r.name ?? r.id)}</Link> },
                { key: 'assignee', header: t('fld.assignee'), render: (r: Row) => cell(r.assignee) },
                { key: 'due', header: t('fld.due'), render: (r: Row) => cell(r.due) },
                ...cards.map((v) => ({ key: `v-${v.name}`, header: v.label, render: (r: Row) => cell(varOf(r, v.name)) })),
              ]}
            />
          )}
        </div>
      </div>
      {edit && <FilterEditor filter={edit} onClose={() => setEdit(null)} onDone={() => { setEdit(null); list.reload(); }} />}
    </Section>
  );
}

/** Ad-hoc criteria search, including sort by process variable (POST /task). */
export function TaskSearch() {
  const { t } = useTranslation();
  const [crit, setCrit] = useState<Crit[]>([{ key: 'nameLike', value: '' }]);
  const [by, setBy] = useState('created');
  const [order, setOrder] = useState('desc');
  const [variable, setVariable] = useState('');
  const [vtype, setVtype] = useState('String');
  const [rows, setRows] = useState<Row[]>();
  const act = useAction();
  const run = () => {
    const sorting = variable ? [{ sortBy: 'processVariable', sortOrder: order, parameters: { variable, type: vtype } }] : [{ sortBy: by, sortOrder: order }];
    void act.run(() => request<Row[]>('POST', `${E}/task`, { body: { ...toQuery(crit), sorting }, query: { maxResults: 200 } })).then((r) => r && setRows(r));
  };
  return (
    <Section title={t('mod.op3.search')}>
      <CriteriaRows rows={crit} onChange={setCrit} />
      <div className="filters">
        <SelectField label={t('mod.op3.sortBy')} value={by} onChange={setBy} options={['created', 'dueDate', 'followUpDate', 'name', 'priority', 'assignee'].map((v) => ({ value: v, label: v }))} />
        <SelectField label={t('mod.op3.order')} value={order} onChange={setOrder} options={[{ value: 'asc', label: '▲' }, { value: 'desc', label: '▼' }]} />
        <TextField label={t('mod.op3.sortVariable')} hint={t('mod.op3.sortVariableHint')} value={variable} onChange={setVariable} />
        <SelectField label={t('fld.type')} value={vtype} onChange={setVtype} options={['String', 'Integer', 'Long', 'Double', 'Boolean', 'Date'].map((v) => ({ value: v, label: v }))} />
        <button type="button" className="primary" disabled={act.busy} onClick={run}>{t('common.search')}</button>
      </div>
      <ProblemAlert error={act.error} />
      {rows && (
        <DataTable caption={t('mod.op3.search')} captionHidden rows={rows} rowKey={(r) => String(r.id)} columns={[
          { key: 'name', header: t('common.name'), render: (r: Row) => <Link to={`/tasks/${encodeURIComponent(String(r.id))}`}>{String(r.name ?? r.id)}</Link> },
          { key: 'assignee', header: t('fld.assignee'), render: (r: Row) => cell(r.assignee) },
          { key: 'due', header: t('fld.due'), render: (r: Row) => cell(r.due) },
        ]} />
      )}
    </Section>
  );
}

/** Inline due and follow-up dates. The engine PUT replaces the whole task, so the current task is read first. */
export function TaskDates({ taskId }: { taskId: string }) {
  const { t } = useTranslation();
  const task = useAsync(() => http.get<Row>(`${E}/task/${encodeURIComponent(taskId)}`), [taskId]);
  const [due, setDue] = useState('');
  const [follow, setFollow] = useState('');
  const act = useAction();
  const iso = (v: string) => (v ? engineDate(v) : undefined);
  return (
    <Section title={t('mod.op3.dates')}>
      <ProblemAlert error={task.error ?? act.error} />
      <p className="small muted">{t('fld.due')}: {cell(task.data?.due)} · {t('fld.followUp')}: {cell(task.data?.followUp)}</p>
      <div className="filters">
        <TextField label={t('fld.due')} type="datetime-local" value={due} onChange={setDue} />
        <TextField label={t('fld.followUp')} type="datetime-local" value={follow} onChange={setFollow} />
        <button type="button" disabled={!task.data || (!due && !follow) || act.busy} onClick={() => void act.run((k) => http.put(`${E}/task/${encodeURIComponent(taskId)}`, { ...task.data, due: iso(due) ?? task.data?.due, followUp: iso(follow) ?? task.data?.followUp }, k).then(() => true)).then((ok) => { if (ok) { announce(t('common.saved')); task.reload(); } })}>{t('common.save')}</button>
      </div>
    </Section>
  );
}

export function AttachmentByUrl() {
  const { t } = useTranslation();
  const [taskId, setTaskId] = useState('');
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const act = useAction();
  const add = () => {
    const fd = new FormData();
    fd.append('attachment-name', name);
    fd.append('url', url);
    void act.run((k) => http.upload(`${E}/task/${encodeURIComponent(taskId)}/attachment/create`, fd, k).then(() => true)).then((ok) => ok && announce(t('common.saved')));
  };
  return (
    <Section title={t('mod.op3.attachUrl')}>
      <TextField label={t('fld.taskId')} value={taskId} onChange={setTaskId} required />
      <TextField label={t('common.name')} value={name} onChange={setName} required />
      <TextField label={t('fld.url')} type="url" value={url} onChange={setUrl} required />
      <button type="button" disabled={!taskId || !name || !/^https?:\/\//.test(url)} onClick={add}>{t('common.save')}</button>
      <ProblemAlert error={act.error} />
    </Section>
  );
}

const coerce = (type: string, v: string): unknown => (type === 'number' ? Number(v) : type === 'boolean' ? v === 'true' : v);

/** Generic-form "add variable": extra name / type / value rows merged into the submitted values. */
export function AddVariables({ values, onChange }: { values: Row; onChange: (v: Row) => void }) {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [type, setType] = useState('string');
  const [value, setValue] = useState('');
  return (
    <details>
      <summary>{t('mod.op3.addVariable')}</summary>
      <div className="filters">
        <TextField label={t('fld.name')} value={name} onChange={setName} />
        <SelectField label={t('fld.type')} value={type} onChange={setType} options={['string', 'number', 'boolean'].map((x) => ({ value: x, label: t(`mod.op3.vt_${x}`) }))} />
        <TextField label={t('fld.value')} value={value} onChange={setValue} />
        <button type="button" disabled={!name} onClick={() => { onChange({ ...values, [name]: coerce(type, value) }); setName(''); setValue(''); }}>{t('common.new')}</button>
      </div>
      {Object.keys(values).length > 0 && <Notice>{Object.entries(values).map(([k, v]) => `${k}=${JSON.stringify(v)}`).join(', ')}</Notice>}
    </details>
  );
}
