// SPDX-License-Identifier: Apache-2.0
/**
 * Declarative screens for the generic Sangam modules (config, ledger, billing, registry, resources, case files,
 * committees, references, programmes, field, geo, compliance). One definition gives:
 * - a server-paged list with query filters and capped counts (A-16);
 * - a detail panel (scalars as a definition list, arrays as tables, objects as JSON) with lazily loaded sections;
 * - create / toolbar / row actions in dialogs built from form fields, with an optional JSON "more fields" editor,
 *   a mandatory reason where the API asks for one, an Idempotency-Key per attempt (R41), and the server response shown.
 * Labels are i18n keys. Paths use {placeholders} resolved from the selected row, then the filter values.
 */
import { useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { http, request, type Query } from '../api/client';
import type { FormField, FormSchema } from '../api/types';
import { announce, useAction, useAsync, usePaged } from '../lib/hooks';
import { fmtDate } from '../i18n';
import { FormRenderer, validateForm, type FormValues } from '../components/FormRenderer';
import { Badge, DataTable, Dialog, Empty, JsonView, KeyValues, Loading, Notice, PageHeader, Pagination, ProblemAlert, Section, TabPanel, Tabs, TextArea, type Column } from '../components/ui';

export type Row = Record<string, unknown>;

export interface Col {
  key: string;
  label: string; // i18n key
  render?: (r: Row) => ReactNode;
}

export interface Act {
  id: string;
  label: string; // i18n key
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  /** Path with {placeholders} from the row (row actions) or the filters (toolbar actions). */
  path: string;
  fields?: FormField[];
  /** Ask for a reason; sent as body.reason, or as ?reason= for DELETE / reason: 'query'. */
  reason?: 'body' | 'query';
  danger?: boolean;
  /** Hide the JSON "more fields" editor (actions with a fixed body). */
  noJson?: boolean;
  visible?: (r: Row) => boolean;
  defaults?: (r?: Row) => FormValues;
  /** Show the server response after success (lookups, verifications, quotes). */
  showResult?: boolean;
  hint?: string; // i18n key
  /** Fixed body fields merged after the form values. */
  bodyExtra?: Row;
  /** Send these fields as null when left empty (clear a value). */
  sendNulls?: string[];
}

export interface DetailSection {
  id: string;
  label: string; // i18n key
  path: string;
  columns?: Col[];
}

export interface ScreenDef {
  id: string;
  title: string; // i18n key
  description?: string; // i18n key
  list: string;
  /** Fixed query parameters of the list. */
  listQuery?: Query;
  filters?: FormField[];
  columns: Col[];
  rowKey: string[];
  detail?: string;
  sections?: DetailSection[];
  create?: Act;
  toolbar?: Act[];
  actions?: Act[];
  /** A list endpoint that is not paged (returns a plain array or object). */
  unpaged?: boolean;
  /** The filters must be filled before the list loads (e.g. a ledger id). */
  requireFilters?: string[];
  emptyHint?: string; // i18n key
}

const ISO = /^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}.*)?$/;
const STATUS_KEYS = /^(status|state|kind|severity|result|outcome|stage|priority|effect)$/i;

export function rowId(r: Row, keys: string[]): string {
  for (const k of keys) if (r[k] !== undefined && r[k] !== null && r[k] !== '') return String(r[k]);
  return JSON.stringify(r).slice(0, 80);
}

export function fill(path: string, ...sources: (Row | FormValues | undefined)[]): string {
  return path.replace(/\{(\w+)\}/g, (_, k: string) => {
    for (const s of sources) {
      const v = s?.[k];
      if (v !== undefined && v !== null && v !== '') return encodeURIComponent(String(v));
    }
    return '';
  });
}

