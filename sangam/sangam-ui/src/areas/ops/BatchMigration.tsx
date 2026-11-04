// SPDX-License-Identifier: Apache-2.0
/**
 * R64 batch operations (PF-06, D-34): list, progress, pause/resume/cancel, four-eyes approve/reject, per-item
 * failures. R48 / PF-04 / FC-18 / EC-12 migration: generate a plan, edit 1→1, 1→N and N→1 mappings, import/export a
 * JSON migration document, validate, dry-run count with the R19 predicate, pre-hook and post-migration modification,
 * then execute as a batch.
 */
import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import type { BatchStatus, InstanceQuery, MigrationPlan, MigrationValidation, ModificationInstruction } from '../../api/types';
import { announce, useAction, useAsync, useInterval, usePaged } from '../../lib/hooks';
import { fmtDate, fmtNumber } from '../../i18n';
import { StackedBar } from '../../components/Charts';
import { Badge, DataTable, Empty, KeyValues, Loading, Notice, PageHeader, Pagination, ProblemAlert, ReasonDialog, Section, SelectField, TextArea, TextField } from '../../components/ui';

export function Batches() {
  const { t } = useTranslation();
  const [status, setStatus] = useState<BatchStatus | ''>('');
  const list = usePaged((p) => api.ops.batches({ ...p, status: status || undefined }), [status]);
  useInterval(() => list.reload(), 15000);
  return (
    <>
      <PageHeader title={t('batch.title')} />
      <SelectField
        label={t('admin.status')}
        value={status}
        onChange={(v) => setStatus(v as BatchStatus)}
        placeholder={t('common.all')}
        options={(['AWAITING_APPROVAL', 'QUEUED', 'RUNNING', 'PAUSED', 'COMPLETED', 'COMPLETED_WITH_FAILURES', 'CANCELED', 'REJECTED'] as const).map((s) => ({ value: s, label: t(`enum.${s}`) }))}
      />
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.loading && !list.data && <Loading />}
      {list.data?.items.length === 0 && <Empty message={t('batch.empty')} />}
      {list.data && list.data.items.length > 0 && (
        <>
          <DataTable
            caption={t('batch.title')}
            captionHidden
            rows={list.data.items}
            rowKey={(r) => r.id}
            columns={[
              { key: 'id', header: t('batch.id'), render: (r) => <Link to={`/ops/batches/${encodeURIComponent(r.id)}`}>{r.id}</Link> },
              { key: 'type', header: t('batch.type.label'), render: (r) => t(`batch.type.${r.type}`) },
              { key: 'status', header: t('admin.status'), render: (r) => <Badge value={r.status} /> },
              {
                key: 'progress',
                header: t('batch.progress'),
                render: (r) => (
                  <StackedBar
                    label={t('batch.progress')}
                    parts={[
                      { name: t('batch.succeeded'), value: r.succeeded, className: 'seg-ok' },
                      { name: t('batch.failed'), value: r.failed, className: 'seg-bad' },
                      { name: t('batch.remaining'), value: r.remaining, className: 'seg-rest' },
                    ]}
                  />
                ),
              },
              { key: 'total', header: t('batch.total'), render: (r) => fmtNumber(r.total) },
              { key: 'by', header: t('batch.createdBy'), render: (r) => `${r.createdBy}, ${fmtDate(r.createdAt)}` },
            ]}
          />
          <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
        </>
      )}
    </>
  );
}

