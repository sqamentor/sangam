// SPDX-License-Identifier: Apache-2.0
/**
 * Shared task lists (decision 404): saved task lists of the caller's own organisation, kept by Sangam
 * (GET/POST/PUT/DELETE /team/task-filters). Reading needs team.read; creating, changing and deleting need team.write
 * (the controls show only then; the server checks again). A change or delete is sent against the version that was read
 * (If-Match), so a list changed by someone else meanwhile is refused (REVISION_CONFLICT), never overwritten; a delete
 * needs a reason, which the server records in the audit trail. The server refuses a query that names an organisation (TASK_FILTER_INVALID): the organisation is
 * always the caller's own.
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import type { TeamTaskFilter } from '../../api/types';
import { useAuth } from '../../auth/AuthProvider';
import { TEAM_WRITE, canAccess } from '../../app/access';
import { announce, useAction, useAsync } from '../../lib/hooks';
import { fmtDate } from '../../i18n';
import { DataTable, Empty, Loading, PageHeader, ProblemAlert, ReasonDialog, Section, TextArea, TextField } from '../../components/ui';

/** The query text as an object, or the i18n key of why it is not one. */
export function parseQuery(text: string): { query: Record<string, unknown> } | { error: string } {
  let v: unknown;
  try {
    v = JSON.parse(text);
  } catch {
    return { error: 'teamLists.queryNotJson' };
  }
  if (!v || typeof v !== 'object' || Array.isArray(v)) return { error: 'teamLists.queryNotObject' };
  return { query: v as Record<string, unknown> };
}

export function TeamTaskLists() {
  const { t } = useTranslation();
  const { me } = useAuth();
  const list = useAsync(() => api.team.taskFilters(), []);
  const canWrite = canAccess(me, TEAM_WRITE);
  const [editing, setEditing] = useState<TeamTaskFilter | null>(null);
  const [deleting, setDeleting] = useState<TeamTaskFilter | null>(null);
  const del = useAction();
  const confirmDelete = async (reason: string) => {
    if (!deleting) return;
    const target = deleting;
    const done = await del.run(async (key) => {
      await api.team.deleteTaskFilter(target.id, target.version, reason, key);
      return true;
    });
    if (done) {
      setDeleting(null);
      if (editing?.id === target.id) setEditing(null);
      announce(t('teamLists.deleted', { name: target.name }));
      list.reload();
    }
  };
  return (
    <>
      <PageHeader title={t('menu.work.taskLists.label')} />
      {canWrite && (
        <ListForm
          key={editing ? `${editing.id}@${editing.version}` : 'new'}
          editing={editing}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            list.reload();
          }}
        />
      )}
      <Section title={t('teamLists.saved')}>
        <ProblemAlert error={list.error} onRetry={list.reload} />
        {list.loading && <Loading slow={list.slow} onRetry={list.reload} />}
        {list.data &&
          (list.data.length === 0 ? (
            <Empty message={t('teamLists.none')} />
          ) : (
            <DataTable<TeamTaskFilter>
              caption={t('teamLists.saved')}
              captionHidden
              rows={list.data}
              rowKey={(f) => f.id}
              columns={[
                { key: 'name', header: t('teamLists.name'), render: (f) => f.name },
                { key: 'query', header: t('teamLists.query'), render: (f) => <code>{JSON.stringify(f.query)}</code> },
                { key: 'createdBy', header: t('teamLists.createdBy'), render: (f) => f.createdBy },
                { key: 'createdAt', header: t('teamLists.createdAt'), render: (f) => fmtDate(f.createdAt) },
                ...(canWrite
                  ? [
                      {
                        key: 'actions',
                        header: t('teamLists.actions'),
                        render: (f: TeamTaskFilter) => (
                          <>
                            <button type="button" className="link" aria-label={t('teamLists.editNamed', { name: f.name })} onClick={() => setEditing(f)}>
                              {t('common.edit')}
                            </button>{' '}
                            <button type="button" className="link danger" aria-label={t('teamLists.deleteNamed', { name: f.name })} onClick={() => setDeleting(f)}>
                              {t('common.delete')}
                            </button>
                          </>
                        ),
                      },
                    ]
                  : []),
              ]}
            />
          ))}
      </Section>
      <ReasonDialog
        open={deleting !== null}
        title={t('teamLists.deleteTitle', { name: deleting?.name ?? '' })}
        onClose={() => setDeleting(null)}
        onConfirm={(r) => void confirmDelete(r)}
        confirmLabel={t('teamLists.deleteButton')}
        busy={del.busy}
        error={del.error}
        danger
      >
        <p>{t('teamLists.deleteBody')}</p>
      </ReasonDialog>
    </>
  );
}

/** Create a list, or change the one being edited (sent against the version it was read at). */
function ListForm({ editing, onSaved, onCancel }: { editing: TeamTaskFilter | null; onSaved: () => void; onCancel: () => void }) {
  const { t } = useTranslation();
  const act = useAction();
  const [name, setName] = useState(editing?.name ?? '');
  const [query, setQuery] = useState(editing ? JSON.stringify(editing.query, null, 2) : '{\n  "candidateGroup": ""\n}');
  const [errors, setErrors] = useState<{ name?: string; query?: string }>({});
  const submit = async () => {
    const parsed = parseQuery(query);
    const next = { name: name.trim() === '' ? t('teamLists.nameRequired') : name.length > 255 ? t('teamLists.nameTooLong') : undefined, query: 'error' in parsed ? t(parsed.error) : undefined };
    setErrors(next);
    if (next.name || next.query || 'error' in parsed) return;
    const body = { name: name.trim(), query: parsed.query };
    const saved = await act.run((key) => (editing ? api.team.updateTaskFilter(editing.id, editing.version, body, key) : api.team.createTaskFilter(body, key)));
    if (saved) {
      setName('');
      announce(t(editing ? 'teamLists.updated' : 'teamLists.created', { name: saved.name }));
      onSaved();
    }
  };
  return (
    <Section title={editing ? t('teamLists.edit', { name: editing.name }) : t('teamLists.create')}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <TextField id="list-name" label={t('teamLists.name')} required value={name} onChange={setName} error={errors.name} maxLength={255} />
        <TextArea id="list-query" label={t('teamLists.query')} hint={t('teamLists.queryHint')} required value={query} onChange={setQuery} error={errors.query} rows={4} />
        <ProblemAlert error={act.error} />
        <button type="submit" disabled={act.busy}>
          {editing ? t('teamLists.saveButton') : t('teamLists.createButton')}
        </button>
        {editing && (
          <>
            {' '}
            <button type="button" onClick={onCancel}>
              {t('teamLists.cancelEdit')}
            </button>
          </>
        )}
      </form>
    </Section>
  );
}
