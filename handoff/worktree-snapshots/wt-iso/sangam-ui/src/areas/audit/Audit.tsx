// SPDX-License-Identifier: Apache-2.0
/**
 * Audit (R42, S11, U01–U03, PF-09/41, EC-09) and documents (R74, EC-18, PF-30/50):
 * - case timeline with seat, person, "acted for", package/rule version, payload hash, operationId and downstream
 *   status (known / pending / uncertain), plus hash-chain verification: the UI checks prevHash linkage of the loaded
 *   page and asks the server to recompute the chain (GET /audit/verify);
 * - audit search, bounded exports (same predicate as the list), evidence pack download;
 * - document store: versions with checksums, legal hold, template-based bilingual generation.
 */
import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import type { AuditEvent, AuditVerifyResult } from '../../api/types';
import { announce, useAction, useAsync, usePaged } from '../../lib/hooks';
import { fmtDate, fmtNumber } from '../../i18n';
import { Badge, DataTable, Empty, JsonView, Loading, Notice, PageHeader, Pagination, ProblemAlert, ReasonDialog, SearchBox, Section, SelectField, TextField } from '../../components/ui';

export function verifyLinkage(events: AuditEvent[]): { ok: boolean; brokenAt?: number } {
  const sorted = [...events].sort((a, b) => a.seq - b.seq);
  for (let i = 1; i < sorted.length; i++) {
    if (sorted[i].prevHash && sorted[i].seq === sorted[i - 1].seq + 1 && sorted[i].prevHash !== sorted[i - 1].hash) return { ok: false, brokenAt: sorted[i].seq };
  }
  return { ok: true };
}

export function Timeline() {
  const { businessKey = '' } = useParams();
  const { t } = useTranslation();
  const [type, setType] = useState('');
  const list = usePaged((p) => api.audit.timeline(businessKey, { ...p, q: type || undefined, sort: 'seq' }), [businessKey, type], 50);
  const [verify, setVerify] = useState<AuditVerifyResult>();
  const [open, setOpen] = useState<string | null>(null);
  const act = useAction();
  const linkage = useMemo(() => (list.data ? verifyLinkage(list.data.items) : undefined), [list.data]);
  return (
    <>
      <PageHeader
        title={t('audit.timelineTitle', { key: businessKey })}
        actions={
          <>
            <button type="button" className="primary" disabled={act.busy} onClick={() => void act.run(() => api.audit.verify(businessKey)).then((r) => r && (setVerify(r), announce(r.valid ? t('audit.chainValid') : t('audit.chainBroken'))))}>
              {t('audit.verify')}
            </button>
            <button type="button" onClick={() => void act.run(() => api.audit.evidencePack(businessKey).then(() => true))}>
              {t('audit.evidencePack')}
            </button>
          </>
        }
      />
      <ProblemAlert error={list.error ?? act.error} onRetry={list.reload} />
      {linkage && !linkage.ok && <Notice tone="bad">{t('audit.linkBroken', { seq: linkage.brokenAt })}</Notice>}
      {verify && (
        <Notice tone={verify.valid ? 'ok' : 'bad'}>
          <strong>{verify.valid ? t('audit.chainValid') : t('audit.chainBroken')}</strong> · {t('audit.checked', { n: fmtNumber(verify.checked) })}
          {verify.firstBrokenSeq !== undefined && ` · ${t('audit.firstBroken', { seq: verify.firstBrokenSeq })}`}
          {verify.headHash && (
            <div className="small">
              {t('audit.headHash')}: <code className="hash">{verify.headHash}</code>
              {verify.anchoredAt && ` · ${t('audit.anchored', { at: fmtDate(verify.anchoredAt) })}`}
            </div>
          )}
          {verify.message && <p>{verify.message}</p>}
        </Notice>
      )}
      <SearchBox label={t('audit.filterType')} value={type} onChange={setType} />
      {list.loading && !list.data && <Loading />}
      {list.data?.items.length === 0 && <Empty message={t('audit.empty')} />}
      <ol className="timeline audit">
        {list.data?.items.map((e) => (
          <li key={e.id} className={e.downstreamStatus === 'UNCERTAIN' ? 'uncertain' : e.downstreamStatus === 'PENDING' ? 'pending' : ''}>
            <div className="tl-head">
              <time dateTime={e.time}>{fmtDate(e.time)}</time> <span className="small muted">#{e.seq}</span> <Badge value={e.type} tone="info" />
              {e.state && <Badge value={e.state} />}
              {e.downstreamStatus && <Badge value={e.downstreamStatus} />}
            </div>
            <div>
              {e.actor}
              {e.seatName && ` · ${e.seatName}`}
              {e.actedFor && <strong> · {t('task.actedFor', { name: e.actedFor })}</strong>}
            </div>
            <div className="small muted">
              {e.packageVersion && `${t('audit.package')} ${e.packageVersion} · `}
              {e.ruleVersion && `${t('audit.rule')} ${e.ruleVersion} · `}
              {e.payloadHash && (
                <>
                  {t('task.payloadHash')} <code className="hash">{e.payloadHash.slice(0, 12)}</code> ·{' '}
                </>
              )}
              {e.operationId && (
                <>
                  {t('recon.operationId')} <code>{e.operationId.slice(0, 12)}</code> ·{' '}
                </>
              )}
              {t('audit.hash')} <code className="hash">{e.hash.slice(0, 12)}</code> ← <code className="hash">{e.prevHash.slice(0, 12)}</code>
            </div>
            {e.details && (
              <>
                <button type="button" className="link small" aria-expanded={open === e.id} onClick={() => setOpen(open === e.id ? null : e.id)}>
                  {t('audit.details')}
                </button>
                {open === e.id && <JsonView value={e.details} />}
              </>
            )}
          </li>
        ))}
      </ol>
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
    </>
  );
}

