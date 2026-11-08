// SPDX-License-Identifier: Apache-2.0
/**
 * O02/O03 incident workbench and detail (claim, guarded bulk retry, retry/skip/cancel with consequence preview,
 * notes; no stack traces for non-platform users, R28) and O04/O05 reconciliation console for UNKNOWN_OUTCOME,
 * LATE_RESULT_AFTER_DELETE and missing cancel-acks: evidence is mandatory for confirm / failed, retry only for
 * capability class (a)/(b) after a lookup (A-2), SoD (resolver ≠ initiator/approver) and four-eyes above a value.
 */
import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import type { ConsequencePreview, ReconStatus } from '../../api/types';
import { announce, useAction, useAsync, usePaged } from '../../lib/hooks';
import { fmtDate, fmtDuration, fmtMoney } from '../../i18n';
import { useAuth } from '../../auth/AuthProvider';
import {
  Badge,
  CopyText,
  DataTable,
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
  TextArea,
} from '../../components/ui';

export function Incidents() {
  const { t } = useTranslation();
  const [type, setType] = useState('');
  const [q, setQ] = useState('');
  const [sp] = useSearchParams();
  const defKey = sp.get('processDefinitionKey') ?? undefined;
  const piId = sp.get('processInstanceId') ?? undefined;
  const list = usePaged((p) => api.ops.incidents({ ...p, type: type || undefined, q, processDefinitionKey: defKey, processInstanceId: piId }), [type, q, defKey, piId]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulk, setBulk] = useState(false);
  const act = useAction();
  return (
    <>
      <PageHeader title={t('ops.incidentsTitle')} />
      <div className="filters">
        <SearchBox label={t('common.search')} value={q} onChange={setQ} />
        <SelectField
          label={t('ops.incidentType')}
          value={type}
          onChange={setType}
          placeholder={t('common.all')}
          options={['failedJob', 'failedExternalTask', 'parkedDelivery', 'poisonEvent'].map((v) => ({ value: v, label: t(`ops.incType.${v}`) }))}
        />
      </div>
      {selected.size > 0 && (
        <div className="toolbar bulk">
          <span>{t('common.selectedN', { n: selected.size })}</span>
          <button type="button" onClick={() => setBulk(true)}>
            {t('ops.bulkRetry')}
          </button>
        </div>
      )}
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.loading && !list.data && <Loading />}
      {list.data?.items.length === 0 && <Empty message={t('ops.noIncidents')} />}
      {list.data && list.data.items.length > 0 && (
        <>
          <DataTable
            caption={t('ops.incidentsTitle')}
            captionHidden
            rows={list.data.items}
            rowKey={(r) => r.id}
            selectable
            selected={selected}
            onSelect={setSelected}
            columns={[
              { key: 'type', header: t('ops.incidentType'), render: (r) => <Link to={`/ops/incidents/${encodeURIComponent(r.id)}`}>{t(`ops.incType.${r.type}`, { defaultValue: r.type })}</Link> },
              { key: 'message', header: t('ops.message'), render: (r) => <span className="clip">{r.message}</span> },
              { key: 'errorClass', header: t('ops.errorClass'), render: (r) => r.errorClass ?? '—' },
              { key: 'case', header: t('ops.businessKey'), render: (r) => r.businessKey ?? '—' },
              { key: 'pack', header: t('task.pack'), render: (r) => r.packKey ?? r.definitionKey ?? '—' },
              { key: 'first', header: t('ops.firstSeen'), render: (r) => fmtDate(r.firstSeen) },
              { key: 'last', header: t('ops.lastSeen'), render: (r) => fmtDate(r.lastSeen) },
              { key: 'owner', header: t('ops.owner'), render: (r) => r.owner ?? '—' },
            ]}
          />
          <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
        </>
      )}
      <ReasonDialog
        open={bulk}
        title={t('ops.bulkRetry')}
        busy={act.busy}
        error={act.error}
        onClose={() => setBulk(false)}
        onConfirm={(reason) =>
          void act.run((k) => api.ops.bulkRetry([...selected], reason, k)).then((b) => {
            if (b) {
              setBulk(false);
              setSelected(new Set());
              announce(t('batch.created', { id: b.id }));
              list.reload();
            }
          })
        }
      >
        <Notice>{t('ops.bulkRetryHint', { n: selected.size })}</Notice>
      </ReasonDialog>
    </>
  );
}

function cause(i: object, key: string) {
  const id = (i as Record<string, unknown>)[key];
  return id ? <Link key={key} to={`/ops/incidents/${encodeURIComponent(String(id))}`}>{String(id)}</Link> : undefined;
}

