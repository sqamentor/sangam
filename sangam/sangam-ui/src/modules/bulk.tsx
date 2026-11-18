// SPDX-License-Identifier: Apache-2.0
/**
 * Bulk ingest (M-029, SL-44), on the contract operations of BulkController (sangam-rest .../modules/ops):
 * - list jobs (GET /bulk/jobs ?state), newest first; the server lists only the object types the caller may load
 *   (BulkController.java:58-69), so the list never fails with 403;
 * - create (POST /bulk/jobs {objectType, expectedChunks?}, :47-56), which checks the type's sink permission
 *   (requireType, :53 and :128-132; the one sink today is ops.variable → ops.tenant.admin, OpsWiring.java:170);
 * - upload a file in chunks (PUT /bulk/jobs/{id}/chunks/{seq} {rows}, :76-81) sized by lib/bulkRows.ts, one chunk at a
 *   time, so a stop can be resumed: the same chunk again is a replay (BulkIngest.java:629-639);
 * - close (POST .../close, :83-87) and abort (POST .../abort, :89-93);
 * - the row report (GET .../rows ?state, :95-104) and the error report (GET .../errors, :106-114).
 * 413 is explained: BULK_LIMIT (a chunk over the server's row / byte limit, BulkController.java:149) and
 * TENANT_QUOTA_EXCEEDED (the tenant's maxBatchSize quota per chunk, with `allowed` and `requested`,
 * TenantQuotaExceededException.java:58-66).
 */
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, http, request } from '../api/client';
import { announce, useAction, useAsync, useInterval, usePaged } from '../lib/hooks';
import { fmtDate, fmtNumber, type Translate } from '../i18n';
import { DEFAULT_MAX_BYTES, DEFAULT_MAX_ROWS, chunkRows, duplicateIds, parseRows, type BulkFormat, type BulkRow } from '../lib/bulkRows';
import { Badge, DataTable, Dialog, Empty, KeyValues, Loading, Notice, PageHeader, Pagination, ProblemAlert, Section, SelectField, TabPanel, Tabs, TextField } from '../components/ui';

export type JobState = 'OPEN' | 'PROCESSING' | 'COMPLETED' | 'COMPLETED_WITH_ERRORS' | 'ABORTED';
export const JOB_STATES: JobState[] = ['OPEN', 'PROCESSING', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'ABORTED'];
export const ROW_STATES = ['PENDING', 'RUNNING', 'DONE', 'SKIPPED', 'FAILED', 'CANCELLED'] as const;

/** BulkJob (sangam-api.yaml BulkJob; BulkIngest.Job, BulkIngest.java:156-159). */
export interface BulkJob {
  id: string;
  tenantId?: string;
  objectType: string;
  state: JobState;
  expectedChunks?: number | null;
  chunks: number;
  totalRows: number;
  rows?: Record<string, number>;
  createdBy?: string;
  createdAt?: string;
  closedAt?: string | null;
  completedAt?: string | null;
}
/** BulkChunkReceipt (BulkIngest.java:161). */
export interface ChunkReceipt {
  chunkSeq: number;
  rows: number;
  invalidRows: number;
  replayed: boolean;
}
/** BulkRowStatus (BulkIngest.java:164-166). */
interface RowStatus {
  chunkSeq: number;
  rowSeq: number;
  externalId?: string;
  state: string;
  attempts?: number;
  errorCode?: string;
  errorText?: string;
  resultRef?: string;
}

const enc = encodeURIComponent;

/** A 413 from a chunk upload, explained (BULK_LIMIT or the tenant quota); null for any other error. */
export function explain413(e: unknown, t: Translate): string | null {
  if (!(e instanceof ApiError) || e.status !== 413) return null;
  const p = e.problem;
  if (p.code === 'TENANT_QUOTA_EXCEEDED') return t('bulk.quotaExceeded', { allowed: fmtNumber(Number(p.allowed)), requested: fmtNumber(Number(p.requested)) });
  return t('bulk.limitExceeded', { detail: p.detail ?? p.title });
}

