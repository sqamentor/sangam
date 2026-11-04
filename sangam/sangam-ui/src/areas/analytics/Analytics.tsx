// SPDX-License-Identifier: Apache-2.0
/**
 * Analytics and process mining (R65, R66, R79; PF-07/14/19/20/42; EC-10; UP-07):
 * KPI tiles with targets, throughput, SLA breaches, bottlenecks on the diagram, variants, conformance against the
 * deployed BPMN, duration and task reports, threshold alerts, AI-agent token/cost budgets (R72), XES / OCEL export.
 * All figures come from the server's analytics read model through R19 predicates; hidden records never count.
 */
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import type { AlertRule } from '../../api/types';
import { useAction, useAsync } from '../../lib/hooks';
import { fmtDate, fmtDuration, fmtNumber } from '../../i18n';
import { BarChart, LineChart, StackedBar, Stat } from '../../components/Charts';
import { BpmnDiagram } from '../../components/BpmnDiagram';
import { Badge, Checkbox, DataTable, Dialog, Empty, Loading, Notice, PageHeader, ProblemAlert, Section, SelectField, TextField } from '../../components/ui';

function useFilters() {
  const today = new Date();
  const monthAgo = new Date(today.getTime() - 30 * 86400000);
  const [from, setFrom] = useState(monthAgo.toISOString().slice(0, 10));
  const [to, setTo] = useState(today.toISOString().slice(0, 10));
  const [definitionKey, setKey] = useState('');
  const [bucket, setBucket] = useState('day');
  return { from, setFrom, to, setTo, definitionKey, setKey, bucket, setBucket, q: { from, to, definitionKey: definitionKey || undefined, bucket } };
}

function Filters({ f, withBucket = true }: { f: ReturnType<typeof useFilters>; withBucket?: boolean }) {
  const { t } = useTranslation();
  const defs = useAsync(() => api.processes.definitions({ latest: true, size: 200 }), []);
  return (
    <div className="filters">
      <SelectField
        label={t('analytics.process')}
        value={f.definitionKey}
        onChange={f.setKey}
        placeholder={t('common.all')}
        options={(defs.data?.items ?? []).map((d) => ({ value: d.key, label: d.name ?? d.key }))}
      />
      <TextField label={t('common.from')} type="date" value={f.from} onChange={f.setFrom} />
      <TextField label={t('common.to')} type="date" value={f.to} onChange={f.setTo} />
      {withBucket && (
        <SelectField label={t('analytics.bucket')} value={f.bucket} onChange={f.setBucket} options={['hour', 'day', 'week', 'month'].map((b) => ({ value: b, label: t(`analytics.bucket_${b}`) }))} />
      )}
    </div>
  );
}

export function AnalyticsDashboard() {
  const { t } = useTranslation();
  const f = useFilters();
  const kpis = useAsync(() => api.analytics.kpis(f.q), [JSON.stringify(f.q)]);
  const thr = useAsync(() => api.analytics.throughput(f.q), [JSON.stringify(f.q)]);
  const sla = useAsync(() => api.analytics.sla(f.q), [JSON.stringify(f.q)]);
  const act = useAction();
  return (
    <>
      <PageHeader
        title={t('analytics.title')}
        actions={
          <>
            <button type="button" onClick={() => void act.run(() => api.analytics.exportLog('xes', f.q).then(() => true))}>
              {t('analytics.exportXes')}
            </button>
            <button type="button" onClick={() => void act.run(() => api.analytics.exportLog('ocel', f.q).then(() => true))}>
              {t('analytics.exportOcel')}
            </button>
          </>
        }
      />
      <Filters f={f} />
      <ProblemAlert error={kpis.error ?? thr.error ?? sla.error ?? act.error} />
      {kpis.data && (
        <div className="stats">
          {kpis.data.map((k) => (
            <Stat
              key={k.id}
              label={k.name}
              value={`${fmtNumber(k.value, 1)}${k.unit ? ` ${k.unit}` : ''}`}
              sub={k.target !== undefined ? t('analytics.target', { v: fmtNumber(k.target, 1) }) : undefined}
              tone={k.status === 'BREACHED' ? 'bad' : k.status === 'WARNING' ? 'warn' : k.status === 'OK' ? 'ok' : undefined}
            />
          ))}
        </div>
      )}
      {thr.loading && <Loading />}
      {thr.data && thr.data.length > 0 && (
        <LineChart
          title={t('analytics.throughput')}
          labels={thr.data.map((p) => fmtDate(p.t, f.bucket === 'hour'))}
          series={[
            { name: t('analytics.started'), values: thr.data.map((p) => p.started), className: 'series-1' },
            { name: t('analytics.completed'), values: thr.data.map((p) => p.completed), className: 'series-2' },
          ]}
        />
      )}
      {sla.data && (
        <Section title={t('analytics.slaTitle')}>
          <DataTable
            caption={t('analytics.slaTitle')}
            captionHidden
            rows={sla.data}
            rowKey={(r) => r.key}
            columns={[
              { key: 'name', header: t('analytics.process'), render: (r) => r.name ?? r.key },
              { key: 'total', header: t('batch.total'), render: (r) => fmtNumber(r.total) },
              {
                key: 'bar',
                header: t('analytics.slaSplit'),
                render: (r) => (
                  <StackedBar
                    label={r.name ?? r.key}
                    parts={[
                      { name: t('analytics.within'), value: r.withinSla, className: 'seg-ok' },
                      { name: t('analytics.warning'), value: r.warning, className: 'seg-warn' },
                      { name: t('analytics.breached'), value: r.breached, className: 'seg-bad' },
                    ]}
                  />
                ),
              },
              { key: 'breached', header: t('analytics.breached'), render: (r) => fmtNumber(r.breached) },
              { key: 'avg', header: t('analytics.avgBusinessDays'), render: (r) => fmtNumber(r.avgBusinessDays, 1) },
            ]}
          />
        </Section>
      )}
    </>
  );
}