export function cell(v: unknown, key = ''): ReactNode {
  if (v === undefined || v === null || v === '') return '—';
  if (typeof v === 'boolean') return v ? '✓' : '✗';
  if (typeof v === 'string' && STATUS_KEYS.test(key)) return <Badge value={v} />;
  if (typeof v === 'string' && ISO.test(v)) return fmtDate(v, v.length > 10);
  if (typeof v === 'object' && v && 'type' in (v as Row) && 'value' in (v as Row)) return String((v as Row).value);
  if (typeof v === 'object') {
    const s = JSON.stringify(v);
    return <code className="small">{s.length > 80 ? `${s.slice(0, 80)}…` : s}</code>;
  }
  return String(v);
}

function translateFields(fields: FormField[], t: (k: string) => string): FormField[] {
  return fields.map((f) => ({
    ...f,
    label: f.label ? t(f.label) : f.label,
    description: f.description ? t(f.description) : undefined,
    values: f.values?.map((v) => ({ ...v, label: t(v.label) })),
    components: f.components ? translateFields(f.components, t) : undefined,
  }));
}

/** Form values → {body, query}: JSON fields are parsed, inQuery fields go to the query string, empties are dropped. */
function flat(fields: FormField[]): FormField[] {
  return fields.flatMap((x) => (x.type === 'group' ? flat(x.components ?? []) : [x]));
}

function setPath(o: Row, key: string, v: unknown) {
  const parts = key.split('.');
  let cur = o;
  for (const p of parts.slice(0, -1)) {
    if (!cur[p] || typeof cur[p] !== 'object') cur[p] = {};
    cur = cur[p] as Row;
  }
  cur[parts[parts.length - 1]] = v;
}

export function buildRequest(fields: FormField[], values: FormValues, extraJson: string): { body: Row; query: Query; error?: string } {
  const body: Row = {};
  const query: Query = {};
  for (const f of flat(fields)) {
    if (!f.key) continue;
    let v = values[f.key];
    if (v === undefined || v === '' || (Array.isArray(v) && v.length === 0)) continue;
    if (f.json && typeof v === 'string') {
      try {
        v = JSON.parse(v);
      } catch {
        return { body, query, error: f.key };
      }
    }
    if (f.type === 'datetime' && f.subtype === 'datetime' && typeof v === 'string') v = new Date(v).toISOString();
    if (f.inQuery) query[f.key] = String(v);
    else setPath(body, f.key, v);
  }
  if (extraJson.trim()) {
    try {
      Object.assign(body, JSON.parse(extraJson) as Row);
    } catch {
      return { body, query, error: 'json' };
    }
  }
  return { body, query };
}

