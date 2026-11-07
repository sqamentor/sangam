// SPDX-License-Identifier: Apache-2.0
/**
 * Remaining operations screens:
 * - CMMN case-instance view for operators (PF-12) and DMN decision definitions / instances / evaluation (R63);
 * - job monitor with retries and due-date change (R63, FC-24); outbox / inbox repair (O09, VI.2);
 * - worker and topic console (I01, FC-36/37); event registry: channels, event definitions, test send (FC-02/03, R83);
 * - notification and DLT failure view (O06); deployments (R43); history cleanup / retention view (PF-10, D-13, R29/R61);
 * - sandbox debugger (R80, EC-13, FC-44): breakpoints, step, fire timers — refused by the server on production;
 * - operator audit log (PF-09, PF-52, EC-20).
 */
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api, type EventChannel, type EventDefinition } from '../../api/endpoints';
import type { DecisionDefinition } from '../../api/types';
import { announce, useAction, useAsync, usePaged } from '../../lib/hooks';
import { fmtDate, fmtDuration, fmtNumber } from '../../i18n';
import { DmnView } from '../../components/DmnView';
import { BpmnDiagram } from '../../components/BpmnDiagram';
import {
  Badge,
  Checkbox,
  DataTable,
  Dialog,
  Empty,
  JsonView,
  KeyValues,
  Loading,
  Notice,
  PageHeader,
  Pagination,
  ProblemAlert,
  ReasonDialog,
  SearchBox,
  Section,
  SelectField,
  TabPanel,
  Tabs,
  TextArea,
  TextField,
} from '../../components/ui';
import { InstanceSearch } from './Processes';

export function OpsCases() {
  const { t } = useTranslation();
  const [state, setState] = useState('');
  const [q, setQ] = useState('');
  const list = usePaged((p) => api.cases.list({ ...p, state: state || undefined, q }), [state, q]);
  const [tab, setTab] = useState('cases');
  return (
    <>
      <PageHeader title={t('ops.casesTitle')} />
      <Tabs label={t('ops.casesTitle')} active={tab} onChange={setTab} tabs={[{ id: 'cases', label: t('nav.cases') }, { id: 'instances', label: t('ops.instanceSearch') }]} />
      <TabPanel id="cases" active={tab}>
        <div className="filters">
          <SearchBox label={t('common.search')} value={q} onChange={setQ} />
          <SelectField label={t('admin.status')} value={state} onChange={setState} placeholder={t('common.all')} options={['ACTIVE', 'COMPLETED', 'TERMINATED', 'SUSPENDED', 'CLOSED'].map((s) => ({ value: s, label: t(`enum.${s}`) }))} />
        </div>
        <ProblemAlert error={list.error} onRetry={list.reload} />
        {list.data && (
          <DataTable
            caption={t('ops.casesTitle')}
            captionHidden
            rows={list.data.items}
            rowKey={(r) => r.id}
            columns={[
              { key: 'id', header: t('cases.reference'), render: (r) => <Link to={`/cases/${encodeURIComponent(r.id)}`}>{r.businessKey ?? r.id}</Link> },
              { key: 'definitionKey', header: t('cases.type') },
              { key: 'state', header: t('admin.status'), render: (r) => <Badge value={r.state} /> },
              { key: 'created', header: t('cases.created'), render: (r) => fmtDate(r.created) },
            ]}
          />
        )}
        <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
      </TabPanel>
      <TabPanel id="instances" active={tab}>
        <InstanceSearch />
      </TabPanel>
    </>
  );
}