export function BatchDetail() {
  const { id = '' } = useParams();
  const { t } = useTranslation();
  const b = useAsync(() => api.ops.batch(id), [id]);
  const [filter, setFilter] = useState('FAILED');
  const items = usePaged((p) => api.ops.batchItems(id, { ...p, status: filter || undefined }), [id, filter]);
  const [action, setAction] = useState<'pause' | 'resume' | 'cancel' | 'approve' | 'reject' | null>(null);
  const act = useAction();
  useInterval(() => {
    if (b.data && (b.data.status === 'RUNNING' || b.data.status === 'QUEUED')) b.reload();
  }, 5000);
  if (b.loading && !b.data) return <Loading />;
  if (!b.data) return <ProblemAlert error={b.error} onRetry={b.reload} />;
  const x = b.data;
  return (
    <>
      <PageHeader
        title={`${t(`batch.type.${x.type}`)} · ${x.id}`}
        actions={
          <>
            {x.status === 'AWAITING_APPROVAL' && (
              <>
                <button type="button" className="primary" onClick={() => setAction('approve')}>
                  {t('batch.approve')}
                </button>
                <button type="button" onClick={() => setAction('reject')}>
                  {t('batch.reject')}
                </button>
              </>
            )}
            {x.status === 'RUNNING' && (
              <button type="button" onClick={() => setAction('pause')}>
                {t('batch.pause')}
              </button>
            )}
            {x.status === 'PAUSED' && (
              <button type="button" onClick={() => setAction('resume')}>
                {t('batch.resume')}
              </button>
            )}
            {['RUNNING', 'PAUSED', 'QUEUED', 'AWAITING_APPROVAL'].includes(x.status) && (
              <button type="button" className="danger" onClick={() => setAction('cancel')}>
                {t('batch.cancel')}
              </button>
            )}
          </>
        }
      />
      {x.status === 'AWAITING_APPROVAL' && <Notice tone="warn">{t('batch.awaitingHint')}</Notice>}
      <KeyValues
        items={[
          [t('admin.status'), <Badge key="s" value={x.status} />],
          [t('batch.total'), fmtNumber(x.total)],
          [t('batch.succeeded'), fmtNumber(x.succeeded)],
          [t('batch.failed'), fmtNumber(x.failed)],
          [t('batch.remaining'), fmtNumber(x.remaining)],
          [t('common.reason'), x.reason],
          [t('batch.createdBy'), `${x.createdBy}, ${fmtDate(x.createdAt)}`],
          [t('batch.approvedBy'), x.approvedBy],
          [t('recon.operationId'), x.operationId],
        ]}
      />
      <StackedBar
        label={t('batch.progress')}
        parts={[
          { name: t('batch.succeeded'), value: x.succeeded, className: 'seg-ok' },
          { name: t('batch.failed'), value: x.failed, className: 'seg-bad' },
          { name: t('batch.remaining'), value: x.remaining, className: 'seg-rest' },
        ]}
      />
      <Section title={t('batch.items')}>
        <SelectField
          label={t('admin.status')}
          value={filter}
          onChange={setFilter}
          placeholder={t('common.all')}
          options={['PENDING', 'SUCCEEDED', 'FAILED', 'SKIPPED'].map((s) => ({ value: s, label: t(`enum.${s}`) }))}
        />
        <ProblemAlert error={items.error} />
        {items.data && (
          <DataTable
            caption={t('batch.items')}
            captionHidden
            rows={items.data.items}
            rowKey={(r) => r.id}
            columns={[
              { key: 'targetId', header: t('batch.target'), render: (r) => <Link to={`/ops/instances/${encodeURIComponent(r.targetId)}`}>{r.targetId}</Link> },
              { key: 'status', header: t('admin.status'), render: (r) => <Badge value={r.status} /> },
              { key: 'message', header: t('ops.message'), render: (r) => r.message ?? '—' },
            ]}
          />
        )}
        <Pagination data={items.data} page={items.page} size={items.size} setPage={items.setPage} setSize={items.setSize} />
      </Section>
      <ReasonDialog
        open={action !== null}
        title={action ? t(`batch.${action}`) : ''}
        busy={act.busy}
        error={act.error}
        danger={action === 'cancel' || action === 'reject'}
        onClose={() => setAction(null)}
        onConfirm={(reason) =>
          void act.run(() => api.ops.batchAction(id, action!, reason)).then((r) => {
            if (r) {
              setAction(null);
              b.setData(r);
            }
          })
        }
      >
        {action === 'approve' && <Notice>{t('batch.approveHint')}</Notice>}
      </ReasonDialog>
    </>
  );
}