export function ActionDialog({ act, row, context, onClose, onDone }: { act: Act; row?: Row; context?: FormValues; onClose: () => void; onDone: (result: unknown) => void }) {
  const { t } = useTranslation();
  const fields = act.fields ?? [];
  const schema: FormSchema = useMemo(() => ({ components: translateFields(fields, t) }), [fields, t]);
  const [values, setValues] = useState<FormValues>(() => act.defaults?.(row) ?? {});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [json, setJson] = useState('');
  const [reason, setReason] = useState('');
  const [result, setResult] = useState<unknown>();
  const [jsonErr, setJsonErr] = useState<string>();
  const run = useAction();
  const method = act.method ?? 'POST';
  const submit = () => {
    const errs = validateForm(schema, values, t);
    setErrors(errs);
    if (Object.keys(errs).length) return;
    const req = buildRequest(fields, values, json);
    if (req.error) {
      setJsonErr(req.error === 'json' ? t('mod.common.invalidJson') : t('mod.common.invalidJsonField', { field: req.error }));
      return;
    }
    setJsonErr(undefined);
    Object.assign(req.body, act.bodyExtra ?? {});
    for (const k of act.sendNulls ?? []) if (req.body[k] === undefined) req.body[k] = null;
    if (act.reason) {
      if (method === 'DELETE' || act.reason === 'query') req.query.reason = reason;
      else req.body.reason = reason;
    }
    if (method === 'GET') {
      for (const [k, v] of Object.entries(req.body)) req.query[k] = typeof v === 'object' ? JSON.stringify(v) : String(v);
      for (const k of Object.keys(req.body)) delete req.body[k];
    }
    const path = fill(act.path, row, values, context);
    void run
      .run(async (k) => {
        const hasBody = method !== 'DELETE' && method !== 'GET' && (Object.keys(req.body).length > 0 || method !== 'POST' || fields.length > 0 || !!json.trim());
        const r = await request<unknown>(method, path, { query: req.query, body: hasBody ? req.body : method === 'POST' ? {} : undefined, idempotencyKey: k });
        return { r };
      })
      .then((x) => {
        if (!x) return;
        announce(t('mod.common.done'));
        if (act.showResult) setResult(x.r ?? t('mod.common.done'));
        else onDone(x.r);
      });
  };
  return (
    <Dialog
      open
      wide
      title={t(act.label)}
      onClose={() => (result !== undefined ? onDone(result) : onClose())}
      footer={
        result !== undefined ? (
          <button type="button" className="primary" onClick={() => onDone(result)}>
            {t('common.close')}
          </button>
        ) : (
          <>
            <button type="button" onClick={onClose}>
              {t('common.cancel')}
            </button>
            <button type="button" className={act.danger ? 'danger' : 'primary'} disabled={run.busy || (!!act.reason && reason.trim().length < 5)} onClick={submit}>
              {run.busy ? t('common.working') : t('common.confirm')}
            </button>
          </>
        )
      }
    >
      {act.hint && <p className="hint">{t(act.hint)}</p>}
      {result !== undefined ? (
        <>
          <Notice tone="ok">{t('mod.common.done')}</Notice>
          <JsonView value={result} />
        </>
      ) : (
        <>
          {fields.length > 0 && <FormRenderer schema={schema} values={values} onChange={setValues} errors={errors} />}
          {!act.noJson && (
            <details>
              <summary>{t('mod.common.moreFields')}</summary>
              <TextArea label={t('mod.common.jsonBody')} hint={t('mod.common.jsonBodyHint')} value={json} onChange={setJson} className="code" />
            </details>
          )}
          {act.reason && <TextArea label={t('common.reason')} hint={t('common.reasonHint')} required value={reason} onChange={setReason} />}
          {jsonErr && <Notice tone="bad">{jsonErr}</Notice>}
          <ProblemAlert error={run.error} />
        </>
      )}
    </Dialog>
  );
}

function DetailView({ value }: { value: unknown }) {
  const { t } = useTranslation();
  if (!value || typeof value !== 'object') return <JsonView value={value} />;
  const obj = value as Row;
  const scalars = Object.entries(obj).filter(([, v]) => v === null || typeof v !== 'object' || ('type' in (v as Row) && 'value' in (v as Row)));
  const arrays = Object.entries(obj).filter(([, v]) => Array.isArray(v) && (v as unknown[]).length > 0);
  const objects = Object.entries(obj).filter(([, v]) => v && typeof v === 'object' && !Array.isArray(v) && !('type' in (v as Row) && 'value' in (v as Row)));
  return (
    <>
      <KeyValues items={scalars.map(([k, v]) => [k, cell(v, k)])} />
      {arrays.map(([k, v]) => {
        const items = v as unknown[];
        if (typeof items[0] !== 'object') return <KeyValues key={k} items={[[k, items.join(', ')]]} />;
        const keys = [...new Set((items as Row[]).flatMap((x) => Object.keys(x)))].slice(0, 8);
        return (
          <DataTable
            key={k}
            caption={k}
            rows={(items as Row[]).map((x, i) => ({ ...x, __i: i }))}
            rowKey={(x) => String(x.__i)}
            columns={keys.map((c) => ({ key: c, header: c, render: (x: Row) => cell(x[c], c) }))}
          />
        );
      })}
      {objects.map(([k, v]) => (
        <details key={k}>
          <summary>{k}</summary>
          <JsonView value={v} />
        </details>
      ))}
      {scalars.length + arrays.length + objects.length === 0 && <p className="muted">{t('common.emptyNone')}</p>}
    </>
  );
}