export function IncidentDetail() {
  const { id = '' } = useParams();
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const inc = useAsync(() => api.ops.incident(id), [id]);
  const [action, setAction] = useState<'retry' | 'skip' | 'cancel' | null>(null);
  const [preview, setPreview] = useState<ConsequencePreview>();
  const [note, setNote] = useState('');
  const [trace, setTrace] = useState<string>();
  const act = useAction();
  const open = async (a: 'retry' | 'skip' | 'cancel') => {
    setAction(a);
    setPreview(await api.ops.incidentPreview(id, a).catch(() => undefined));
  };
  if (inc.loading && !inc.data) return <Loading />;
  if (!inc.data) return <ProblemAlert error={inc.error} onRetry={inc.reload} />;
  const i = inc.data;
  return (
    <>
      <PageHeader
        title={`${t('ops.incident')}: ${t(`ops.incType.${i.type}`, { defaultValue: i.type })}`}
        actions={
          <>
            <button type="button" onClick={() => void act.run(() => api.ops.incidentAction(id, 'claim', {}).then(() => true)).then(() => inc.reload())}>
              {t('ops.claimIncident')}
            </button>
            <button type="button" className="primary" onClick={() => void open('retry')}>
              {t('ops.retry')}
            </button>
            {i.processInstanceId && (
              <Link to={`/ops/instances/${encodeURIComponent(i.processInstanceId)}`}>{t('ops.modifyInInstance')}</Link>
            )}
          </>
        }
      />
      <ProblemAlert error={act.error} />
      <KeyValues
        items={[
          [t('ops.message'), i.message],
          [t('ops.errorClass'), i.errorClass],
          [t('ops.businessKey'), i.businessKey],
          [t('diagram.activity'), i.activityId],
          [t('ops.attempts'), String(i.attempts ?? '—')],
          [t('ops.lockVersion'), String(i.lockVersion ?? '—')],
          [t('ops.externalEffect'), i.externalEffectStatus ? <Badge key="e" value={i.externalEffectStatus} /> : undefined],
          [t('ops.firstSeen'), fmtDate(i.firstSeen)],
          [t('ops.lastSeen'), fmtDate(i.lastSeen)],
          [t('ops.owner'), i.owner],
          [t('ops.causeIncident'), cause(i, 'causeIncidentId')],
          [t('ops.rootCauseIncident'), cause(i, 'rootCauseIncidentId')],
        ]}
      />
      {i.externalEffectStatus === 'UNKNOWN_OUTCOME' && <Notice tone="warn">{t('ops.unknownOutcomeHint')}</Notice>}
      {hasRole('PLATFORM_OPERATOR') && i.jobId && (
        <Section title={t('ops.stacktrace')}>
          {!trace ? (
            <button type="button" onClick={() => void api.ops.jobStacktrace(i.jobId!).then(setTrace)}>
              {t('ops.showStacktrace')}
            </button>
          ) : (
            <pre className="json">{trace}</pre>
          )}
        </Section>
      )}
      <Section title={t('ops.note')}>
        {i.note && <p>{i.note}</p>}
        <TextArea label={t('ops.addNote')} value={note} onChange={setNote} />
        <button
          type="button"
          disabled={!note.trim()}
          onClick={() =>
            void act.run(() => api.ops.incidentAction(id, 'note', { note }).then(() => true)).then((ok) => {
              if (ok) {
                setNote('');
                inc.reload();
              }
            })
          }
        >
          {t('common.save')}
        </button>
      </Section>
      <ReasonDialog
        open={action !== null}
        title={action ? t(`ops.${action}`) : ''}
        preview={preview}
        busy={act.busy}
        error={act.error}
        onClose={() => setAction(null)}
        onConfirm={(reason) =>
          void act.run((k) => api.ops.incidentAction(id, action === 'retry' ? 'retry' : 'resolve', { reason }, k).then(() => true)).then((ok) => {
            if (ok) {
              setAction(null);
              announce(t('ops.actionDone'));
              inc.reload();
            }
          })
        }
      />
    </>
  );
}