export function AuditSearch() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const [bk, setBk] = useState('');
  const [actor, setActor] = useState('');
  const [seat, setSeat] = useState('');
  const [op, setOp] = useState('');
  const [rule, setRule] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [exportOpen, setExportOpen] = useState(false);
  const [format, setFormat] = useState<'CSV' | 'JSON' | 'PDFA'>('CSV');
  const filters = { q: bk || undefined, actor: actor || undefined, seat: seat || undefined, operationId: op || undefined, ruleVersion: rule || undefined, from: from || undefined, to: to || undefined };
  const list = usePaged((p) => api.audit.search({ ...p, ...filters }), [JSON.stringify(filters)]);
  const act = useAction();
  return (
    <>
      <PageHeader
        title={t('audit.searchTitle')}
        actions={
          <button type="button" onClick={() => setExportOpen(true)}>
            {t('audit.export')}
          </button>
        }
      />
      <form
        className="filters"
        onSubmit={(e) => {
          e.preventDefault();
          if (bk && !actor && !seat && !op && !rule) nav(`/audit/timeline/${encodeURIComponent(bk)}`);
        }}
      >
        <TextField label={t('ops.businessKey')} value={bk} onChange={setBk} />
        <TextField label={t('audit.actor')} value={actor} onChange={setActor} />
        <TextField label={t('admin.seat')} value={seat} onChange={setSeat} />
        <TextField label={t('recon.operationId')} value={op} onChange={setOp} />
        <TextField label={t('audit.rule')} value={rule} onChange={setRule} />
        <TextField label={t('common.from')} type="date" value={from} onChange={setFrom} />
        <TextField label={t('common.to')} type="date" value={to} onChange={setTo} />
        <button type="submit">{t('audit.openTimeline')}</button>
      </form>
      <ProblemAlert error={list.error ?? act.error} onRetry={list.reload} />
      {list.data && list.data.items.length === 0 && <Empty message={t('common.emptyFiltered')} />}
      {list.data && list.data.items.length > 0 && (
        <DataTable
          caption={t('audit.searchTitle')}
          captionHidden
          rows={list.data.items}
          rowKey={(r) => r.id}
          columns={[
            { key: 'time', header: t('task.when'), render: (r) => fmtDate(r.time) },
            { key: 'bk', header: t('ops.businessKey'), render: (r) => <Link to={`/audit/timeline/${encodeURIComponent(r.businessKey)}`}>{r.businessKey}</Link> },
            { key: 'type', header: t('audit.type'), render: (r) => <Badge value={r.type} tone="info" /> },
            { key: 'actor', header: t('audit.actor'), render: (r) => `${r.actor}${r.seatName ? ` · ${r.seatName}` : ''}${r.actedFor ? ` · ${t('task.actedFor', { name: r.actedFor })}` : ''}` },
            { key: 'rule', header: t('audit.rule'), render: (r) => r.ruleVersion ?? '—' },
            { key: 'op', header: t('recon.operationId'), render: (r) => (r.operationId ? <code>{r.operationId.slice(0, 12)}</code> : '—') },
          ]}
        />
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
      <ReasonDialog
        open={exportOpen}
        title={t('audit.export')}
        preview={list.data ? { summary: [list.data.capped ? t('common.moreThan', { n: fmtNumber(list.data.total ?? 0) }) : t('common.totalN', { n: fmtNumber(list.data.total ?? list.data.items.length) })] } : undefined}
        busy={act.busy}
        error={act.error}
        onClose={() => setExportOpen(false)}
        onConfirm={() =>
          // The export request itself is logged server-side (U02); the contract export takes no reason field.
          void act.run(() => api.audit.exportNow(bk || undefined, format).then(() => true)).then((ok) => {
            if (ok) setExportOpen(false);
          })
        }
      >
        <SelectField label={t('audit.format')} value={format} onChange={(v) => setFormat(v as typeof format)} options={['CSV', 'JSON', 'PDFA'].map((f) => ({ value: f, label: t(`audit.fmt.${f}`) }))} />
        <Notice>{t('audit.exportLogged')}</Notice>
      </ReasonDialog>
    </>
  );
}

export function AuditExports() {
  const { t } = useTranslation();
  const list = useAsync(() => api.audit.exports(), []);
  return (
    <>
      <PageHeader
        title={t('audit.exportsTitle')}
        actions={
          <button type="button" onClick={list.reload}>
            {t('common.refresh')}
          </button>
        }
      />
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.data?.length === 0 && <Empty message={t('audit.noExports')} />}
      {list.data && list.data.length > 0 && (
        <DataTable
          caption={t('audit.exportsTitle')}
          captionHidden
          rows={list.data}
          rowKey={(r) => r.id}
          columns={[
            { key: 'requestedAt', header: t('task.when'), render: (r) => fmtDate(r.requestedAt) },
            { key: 'format', header: t('audit.format'), render: (r) => t(`audit.fmt.${r.format}`) },
            { key: 'status', header: t('admin.status'), render: (r) => <Badge value={r.status} /> },
            { key: 'rows', header: t('audit.rows'), render: (r) => fmtNumber(r.rowCount) },
            {
              key: 'a',
              header: t('common.actions'),
              render: (r) =>
                r.status === 'READY' ? (
                  <button type="button" className="link" onClick={() => void api.audit.downloadExport(r.id)}>
                    {t('common.download')}
                  </button>
                ) : null,
            },
          ]}
        />
      )}
    </>
  );
}