export function ProcessMining() {
  const { t } = useTranslation();
  const f = useFilters();
  const defs = useAsync(() => api.processes.definitions({ latest: true, size: 200 }), []);
  const def = defs.data?.items.find((d) => d.key === f.definitionKey);
  const xml = useAsync(() => api.processes.xml(def!.id), [def?.id], !!def);
  const stats = useAsync(() => api.processes.statistics(def!.id, 'history', { from: f.from, to: f.to }, def!.key), [def?.id, f.from, f.to], !!def);
  const bott = useAsync(() => api.analytics.bottlenecks(f.q), [JSON.stringify(f.q)], !!f.definitionKey);
  const vars = useAsync(() => api.analytics.variants(f.q), [JSON.stringify(f.q)], !!f.definitionKey);
  const conf = useAsync(() => api.analytics.conformance(f.q), [JSON.stringify(f.q)], !!f.definitionKey);
  const [showConformant, setShowConformant] = useState(true);
  const variants = useMemo(() => (vars.data ?? []).filter((v) => showConformant || v.conformant === false), [vars.data, showConformant]);
  return (
    <>
      <PageHeader title={t('mining.title')} description={t('mining.description')} />
      <Filters f={f} withBucket={false} />
      {!f.definitionKey && <Notice>{t('mining.pickProcess')}</Notice>}
      <ProblemAlert error={bott.error ?? vars.error ?? conf.error} />
      {xml.data && stats.data && <BpmnDiagram title={t('mining.heatmap')} xml={xml.data.bpmn20Xml} stats={stats.data.activities} flows={stats.data.flows} mode="duration" />}
      {conf.data && (
        <Section title={t('mining.conformance')}>
          <div className="stats">
            <Stat label={t('mining.fitness')} value={fmtNumber(conf.data.fitness, 3)} tone={conf.data.fitness >= 0.95 ? 'ok' : conf.data.fitness >= 0.8 ? 'warn' : 'bad'} />
            {conf.data.precision !== undefined && <Stat label={t('mining.precision')} value={fmtNumber(conf.data.precision, 3)} />}
          </div>
          {!conf.data.fixtureId && <Notice tone="warn">{t('mining.noFixture')}</Notice>}
          <BarChart title={t('mining.deviations')} rows={conf.data.deviations.map((d) => ({ label: `${t(`mining.dev.${d.kind}`)} ${d.activityId ?? ''}`, value: d.count, tone: 'warn' as const }))} />
        </Section>
      )}
      {bott.data && (
        <Section title={t('mining.bottlenecks')}>
          <BarChart
            title={t('mining.avgWait')}
            rows={[...bott.data].sort((a, b) => b.avgWaitMs - a.avgWaitMs).slice(0, 10).map((b) => ({ label: b.activityName ?? b.activityId, value: b.avgWaitMs, tone: 'warn' as const }))}
            format={fmtDuration}
          />
          <DataTable
            caption={t('mining.bottlenecks')}
            captionHidden
            rows={bott.data}
            rowKey={(r) => r.activityId}
            columns={[
              { key: 'activity', header: t('diagram.activity'), render: (r) => r.activityName ?? r.activityId },
              { key: 'wait', header: t('mining.avgWait'), render: (r) => fmtDuration(r.avgWaitMs) },
              { key: 'service', header: t('mining.avgService'), render: (r) => fmtDuration(r.avgServiceMs) },
              { key: 'p95', header: t('diagram.p95'), render: (r) => fmtDuration(r.p95Ms) },
              { key: 'count', header: t('diagram.executions'), render: (r) => fmtNumber(r.count) },
            ]}
          />
        </Section>
      )}
      {vars.data && (
        <Section title={t('mining.variants')}>
          <Checkbox label={t('mining.showConformant')} checked={showConformant} onChange={setShowConformant} />
          {variants.length === 0 && <Empty message={t('chart.noData')} />}
          <ol className="variants">
            {variants.map((v) => (
              <li key={v.id}>
                <div>
                  <strong>{fmtNumber(v.share * 100, 1)}%</strong> · {fmtNumber(v.count)} · {fmtDuration(v.avgDurationMs)}{' '}
                  {v.conformant !== undefined && <Badge value={v.conformant ? 'CONFORMANT' : 'DEVIATING'} tone={v.conformant ? 'ok' : 'warn'} />}
                </div>
                <ol className="path" aria-label={t('mining.path')}>
                  {v.path.map((p, i) => (
                    <li key={i}>{p}</li>
                  ))}
                </ol>
              </li>
            ))}
          </ol>
        </Section>
      )}
    </>
  );
}