function JobList({ onOpen }: { onOpen: (j: BulkJob) => void }) {
  const { t } = useTranslation();
  const [state, setState] = useState('');
  const list = usePaged((p, signal) => http.page<BulkJob>('/bulk/jobs', { ...p, state: state || undefined }, signal), [state]);
  return (
    <>
      <div className="filters">
        <SelectField label={t('admin.status')} value={state} onChange={setState} placeholder={t('common.all')} options={JOB_STATES.map((s) => ({ value: s, label: t(`bulk.state.${s}`) }))} />
      </div>
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.loading && !list.data && <Loading />}
      {list.data && list.data.items.length === 0 && <Empty message={t('bulk.noJobs')} />}
      {list.data && list.data.items.length > 0 && (
        <DataTable
          caption={t('bulk.jobs')}
          captionHidden
          rows={list.data.items}
          rowKey={(j) => j.id}
          columns={[
            { key: 'id', header: t('bulk.job'), render: (j) => <button type="button" className="link" onClick={() => onOpen(j)}>{j.id}</button> },
            { key: 'objectType', header: t('bulk.objectType') },
            { key: 'state', header: t('admin.status'), render: (j) => <Badge value={j.state} /> },
            { key: 'chunks', header: t('bulk.chunks'), className: 'num', render: (j) => (j.expectedChunks ? `${fmtNumber(j.chunks)} / ${fmtNumber(j.expectedChunks)}` : fmtNumber(j.chunks)) },
            { key: 'totalRows', header: t('bulk.rows'), className: 'num', render: (j) => fmtNumber(j.totalRows) },
            { key: 'failed', header: t('bulk.failed'), className: 'num', render: (j) => fmtNumber(j.rows?.FAILED ?? 0) },
            { key: 'createdBy', header: t('bulk.createdBy') },
            { key: 'createdAt', header: t('bulk.createdAt'), render: (j) => fmtDate(j.createdAt) },
          ]}
        />
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
    </>
  );
}

function CreateJob({ onCreated, onClose }: { onCreated: (j: BulkJob) => void; onClose: () => void }) {
  const { t } = useTranslation();
  const [objectType, setObjectType] = useState('ops.variable');
  const [expected, setExpected] = useState('');
  const act = useAction();
  const n = expected.trim() ? Number(expected) : undefined;
  const bad = n !== undefined && (!Number.isInteger(n) || n < 1);
  return (
    <Dialog
      open
      title={t('bulk.create')}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>{t('common.cancel')}</button>
          <button
            type="button"
            className="primary"
            disabled={act.busy || !objectType.trim() || bad}
            onClick={() => void act.run((k) => request<BulkJob>('POST', '/bulk/jobs', { body: { objectType: objectType.trim(), expectedChunks: n }, idempotencyKey: k })).then((j) => j && onCreated(j))}
          >
            {act.busy ? t('common.working') : t('common.confirm')}
          </button>
        </>
      }
    >
      <TextField label={t('bulk.objectType')} hint={t('bulk.objectTypeHint')} required value={objectType} onChange={setObjectType} />
      <TextField label={t('bulk.expectedChunks')} hint={t('bulk.expectedChunksHint')} type="number" min={1} value={expected} onChange={setExpected} error={bad ? t('bulk.positiveWhole') : undefined} />
      <ProblemAlert error={act.error} />
    </Dialog>
  );
}

