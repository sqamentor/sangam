// SPDX-License-Identifier: Apache-2.0
/**
 * S14 my delegations and leave, S13 notification centre, S15 field verification with an offline queue (EC-16).
 */
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import { newIdempotencyKey } from '../../api/client';
import { announce, useAction, useAsync, usePaged } from '../../lib/hooks';
import { fmtDate } from '../../i18n';
import { useAuth } from '../../auth/AuthProvider';
import { Badge, Checkbox, DataTable, Empty, Loading, Notice, PageHeader, Pagination, ProblemAlert, Section, SelectField, TextArea, TextField } from '../../components/ui';

export function MyDelegations() {
  const { t } = useTranslation();
  const { me } = useAuth();
  const list = useAsync(() => api.me.delegations(), []);
  const act = useAction();
  const [seatId, setSeatId] = useState('');
  const [sub, setSub] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [reason, setReason] = useState('');
  return (
    <>
      <PageHeader title={t('my.delegationsTitle')} description={t('my.delegationsDesc')} />
      <Section title={t('my.seatsHeld')}>
        <ul>
          {me?.seats.map((s) => (
            <li key={s.seatId}>
              <strong>{s.seatName}</strong> — {t(`seat.kind.${s.kind}`)} · {fmtDate(s.validFrom, false)} – {s.validTo ? fmtDate(s.validTo, false) : t('common.open')}
              {s.actingForPersonName && ` · ${t('seat.actingFor', { name: s.actingForPersonName })}`}
              {s.pendingApproval && <Badge value="PENDING" />}
            </li>
          ))}
        </ul>
      </Section>
      <Section title={t('my.chargesTitle')}>
        <ProblemAlert error={list.error} onRetry={list.reload} />
        {list.data?.length === 0 && <Empty message={t('my.noCharges')} />}
        {list.data && list.data.length > 0 && (
          <DataTable
            caption={t('my.chargesTitle')}
            captionHidden
            rows={list.data}
            rowKey={(r) => r.id}
            columns={[
              { key: 'seatName', header: t('admin.seat') },
              { key: 'personName', header: t('admin.person') },
              { key: 'kind', header: t('admin.kind'), render: (r) => t(`seat.kind.${r.kind}`) },
              { key: 'validFrom', header: t('admin.validFrom'), render: (r) => fmtDate(r.validFrom, false) },
              { key: 'validTo', header: t('admin.validTo'), render: (r) => (r.validTo ? fmtDate(r.validTo, false) : '—') },
              { key: 'status', header: t('admin.status'), render: (r) => <Badge value={r.status} /> },
            ]}
          />
        )}
      </Section>
      <Section title={t('my.requestLeave')}>
        <p className="hint">{t('my.requestLeaveHint')}</p>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void act
              .run(() =>
                api.me.requestLeave({ seatId, substituteSeatId: sub || undefined, from: new Date(from).toISOString(), to: new Date(to).toISOString(), reason }),
              )
              .then((r) => {
                if (r) {
                  announce(t('my.leaveRequested'));
                  list.reload();
                }
              });
          }}
        >
          <SelectField
            label={t('admin.seat')}
            required
            value={seatId}
            onChange={setSeatId}
            placeholder={t('form.choose')}
            options={(me?.seats ?? []).filter((s) => s.kind === 'SUBSTANTIVE').map((s) => ({ value: s.seatId, label: s.seatName }))}
          />
          <TextField label={t('my.substituteSeat')} value={sub} onChange={setSub} hint={t('my.substituteHint')} />
          <TextField label={t('admin.validFrom')} type="date" required value={from} onChange={setFrom} />
          <TextField label={t('admin.validTo')} type="date" required value={to} onChange={setTo} />
          <TextArea label={t('common.reason')} required value={reason} onChange={setReason} />
          <ProblemAlert error={act.error} />
          <button type="submit" className="primary" disabled={act.busy || !seatId || !from || !to || !reason}>
            {t('my.submitLeave')}
          </button>
        </form>
      </Section>
    </>
  );
}

export function Notifications() {
  const { t } = useTranslation();
  const list = usePaged((p) => api.me.notifications(p), []);
  return (
    <>
      <PageHeader title={t('notif.title')} />
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.loading && !list.data && <Loading />}
      {list.data?.items.length === 0 && <Empty message={t('notif.empty')} />}
      <ul className="notifications">
        {list.data?.items.map((n) => (
          <li key={n.id} className={n.read ? 'read' : 'unread'}>
            <div>
              <strong>{n.title}</strong> {n.category === 'GOVERNMENT_MESSAGE' && <Badge value="GOVERNMENT_MESSAGE" tone="info" />}
              <div className="small muted">{fmtDate(n.at)}</div>
              {n.body && <p>{n.body}</p>}
              {n.link && <Link to={n.link}>{t('common.open')}</Link>}
            </div>
            {!n.read && (
              <button type="button" className="link" onClick={() => void api.me.markRead(n.id).then(() => list.reload())}>
                {t('notif.markRead')}
              </button>
            )}
          </li>
        ))}
      </ul>
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
    </>
  );
}