function SectionList({ section, row, context }: { section: DetailSection; row: Row; context?: FormValues }) {
  const { t } = useTranslation();
  const data = useAsync(() => http.get<unknown>(fill(section.path, row, context)), [section.path, JSON.stringify(row)]);
  if (data.loading) return <Loading />;
  if (data.error) return <ProblemAlert error={data.error} onRetry={data.reload} />;
  const v = data.data as unknown;
  const items = Array.isArray(v) ? (v as Row[]) : v && typeof v === 'object' && Array.isArray((v as Row).items) ? ((v as Row).items as Row[]) : null;
  if (!items) return <DetailView value={v} />;
  if (items.length === 0) return <Empty message={t('common.emptyNone')} />;
  const cols: Col[] = section.columns ?? [...new Set(items.flatMap((x) => Object.keys(x)))].slice(0, 8).map((k) => ({ key: k, label: k }));
  return (
    <DataTable
      caption={t(section.label)}
      captionHidden
      rows={items.map((x, i) => ({ ...x, __i: i }))}
      rowKey={(x) => String(x.__i)}
      columns={cols.map((c) => ({ key: c.key, header: c.label.includes('.') ? t(c.label) : c.label, render: (x: Row) => (c.render ? c.render(x) : cell(x[c.key], c.key)) }))}
    />
  );
}