export function Decisions() {
  const { t } = useTranslation();
  const defs = usePaged((p) => api.decisions.definitions(p), []);
  const [sel, setSel] = useState<DecisionDefinition | null>(null);
  const xml = useAsync(() => api.decisions.xml(sel!.id), [sel?.id], !!sel);
  const inst = usePaged((p) => api.decisions.instances({ ...p, decisionKey: sel?.key }), [sel?.key]);
  const [vars, setVars] = useState('{}');
  const evalAct = useAction();
  const [out, setOut] = useState<unknown>();
  const [hit, setHit] = useState<string[]>([]);
  return (
    <>
      <PageHeader title={t('ops.decisionsTitle')} />
      <ProblemAlert error={defs.error} onRetry={defs.reload} />
      {defs.data && (
        <DataTable
          caption={t('ops.decisionsTitle')}
          captionHidden
          rows={defs.data.items}
          rowKey={(r) => r.id}
          columns={[
            {
              key: 'name',
              header: t('ops.definition'),
              render: (r) => (
                <button type="button" className="link" aria-pressed={sel?.id === r.id} onClick={() => setSel(r)}>
                  {r.name ?? r.key}
                </button>
              ),
            },
            { key: 'key', header: t('ops.key') },
            { key: 'version', header: t('ops.version') },
            { key: 'drd', header: t('ops.drd'), render: (r) => r.decisionRequirementsKey ?? '—' },
          ]}
        />
      )}
      <Pagination data={defs.data} page={defs.page} size={defs.size} setPage={defs.setPage} setSize={defs.setSize} />
      {sel && (
        <>
          {xml.data && <DmnView xml={xml.data.dmnXml} title={sel.name ?? sel.key} highlightRules={hit} />}
          <Section title={t('ops.evaluate')}>
            <TextArea label={t('ops.variablesJson')} value={vars} onChange={setVars} />
            <button
              type="button"
              onClick={() => {
                let v: Record<string, unknown> = {};
                try {
                  v = JSON.parse(vars) as Record<string, unknown>;
                } catch {
                  /* empty */
                }
                void evalAct.run(() => api.decisions.evaluate(sel.key, v)).then((r) => r && setOut(r.outputs));
              }}
            >
              {t('ops.evaluate')}
            </button>
            <ProblemAlert error={evalAct.error} />
            {out !== undefined && <JsonView value={out} />}
          </Section>
          <Section title={t('ops.decisionInstances')}>
            <ProblemAlert error={inst.error} />
            {inst.data && (
              <DataTable
                caption={t('ops.decisionInstances')}
                captionHidden
                rows={inst.data.items}
                rowKey={(r) => r.id}
                columns={[
                  {
                    key: 'time',
                    header: t('task.when'),
                    render: (r) => (
                      <button type="button" className="link" onClick={() => setHit(((r.outputs ?? []) as { rule?: string; ruleId?: string }[]).map((x) => x.ruleId ?? x.rule ?? '').filter(Boolean))}>
                        {fmtDate(r.evaluationTime)}
                      </button>
                    ),
                  },
                  { key: 'v', header: t('ops.version'), render: (r) => String(r.decisionVersion) },
                  { key: 'pi', header: t('ops.instance'), render: (r) => (r.processInstanceId ? <Link to={`/ops/instances/${encodeURIComponent(r.processInstanceId)}`}>{r.businessKey ?? r.processInstanceId}</Link> : '—') },
                  { key: 'in', header: t('ops.inputs'), render: (r) => r.inputs.map((x) => `${x.name}=${JSON.stringify(x.value)}`).join(', ') },
                  { key: 'out', header: t('ops.outputs'), render: (r) => r.outputs.map((x) => `${x.rule ? `[${x.rule}] ` : ''}${x.name}=${JSON.stringify(x.value)}`).join(', ') },
                ]}
              />
            )}
            <Pagination data={inst.data} page={inst.page} size={inst.size} setPage={inst.setPage} setSize={inst.setSize} />
          </Section>
        </>
      )}
    </>
  );
}