/** Reads the file, sizes the chunks and uploads them in order from `firstSeq`; stops at the first error. */
function Upload({ job, onProgress }: { job: BulkJob; onProgress: () => void }) {
  const { t } = useTranslation();
  const [format, setFormat] = useState<BulkFormat>('csv');
  const [text, setText] = useState<string>();
  const [fileName, setFileName] = useState('');
  const [maxRows, setMaxRows] = useState(String(DEFAULT_MAX_ROWS));
  const [maxBytes, setMaxBytes] = useState(String(DEFAULT_MAX_BYTES));
  const [receipts, setReceipts] = useState<ChunkReceipt[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const parsed = useMemo(() => (text === undefined ? null : parseRows(text, format)), [text, format]);
  const sizing = useMemo(() => (parsed ? chunkRows(parsed.rows, Math.max(1, Number(maxRows) || DEFAULT_MAX_ROWS), Math.max(1, Number(maxBytes) || DEFAULT_MAX_BYTES)) : null), [parsed, maxRows, maxBytes]);
  const dups = useMemo(() => (parsed ? duplicateIds(parsed.rows) : []), [parsed]);
  const firstSeq = job.chunks;

  const start = async (chunks: BulkRow[][]) => {
    setBusy(true);
    setError(undefined);
    const done: ChunkReceipt[] = [];
    try {
      for (let i = 0; i < chunks.length; i++) {
        const seq = firstSeq + i;
        // One key per job and sequence: a retry of the same chunk is a replay, never a second chunk (R41).
        const r = await request<ChunkReceipt>('PUT', `/bulk/jobs/${enc(job.id)}/chunks/${seq}`, { body: { rows: chunks[i] }, idempotencyKey: `bulk-${job.id}-${seq}` });
        done.push(r);
        setReceipts([...done]);
      }
      announce(t('bulk.uploaded', { n: done.length }));
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
      onProgress();
    }
  };

  const quota = explain413(error, t);
  return (
    <Section title={t('bulk.upload')}>
      <p className="hint">{t('bulk.uploadHint')}</p>
      <div className="filters">
        <SelectField label={t('bulk.format')} value={format} onChange={(v) => setFormat(v as BulkFormat)} options={(['csv', 'json', 'ndjson'] as const).map((f) => ({ value: f, label: t(`bulk.fmt.${f}`) }))} />
        <div className="field">
          <label htmlFor="bulk-file">{t('bulk.file')}</label>
          <input
            id="bulk-file"
            type="file"
            accept=".csv,.json,.ndjson,.jsonl,text/csv,application/json"
            onChange={(e) => {
              const f = e.target.files?.[0];
              setReceipts([]);
              setError(undefined);
              setFileName(f?.name ?? '');
              void f?.text().then(setText);
            }}
          />
        </div>
        <TextField label={t('bulk.maxRows')} type="number" min={1} value={maxRows} onChange={setMaxRows} />
        <TextField label={t('bulk.maxBytes')} type="number" min={1} value={maxBytes} onChange={setMaxBytes} />
      </div>
      {parsed && sizing && (
        <>
          <KeyValues
            items={[
              [t('bulk.file'), fileName],
              [t('bulk.rowsRead'), fmtNumber(parsed.rows.length)],
              [t('bulk.chunksPlanned'), fmtNumber(sizing.chunks.length)],
              [t('bulk.firstSeq'), fmtNumber(firstSeq)],
            ]}
          />
          {parsed.errors.length > 0 && (
            <Notice tone="warn">
              {t('bulk.parseErrors', { n: parsed.errors.length })}
              <ul>{parsed.errors.slice(0, 20).map((e) => <li key={`${e.at}-${e.message}`}>{t('bulk.parseError', { at: e.at, message: e.message })}</li>)}</ul>
            </Notice>
          )}
          {sizing.oversize.length > 0 && <Notice tone="bad">{t('bulk.oversize', { n: sizing.oversize.length, ids: sizing.oversize.slice(0, 5).map((r) => r.externalId).join(', ') })}</Notice>}
          {dups.length > 0 && <Notice tone="warn">{t('bulk.duplicates', { n: dups.length, ids: dups.slice(0, 5).join(', ') })}</Notice>}
          {job.expectedChunks ? firstSeq + sizing.chunks.length > job.expectedChunks && <Notice tone="bad">{t('bulk.tooManyChunks', { n: job.expectedChunks })}</Notice> : null}
          <button type="button" className="primary" disabled={busy || job.state !== 'OPEN' || sizing.chunks.length === 0 || sizing.oversize.length > 0} onClick={() => void start(sizing.chunks)}>
            {busy ? t('bulk.uploading', { done: receipts.length, total: sizing.chunks.length }) : t('bulk.uploadN', { n: sizing.chunks.length })}
          </button>
        </>
      )}
      {quota ? <Notice tone="bad">{quota}</Notice> : <ProblemAlert error={error instanceof Error ? error : undefined} />}
      {receipts.length > 0 && (
        <DataTable
          caption={t('bulk.receipts')}
          rows={receipts}
          rowKey={(r) => String(r.chunkSeq)}
          columns={[
            { key: 'chunkSeq', header: t('bulk.seq'), className: 'num' },
            { key: 'rows', header: t('bulk.rows'), className: 'num', render: (r) => fmtNumber(r.rows) },
            { key: 'invalidRows', header: t('bulk.invalidRows'), className: 'num', render: (r) => fmtNumber(r.invalidRows) },
            { key: 'replayed', header: t('bulk.replayed'), render: (r) => (r.replayed ? t('common.yes') : t('common.no')) },
          ]}
        />
      )}
    </Section>
  );
}

function RowReport({ job, errorsOnly }: { job: BulkJob; errorsOnly?: boolean }) {
  const { t } = useTranslation();
  const [state, setState] = useState('');
  const path = `/bulk/jobs/${enc(job.id)}/${errorsOnly ? 'errors' : 'rows'}`;
  const list = usePaged((p, signal) => http.page<RowStatus>(path, { ...p, state: errorsOnly ? undefined : state || undefined }, signal), [path, state, job.state, job.chunks]);
  return (
    <>
      {!errorsOnly && (
        <div className="filters">
          <SelectField label={t('admin.status')} value={state} onChange={setState} placeholder={t('common.all')} options={ROW_STATES.map((s) => ({ value: s, label: t(`bulk.rowState.${s}`) }))} />
        </div>
      )}
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.data && list.data.items.length === 0 && <Empty message={errorsOnly ? t('bulk.noErrors') : t('common.emptyNone')} />}
      {list.data && list.data.items.length > 0 && (
        <DataTable
          caption={errorsOnly ? t('bulk.errorReport') : t('bulk.rowReport')}
          captionHidden
          rows={list.data.items}
          rowKey={(r) => `${r.chunkSeq}-${r.rowSeq}`}
          columns={[
            { key: 'chunkSeq', header: t('bulk.seq'), className: 'num' },
            { key: 'rowSeq', header: t('bulk.rowSeq'), className: 'num' },
            { key: 'externalId', header: t('bulk.externalId') },
            { key: 'state', header: t('admin.status'), render: (r) => <Badge value={r.state} /> },
            { key: 'attempts', header: t('bulk.attempts'), className: 'num' },
            { key: 'errorCode', header: t('bulk.errorCode'), render: (r) => r.errorCode ?? '—' },
            { key: 'errorText', header: t('bulk.errorText'), render: (r) => r.errorText ?? '—' },
            { key: 'resultRef', header: t('bulk.resultRef'), render: (r) => r.resultRef ?? '—' },
          ]}
        />
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
    </>
  );
}

function JobDetail({ id, onClose }: { id: string; onClose: () => void }) {
  const { t } = useTranslation();
  const job = useAsync((signal) => http.get<BulkJob>(`/bulk/jobs/${enc(id)}`, undefined, signal), [id]);
  const [tab, setTab] = useState('upload');
  const [confirm, setConfirm] = useState<'close' | 'abort' | null>(null);
  const act = useAction();
  const j = job.data;
  useInterval(() => job.reload(), j?.state === 'PROCESSING' ? 5000 : null);
  const step = (what: 'close' | 'abort') =>
    void act.run((k) => request<BulkJob>('POST', `/bulk/jobs/${enc(id)}/${what}`, { body: {}, idempotencyKey: k })).then((r) => {
      if (r) {
        job.setData(r);
        setConfirm(null);
        announce(t('mod.common.done'));
      }
    });
  return (
    <Section
      title={t('bulk.jobTitle', { id })}
      actions={
        <>
          {j?.state === 'OPEN' && <button type="button" onClick={() => setConfirm('close')}>{t('bulk.close')}</button>}
          {(j?.state === 'OPEN' || j?.state === 'PROCESSING') && <button type="button" className="danger" onClick={() => setConfirm('abort')}>{t('bulk.abort')}</button>}
          <button type="button" className="link" onClick={onClose}>{t('common.close')}</button>
        </>
      }
    >
      <ProblemAlert error={job.error} onRetry={job.reload} />
      {!j && job.loading && <Loading />}
      {j && (
        <>
          <KeyValues
            items={[
              [t('bulk.objectType'), j.objectType],
              [t('admin.status'), <Badge key="s" value={j.state} />],
              [t('bulk.chunks'), j.expectedChunks ? `${fmtNumber(j.chunks)} / ${fmtNumber(j.expectedChunks)}` : fmtNumber(j.chunks)],
              [t('bulk.rows'), fmtNumber(j.totalRows)],
              ...Object.entries(j.rows ?? {}).map(([k, v]) => [t(`bulk.rowState.${k}`, { defaultValue: k }), fmtNumber(v)] as [string, string]),
              [t('bulk.createdBy'), j.createdBy],
              [t('bulk.createdAt'), fmtDate(j.createdAt)],
              [t('bulk.closedAt'), fmtDate(j.closedAt)],
              [t('bulk.completedAt'), fmtDate(j.completedAt)],
            ]}
          />
          <Tabs label={t('bulk.jobTitle', { id })} active={tab} onChange={setTab} tabs={[{ id: 'upload', label: t('bulk.upload') }, { id: 'rows', label: t('bulk.rowReport') }, { id: 'errors', label: t('bulk.errorReport') }]} />
          <TabPanel id="upload" active={tab}>
            {j.state === 'OPEN' ? <Upload job={j} onProgress={job.reload} /> : <Notice>{t('bulk.notOpen')}</Notice>}
          </TabPanel>
          <TabPanel id="rows" active={tab}>
            <RowReport job={j} />
          </TabPanel>
          <TabPanel id="errors" active={tab}>
            <RowReport job={j} errorsOnly />
          </TabPanel>
        </>
      )}
      {confirm && (
        <Dialog
          open
          title={confirm === 'close' ? t('bulk.close') : t('bulk.abort')}
          onClose={() => setConfirm(null)}
          footer={
            <>
              <button type="button" onClick={() => setConfirm(null)}>{t('common.cancel')}</button>
              <button type="button" className={confirm === 'abort' ? 'danger' : 'primary'} disabled={act.busy} onClick={() => step(confirm)}>
                {act.busy ? t('common.working') : t('common.confirm')}
              </button>
            </>
          }
        >
          <p>{confirm === 'close' ? t('bulk.closeHint') : t('bulk.abortHint')}</p>
          <ProblemAlert error={act.error} />
        </Dialog>
      )}
    </Section>
  );
}

export function BulkIngestPage() {
  const { t } = useTranslation();
  const [open, setOpen] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [n, setN] = useState(0);
  return (
    <>
      <PageHeader
        title={t('bulk.title')}
        description={t('bulk.desc')}
        actions={<button type="button" className="primary" onClick={() => setCreating(true)}>{t('bulk.create')}</button>}
      />
      <JobList key={n} onOpen={(j) => setOpen(j.id)} />
      {open && <JobDetail key={open} id={open} onClose={() => (setOpen(null), setN(n + 1))} />}
      {creating && (
        <CreateJob
          onClose={() => setCreating(false)}
          onCreated={(j) => {
            setCreating(false);
            setOpen(j.id);
            setN(n + 1);
            announce(t('mod.common.done'));
          }}
        />
      )}
    </>
  );
}
