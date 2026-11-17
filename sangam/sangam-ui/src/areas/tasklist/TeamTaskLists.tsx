// SPDX-License-Identifier: Apache-2.0
/**
 * Shared task lists (decision 404): saved task lists of the caller's own organisation, kept by Sangam
 * (GET/POST /team/task-filters). Reading needs team.read; creating needs team.write (the form shows only then; the server
 * checks again). The server refuses a query that names an organisation (TASK_FILTER_INVALID): the organisation is
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
import { DataTable, Empty, Loading, PageHeader, ProblemAlert, Section, TextArea, TextField } from '../../components/ui';

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
  const canCreate = canAccess(me, TEAM_WRITE);
  return (
    <>
      <PageHeader title={t('menu.work.taskLists.label')} />
      {canCreate && <CreateList onCreated={list.reload} />}
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
              ]}
            />
          ))}
      </Section>
    </>
  );
}

function CreateList({ onCreated }: { onCreated: () => void }) {
  const { t } = useTranslation();
  const act = useAction();
  const [name, setName] = useState('');
  const [query, setQuery] = useState('{\n  "candidateGroup": ""\n}');
  const [errors, setErrors] = useState<{ name?: string; query?: string }>({});
  const submit = async () => {
    const parsed = parseQuery(query);
    const next = { name: name.trim() === '' ? t('teamLists.nameRequired') : name.length > 255 ? t('teamLists.nameTooLong') : undefined, query: 'error' in parsed ? t(parsed.error) : undefined };
    setErrors(next);
    if (next.name || next.query || 'error' in parsed) return;
    const created = await act.run((key) => api.team.createTaskFilter({ name: name.trim(), query: parsed.query }, key));
    if (created) {
      setName('');
      announce(t('teamLists.created', { name: created.name }));
      onCreated();
    }
  };
  return (
    <Section title={t('teamLists.create')}>
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
          {t('teamLists.createButton')}
        </button>
      </form>
    </Section>
  );
}