export function Jobs() {
  const { t } = useTranslation();
  const [failed, setFailed] = useState(true);
  const [type, setType] = useState('');
  const list = usePaged((p) => api.ops.jobs({ ...p, failed, type: type || undefined }), [failed, type]);
  const summary = useAsync(() => api.ops.jobSummary(), []);
  const [retry, setRetry] = useState<string | null>(null);
  const [retries, setRetries] = useState('1');
  const [due, setDue] = useState('');
  const act = useAction();
  return (
    <>
      <PageHeader title={t('ops.jobsTitle')} />
      {summary.data && (
        <KeyValues
          items={[
            [t('ops.jobsExecutable'), fmtNumber(summary.data.executable)],
            [t('ops.jobsFailed'), fmtNumber(summary.data.failed)],
            [t('ops.timersDue'), fmtNumber(summary.data.timersDue)],
            [t('ops.jobsLocked'), fmtNumber(summary.data.locked)],
          ]}
        />
      )}
      <div className="filters">
        <Checkbox label={t('ops.failedOnly')} checked={failed} onChange={setFailed} />
        <SelectField label={t('ops.jobType')} value={type} onChange={setType} placeholder={t('common.all')} options={['timer', 'async-continuation', 'message', 'batch'].map((v) => ({ value: v, label: v }))} />
      </div>
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.data && list.data.items.length === 0 && <Empty message={t('common.emptyFiltered')} />}
      {list.data && list.data.items.length > 0 && (
        <DataTable
          caption={t('ops.jobsTitle')}
          captionHidden
          rows={list.data.items}
          rowKey={(r) => r.id}
          columns={[
            { key: 'id', header: t('ops.job') },
            { key: 'type', header: t('ops.jobType') },
            { key: 'pi', header: t('ops.instance'), render: (r) => (r.processInstanceId ? <Link to={`/ops/instances/${encodeURIComponent(r.processInstanceId)}`}>{r.processInstanceId}</Link> : '—') },
            { key: 'activityId', header: t('diagram.activity'), render: (r) => r.activityId ?? '—' },
            { key: 'due', header: t('ops.due'), render: (r) => fmtDate(r.dueDate) },
            { key: 'retries', header: t('ops.retries') },
            { key: 'partition', header: t('ops.partition'), render: (r) => String(r.partition ?? '—') },
            { key: 'lock', header: t('ops.lockOwner'), render: (r) => (r.lockOwner ? `${r.lockOwner} → ${fmtDate(r.lockExpiresAt)}` : '—') },
            { key: 'err', header: t('ops.message'), render: (r) => <span className="clip">{r.exceptionMessage ?? '—'}</span> },
            {
              key: 'a',
              header: t('common.actions'),
              render: (r) => (
                <button type="button" className="link" onClick={() => setRetry(r.id)}>
                  {t('ops.setRetries')}
                </button>
              ),
            },
          ]}
        />
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
      <ReasonDialog
        open={retry !== null}
        title={t('ops.setRetries')}
        busy={act.busy}
        error={act.error}
        onClose={() => setRetry(null)}
        onConfirm={(reason) =>
          void act.run(() => api.ops.jobRetry(retry!, Number(retries), reason, due ? new Date(due).toISOString() : undefined).then(() => true)).then((ok) => {
            if (ok) {
              setRetry(null);
              list.reload();
            }
          })
        }
      >
        <TextField label={t('ops.retries')} type="number" min={0} value={retries} onChange={setRetries} />
        <TextField label={t('ops.newDueDate')} type="datetime-local" value={due} onChange={setDue} />
      </ReasonDialog>
    </>
  );
}

export function OutboxInbox() {
  const { t } = useTranslation();
  const [tab, setTab] = useState('outbox');
  const [status, setStatus] = useState('PARKED');
  const outbox = usePaged((p) => api.ops.outbox({ ...p, status: status || undefined }), [status]);
  const inbox = usePaged((p) => api.ops.inbox(p), []);
  const [pending, setPending] = useState<{ id: string; action: 'requeue' | 'skip' } | null>(null);
  const act = useAction();
  return (
    <>
      <PageHeader title={t('ops.outboxTitle')} description={t('ops.outboxDesc')} />
      <Tabs label={t('ops.outboxTitle')} active={tab} onChange={setTab} tabs={[{ id: 'outbox', label: t('ops.outbox') }, { id: 'inbox', label: t('ops.inbox') }]} />
      <TabPanel id="outbox" active={tab}>
        <SelectField label={t('admin.status')} value={status} onChange={setStatus} placeholder={t('common.all')} options={['PENDING', 'IN_FLIGHT', 'PARKED', 'DELIVERED', 'SKIPPED'].map((s) => ({ value: s, label: t(`enum.${s}`) }))} />
        <ProblemAlert error={outbox.error ?? act.error} onRetry={outbox.reload} />
        {outbox.data && (
          <DataTable
            caption={t('ops.outbox')}
            captionHidden
            rows={outbox.data.items}
            rowKey={(r) => r.id}
            columns={[
              { key: 'destination', header: t('recon.destination') },
              { key: 'rootKey', header: t('ops.rootKey') },
              { key: 'status', header: t('admin.status'), render: (r) => <Badge value={r.status} /> },
              { key: 'ordered', header: t('ops.ordered'), render: (r) => (r.ordered ? t('ops.orderedBlocks') : t('common.no')) },
              { key: 'attempts', header: t('ops.attempts') },
              { key: 'err', header: t('ops.message'), render: (r) => <span className="clip">{r.lastError ?? '—'}</span> },
              { key: 'op', header: t('recon.operationId'), render: (r) => <code className="small">{r.operationId.slice(0, 12)}</code> },
              {
                key: 'a',
                header: t('common.actions'),
                render: (r) =>
                  r.status === 'PARKED' ? (
                    <span className="toolbar inline">
                      <button type="button" className="link" onClick={() => setPending({ id: r.id, action: 'requeue' })}>
                        {t('ops.requeue')}
                      </button>
                      <button type="button" className="link danger" onClick={() => setPending({ id: r.id, action: 'skip' })}>
                        {t('ops.skip')}
                      </button>
                    </span>
                  ) : null,
              },
            ]}
          />
        )}
        <Pagination data={outbox.data} page={outbox.page} size={outbox.size} setPage={outbox.setPage} setSize={outbox.setSize} />
      </TabPanel>
      <TabPanel id="inbox" active={tab}>
        <ProblemAlert error={inbox.error} onRetry={inbox.reload} />
        {inbox.data && (
          <DataTable
            caption={t('ops.inbox')}
            captionHidden
            rows={inbox.data.items}
            rowKey={(r) => r.id}
            columns={[
              { key: 'source', header: t('ops.source') },
              { key: 'messageId', header: t('ops.messageId') },
              { key: 'status', header: t('admin.status'), render: (r) => <Badge value={r.status} /> },
              { key: 'correlationKey', header: t('ops.correlationKey'), render: (r) => r.correlationKey ?? '—' },
              { key: 'receivedAt', header: t('ops.received'), render: (r) => fmtDate(r.receivedAt) },
            ]}
          />
        )}
        <Pagination data={inbox.data} page={inbox.page} size={inbox.size} setPage={inbox.setPage} setSize={inbox.setSize} />
      </TabPanel>
      <ReasonDialog
        open={pending !== null}
        title={pending ? t(`ops.${pending.action}`) : ''}
        danger={pending?.action === 'skip'}
        busy={act.busy}
        error={act.error}
        onClose={() => setPending(null)}
        onConfirm={(reason) =>
          void act.run((k) => api.ops.outboxAction(pending!.id, pending!.action, reason, k).then(() => true)).then((ok) => {
            if (ok) {
              setPending(null);
              outbox.reload();
            }
          })
        }
      >
        {pending?.action === 'skip' && <Notice tone="warn">{t('ops.skipHint')}</Notice>}
      </ReasonDialog>
    </>
  );
}

export function Workers() {
  const { t } = useTranslation();
  const workers = useAsync(() => api.ops.workers(), []);
  const topics = useAsync(() => api.ops.topics(), []);
  const [pause, setPause] = useState<{ name: string; paused: boolean } | null>(null);
  const act = useAction();
  return (
    <>
      <PageHeader title={t('ops.workersTitle')} />
      <Section title={t('ops.topics')}>
        <ProblemAlert error={topics.error ?? act.error} onRetry={topics.reload} />
        {topics.data && (
          <DataTable
            caption={t('ops.topics')}
            captionHidden
            rows={topics.data}
            rowKey={(r) => r.name}
            columns={[
              { key: 'name', header: t('ops.topic') },
              { key: 'cc', header: t('recon.class'), render: (r) => (r.capabilityClass ? t(`recon.classLabel.${r.capabilityClass}`) : '—') },
              { key: 'backlog', header: t('ops.backlog'), render: (r) => fmtNumber(r.backlog) },
              { key: 'lock', header: t('ops.lockDuration'), render: (r) => fmtDuration(r.lockDurationMs) },
              { key: 'paused', header: t('admin.status'), render: (r) => <Badge value={r.paused ? 'PAUSED' : 'ACTIVE'} /> },
              {
                key: 'a',
                header: t('common.actions'),
                render: (r) => (
                  <button type="button" className="link" onClick={() => setPause({ name: r.name, paused: !r.paused })}>
                    {r.paused ? t('ops.resumeTopic') : t('ops.pauseTopic')}
                  </button>
                ),
              },
            ]}
          />
        )}
      </Section>
      <Section title={t('ops.workers')}>
        <ProblemAlert error={workers.error} onRetry={workers.reload} />
        {workers.data && workers.data.length === 0 && <Empty message={t('ops.noWorkers')} />}
        {workers.data && workers.data.length > 0 && (
          <DataTable
            caption={t('ops.workers')}
            captionHidden
            rows={workers.data}
            rowKey={(r) => r.workerId}
            columns={[
              { key: 'workerId', header: t('ops.worker') },
              { key: 'sdk', header: t('ops.sdk'), render: (r) => `${r.sdk} ${r.sdkVersion ?? ''} ${r.language ? `(${r.language})` : ''}` },
              { key: 'topics', header: t('ops.topics'), render: (r) => r.topics.join(', ') },
              { key: 'leases', header: t('ops.activeLeases'), render: (r) => fmtNumber(r.activeLeases) },
              { key: 'hb', header: t('ops.heartbeat'), render: (r) => <span className={r.heartbeatAgeSec > 60 ? 'tone-warn' : undefined}>{fmtDuration(r.heartbeatAgeSec * 1000)}</span> },
              { key: 'err', header: t('ops.errorRate'), render: (r) => (r.errorRate !== undefined ? `${fmtNumber(r.errorRate * 100, 1)}%` : '—') },
              { key: 'stale', header: t('ops.staleLeases'), render: (r) => fmtNumber(r.staleLeaseCount ?? 0) },
            ]}
          />
        )}
      </Section>
      <ReasonDialog
        open={pause !== null}
        title={pause?.paused ? t('ops.pauseTopic') : t('ops.resumeTopic')}
        busy={act.busy}
        error={act.error}
        onClose={() => setPause(null)}
        onConfirm={(reason) =>
          void act.run(() => api.ops.topicPause(pause!.name, pause!.paused, reason).then(() => true)).then((ok) => {
            if (ok) {
              setPause(null);
              topics.reload();
            }
          })
        }
      />
    </>
  );
}

export function EventRegistry() {
  const { t } = useTranslation();
  const channels = useAsync(() => api.events.channels(), []);
  const defs = useAsync(() => api.events.definitions(), []);
  const [tab, setTab] = useState('channels');
  const [ch, setCh] = useState<(EventChannel & { _new?: boolean }) | null>(null);
  const [def, setDef] = useState<(EventDefinition & { corr?: string; pl?: string; _new?: boolean }) | null>(null);
  const [send, setSend] = useState<{ channel: string; payload: string } | null>(null);
  const act = useAction();
  return (
    <>
      <PageHeader title={t('events.title')} description={t('events.description')} />
      <Tabs label={t('events.title')} active={tab} onChange={setTab} tabs={[{ id: 'channels', label: t('events.channels') }, { id: 'defs', label: t('events.definitions') }]} />
      <ProblemAlert error={channels.error ?? defs.error ?? act.error} />
      <TabPanel id="channels" active={tab}>
        <button type="button" className="primary" onClick={() => setCh({ key: '', kind: 'KAFKA', direction: 'INBOUND', destination: '', active: true, _new: true })}>
          {t('common.new')}
        </button>
        {channels.data && (
          <DataTable
            caption={t('events.channels')}
            captionHidden
            rows={channels.data}
            rowKey={(r) => r.id ?? r.key}
            columns={[
              { key: 'key', header: t('ops.key') },
              { key: 'kind', header: t('events.kind') },
              { key: 'direction', header: t('events.direction'), render: (r) => t(`events.dir.${r.direction}`) },
              { key: 'destination', header: t('recon.destination') },
              { key: 'dedup', header: t('events.dedupWindow'), render: (r) => (r.dedupWindowSec ? fmtDuration(r.dedupWindowSec * 1000) : '—') },
              { key: 'active', header: t('admin.status'), render: (r) => <Badge value={r.active ? 'ACTIVE' : 'PAUSED'} /> },
              {
                key: 'a',
                header: t('common.actions'),
                render: (r) => (
                  <span className="toolbar inline">
                    <button type="button" className="link" onClick={() => setCh(r)}>
                      {t('common.edit')}
                    </button>
                    {r.direction === 'INBOUND' && (
                      <button type="button" className="link" onClick={() => setSend({ channel: r.key, payload: '{}' })}>
                        {t('events.testSend')}
                      </button>
                    )}
                  </span>
                ),
              },
            ]}
          />
        )}
      </TabPanel>
      <TabPanel id="defs" active={tab}>
        <button type="button" className="primary" onClick={() => setDef({ key: '', name: '', correlationParameters: [], payload: [], _new: true })}>
          {t('common.new')}
        </button>
        {defs.data && (
          <DataTable
            caption={t('events.definitions')}
            captionHidden
            rows={defs.data}
            rowKey={(r) => r.id ?? r.key}
            columns={[
              { key: 'key', header: t('ops.key') },
              { key: 'name', header: t('common.name') },
              { key: 'corr', header: t('events.correlation'), render: (r) => r.correlationParameters.join(', ') },
              { key: 'payload', header: t('events.payload'), render: (r) => r.payload.map((p) => `${p.name}:${p.type}`).join(', ') },
              { key: 'unique', header: t('events.uniqueStart'), render: (r) => (r.uniqueStart ? t('common.yes') : t('common.no')) },
              {
                key: 'a',
                header: t('common.actions'),
                render: (r) => (
                  <button type="button" className="link" onClick={() => setDef(r)}>
                    {t('common.edit')}
                  </button>
                ),
              },
            ]}
          />
        )}
      </TabPanel>
      <Dialog
        open={ch !== null}
        title={t('events.channel')}
        onClose={() => setCh(null)}
        footer={
          <button
            type="button"
            className="primary"
            onClick={() =>
              void act.run(() => { const { _new, ...body } = ch!; return api.events.saveChannel(body, !!_new); }).then((r) => {
                if (r) {
                  setCh(null);
                  channels.reload();
                }
              })
            }
          >
            {t('common.save')}
          </button>
        }
      >
        {ch && (
          <>
            <TextField label={t('ops.key')} required value={ch.key} onChange={(v) => setCh({ ...ch, key: v })} />
            <SelectField label={t('events.kind')} value={ch.kind} onChange={(v) => setCh({ ...ch, kind: v as EventChannel['kind'] })} options={['KAFKA', 'AMQP', 'JMS', 'WEBHOOK', 'MATRIX', 'TEAMS', 'SLACK', 'WHATSAPP'].map((k) => ({ value: k, label: k }))} />
            <SelectField label={t('events.direction')} value={ch.direction} onChange={(v) => setCh({ ...ch, direction: v as EventChannel['direction'] })} options={['INBOUND', 'OUTBOUND'].map((k) => ({ value: k, label: t(`events.dir.${k}`) }))} />
            <TextField label={t('recon.destination')} required value={ch.destination} onChange={(v) => setCh({ ...ch, destination: v })} />
            <TextField label={t('events.keyDetector')} hint={t('events.detectorHint')} value={ch.keyDetector ?? ''} onChange={(v) => setCh({ ...ch, keyDetector: v })} />
            <TextField label={t('events.tenantDetector')} value={ch.tenantDetector ?? ''} onChange={(v) => setCh({ ...ch, tenantDetector: v })} />
            <TextField label={t('events.dedupWindow')} type="number" value={String(ch.dedupWindowSec ?? '')} onChange={(v) => setCh({ ...ch, dedupWindowSec: v ? Number(v) : undefined })} />
            <Checkbox label={t('events.active')} checked={ch.active} onChange={(v) => setCh({ ...ch, active: v })} />
          </>
        )}
      </Dialog>
      <Dialog
        open={def !== null}
        title={t('events.definition')}
        onClose={() => setDef(null)}
        footer={
          <button
            type="button"
            className="primary"
            onClick={() => {
              const d = def!;
              const body: EventDefinition = {
                id: d.id,
                key: d.key,
                name: d.name,
                uniqueStart: d.uniqueStart,
                correlationParameters: (d.corr ?? d.correlationParameters.join(',')).split(',').map((s) => s.trim()).filter(Boolean),
                payload: (d.pl ?? d.payload.map((p) => `${p.name}:${p.type}`).join(','))
                  .split(',')
                  .map((s) => s.trim())
                  .filter(Boolean)
                  .map((s) => ({ name: s.split(':')[0], type: s.split(':')[1] ?? 'string' })),
              };
              void act.run(() => api.events.saveDefinition(body, !!d._new)).then((r) => {
                if (r) {
                  setDef(null);
                  defs.reload();
                }
              });
            }}
          >
            {t('common.save')}
          </button>
        }
      >
        {def && (
          <>
            <TextField label={t('ops.key')} required value={def.key} onChange={(v) => setDef({ ...def, key: v })} />
            <TextField label={t('common.name')} required value={def.name} onChange={(v) => setDef({ ...def, name: v })} />
            <TextField label={t('events.correlation')} hint={t('events.commaList')} value={def.corr ?? def.correlationParameters.join(', ')} onChange={(v) => setDef({ ...def, corr: v })} />
            <TextField label={t('events.payload')} hint={t('events.payloadHint')} value={def.pl ?? def.payload.map((p) => `${p.name}:${p.type}`).join(', ')} onChange={(v) => setDef({ ...def, pl: v })} />
            <Checkbox label={t('events.uniqueStart')} hint={t('events.uniqueStartHint')} checked={!!def.uniqueStart} onChange={(v) => setDef({ ...def, uniqueStart: v })} />
          </>
        )}
      </Dialog>
      <Dialog
        open={send !== null}
        title={t('events.testSend')}
        onClose={() => setSend(null)}
        footer={
          <button
            type="button"
            className="primary"
            onClick={() => {
              let p: unknown = {};
              try {
                p = JSON.parse(send!.payload);
              } catch {
                /* empty */
              }
              void act.run((k) => api.events.send(send!.channel, p, k).then(() => true)).then((ok) => {
                if (ok) {
                  announce(t('events.sent'));
                  setSend(null);
                }
              });
            }}
          >
            {t('common.send')}
          </button>
        }
      >
        {send && <TextArea label={t('events.payload')} value={send.payload} onChange={(v) => setSend({ ...send, payload: v })} />}
      </Dialog>
    </>
  );
}

export function NotificationFailures() {
  const { t } = useTranslation();
  const list = usePaged((p) => api.ops.notificationFailures(p), []);
  const [resend, setResend] = useState<string | null>(null);
  const act = useAction();
  return (
    <>
      <PageHeader title={t('ops.notifTitle')} />
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.data?.items.length === 0 && <Empty message={t('ops.notifEmpty')} />}
      {list.data && list.data.items.length > 0 && (
        <DataTable
          caption={t('ops.notifTitle')}
          captionHidden
          rows={list.data.items}
          rowKey={(r) => r.id}
          columns={[
            { key: 'at', header: t('task.when'), render: (r) => fmtDate(r.at) },
            { key: 'channel', header: t('ops.channel') },
            { key: 'reason', header: t('common.reason'), render: (r) => t(`ops.notifReason.${r.reason}`, { defaultValue: r.reason }) },
            { key: 'template', header: t('ops.template'), render: (r) => r.templateId ?? '—' },
            { key: 'bk', header: t('ops.businessKey'), render: (r) => r.businessKey ?? '—' },
            { key: 'to', header: t('ops.recipient'), render: (r) => r.recipientMasked },
            {
              key: 'a',
              header: t('common.actions'),
              render: (r) => (
                <span className="toolbar inline">
                  <button type="button" className="link" onClick={() => setResend(r.id)}>
                    {t('ops.resend')}
                  </button>
                  <Link to="/admin/templates">{t('ops.fixTemplate')}</Link>
                </span>
              ),
            },
          ]}
        />
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
      <ReasonDialog
        open={resend !== null}
        title={t('ops.resend')}
        busy={act.busy}
        error={act.error}
        onClose={() => setResend(null)}
        onConfirm={(reason) =>
          void act.run(() => api.ops.resendNotification(resend!, reason).then(() => true)).then((ok) => {
            if (ok) {
              setResend(null);
              list.reload();
            }
          })
        }
      >
        <Notice>{t('ops.resendHint')}</Notice>
      </ReasonDialog>
    </>
  );
}

export function Deployments() {
  const { t } = useTranslation();
  const list = usePaged((p) => api.deployments.list(p), []);
  const [open, setOpen] = useState<string | null>(null);
  return (
    <>
      <PageHeader title={t('ops.deploymentsTitle')} description={t('ops.deploymentsDesc')} actions={<Link to="/modeller">{t('nav.packages')}</Link>} />
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.data && (
        <DataTable
          caption={t('ops.deploymentsTitle')}
          captionHidden
          rows={list.data.items}
          rowKey={(r) => r.id}
          columns={[
            {
              key: 'name',
              header: t('common.name'),
              render: (r) => (
                <button type="button" className="link" aria-expanded={open === r.id} onClick={() => setOpen(open === r.id ? null : r.id)}>
                  {r.name}
                </button>
              ),
            },
            { key: 'time', header: t('ops.deployedAt'), render: (r) => fmtDate(r.deploymentTime) },
            { key: 'source', header: t('ops.source'), render: (r) => r.source ?? '—' },
            { key: 'pkg', header: t('ops.package'), render: (r) => (r.packageId ? <Link to={`/modeller/${encodeURIComponent(r.packageId)}`}>{r.packageId}</Link> : '—') },
            { key: 'res', header: t('ops.resources'), render: (r) => (open === r.id ? (r.resources ?? []).map((x) => x.name).join(', ') : String(r.resources?.length ?? 0)) },
          ]}
        />
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
    </>
  );
}

export function Cleanup() {
  const { t } = useTranslation();
  const st = useAsync(() => api.ops.cleanup(), []);
  const [run, setRun] = useState(false);
  const [ttl, setTtl] = useState<{ key: string; days: string } | null>(null);
  const act = useAction();
  const s = st.data;
  return (
    <>
      <PageHeader title={t('ops.cleanupTitle')} description={t('ops.cleanupDesc')} actions={<button type="button" onClick={() => setRun(true)}>{t('ops.runCleanup')}</button>} />
      <ProblemAlert error={st.error ?? act.error} onRetry={st.reload} />
      {st.loading && <Loading />}
      {s && (
        <>
          <KeyValues
            items={[
              [t('admin.status'), <Badge key="e" value={s.enabled ? 'ACTIVE' : 'PAUSED'} />],
              [t('ops.window'), s.window],
              [t('ops.lastRun'), fmtDate(s.lastRun)],
              [t('ops.nextRun'), fmtDate(s.nextRun)],
              [t('ops.batchSize'), String(s.batchSize ?? '—')],
              [t('ops.legalHolds'), fmtNumber(s.legalHolds ?? 0)],
            ]}
          />
          <Notice>{t('ops.retentionNotice')}</Notice>
          <DataTable
            caption={t('ops.cleanupDefinitions')}
            rows={s.definitions}
            rowKey={(r) => `${r.key}:${r.version}`}
            columns={[
              { key: 'key', header: t('ops.key') },
              { key: 'version', header: t('ops.version') },
              { key: 'ttl', header: t('ops.ttlDays'), render: (r) => (r.ttlDays === undefined ? t('ops.ttlNone') : fmtNumber(r.ttlDays)) },
              { key: 'finished', header: t('ops.finished'), render: (r) => fmtNumber(r.finished) },
              { key: 'cleanable', header: t('ops.cleanable'), render: (r) => fmtNumber(r.cleanable) },
              {
                key: 'a',
                header: t('common.actions'),
                render: (r) => (
                  <button type="button" className="link" onClick={() => setTtl({ key: r.key, days: String(r.ttlDays ?? '') })}>
                    {t('ops.changeTtl')}
                  </button>
                ),
              },
            ]}
          />
        </>
      )}
      <ReasonDialog
        open={run}
        title={t('ops.runCleanup')}
        busy={act.busy}
        error={act.error}
        onClose={() => setRun(false)}
        onConfirm={(reason) =>
          void act.run(() => api.ops.cleanupRun(reason).then(() => true)).then((ok) => {
            if (ok) {
              setRun(false);
              st.reload();
            }
          })
        }
      />
      <ReasonDialog
        open={ttl !== null}
        title={t('ops.changeTtl')}
        busy={act.busy}
        error={act.error}
        onClose={() => setTtl(null)}
        onConfirm={(reason) =>
          void act.run(() => api.ops.cleanupTtl(ttl!.key, Number(ttl!.days), reason).then(() => true)).then((ok) => {
            if (ok) {
              setTtl(null);
              st.reload();
            }
          })
        }
      >
        {ttl && <TextField label={t('ops.ttlDays')} type="number" min={0} value={ttl.days} onChange={(v) => setTtl({ ...ttl, days: v })} />}
      </ReasonDialog>
    </>
  );
}

export function Debugger() {
  const { t } = useTranslation();
  const status = useAsync(() => api.debug.status(), []);
  const [instanceId, setInstanceId] = useState('');
  const [active, setActive] = useState('');
  const inst = useAsync(() => api.processes.instance(active), [active], !!active);
  const xml = useAsync(() => api.processes.xml(inst.data!.definitionId), [inst.data?.definitionId], !!inst.data);
  const stats = useAsync(() => api.processes.instanceStatistics(active), [active], !!active);
  const bps = useAsync(() => api.debug.breakpoints(active), [active], !!active);
  const timers = useAsync(() => api.debug.timers(active), [active], !!active);
  const act = useAction();
  const [last, setLast] = useState<string>();
  const enabled = status.data?.enabled;
  const refresh = () => {
    stats.reload();
    timers.reload();
    inst.reload();
  };
  return (
    <>
      <PageHeader title={t('debug.title')} description={t('debug.description')} />
      <ProblemAlert error={status.error ?? act.error} />
      {status.data && !enabled && <Notice tone="bad">{t('debug.disabled', { profile: status.data.profile })}</Notice>}
      {enabled && (
        <>
          <Notice tone="warn">{t('debug.sandboxOnly', { tenant: status.data?.sandboxTenant ?? '' })}</Notice>
          <form
            className="filters"
            onSubmit={(e) => {
              e.preventDefault();
              setActive(instanceId.trim());
            }}
          >
            <TextField label={t('ops.instance')} value={instanceId} onChange={setInstanceId} required />
            <button type="submit">{t('debug.attach')}</button>
          </form>
          {active && xml.data && (
            <>
              <BpmnDiagram
                title={t('debug.title')}
                xml={xml.data.bpmn20Xml}
                stats={stats.data?.activities}
                selected={bps.data ?? []}
                onSelect={(a) => {
                  const cur = bps.data ?? [];
                  const next = cur.includes(a) ? cur.filter((x) => x !== a) : [...cur, a];
                  void act.run(() => api.debug.setBreakpoints(active, next).then(() => true)).then(() => bps.reload());
                }}
              />
              <p className="hint">{t('debug.breakpointHint')}</p>
              <div className="toolbar">
                <button
                  type="button"
                  className="primary"
                  onClick={() =>
                    void act.run(() => api.debug.step(active)).then((r) => {
                      if (r) {
                        setLast(r.executed);
                        refresh();
                      }
                    })
                  }
                >
                  {t('debug.step')}
                </button>
                <Link to={`/ops/instances/${encodeURIComponent(active)}`}>{t('debug.editVariables')}</Link>
              </div>
              {last && <Notice>{t('debug.executed', { what: last })}</Notice>}
              <Section title={t('debug.timers')}>
                {timers.data?.length === 0 && <Empty message={t('debug.noTimers')} />}
                <ul>
                  {timers.data?.map((j) => (
                    <li key={j.id}>
                      {j.activityId} · {fmtDate(j.dueDate)}{' '}
                      <button type="button" className="link" onClick={() => void act.run(() => api.debug.fireTimer(active, j.id).then(() => true)).then(() => refresh())}>
                        {t('debug.fire')}
                      </button>
                    </li>
                  ))}
                </ul>
              </Section>
            </>
          )}
        </>
      )}
    </>
  );
}

export function OperatorAudit() {
  const { t } = useTranslation();
  const [actor, setActor] = useState('');
  const [operation, setOperation] = useState('');
  const list = usePaged((p) => api.ops.auditLog({ ...p, actor: actor || undefined, operation: operation || undefined }), [actor, operation]);
  return (
    <>
      <PageHeader title={t('ops.auditTitle')} description={t('ops.auditDesc')} />
      <div className="filters">
        <SearchBox label={t('audit.actor')} value={actor} onChange={setActor} />
        <SearchBox label={t('ops.operation')} value={operation} onChange={setOperation} />
      </div>
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.data && (
        <DataTable
          caption={t('ops.auditTitle')}
          captionHidden
          rows={list.data.items}
          rowKey={(r) => r.id}
          columns={[
            { key: 'time', header: t('task.when'), render: (r) => fmtDate(r.time) },
            { key: 'actor', header: t('audit.actor'), render: (r) => r.actor ?? '—' },
            { key: 'operation', header: t('ops.operation'), render: (r) => r.operation ?? '—' },
            { key: 'message', header: t('common.reason'), render: (r) => r.message ?? '—' },
            { key: 'link', header: t('ops.instance'), render: (r) => (r.linkId ? <Link to={`/ops/instances/${encodeURIComponent(r.linkId)}`}>{r.linkId}</Link> : '—') },
          ]}
        />
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
    </>
  );
}