export function ModuleScreen({ def, embedded }: { def: ScreenDef; embedded?: boolean }) {
  const { t } = useTranslation();
  const filterSchema: FormSchema = useMemo(() => ({ components: translateFields(def.filters ?? [], t) }), [def.filters, t]);
  const [draft, setDraft] = useState<FormValues>({});
  const [filters, setFilters] = useState<FormValues>({});
  const [selected, setSelected] = useState<Row | null>(null);
  const [act, setAct] = useState<{ a: Act; row?: Row } | null>(null);
  const [tab, setTab] = useState('detail');
  const ready = (def.requireFilters ?? []).every((k) => filters[k] !== undefined && filters[k] !== '');
  const listPath = fill(def.list, filters);
  const q: Query = { ...def.listQuery, ...Object.fromEntries(Object.entries(filters).filter(([k]) => !def.list.includes(`{${k}}`)).map(([k, v]) => [k, v as string])) };
  const paged = usePaged(
    (p) => (ready && !def.unpaged ? http.page<Row>(listPath, { ...q, ...p }) : Promise.resolve({ items: [], page: 0, size: p.size, total: 0, capped: false })),
    [listPath, JSON.stringify(q), ready],
  );
  const plain = useAsync(async () => {
    const r = await http.get<unknown>(listPath, q);
    if (Array.isArray(r)) return r as Row[];
    if (r && typeof r === 'object' && Array.isArray((r as Row).items)) return (r as Row).items as Row[];
    return r && typeof r === 'object' ? Object.entries(r as Row).map(([k, v]) => (v && typeof v === 'object' && !Array.isArray(v) ? { key: k, ...(v as Row) } : { key: k, value: v })) : [];
  }, [listPath, JSON.stringify(q), ready], ready && !!def.unpaged);
  const detail = useAsync(() => http.get<unknown>(fill(def.detail!, selected!, filters)), [def.detail, selected ? rowId(selected, def.rowKey) : ''], !!def.detail && !!selected);
  const rows = def.unpaged ? plain.data ?? [] : paged.data?.items ?? [];
  const loading = def.unpaged ? plain.loading : paged.loading;
  const error = def.unpaged ? plain.error : paged.error;
  const reload = () => {
    if (def.unpaged) plain.reload();
    else paged.reload();
    detail.reload();
  };

  const cols: Column<Row>[] = [
    ...def.columns.map((c, i) => ({
      key: c.key,
      header: t(c.label),
      render: (r: Row) =>
        i === 0 ? (
          <button type="button" className="link" aria-pressed={!!selected && rowId(selected, def.rowKey) === rowId(r, def.rowKey)} onClick={() => (setSelected({ ...r, id: r.id ?? rowId(r, def.rowKey) }), setTab('detail'))}>
            {c.render ? c.render(r) : cell(r[c.key], c.key)}
          </button>
        ) : c.render ? (
          c.render(r)
        ) : (
          cell(r[c.key], c.key)
        ),
    })),
  ];

  const header = (
    <PageHeader
      title={t(def.title)}
      description={def.description ? t(def.description) : undefined}
      actions={
        <>
          {(def.toolbar ?? []).map((a) => (
            <button key={a.id} type="button" className={a.danger ? 'danger' : undefined} onClick={() => setAct({ a })}>
              {t(a.label)}
            </button>
          ))}
          {def.create && (
            <button type="button" className="primary" onClick={() => setAct({ a: def.create! })}>
              {t(def.create.label)}
            </button>
          )}
        </>
      }
    />
  );

  return (
    <>
      {embedded ? (
        <div className="card-head">
          <h2>{t(def.title)}</h2>
          <div className="toolbar">
            {(def.toolbar ?? []).map((a) => (
              <button key={a.id} type="button" onClick={() => setAct({ a })}>
                {t(a.label)}
              </button>
            ))}
            {def.create && (
              <button type="button" className="primary" onClick={() => setAct({ a: def.create! })}>
                {t(def.create.label)}
              </button>
            )}
          </div>
        </div>
      ) : (
        header
      )}
      {embedded && def.description && <p className="muted">{t(def.description)}</p>}
      {def.filters && def.filters.length > 0 && (
        <form
          className="filters module-filters"
          onSubmit={(e) => {
            e.preventDefault();
            setFilters(draft);
            setSelected(null);
          }}
        >
          <FormRenderer schema={filterSchema} values={draft} onChange={setDraft} />
          <button type="submit">{t('common.search')}</button>
        </form>
      )}
      {!ready && <Notice>{def.emptyHint ? t(def.emptyHint) : t('mod.common.fillFilters')}</Notice>}
      <ProblemAlert error={error} onRetry={reload} />
      {ready && loading && rows.length === 0 && <Loading />}
      {ready && !loading && rows.length === 0 && !error && <Empty message={t('common.emptyNone')} />}
      {rows.length > 0 && (
        <DataTable caption={t(def.title)} captionHidden rows={rows} rowKey={(r) => rowId(r, def.rowKey)} columns={cols} />
      )}
      {!def.unpaged && <Pagination data={paged.data} page={paged.page} size={paged.size} setPage={paged.setPage} setSize={paged.setSize} />}
      {selected && (
        <Section
          title={rowId(selected, def.rowKey)}
          actions={
            <>
              {(def.actions ?? [])
                .filter((a) => !a.visible || a.visible((detail.data as Row) ?? selected))
                .map((a) => (
                  <button key={a.id} type="button" className={a.danger ? 'danger' : undefined} onClick={() => setAct({ a, row: { ...selected, ...((detail.data as Row) ?? {}) } })}>
                    {t(a.label)}
                  </button>
                ))}
              <button type="button" className="link" onClick={() => setSelected(null)}>
                {t('common.close')}
              </button>
            </>
          }
        >
          {(def.sections?.length ?? 0) > 0 && (
            <Tabs
              label={rowId(selected, def.rowKey)}
              active={tab}
              onChange={setTab}
              tabs={[{ id: 'detail', label: t('mod.common.details') }, ...def.sections!.map((s) => ({ id: s.id, label: t(s.label) }))]}
            />
          )}
          <TabPanel id="detail" active={tab}>
            {def.detail ? detail.loading ? <Loading /> : detail.error ? <ProblemAlert error={detail.error} onRetry={detail.reload} /> : <DetailView value={detail.data} /> : <DetailView value={selected} />}
          </TabPanel>
          {def.sections?.map((s) => (
            <TabPanel key={s.id} id={s.id} active={tab}>
              <SectionList section={s} row={{ ...selected, ...((detail.data as Row) ?? {}) }} context={filters} />
            </TabPanel>
          ))}
        </Section>
      )}
      {act && (
        <ActionDialog
          act={act.a}
          row={act.row}
          context={filters}
          onClose={() => setAct(null)}
          onDone={() => {
            setAct(null);
            reload();
          }}
        />
      )}
    </>
  );
}