export function Reports() {
  const { t } = useTranslation();
  const f = useFilters();
  const dur = useAsync(() => api.analytics.durationReport(f.q), [JSON.stringify(f.q)]);
  const tasks = useAsync(() => api.analytics.taskReport(f.q), [JSON.stringify(f.q)]);
  return (
    <>
      <PageHeader title={t('reports.title')} />
      <Filters f={f} />
      <ProblemAlert error={dur.error ?? tasks.error} />
      {dur.data && dur.data.length > 0 && (
        <LineChart
          title={t('reports.duration')}
          labels={dur.data.map((d) => d.period)}
          series={[
            { name: t('reports.avg'), values: dur.data.map((d) => d.avgMs / 3600000), className: 'series-1' },
            { name: t('reports.max'), values: dur.data.map((d) => d.maxMs / 3600000), className: 'series-2' },
          ]}
        />
      )}
      {dur.data && (
        <DataTable
          caption={t('reports.duration')}
          rows={dur.data}
          rowKey={(r) => r.period}
          columns={[
            { key: 'period', header: t('chart.period') },
            { key: 'count', header: t('batch.total'), render: (r) => fmtNumber(r.count) },
            { key: 'avg', header: t('reports.avg'), render: (r) => fmtDuration(r.avgMs) },
            { key: 'min', header: t('reports.min'), render: (r) => fmtDuration(r.minMs) },
            { key: 'max', header: t('reports.max'), render: (r) => fmtDuration(r.maxMs) },
          ]}
        />
      )}
      {tasks.data && (
        <DataTable
          caption={t('reports.tasks')}
          rows={tasks.data}
          rowKey={(r) => r.taskName}
          columns={[
            { key: 'taskName', header: t('reports.task') },
            { key: 'completed', header: t('reports.completed'), render: (r) => fmtNumber(r.completed) },
            { key: 'avg', header: t('reports.avg'), render: (r) => fmtDuration(r.avgMs) },
            { key: 'users', header: t('reports.byUser'), render: (r) => (r.byUser ?? []).map((u) => `${u.user}: ${u.count}`).join(', ') || '—' },
          ]}
        />
      )}
    </>
  );
}