export function Reconciliation() {
  const { t } = useTranslation();
  const [status, setStatus] = useState<ReconStatus | ''>('');
  const [mine, setMine] = useState(false);
  const [sort, setSort] = useState('-ageSec');
  const { me } = useAuth();
  const list = usePaged((p) => api.ops.reconciliation({ ...p, status: status || undefined, owner: mine ? me?.userId : undefined, open: true, sort }), [status, mine, sort]);
  return (
    <>
      <PageHeader title={t('recon.title')} description={t('recon.description')} />
      <div className="filters">
        <SelectField
          label={t('admin.status')}
          value={status}
          onChange={(v) => setStatus(v as ReconStatus)}
          placeholder={t('common.all')}
          options={(['UNKNOWN_OUTCOME', 'LATE_RESULT_AFTER_DELETE', 'CANCEL_ACK_MISSING'] as const).map((s) => ({ value: s, label: t(`enum.${s}`) }))}
        />
        <label className="choice">
          <input type="checkbox" checked={mine} onChange={(e) => setMine(e.target.checked)} /> {t('recon.mine')}
        </label>
      </div>
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.loading && !list.data && <Loading />}
      {list.data?.items.length === 0 && <Empty message={t('recon.empty')} />}
      {list.data && list.data.items.length > 0 && (
        <>
          <DataTable
            caption={t('recon.title')}
            captionHidden
            rows={list.data.items}
            rowKey={(r) => r.id}
            sort={sort}
            onSort={setSort}
            columns={[
              { key: 'operationId', header: t('recon.operationId'), render: (r) => <Link to={`/ops/reconciliation/${encodeURIComponent(r.id)}`}>{r.operationId.slice(0, 18)}…</Link> },
              { key: 'status', header: t('admin.status'), render: (r) => <Badge value={r.status} /> },
              { key: 'amount', header: t('task.amount'), sortable: true, className: 'num', render: (r) => (r.amount !== undefined ? fmtMoney(r.amount, r.currency) : '—') },
              { key: 'capabilityClass', header: t('recon.class'), render: (r) => t(`recon.classLabel.${r.capabilityClass}`) },
              { key: 'destination', header: t('recon.destination'), render: (r) => r.destination ?? '—' },
              { key: 'ageSec', header: t('recon.age'), sortable: true, render: (r) => fmtDuration(r.ageSec * 1000) },
              { key: 'owner', header: t('ops.owner'), render: (r) => r.owner ?? '—' },
              { key: 'evidenceCount', header: t('recon.evidence'), render: (r) => String(r.evidenceCount) },
            ]}
          />
          <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
        </>
      )}
    </>
  );
}

type Resolution = 'CONFIRM_SUCCESS' | 'RETRY_SAME_OPERATION' | 'MARK_FAILED';