/** Several screens of one module on one page, as tabs. */
export function ModulePage({ title, description, screens, extra }: { title: string; description?: string; screens: ScreenDef[]; extra?: { id: string; label: string; node: ReactNode }[] }) {
  const { t } = useTranslation();
  const [tab, setTab] = useState(screens[0]?.id ?? extra?.[0]?.id ?? '');
  const tabs = [...screens.map((s) => ({ id: s.id, label: t(s.title) })), ...(extra ?? []).map((e) => ({ id: e.id, label: t(e.label) }))];
  return (
    <>
      <PageHeader title={t(title)} description={description ? t(description) : undefined} />
      <Tabs label={t(title)} active={tab} onChange={setTab} tabs={tabs} />
      {screens.map((s) => (
        <TabPanel key={s.id} id={s.id} active={tab}>
          <ModuleScreen def={s} embedded />
        </TabPanel>
      ))}
      {(extra ?? []).map((e) => (
        <TabPanel key={e.id} id={e.id} active={tab}>
          {e.node}
        </TabPanel>
      ))}
    </>
  );
}

// ------------------------------------------------------------------ field helpers (labels are i18n keys)

export const f = {
  text: (key: string, label = `fld.${key}`, required = false, extra: Partial<FormField> = {}): FormField => ({ key, label, type: 'textfield', validate: required ? { required } : undefined, ...extra }),
  num: (key: string, label = `fld.${key}`, required = false): FormField => ({ key, label, type: 'number', validate: required ? { required } : undefined }),
  money: (key: string, label = `fld.${key}`, required = false): FormField => ({ key, label, type: 'textfield', description: 'fld.decimalHint', validate: { required, pattern: '-?\\d+(\\.\\d{1,4})?' } }),
  date: (key: string, label = `fld.${key}`, required = false, extra: Partial<FormField> = {}): FormField => ({ key, label, type: 'datetime', subtype: 'date', validate: required ? { required } : undefined, ...extra }),
  dt: (key: string, label = `fld.${key}`, required = false): FormField => ({ key, label, type: 'datetime', subtype: 'datetime', validate: required ? { required } : undefined }),
  area: (key: string, label = `fld.${key}`, required = false): FormField => ({ key, label, type: 'textarea', validate: required ? { required } : undefined }),
  json: (key: string, label = `fld.${key}`, required = false): FormField => ({ key, label, type: 'textarea', json: true, description: 'fld.jsonHint', validate: required ? { required } : undefined }),
  bool: (key: string, label = `fld.${key}`): FormField => ({ key, label, type: 'checkbox' }),
  select: (key: string, opts: string[], label = `fld.${key}`, required = false, prefix = 'opt.'): FormField => ({
    key,
    label,
    type: 'select',
    values: opts.map((o) => ({ value: o, label: `${prefix}${o}` })),
    validate: required ? { required } : undefined,
  }),
  q: (field: FormField): FormField => ({ ...field, inQuery: true }),
};

export const c = (key: string, label = `fld.${key}`, render?: (r: Row) => ReactNode): Col => ({ key, label, render });