export function Migration() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const [params] = useSearchParams();
  const [source, setSource] = useState(params.get('source') ?? '');
  const [target, setTarget] = useState('');
  const ids = params.get('ids')?.split(',').filter(Boolean);
  const defs = useAsync(() => api.processes.definitions({ size: 200, latest: false }), []);
  const [plan, setPlan] = useState<MigrationPlan | null>(null);
  const [validation, setValidation] = useState<MigrationValidation>();
  const [count, setCount] = useState<{ count: number; capped?: boolean }>();
  const [preHook, setPreHook] = useState('');
  const [postMod, setPostMod] = useState('[]');
  const [doc, setDoc] = useState('');
  const [execOpen, setExecOpen] = useState(false);
  const act = useAction();
  const query: InstanceQuery = ids?.length ? { instanceIds: ids } : { definitionId: source, state: 'ACTIVE' };

  useEffect(() => {
    setPlan(null);
    setValidation(undefined);
    setCount(undefined);
  }, [source, target]);

  // Any other definition may be a target (cross-key migration is allowed; validation reports what cannot map).
  const targets = (defs.data?.items ?? []).filter((d) => d.id !== source);
  const editInstr = (i: number, side: 'sourceActivityIds' | 'targetActivityIds', v: string) => {
    if (!plan) return;
    const list = v.split(',').map((s) => s.trim()).filter(Boolean);
    setPlan({ ...plan, instructions: plan.instructions.map((x, j) => (j === i ? { ...x, [side]: list } : x)) });
    setValidation(undefined);
  };

  return (
    <>
      <PageHeader title={t('mig.title')} description={t('mig.description')} />
      {ids?.length ? <Notice>{t('mig.selectedInstances', { n: ids.length })}</Notice> : null}
      <ProblemAlert error={defs.error ?? act.error} />
      <div className="filters">
        <SelectField
          label={t('mig.source')}
          value={source}
          onChange={setSource}
          placeholder={t('form.choose')}
          options={(defs.data?.items ?? []).map((d) => ({ value: d.id, label: `${d.name ?? d.key} v${d.version}` }))}
        />
        <SelectField
          label={t('mig.target')}
          value={target}
          onChange={setTarget}
          placeholder={t('form.choose')}
          options={targets.map((d) => ({ value: d.id, label: `${d.name ?? d.key} v${d.version}` }))}
        />
        <button type="button" disabled={!source || !target || act.busy} onClick={() => void act.run(() => api.migration.generate(source, target)).then((p) => p && setPlan(p))}>
          {t('mig.generate')}
        </button>
      </div>
      <Section title={t('mig.document')}>
        <TextArea label={t('mig.importJson')} hint={t('mig.importHint')} value={doc} onChange={setDoc} />
        <div className="toolbar">
          <button
            type="button"
            disabled={!doc.trim()}
            onClick={() => {
              try {
                const p = JSON.parse(doc) as MigrationPlan;
                setSource(p.sourceDefinitionId);
                setTarget(p.targetDefinitionId);
                window.setTimeout(() => setPlan(p), 0);
              } catch {
                announce(t('mig.invalidJson'));
              }
            }}
          >
            {t('mig.import')}
          </button>
          <button type="button" disabled={!plan} onClick={() => setDoc(JSON.stringify(plan, null, 2))}>
            {t('mig.export')}
          </button>
        </div>
      </Section>
      {plan && (
        <Section title={t('mig.mappings')}>
          <p className="hint">{t('mig.mappingHint')}</p>
          <DataTable
            caption={t('mig.mappings')}
            captionHidden
            rows={plan.instructions.map((x, i) => ({ ...x, i }))}
            rowKey={(r) => String(r.i)}
            columns={[
              {
                key: 'src',
                header: t('mig.sourceActivities'),
                render: (r) => (
                  <input aria-label={t('mig.sourceActivities')} value={r.sourceActivityIds.join(', ')} onChange={(e) => editInstr(r.i, 'sourceActivityIds', e.target.value)} />
                ),
              },
              {
                key: 'tgt',
                header: t('mig.targetActivities'),
                render: (r) => (
                  <input aria-label={t('mig.targetActivities')} value={r.targetActivityIds.join(', ')} onChange={(e) => editInstr(r.i, 'targetActivityIds', e.target.value)} />
                ),
              },
              {
                key: 'ev',
                header: t('mig.updateEventTrigger'),
                render: (r) => (
                  <input
                    type="checkbox"
                    aria-label={t('mig.updateEventTrigger')}
                    checked={!!r.updateEventTrigger}
                    onChange={(e) => setPlan({ ...plan, instructions: plan.instructions.map((x, j) => (j === r.i ? { ...x, updateEventTrigger: e.target.checked } : x)) })}
                  />
                ),
              },
              {
                key: 'v',
                header: t('mig.validation'),
                render: (r) => {
                  const rep = validation?.instructionReports.find((x) => x.instruction.sourceActivityIds.join() === r.sourceActivityIds.join());
                  return rep?.failures.length ? <span className="field-error">{rep.failures.join('; ')}</span> : validation ? <Badge value="OK" /> : '—';
                },
              },
              {
                key: 'x',
                header: '',
                render: (r) => (
                  <button type="button" className="link danger" onClick={() => setPlan({ ...plan, instructions: plan.instructions.filter((_, j) => j !== r.i) })}>
                    {t('common.remove')}
                  </button>
                ),
              },
            ]}
          />
          <button type="button" onClick={() => setPlan({ ...plan, instructions: [...plan.instructions, { sourceActivityIds: [], targetActivityIds: [] }] })}>
            {t('mig.addMapping')}
          </button>
          <TextField label={t('mig.preHook')} hint={t('mig.preHookHint')} value={preHook} onChange={setPreHook} />
          <TextArea label={t('mig.postModification')} hint={t('mig.postModificationHint')} value={postMod} onChange={setPostMod} />
          <div className="toolbar">
            <button type="button" onClick={() => void act.run(() => api.migration.validate(plan)).then((v) => v && setValidation(v))}>
              {t('mig.validate')}
            </button>
            <button type="button" onClick={() => void act.run(() => api.migration.count(plan, query)).then((c) => c && setCount(c))}>
              {t('mig.dryRun')}
            </button>
            <button type="button" className="primary" disabled={!validation?.valid || !count} onClick={() => setExecOpen(true)}>
              {t('mig.execute')}
            </button>
          </div>
          {validation && <Notice tone={validation.valid ? 'ok' : 'bad'}>{validation.valid ? t('mig.valid') : t('mig.invalid')}</Notice>}
          {count && <Notice>{count.capped ? t('mig.countCapped', { n: fmtNumber(count.count) }) : t('mig.count', { n: fmtNumber(count.count) })}</Notice>}
        </Section>
      )}
      <ReasonDialog
        open={execOpen}
        title={t('mig.execute')}
        preview={count ? { summary: [t('mig.count', { n: fmtNumber(count.count) })], affectedCount: count.count } : undefined}
        busy={act.busy}
        error={act.error}
        onClose={() => setExecOpen(false)}
        onConfirm={(reason) => {
          let pm: ModificationInstruction[] | undefined;
          try {
            pm = JSON.parse(postMod) as ModificationInstruction[];
          } catch {
            pm = undefined;
          }
          void act
            .run((k) => api.migration.execute({ plan: plan!, query, reason, preHook: preHook || undefined, postModification: pm?.length ? pm : undefined }, k))
            .then((b) => {
              if (b) nav(`/ops/batches/${encodeURIComponent(b.id)}`);
            });
        }}
      />
    </>
  );
}
