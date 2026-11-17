// SPDX-License-Identifier: Apache-2.0
/**
 * Team workload (decision 401): open, assigned and unassigned tasks of the caller's own organisation, and the
 * unassigned tasks per team list. Read from GET /team/task-groups (permission team.read); the server limits every
 * count to the caller's tenant.
 */
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import type { TeamTaskGroupReport } from '../../api/types';
import { useAsync } from '../../lib/hooks';
import { fmtNumber } from '../../i18n';
import { DataTable, Empty, KeyValues, Loading, PageHeader, ProblemAlert, Section } from '../../components/ui';

export function TeamWorkload() {
  const { t } = useTranslation();
  const r = useAsync(() => api.team.taskGroups(), []);
  const d: TeamTaskGroupReport | undefined = r.data;
  return (
    <>
      <PageHeader title={t('menu.work.team.label')} />
      <ProblemAlert error={r.error} onRetry={r.reload} />
      {r.loading && <Loading slow={r.slow} onRetry={r.reload} />}
      {d && (
        <>
          <Section title={t('team.totals')}>
            <KeyValues
              items={[
                [t('team.open'), fmtNumber(d.open)],
                [t('team.assigned'), fmtNumber(d.assigned)],
                [t('team.unassigned'), fmtNumber(d.unassigned)],
                [t('team.withoutGroup'), fmtNumber(d.withoutCandidateGroup)],
              ]}
            />
          </Section>
          <Section title={t('team.byGroup')}>
            {d.groups.length === 0 ? (
              <Empty message={t('team.noGroups')} />
            ) : (
              <DataTable
                caption={t('team.byGroup')}
                captionHidden
                rows={d.groups}
                rowKey={(g) => g.group}
                columns={[
                  { key: 'group', header: t('team.group'), render: (g) => g.group },
                  { key: 'unassignedTasks', header: t('team.unassignedTasks'), render: (g) => fmtNumber(g.unassignedTasks) },
                ]}
              />
            )}
          </Section>
        </>
      )}
    </>
  );
}