export function ReconciliationDetail() {
  const { id = '' } = useParams();
  const { t } = useTranslation();
  const { me } = useAuth();
  const item = useAsync(() => api.ops.reconItem(id), [id]);
  const act = useAction();
  const [res, setRes] = useState<Resolution | null>(null);
  const [kind, setKind] = useState('STATUS_FILE');
  const [result, setResult] = useState('{}');
  const [selectedEvidence, setSelectedEvidence] = useState<Set<string>>(new Set());
  const [lookup, setLookup] = useState<string>();
  const [evidenceNote, setEvidenceNote] = useState('');
  if (item.loading && !item.data) return <Loading />;
  if (!item.data) return <ProblemAlert error={item.error} onRetry={item.reload} />;
  const r = item.data;
  const sodConflict = !!me && (r.initiator === me.userId || r.approver === me.userId);
  const retryAllowed = (r.capabilityClass === 'a' || r.capabilityClass === 'b') && (r.lookupAvailable ? !!(lookup ?? r.lookupResult) : true);
  const resolved = !!r.resolution;
  return (
    <>
      <PageHeader
        title={`${t('recon.item')}: ${r.operationId}`}
        actions={
          !resolved && (
            <>
              {r.owner !== me?.userId && (
                <button type="button" onClick={() => void act.run(() => api.ops.reconClaim(id).then(() => true)).then(() => item.reload())}>
                  {t('recon.claim')}
                </button>
              )}
              {r.lookupAvailable && (
                <button type="button" onClick={() => void act.run(() => api.ops.reconLookup(id)).then((x) => x && setLookup(`${x.result} (${fmtDate(x.at)})`))}>
                  {t('recon.lookup')}
                </button>
              )}
            </>
          )
        }
      />
      <ProblemAlert error={act.error} />
      {sodConflict && <Notice tone="bad">{t('recon.sodConflict')}</Notice>}
      {r.fourEyesRequired && <Notice tone="warn">{t('recon.fourEyes')}</Notice>}
      {resolved && <Notice tone="ok">{t('recon.resolvedAs', { r: t(`enum.${r.resolution}`) })}</Notice>}
      <div className="grid-2">
        <Section title={t('recon.facts')}>
          <KeyValues
            items={[
              [t('admin.status'), <Badge key="s" value={r.status} />],
              [t('recon.operationId'), <CopyText key="o" text={r.operationId} />],
              [t('recon.fingerprint'), <code key="f" className="hash">{r.requestFingerprint}</code>],
              [t('recon.class'), t(`recon.classLabel.${r.capabilityClass}`)],
              [t('task.amount'), r.amount !== undefined ? fmtMoney(r.amount, r.currency) : undefined],
              [t('recon.destination'), r.destination],
              [t('ops.businessKey'), r.businessKey ? <Link key="b" to={`/audit/timeline/${encodeURIComponent(r.businessKey)}`}>{r.businessKey}</Link> : undefined],
              [t('recon.lookupResult'), lookup ?? r.lookupResult],
            ]}
          />
        </Section>
        <Section title={t('recon.attempts')}>
          <ol className="timeline">
            {r.attempts.map((a) => (
              <li key={a.attemptId}>
                <time dateTime={a.at}>{fmtDate(a.at)}</time> <code className="small">{a.attemptId}</code> <Badge value={a.outcome} /> {a.message}
              </li>
            ))}
          </ol>
        </Section>
      </div>
      <Section title={t('recon.evidence')}>
        {r.evidence.length === 0 && <Empty message={t('recon.noEvidence')} />}
        {r.evidence.length > 0 && (
          <DataTable
            caption={t('recon.evidence')}
            captionHidden
            rows={r.evidence}
            rowKey={(e) => e.id}
            selectable
            selected={selectedEvidence}
            onSelect={setSelectedEvidence}
            columns={[
              { key: 'kind', header: t('recon.evidenceKind'), render: (e) => t(`recon.kind.${e.kind}`, { defaultValue: e.kind }) },
              { key: 'name', header: t('task.fileName') },
              { key: 'by', header: t('task.uploadedBy'), render: (e) => `${e.addedBy}, ${fmtDate(e.addedAt)}` },
              { key: 'sha', header: t('task.checksum'), render: (e) => (e.sha256 ? <code className="hash">{e.sha256.slice(0, 12)}</code> : '—') },
            ]}
          />
        )}
        {!resolved && <TextArea label={t('recon.evidenceNote')} hint={t('recon.evidenceNoteHint')} value={evidenceNote} onChange={setEvidenceNote} />}
        {!resolved && (
          <div className="filters">
            <SelectField
              label={t('recon.evidenceKind')}
              value={kind}
              onChange={setKind}
              options={['STATUS_FILE', 'GATEWAY_QUERY', 'BANK_STATEMENT', 'EMAIL', 'OTHER'].map((k) => ({ value: k, label: t(`recon.kind.${k}`) }))}
            />
            <div className="field">
              <label htmlFor="ev-file">{t('recon.addEvidence')}</label>
              <input
                id="ev-file"
                type="file"
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  e.target.value = '';
                  if (f) void act.run((k) => api.ops.reconEvidence(id, kind, f, k).then(() => true)).then(() => item.reload());
                }}
              />
            </div>
          </div>
        )}
      </Section>
      {r.ledgerEntry && (
        <Section title={t('recon.ledger')}>
          <JsonView value={r.ledgerEntry} />
        </Section>
      )}
      {!resolved && (
        <div className="decision-bar" role="group" aria-label={t('recon.resolve')}>
          <button type="button" className="primary" disabled={sodConflict || (selectedEvidence.size === 0 && !evidenceNote.trim())} onClick={() => setRes('CONFIRM_SUCCESS')}>
            {t('recon.confirmSuccess')}
          </button>
          <button type="button" disabled={sodConflict || !retryAllowed} onClick={() => setRes('RETRY_SAME_OPERATION')}>
            {t('recon.retrySame')}
          </button>
          <button type="button" className="danger" disabled={sodConflict || (selectedEvidence.size === 0 && !evidenceNote.trim())} onClick={() => setRes('MARK_FAILED')}>
            {t('recon.markFailed')}
          </button>
          <span className="small muted">{t('recon.rules')}</span>
        </div>
      )}
      <ReasonDialog
        open={res !== null}
        title={res ? t(`recon.res.${res}`) : ''}
        danger={res === 'MARK_FAILED'}
        busy={act.busy}
        error={act.error}
        onClose={() => setRes(null)}
        onConfirm={(reason) => {
          let parsed: Record<string, unknown> | undefined;
          try {
            parsed = res === 'CONFIRM_SUCCESS' ? (JSON.parse(result) as Record<string, unknown>) : undefined;
          } catch {
            parsed = undefined;
          }
          void act.run((k) => api.ops.reconResolve(id, { resolution: res!, reason, result: parsed, evidence: [...[...selectedEvidence].map((e) => `doc:${e}`), evidenceNote.trim()].filter(Boolean).join('\n') || reason }, k)).then((x) => {
            if (x) {
              setRes(null);
              announce(x.fourEyesPending ? t('recon.pendingSecond') : t('recon.resolved'));
              item.reload();
            }
          });
        }}
      >
        {res === 'CONFIRM_SUCCESS' && <TextArea label={t('recon.resultJson')} hint={t('recon.resultHint')} value={result} onChange={setResult} />}
        {res === 'RETRY_SAME_OPERATION' && <Notice>{t('recon.retryHint')}</Notice>}
      </ReasonDialog>
    </>
  );
}
