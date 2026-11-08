// SPDX-License-Identifier: Apache-2.0
/**
 * S03 work inbox / R76 universal inbox: sections My seat · Acting for · Pool · Escalated · Returned to me, server
 * paging with capped counts (A-16), claim and guarded bulk claim (≤ 50), bulk completion as an R64 batch (D-34),
 * "N new tasks — refresh" without reordering under the cursor.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import type { TaskAction, TaskSection, TaskSummary } from '../../api/types';
import { useAction, useAsync, useInterval, usePaged, announce } from '../../lib/hooks';
import { fmtDate, fmtMoney } from '../../i18n';
import { Badge, DataTable, Empty, Loading, Notice, PageHeader, Pagination, ProblemAlert, ReasonDialog, SearchBox, SelectField, Tabs } from '../../components/ui';
import { useAuth } from '../../auth/AuthProvider';

const SECTIONS: TaskSection[] = ['MY_SEAT', 'ACTING_FOR', 'POOL', 'ESCALATED', 'RETURNED'];
const BULK_LIMIT = 50;

export function TaskInbox() {
  const { t } = useTranslation();
  const { seat } = useAuth();
  const [section, setSection] = useState<TaskSection>('MY_SEAT');
  const [q, setQ] = useState('');
  const [sla, setSla] = useState('');
  const [sort, setSort] = useState('slaDue');
  const [sort2, setSort2] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkResult, setBulkResult] = useState<string>();
  const [bulkComplete, setBulkComplete] = useState<TaskAction | null>(null);
  const counts = useAsync(() => api.tasks.counts(), [seat?.seatId]);
  const list = usePaged((p) => api.tasks.list({ ...p, section, q, slaState: sla || undefined, sort: sort2 ? `${sort},${sort2}` : sort }), [section, q, sla, sort, sort2, seat?.seatId]);
  const action = useAction();
  const baseline = useRef<string>('');
  const [newCount, setNewCount] = useState(0);

  useEffect(() => {
    setSelected(new Set());
  }, [section, q, sla]);

  useEffect(() => {
    if (counts.data) baseline.current = JSON.stringify(counts.data);
    setNewCount(0);
  }, [list.data]); // eslint-disable-line react-hooks/exhaustive-deps

  useInterval(() => {
    void api.tasks.counts().then((c) => {
      const before = baseline.current ? (JSON.parse(baseline.current) as Record<string, number>)[section] : undefined;
      const now = (c as Record<string, number | string>)[section];
      if (typeof before === 'number' && typeof now === 'number' && now > before) setNewCount(now - before);
    });
  }, 60000);

  const claimOne = async (id: string) => {
    const r = await action.run((k) => api.tasks.claim(id, k).then(() => true));
    if (r) {
      announce(t('task.claimed'));
      list.reload();
      counts.reload();
    }
  };

  const bulkClaim = async () => {
    const ids = [...selected].slice(0, BULK_LIMIT);
    const r = await action.run((k) => api.tasks.bulkClaim(ids, k));
    if (r) {
      const msg = t('task.bulkClaimResult', { ok: r.succeeded.length, total: ids.length, failed: r.failed.length });
      setBulkResult(msg);
      announce(msg);
      setSelected(new Set());
      list.reload();
      counts.reload();
    }
  };

  const columns = useMemo(
    () => [
      {
        key: 'reference',
        header: t('task.reference'),
        render: (r: TaskSummary) => <Link to={`/tasks/${encodeURIComponent(r.id)}`}>{r.reference ?? r.businessKey ?? r.id}</Link>,
      },
      { key: 'subject', header: t('task.subject'), render: (r: TaskSummary) => r.subject ?? r.name },
      { key: 'packKey', header: t('task.pack'), render: (r: TaskSummary) => r.packKey ?? r.processDefinitionKey ?? '—' },
      { key: 'amount', header: t('task.amount'), className: 'num', render: (r: TaskSummary) => (r.amount !== undefined ? fmtMoney(r.amount, r.currency) : '—') },
      {
        key: 'fromSeatName',
        header: t('task.fromSeat'),
        render: (r: TaskSummary) => (
          <>
            {r.fromSeatName ?? '—'}
            {r.actingForSeatName && <div className="small muted">{t('task.actingForSeat', { seat: r.actingForSeatName })}</div>}
          </>
        ),
      },
      { key: 'received', header: t('task.received'), sortable: true, render: (r: TaskSummary) => fmtDate(r.received ?? r.created) },
      {
        key: 'slaDue',
        header: t('task.slaDue'),
        sortable: true,
        render: (r: TaskSummary) => (
          <>
            {fmtDate(r.slaDue ?? r.due)} {r.slaState && r.slaState !== 'OK' && <Badge value={r.slaState} />}
          </>
        ),
      },
      {
        key: 'act',
        header: t('common.actions'),
        render: (r: TaskSummary) =>
          r.claimable || section === 'POOL' ? (
            <button type="button" onClick={() => void claimOne(r.id)} disabled={action.busy}>
              {t('task.claim')}
            </button>
          ) : (
            <Link to={`/tasks/${encodeURIComponent(r.id)}`}>{t('common.open')}</Link>
          ),
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [t, section, action.busy],
  );

  const countFor = (s: TaskSection) => {
    const v = counts.data?.[s];
    return v === undefined ? undefined : String(v);
  };

  return (
    <>
      <PageHeader title={t('task.inboxTitle')} description={seat ? t('task.inboxFor', { seat: seat.seatName }) : undefined} />
      <Tabs label={t('task.sections')} active={section} onChange={(s) => setSection(s as TaskSection)} tabs={SECTIONS.map((s) => ({ id: s, label: t(`task.section.${s}`), badge: countFor(s) }))} />
      <div role="tabpanel" id={`panel-${section}`} aria-labelledby={`tab-${section}`}>
        <div className="filters">
          <SearchBox label={t('task.search')} value={q} onChange={setQ} />
          <SelectField
            label={t('task.slaFilter')}
            value={sla}
            onChange={setSla}
            placeholder={t('common.all')}
            options={['OK', 'WARNING', 'BREACHED', 'PAUSED'].map((v) => ({ value: v, label: t(`enum.${v}`) }))}
          />
        </div>
        <SelectField
          label={t('task.thenSortBy')}
          value={sort2}
          onChange={setSort2}
          placeholder={t('common.no')}
          options={['received', 'slaDue'].flatMap((k) => [{ value: k, label: t(`task.${k}`) + ' ▲' }, { value: `-${k}`, label: t(`task.${k}`) + ' ▼' }])}
        />
        {newCount > 0 && (
          <Notice>
            {t('task.newTasks', { n: newCount })}{' '}
            <button type="button" className="link" onClick={() => list.reload()}>
              {t('common.refresh')}
            </button>
          </Notice>
        )}
        {bulkResult && <Notice tone="ok">{bulkResult}</Notice>}
        <ProblemAlert error={list.error ?? action.error} onRetry={list.reload} />
        {selected.size > 0 && (
          <div className="toolbar bulk" role="region" aria-label={t('task.bulkActions')}>
            <span>{t('common.selectedN', { n: selected.size })}</span>
            {selected.size > BULK_LIMIT && <span className="muted">{t('task.bulkLimit', { n: BULK_LIMIT })}</span>}
            {section === 'POOL' && (
              <button type="button" onClick={() => void bulkClaim()} disabled={action.busy}>
                {t('task.bulkClaim')}
              </button>
            )}
            {section !== 'POOL' && (
              <button type="button" onClick={() => setBulkComplete('APPROVE')} disabled={action.busy}>
                {t('task.bulkApprove')}
              </button>
            )}
          </div>
        )}
        {list.loading && !list.data ? (
          <Loading slow={list.slow} onRetry={list.reload} />
        ) : list.data && list.data.items.length === 0 ? (
          <Empty message={q || sla ? t('task.emptyFiltered') : t('task.emptySection', { seat: seat?.seatName ?? '' })} />
        ) : (
          list.data && (
            <>
              <DataTable
                caption={t(`task.section.${section}`)}
                rows={list.data.items}
                rowKey={(r) => r.id}
                columns={columns}
                selectable
                selected={selected}
                onSelect={setSelected}
                sort={sort}
                onSort={setSort}
              />
              <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
            </>
          )
        )}
      </div>
      <ReasonDialog
        open={bulkComplete !== null}
        title={t('task.bulkApprove')}
        busy={action.busy}
        error={action.error}
        onClose={() => setBulkComplete(null)}
        onConfirm={(reason) =>
          void action.run((k) => api.tasks.bulkComplete([...selected].slice(0, BULK_LIMIT), bulkComplete!, reason, k)).then((b) => {
            if (b) {
              setBulkComplete(null);
              setBulkResult(t('task.bulkBatchCreated', { id: b.id, n: b.total }));
              setSelected(new Set());
              list.reload();
            }
          })
        }
      >
        <Notice tone="warn">{t('task.bulkApproveHint')}</Notice>
      </ReasonDialog>
    </>
  );
}