export function Alerts() {
  const { t } = useTranslation();
  const list = useAsync(() => api.analytics.alerts(), []);
  const [edit, setEdit] = useState<Partial<AlertRule> | null>(null);
  const act = useAction();
  return (
    <>
      <PageHeader
        title={t('alerts.title')}
        actions={
          <button type="button" className="primary" onClick={() => setEdit({ operator: '>', channel: 'IN_APP', active: true, recipients: [] })}>
            {t('common.new')}
          </button>
        }
      />
      <ProblemAlert error={list.error ?? act.error} onRetry={list.reload} />
      {list.data?.length === 0 && <Empty message={t('alerts.empty')} />}
      {list.data && list.data.length > 0 && (
        <DataTable
          caption={t('alerts.title')}
          captionHidden
          rows={list.data}
          rowKey={(r) => r.id}
          columns={[
            { key: 'name', header: t('common.name') },
            { key: 'rule', header: t('alerts.rule'), render: (r) => `${r.kpi} ${r.operator} ${r.threshold}` },
            { key: 'channel', header: t('ops.channel') },
            { key: 'recipients', header: t('alerts.recipients'), render: (r) => r.recipients.join(', ') },
            { key: 'active', header: t('admin.status'), render: (r) => <Badge value={r.active ? 'ACTIVE' : 'PAUSED'} /> },
            {
              key: 'a',
              header: t('common.actions'),
              render: (r) => (
                <span className="toolbar inline">
                  <button type="button" className="link" onClick={() => setEdit(r)}>
                    {t('common.edit')}
                  </button>
                  <button type="button" className="link danger" onClick={() => void act.run(() => api.analytics.deleteAlert(r.id).then(() => true)).then(() => list.reload())}>
                    {t('common.delete')}
                  </button>
                </span>
              ),
            },
          ]}
        />
      )}
      <Dialog
        open={edit !== null}
        title={t('alerts.rule')}
        onClose={() => setEdit(null)}
        footer={
          <button
            type="button"
            className="primary"
            disabled={!edit?.name || !edit?.kpi || edit?.threshold === undefined}
            onClick={() =>
              void act.run(() => api.analytics.saveAlert(edit!)).then((r) => {
                if (r) {
                  setEdit(null);
                  list.reload();
                }
              })
            }
          >
            {t('common.save')}
          </button>
        }
      >
        {edit && (
          <>
            <TextField label={t('common.name')} required value={edit.name ?? ''} onChange={(v) => setEdit({ ...edit, name: v })} />
            <TextField label={t('alerts.kpi')} required value={edit.kpi ?? ''} onChange={(v) => setEdit({ ...edit, kpi: v })} />
            <SelectField label={t('alerts.operator')} value={edit.operator ?? '>'} onChange={(v) => setEdit({ ...edit, operator: v as AlertRule['operator'] })} options={['>', '<', '>=', '<='].map((o) => ({ value: o, label: o }))} />
            <TextField label={t('alerts.threshold')} type="number" required value={edit.threshold === undefined ? '' : String(edit.threshold)} onChange={(v) => setEdit({ ...edit, threshold: v === '' ? undefined : Number(v) })} />
            <SelectField label={t('ops.channel')} value={edit.channel ?? 'IN_APP'} onChange={(v) => setEdit({ ...edit, channel: v as AlertRule['channel'] })} options={['IN_APP', 'EMAIL', 'WEBHOOK'].map((o) => ({ value: o, label: t(`alerts.ch.${o}`) }))} />
            <TextField label={t('alerts.recipients')} hint={t('events.commaList')} value={(edit.recipients ?? []).join(', ')} onChange={(v) => setEdit({ ...edit, recipients: v.split(',').map((s) => s.trim()).filter(Boolean) })} />
            <Checkbox label={t('events.active')} checked={edit.active ?? true} onChange={(v) => setEdit({ ...edit, active: v })} />
          </>
        )}
      </Dialog>
    </>
  );
}

export function AgentUsage() {
  const { t } = useTranslation();
  const f = useFilters();
  const u = useAsync(() => api.analytics.agentUsage(f.q), [JSON.stringify(f.q)]);
  return (
    <>
      <PageHeader title={t('agents.title')} description={t('agents.description')} />
      <Filters f={f} withBucket={false} />
      <ProblemAlert error={u.error} />
      {u.data?.length === 0 && <Empty message={t('chart.noData')} />}
      {u.data && u.data.length > 0 && (
        <>
          <BarChart title={t('agents.tokens')} rows={u.data.map((a) => ({ label: a.agent, value: a.tokens, tone: a.budget !== undefined && a.tokens > a.budget ? 'bad' : undefined }))} />
          <DataTable
            caption={t('agents.title')}
            captionHidden
            rows={u.data}
            rowKey={(r) => r.agent}
            columns={[
              { key: 'agent', header: t('agents.agent') },
              { key: 'runs', header: t('agents.runs'), render: (r) => fmtNumber(r.runs) },
              { key: 'tokens', header: t('agents.tokens'), render: (r) => fmtNumber(r.tokens) },
              { key: 'budget', header: t('admin.tokenBudget'), render: (r) => (r.budget !== undefined ? fmtNumber(r.budget) : '—') },
              { key: 'cost', header: t('agents.cost'), render: (r) => fmtNumber(r.costUnits, 2) },
              { key: 'guard', header: t('agents.guardrailBlocks'), render: (r) => fmtNumber(r.guardrailBlocks) },
              { key: 'review', header: t('agents.humanReviews'), render: (r) => fmtNumber(r.humanReviews) },
            ]}
          />
        </>
      )}
    </>
  );
}