interface QueuedRecord {
  visitId: string;
  key: string;
  body: { answers: Record<string, boolean | string>; notes: string; recordedAt: string };
}
const QUEUE_KEY = 'sangam.field.queue';

function readQueue(): QueuedRecord[] {
  try {
    return JSON.parse(window.localStorage.getItem(QUEUE_KEY) ?? '[]') as QueuedRecord[];
  } catch {
    return [];
  }
}
function writeQueue(q: QueuedRecord[]) {
  try {
    window.localStorage.setItem(QUEUE_KEY, JSON.stringify(q));
  } catch {
    /* storage unavailable: records are sent immediately only */
  }
}

/** S15: records are queued locally with their idempotency key and synced when online (no duplicates, R41). */
export function FieldVisits() {
  const { t } = useTranslation();
  const visits = useAsync(() => api.field.visits(), []);
  const [queue, setQueue] = useState<QueuedRecord[]>(readQueue);
  const [online, setOnline] = useState(navigator.onLine);
  const [open, setOpen] = useState<string | null>(null);
  const [answers, setAnswers] = useState<Record<string, boolean | string>>({});
  const [notes, setNotes] = useState('');
  const [syncErr, setSyncErr] = useState<Error>();

  useEffect(() => {
    const on = () => setOnline(true);
    const off = () => setOnline(false);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => {
      window.removeEventListener('online', on);
      window.removeEventListener('offline', off);
    };
  }, []);

  const sync = async () => {
    const q = readQueue();
    const left: QueuedRecord[] = [];
    for (const r of q) {
      try {
        await api.field.record(r.visitId, r.body, r.key);
      } catch (e) {
        left.push(r);
        setSyncErr(e as Error);
      }
    }
    writeQueue(left);
    setQueue(left);
    if (left.length === 0) announce(t('field.synced'));
  };

  useEffect(() => {
    if (online && queue.length > 0) void sync();
  }, [online]); // eslint-disable-line react-hooks/exhaustive-deps

  const visit = visits.data?.find((v) => v.id === open);
  return (
    <>
      <PageHeader title={t('field.title')} description={t('field.description')} />
      <Notice tone={online ? 'ok' : 'warn'}>{online ? t('field.online') : t('field.offline')}</Notice>
      {queue.length > 0 && (
        <Notice tone="warn">
          {t('field.queued', { n: queue.length })}{' '}
          <button type="button" className="link" disabled={!online} onClick={() => void sync()}>
            {t('field.syncNow')}
          </button>
        </Notice>
      )}
      <ProblemAlert error={visits.error ?? syncErr} onRetry={visits.reload} />
      {visits.loading && <Loading />}
      {visits.data?.length === 0 && <Empty message={t('field.empty')} />}
      {!visit && (
        <ul className="cards">
          {visits.data?.map((v) => (
            <li key={v.id} className="card">
              <h2>{v.businessKey}</h2>
              <p>{v.address}</p>
              {v.scheduledAt && <p className="small">{fmtDate(v.scheduledAt)}</p>}
              <button
                type="button"
                className="primary"
                onClick={() => {
                  setOpen(v.id);
                  setAnswers({});
                  setNotes('');
                }}
              >
                {t('field.record')}
              </button>
            </li>
          ))}
        </ul>
      )}
      {visit && (
        <Section title={`${visit.businessKey} — ${visit.address}`}>
          <fieldset className="field">
            <legend>{t('field.checklist')}</legend>
            {visit.checklist.map((c) => (
              <Checkbox key={c.id} label={c.label} checked={Boolean(answers[c.id])} onChange={(v) => setAnswers({ ...answers, [c.id]: v })} />
            ))}
          </fieldset>
          <TextArea label={t('field.notes')} value={notes} onChange={setNotes} />
          <div className="toolbar">
            <button type="button" onClick={() => setOpen(null)}>
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="primary"
              onClick={() => {
                const rec: QueuedRecord = { visitId: visit.id, key: newIdempotencyKey(), body: { answers, notes, recordedAt: new Date().toISOString() } };
                const q = [...readQueue(), rec];
                writeQueue(q);
                setQueue(q);
                setOpen(null);
                announce(t('field.saved'));
                if (online) void sync();
              }}
            >
              {t('field.save')}
            </button>
          </div>
        </Section>
      )}
    </>
  );
}
