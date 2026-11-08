// SPDX-License-Identifier: Apache-2.0
/**
 * O01 operations dashboard (PF-13 metrics, PF-43 Control, EC-11/EC-12 job-health, FC-38 engine/actuator figures):
 * node health, backlog per topic, outbox use vs budget with drain time, incidents, reconciliation by age, SLA breaches,
 * notification failures and security-incident clocks. Aggregates only, tenant-scoped by the server.
 */
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import { useAsync, useInterval } from '../../lib/hooks';
import { fmtDate, fmtDuration, fmtNumber } from '../../i18n';
import { BarChart, Stat } from '../../components/Charts';
import { Badge, DataTable, Loading, PageHeader, ProblemAlert, Section } from '../../components/ui';

export function OpsDashboard() {
  const { t } = useTranslation();
  const d = useAsync(() => api.ops.dashboard(), []);
  const jobs = useAsync(() => api.ops.jobSummary(), []);
  useInterval(() => {
    d.reload();
    jobs.reload();
  }, 30000);
  const x = d.data;
  const outboxPct = x ? Math.round((x.outbox.used / Math.max(1, x.outbox.budget)) * 100) : 0;
  return (
    <>
      <PageHeader
        title={t('ops.dashboardTitle')}
        actions={
          <button type="button" onClick={() => (d.reload(), jobs.reload())}>
            {t('common.refresh')}
          </button>
        }
      />
      <ProblemAlert error={d.error} onRetry={d.reload} />
      {d.loading && !x && <Loading slow={d.slow} />}
      {x && (
        <>
          <div className="stats">
            <Stat label={t('ops.openIncidents')} value={fmtNumber(x.openIncidents)} tone={x.openIncidents > 0 ? 'bad' : 'ok'} />
            <Stat label={t('ops.slaBreaches')} value={fmtNumber(x.slaBreaches)} tone={x.slaBreaches > 0 ? 'warn' : 'ok'} />
            <Stat
              label={t('ops.outboxUse')}
              value={`${outboxPct}%`}
              sub={x.outbox.drainTimeSec !== undefined ? t('ops.drainTime', { d: fmtDuration(x.outbox.drainTimeSec * 1000) }) : undefined}
              tone={outboxPct >= 95 ? 'bad' : outboxPct >= 80 ? 'warn' : outboxPct >= 50 ? 'warn' : 'ok'}
            />
            {x.notificationFailureRate !== undefined && (
              <Stat label={t('ops.notifFailureRate')} value={`${fmtNumber(x.notificationFailureRate * 100, 1)}%`} tone={x.notificationFailureRate > 0.02 ? 'warn' : 'ok'} />
            )}
            {jobs.data && (
              <>
                <Stat label={t('ops.jobsExecutable')} value={fmtNumber(jobs.data.executable)} />
                <Stat label={t('ops.jobsFailed')} value={fmtNumber(jobs.data.failed)} tone={jobs.data.failed ? 'bad' : 'ok'} />
                <Stat label={t('ops.timersDue')} value={fmtNumber(jobs.data.timersDue)} />
              </>
            )}
          </div>
          <div className="grid-2">
            <Section title={t('ops.nodes')}>
              <DataTable
                caption={t('ops.nodes')}
                captionHidden
                rows={x.nodes}
                rowKey={(r) => r.id}
                columns={[
                  { key: 'id', header: t('ops.node') },
                  { key: 'status', header: t('admin.status'), render: (r) => <Badge value={r.status} /> },
                  { key: 'je', header: t('ops.jobExecutor'), render: (r) => (r.jobExecutorActive ? t('common.yes') : t('common.no')) },
                  { key: 'hb', header: t('ops.heartbeat'), render: (r) => fmtDate(r.lastHeartbeat) },
                ]}
              />
            </Section>
            <Section title={t('ops.backlog')}>
              <BarChart
                title={t('ops.backlogByTopic')}
                rows={x.backlogByTopic.map((b) => ({ label: `${b.topic} (${t('ops.oldest', { d: fmtDuration(b.oldestAgeSec * 1000) })})`, value: b.count, tone: b.oldestAgeSec > 3600 ? 'warn' : undefined }))}
              />
            </Section>
            <Section title={t('ops.reconByAge')} actions={<Link to="/ops/reconciliation">{t('common.open')}</Link>}>
              <BarChart title={t('ops.reconByAge')} rows={x.openReconciliationByAge.map((b) => ({ label: b.bucket, value: b.count, tone: 'warn' as const }))} />
            </Section>
            {jobs.data?.byPartition && (
              <Section title={t('ops.partitions')}>
                <DataTable
                  caption={t('ops.partitions')}
                  captionHidden
                  rows={jobs.data.byPartition}
                  rowKey={(r) => String(r.partition)}
                  columns={[
                    { key: 'partition', header: t('ops.partition') },
                    { key: 'owner', header: t('ops.owner'), render: (r) => r.owner ?? '—' },
                    { key: 'count', header: t('ops.jobs'), render: (r) => fmtNumber(r.count) },
                  ]}
                />
              </Section>
            )}
            {x.securityIncidentClocks && x.securityIncidentClocks.length > 0 && (
              <Section title={t('ops.securityClocks')}>
                <ul>
                  {x.securityIncidentClocks.map((s) => (
                    <li key={s.id}>
                      {s.title}: {t('ops.reportBy', { date: fmtDate(s.deadline) })}
                    </li>
                  ))}
                </ul>
              </Section>
            )}
            {x.metrics && x.metrics.length > 0 && (
              <Section title={t('ops.metrics')}>
                <dl className="kv">
                  {x.metrics.map((m) => (
                    <div key={m.name}>
                      <dt>{m.name}</dt>
                      <dd>
                        {fmtNumber(m.value, 2)} {m.unit}
                      </dd>
                    </div>
                  ))}
                </dl>
              </Section>
            )}
          </div>
        </>
      )}
    </>
  );
}
