// SPDX-License-Identifier: Apache-2.0
/**
 * Owner decision 338: an officer looks up a data principal's consents or rights requests with `POST .../search` and the
 * principal reference in the body (ComplianceSubjectSearch), never in the URL, so the reference reaches no access log,
 * proxy or browser history. The generic module screens load lists by GET with the filters in the query string, so
 * these two registers have their own screen: the same columns, detail, create and row actions (through the module
 * ActionDialog), with the list read by POST. Each search of another principal is audited by the server
 * (PERSONAL_DATA_READ, decision 348).
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { http } from '../api/client';
import { useAction, useAsync } from '../lib/hooks';
import { DataTable, Empty, JsonView, Loading, Notice, ProblemAlert, Section, TextField, type Column } from '../components/ui';
import { ActionDialog, cell, fill, rowId, type Act, type Col, type Row } from './engine';

/** A register searched by principal reference (POST {search} {subject}). */
export interface PrincipalSearchDef {
  id: string;
  title: string; // i18n key
  description?: string; // i18n key
  /** The POST path of the search (body {subject}). */
  search: string;
  columns: Col[];
  rowKey: string[];
  /** GET path of one record ({id} from the selected row). */
  detail?: string;
  create?: Act;
  actions?: Act[];
}

/** The body of a search (ComplianceSubjectSearch): the reference as typed, trimmed; never a query parameter. */
export function searchBody(subject: string): { subject: string } {
  return { subject: subject.trim() };
}

export function PrincipalSearchScreen({ def }: { def: PrincipalSearchDef }) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState('');
  const [rows, setRows] = useState<Row[] | null>(null);
  const [subject, setSubject] = useState('');
  const [selected, setSelected] = useState<Row | null>(null);
  const [act, setAct] = useState<{ a: Act; row?: Row } | null>(null);
  const search = useAction();
  const detail = useAsync((signal) => http.get<unknown>(fill(def.detail!, selected!), undefined, signal), [def.detail, selected ? rowId(selected, def.rowKey) : ''], !!def.detail && !!selected);

  const run = (who: string) => {
    if (!who.trim()) return;
    void search.run(() => http.post<unknown>(def.search, searchBody(who))).then((r) => {
      if (r === undefined) return;
      setSubject(who);
      setRows(Array.isArray(r) ? (r as Row[]) : []);
    });
  };

  const cols: Column<Row>[] = def.columns.map((col, i) => ({
    key: col.key,
    header: t(col.label),
    render: (r: Row) =>
      i === 0 ? (
        <button type="button" className="link" aria-pressed={!!selected && rowId(selected, def.rowKey) === rowId(r, def.rowKey)} onClick={() => setSelected({ ...r, id: r.id ?? rowId(r, def.rowKey) })}>
          {col.render ? col.render(r) : cell(r[col.key], col.key)}
        </button>
      ) : col.render ? (
        col.render(r)
      ) : (
        cell(r[col.key], col.key)
      ),
  }));

  return (
    <>
      <div className="card-head">
        <h2>{t(def.title)}</h2>
        <div className="toolbar">
          {def.create && (
            <button type="button" className="primary" onClick={() => setAct({ a: def.create! })}>
              {t(def.create.label)}
            </button>
          )}
        </div>
      </div>
      {def.description && <p className="muted">{t(def.description)}</p>}
      <form
        className="filters module-filters"
        onSubmit={(e) => {
          e.preventDefault();
          setSelected(null);
          run(draft);
        }}
      >
        <TextField label={t('fld.principalRef')} required value={draft} onChange={setDraft} hint={t('mod.compliance.searchHint')} autoComplete="off" />
        <button type="submit" disabled={search.busy || !draft.trim()}>
          {t('common.search')}
        </button>
      </form>
      <ProblemAlert error={search.error} />
      {rows === null && !search.busy && <Notice>{t('mod.common.fillFilters')}</Notice>}
      {search.busy && rows === null && <Loading />}
      {rows !== null && rows.length === 0 && !search.error && <Empty message={t('common.emptyNone')} />}
      {rows !== null && rows.length > 0 && <DataTable caption={t(def.title)} captionHidden rows={rows} rowKey={(r) => rowId(r, def.rowKey)} columns={cols} />}
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
          {def.detail ? detail.loading ? <Loading /> : detail.error ? <ProblemAlert error={detail.error} onRetry={detail.reload} /> : <JsonView value={detail.data} /> : <JsonView value={selected} />}
        </Section>
      )}
      {act && (
        <ActionDialog
          act={act.a}
          row={act.row}
          onClose={() => setAct(null)}
          onDone={() => {
            setAct(null);
            setSelected(null);
            if (subject) run(subject);
          }}
        />
      )}
    </>
  );
}