export function Documents() {
  const { t, i18n } = useTranslation();
  const [bk, setBk] = useState('');
  const list = usePaged((p) => api.documents.list({ ...p, businessKey: bk || undefined }), [bk]);
  const templates = useAsync(() => api.documents.templates(), []);
  const [versionsOf, setVersionsOf] = useState<string | null>(null);
  const versions = useAsync(() => api.documents.versions(versionsOf!), [versionsOf], !!versionsOf);
  const [hold, setHold] = useState<{ id: string; hold: boolean } | null>(null);
  const [gen, setGen] = useState({ templateId: '', businessKey: '', language: i18n.language });
  const act = useAction();
  return (
    <>
      <PageHeader title={t('docs.title')} description={t('docs.description')} />
      <SearchBox label={t('ops.businessKey')} value={bk} onChange={setBk} />
      <ProblemAlert error={list.error ?? act.error} onRetry={list.reload} />
      {list.data && list.data.items.length === 0 && <Empty message={t('docs.empty')} />}
      {list.data && list.data.items.length > 0 && (
        <DataTable
          caption={t('docs.title')}
          captionHidden
          rows={list.data.items}
          rowKey={(r) => r.id}
          columns={[
            {
              key: 'name',
              header: t('task.fileName'),
              render: (r) => (
                <button type="button" className="link" onClick={() => void api.documents.content(r.id, r.name)}>
                  {r.name}
                </button>
              ),
            },
            { key: 'bk', header: t('ops.businessKey'), render: (r) => r.businessKey ?? '—' },
            { key: 'v', header: t('task.version'), render: (r) => `v${r.version}` },
            { key: 'sha', header: t('task.checksum'), render: (r) => <code className="hash">{r.sha256.slice(0, 12)}</code> },
            { key: 'scan', header: t('task.scan'), render: (r) => (r.scanStatus ? <Badge value={r.scanStatus} /> : '—') },
            { key: 'ret', header: t('docs.retention'), render: (r) => r.retentionClass ?? '—' },
            { key: 'hold', header: t('docs.legalHold'), render: (r) => (r.legalHold ? <Badge value="HOLD" tone="warn" /> : '—') },
            {
              key: 'a',
              header: t('common.actions'),
              render: (r) => (
                <span className="toolbar inline">
                  <button type="button" className="link" onClick={() => setVersionsOf(r.id)}>
                    {t('docs.versions')}
                  </button>
                  <button type="button" className="link" onClick={() => setHold({ id: r.id, hold: !r.legalHold })}>
                    {r.legalHold ? t('docs.releaseHold') : t('docs.setHold')}
                  </button>
                </span>
              ),
            },
          ]}
        />
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
      {versionsOf && (
        <Section title={t('docs.versions')}>
          <ProblemAlert error={versions.error} />
          <ul>
            {versions.data?.map((v) => (
              <li key={v.version}>
                <button type="button" className="link" onClick={() => void api.documents.content(versionsOf, v.name, v.version)}>
                  v{v.version} · {v.name}
                </button>{' '}
                <span className="small muted">
                  {v.uploadedBy} · {fmtDate(v.uploadedAt)} · <code className="hash">{v.sha256.slice(0, 12)}</code>
                </span>
              </li>
            ))}
          </ul>
        </Section>
      )}
      <Section title={t('docs.generate')}>
        <div className="filters">
          <SelectField
            label={t('ops.template')}
            value={gen.templateId}
            onChange={(v) => setGen({ ...gen, templateId: v })}
            placeholder={t('form.choose')}
            options={(templates.data ?? []).map((x) => ({ value: x.id, label: `${x.name} v${x.version} (${x.format})` }))}
          />
          <TextField label={t('ops.businessKey')} value={gen.businessKey} onChange={(v) => setGen({ ...gen, businessKey: v })} />
          <SelectField label={t('common.language')} value={gen.language} onChange={(v) => setGen({ ...gen, language: v })} options={[{ value: 'en', label: 'English' }, { value: 'hi', label: 'हिन्दी' }, { value: 'bi', label: t('lang.bilingual') }]} />
          <button
            type="button"
            className="primary"
            disabled={!gen.templateId || !gen.businessKey}
            onClick={() =>
              void act.run((k) => api.documents.generate(gen.templateId, gen.businessKey, gen.language, k)).then((d) => {
                if (d) {
                  announce(t('docs.generated', { name: d.name }));
                  list.reload();
                }
              })
            }
          >
            {t('docs.generateBtn')}
          </button>
        </div>
      </Section>
      <ReasonDialog
        open={hold !== null}
        title={hold?.hold ? t('docs.setHold') : t('docs.releaseHold')}
        busy={act.busy}
        error={act.error}
        onClose={() => setHold(null)}
        onConfirm={(reason) =>
          void act.run(() => api.documents.setHold(hold!.id, hold!.hold, reason).then(() => true)).then((ok) => {
            if (ok) {
              setHold(null);
              list.reload();
            }
          })
        }
      />
    </>
  );
}
