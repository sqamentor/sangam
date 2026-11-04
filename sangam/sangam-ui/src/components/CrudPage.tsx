// SPDX-License-Identifier: Apache-2.0
/**
 * Declarative list + editor screen for registry-style resources (service registry, channels, SLA models, templates,
 * connectors ...). Labels are i18n keys; lists use server paging and capped counts (A-16); every delete or row action
 * asks for a reason and is audited server-side (R42).
 */
import { useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/endpoints';
import type { FormField, FormSchema } from '../api/types';
import { useAction, usePaged, announce } from '../lib/hooks';
import { FormRenderer, validateForm, type FormValues } from './FormRenderer';
import { DataTable, Dialog, Empty, Loading, PageHeader, Pagination, ProblemAlert, ReasonDialog, SearchBox, type Column } from './ui';

export interface CrudColumn<R> {
  key: string;
  label: string; // i18n key
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  render?: (r: R & Record<string, any>) => ReactNode;
}

export interface CrudRowAction<R> {
  id: string;
  label: string; // i18n key
  reason?: boolean;
  danger?: boolean;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  visible?: (r: R & Record<string, any>) => boolean;
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

export function CrudPage<R extends { id?: string }>({
  title,
  description,
  path,
  columns,
  fields,
  rowActions = [],
  canCreate = true,
  canDelete = false,
  newDefaults = {},
  extraToolbar,
}: {
  title: string;
  description?: string;
  path: string;
  columns: CrudColumn<R>[];
  fields: FormField[];
  rowActions?: CrudRowAction<R>[];
  canCreate?: boolean;
  canDelete?: boolean;
  newDefaults?: Record<string, unknown>;
  extraToolbar?: ReactNode;
}) {
  const { t } = useTranslation();
  const [q, setQ] = useState('');
  const [sort, setSort] = useState<string>();
  const list = usePaged((p) => api.resource.list<R>(path, { ...p, q, sort }), [path, q, sort]);
  const [editing, setEditing] = useState<FormValues | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [pending, setPending] = useState<{ row: R; action: CrudRowAction<R> | 'delete' } | null>(null);
  const save = useAction();
  const act = useAction();
  const schema: FormSchema = useMemo(() => ({ components: translateFields(fields, t) }), [fields, t]);

  const cols: Column<R>[] = [
    ...columns.map((c) => ({ key: c.key, header: t(c.label), render: c.render as ((r: R) => ReactNode) | undefined, sortable: !c.render })),
    {
      key: '_actions',
      header: t('common.actions'),
      render: (r: R) => (
        <div className="toolbar">
          <button type="button" className="link" onClick={() => setEditing({ ...(r as Record<string, unknown>) })}>
            {t('common.edit')}
          </button>
          {rowActions
            .filter((a) => !a.visible || a.visible(r))
            .map((a) => (
              <button
                key={a.id}
                type="button"
                className={a.danger ? 'link danger' : 'link'}
                onClick={() =>
                  a.reason
                    ? setPending({ row: r, action: a })
                    : void act.run(() => api.resource.action(path, r.id!, a.id)).then(() => list.reload())
                }
              >
                {t(a.label)}
              </button>
            ))}
          {canDelete && (
            <button type="button" className="link danger" onClick={() => setPending({ row: r, action: 'delete' })}>
              {t('common.delete')}
            </button>
          )}
        </div>
      ),
    },
  ];

  const onSave = async () => {
    if (!editing) return;
    const errs = validateForm(schema, editing, t);
    setErrors(errs);
    if (Object.keys(errs).length) return;
    const r = await save.run(() => api.resource.save(path, editing as unknown as R));
    if (r) {
      announce(t('common.saved'));
      setEditing(null);
      list.reload();
    }
  };

  return (
    <>
      <PageHeader
        title={title}
        description={description}
        actions={
          <>
            {extraToolbar}
            {canCreate && (
              <button type="button" className="primary" onClick={() => setEditing({ ...newDefaults })}>
                {t('common.new')}
              </button>
            )}
          </>
        }
      />
      <SearchBox label={t('common.search')} value={q} onChange={setQ} />
      <ProblemAlert error={list.error ?? act.error} onRetry={list.reload} />
      {list.loading && !list.data ? (
        <Loading slow={list.slow} onRetry={list.reload} />
      ) : list.data && list.data.items.length === 0 ? (
        <Empty message={q ? t('common.emptyFiltered') : t('common.emptyNone')} />
      ) : (
        list.data && (
          <>
            <DataTable caption={title} rows={list.data.items} rowKey={(r) => String(r.id)} columns={cols} sort={sort} onSort={setSort} captionHidden />
            <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
          </>
        )
      )}
      <Dialog
        open={editing !== null}
        title={editing?.id ? t('common.edit') : t('common.new')}
        onClose={() => setEditing(null)}
        wide
        footer={
          <>
            <button type="button" onClick={() => setEditing(null)}>
              {t('common.cancel')}
            </button>
            <button type="button" className="primary" disabled={save.busy} onClick={() => void onSave()}>
              {save.busy ? t('common.working') : t('common.save')}
            </button>
          </>
        }
      >
        {editing && <FormRenderer schema={schema} values={editing} onChange={setEditing} errors={errors} />}
        <ProblemAlert error={save.error} />
      </Dialog>
      <ReasonDialog
        open={pending !== null}
        title={pending ? (pending.action === 'delete' ? t('common.delete') : t(pending.action.label)) : ''}
        danger={!!pending && (pending.action === 'delete' || !!pending.action.danger)}
        busy={act.busy}
        error={act.error}
        onClose={() => setPending(null)}
        onConfirm={(reason) => {
          if (!pending) return;
          const id = pending.row.id!;
          const a = pending.action;
          void act
            .run(async () => {
              await (a === 'delete' ? api.resource.remove(path, id, reason) : api.resource.action(path, id, a.id, { reason }));
              return true;
            })
            .then((ok) => {
              if (ok) {
                setPending(null);
                list.reload();
              }
            });
        }}
      />
    </>
  );
}
