// SPDX-License-Identifier: Apache-2.0
/**
 * S14 my delegations and leave, S13 notification centre, S15 field verification with an encrypted offline queue (EC-16;
 * M-001: LocalQueueContract, device registration and the /sync protocol).
 */
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import { ApiError, newIdempotencyKey } from '../../api/client';
import { FieldQueueError, type CachedState, type FieldSync, type PendingItem, type PulledTemplate, type TemplateItem } from '../../lib/offline/fieldSync';
import { openFieldSync } from '../../lib/offline/session';
import { javaInstant, type GpsFix } from '../../lib/offline/canonical';
import { defaultBackend } from '../../lib/offline/storage';
import { canonicalDecimalOrUndefined, compareDecimal, withinScale } from '../../lib/decimal';
import { announce, useAction, useAsync, usePaged } from '../../lib/hooks';
import { fmtDate, type Translate } from '../../i18n';
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

/** Open assignment states, as the server's own visit list uses them (FieldRestService.java:43-44). */
const OPEN_ASSIGNMENT = new Set(['ASSIGNED', 'IN_PROGRESS', 'OVERDUE', 'REJECTED']);

const label = (l: Record<string, string> | undefined, lang: string, fallback: string) => (l ? l[lang] ?? Object.values(l)[0] ?? fallback : fallback);

/** Best-effort position for the packet's fix (geofence checks); none when denied, unavailable or slow. */
function locate(): Promise<GpsFix | undefined> {
  return new Promise((resolve) => {
    if (typeof navigator === 'undefined' || !navigator.geolocation) return resolve(undefined);
    navigator.geolocation.getCurrentPosition(
      (p) =>
        resolve({
          lat: p.coords.latitude,
          lon: p.coords.longitude,
          accuracyM: p.coords.accuracy,
          altitudeM: p.coords.altitude ?? undefined,
          provider: 'browser',
          mockLocation: false,
          fixTime: javaInstant(new Date(p.timestamp)),
        }),
      () => resolve(undefined),
      { enableHighAccuracy: true, timeout: 10_000, maximumAge: 60_000 },
    );
  });
}

type AnswerValue = boolean | string | number | string[];

/** Why a NUMBER answer is refused, checked exactly as sangam-field ChecklistValidator does (decisions 212, 230, 242). */
export type NumberProblem = { code: 'notANumber' } | { code: 'scale'; scale: number } | { code: 'min'; bound: string } | { code: 'max'; bound: string };

/** compareDecimal, or undefined when a bound is not decimal text (the server validated it; it is then not applied here). */
function compareBound(answer: string, bound: string | number): number | undefined {
  try {
    return compareDecimal(answer, typeof bound === 'number' ? bound : String(bound));
  } catch {
    return undefined;
  }
}

/**
 * Owner decisions 212, 230, 242: a NUMBER answer must be a number, have no more decimal places than the item's scale
 * (counted on the canonical form, so 12.50 has 1) and lie within min..max (inclusive). Every check is exact decimal
 * arithmetic, never a float.
 */
export function numberProblem(item: Pick<TemplateItem, 'min' | 'max' | 'scale'>, text: string): NumberProblem | undefined {
  const c = canonicalDecimalOrUndefined(text);
  if (c === undefined) return { code: 'notANumber' };
  if (typeof item.scale === 'number' && !withinScale(c, item.scale)) return { code: 'scale', scale: item.scale };
  if (item.min !== undefined && item.min !== null && (compareBound(c, item.min) ?? 0) < 0) return { code: 'min', bound: String(item.min) };
  if (item.max !== undefined && item.max !== null && (compareBound(c, item.max) ?? 0) > 0) return { code: 'max', bound: String(item.max) };
  return undefined;
}

/** The user's words for a NUMBER problem (form.* messages). */
export function numberProblemText(p: NumberProblem, t: Translate): string {
  switch (p.code) {
    case 'notANumber':
      return t('form.notANumber');
    case 'scale':
      return p.scale === 0 ? t('form.wholeNumber') : t('form.tooManyPlaces', { n: p.scale });
    case 'min':
      return t('form.min', { n: p.bound });
    case 'max':
      return t('form.max', { n: p.bound });
  }
}

/**
 * Owner decision 212: NUMBER answers travel as canonical decimal text (never a JavaScript float), the same text the
 * server's PacketHasher writes for a number, so the packet hash matches. Returns the answers to capture, or the ids of
 * NUMBER items whose answer is refused: not a number, more decimal places than the item's scale (decision 242), or
 * outside its min..max (decision 230).
 */
export function capturedAnswers(items: TemplateItem[], answers: Record<string, AnswerValue | undefined>): { answers: Record<string, AnswerValue>; invalid: string[] } {
  const numbers = new Map(items.filter((i) => i.type === 'NUMBER').map((i) => [i.id, i]));
  const out: Record<string, AnswerValue> = {};
  const invalid: string[] = [];
  for (const [k, v] of Object.entries(answers)) {
    if (v === undefined) continue;
    const item = numbers.get(k);
    if (item) {
      const text = canonicalDecimalOrUndefined(String(v));
      if (text === undefined || numberProblem(item, text)) invalid.push(k);
      else out[k] = text;
    } else out[k] = v;
  }
  return { answers: out, invalid };
}

/** One answer input per template item type (FieldTemplate.ItemType); media and position items are not captured here. */
function ItemInput({ item, value, onChange, lang }: { item: TemplateItem; value: AnswerValue | undefined; onChange: (v: AnswerValue | undefined) => void; lang: string }) {
  const { t } = useTranslation();
  const l = label(item.label, lang, item.id);
  switch (item.type) {
    case 'BOOLEAN':
      return <Checkbox label={l} checked={value === true} onChange={(v) => onChange(v)} />;
    case 'CHOICE':
      return (
        <SelectField
          label={l}
          required={item.required}
          value={typeof value === 'string' ? value : ''}
          onChange={(v) => onChange(v || undefined)}
          placeholder={t('form.choose')}
          options={(item.options ?? []).map((o) => ({ value: o.value, label: label(o.label, lang, o.value) }))}
        />
      );
    case 'MULTI_CHOICE': {
      const set = Array.isArray(value) ? value : [];
      return (
        <fieldset className="field">
          <legend>{l}</legend>
          {(item.options ?? []).map((o) => (
            <Checkbox
              key={o.value}
              label={label(o.label, lang, o.value)}
              checked={set.includes(o.value)}
              onChange={(v) => onChange(v ? [...set, o.value] : set.filter((x) => x !== o.value))}
            />
          ))}
        </fieldset>
      );
    }
    case 'NUMBER': {
      // decision 212: the typed text is kept as it is (no Number()); it is made canonical when captured. Decisions 230
      // and 242: the bounds and the scale are checked exactly on that text.
      const problem = value === undefined ? undefined : numberProblem(item, String(value));
      return (
        <TextField
          label={l}
          type="text"
          inputMode={item.scale === 0 ? 'numeric' : 'decimal'}
          required={item.required}
          value={value === undefined ? '' : String(value)}
          error={problem ? numberProblemText(problem, t) : undefined}
          onChange={(v) => onChange(v.trim() === '' ? undefined : v)}
        />
      );
    }
    case 'DATE':
      return <TextField label={l} type="date" required={item.required} value={typeof value === 'string' ? value : ''} onChange={(v) => onChange(v || undefined)} />;
    case 'TEXT':
      return <TextArea label={l} required={item.required} value={typeof value === 'string' ? value : ''} onChange={(v) => onChange(v || undefined)} />;
    default:
      return <Notice tone="warn">{t('field.itemNotHere', { label: l })}</Notice>;
  }
}

/**
 * S15 field work (EC-16, M-001): assignments pulled to this device and task-based visits are captured into the
 * encrypted local queue (LocalQueueContract) and synced when online, each with an id fixed at capture (R41).
 */
export function FieldVisits() {
  const { t, i18n } = useTranslation();
  const { me } = useAuth();
  const visits = useAsync(() => api.field.visits(), []);
  const [client, setClient] = useState<FieldSync | null>(null);
  const [durable, setDurable] = useState(true);
  const [pending, setPending] = useState<PendingItem[]>([]);
  const [unclaimed, setUnclaimed] = useState(0);
  const [cache, setCache] = useState<CachedState | null>(null);
  const [online, setOnline] = useState(navigator.onLine);
  const [open, setOpen] = useState<{ kind: 'visit' | 'assignment'; id: string } | null>(null);
  const [answers, setAnswers] = useState<Record<string, AnswerValue | undefined>>({});
  const [notes, setNotes] = useState('');
  const [template, setTemplate] = useState<PulledTemplate | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState<string | null>(null);
  const [syncErr, setSyncErr] = useState<Error>();
  const [notice, setNotice] = useState<string | null>(null);
  const tenantId = me?.tenantId;
  const userId = me?.userId;

  const refresh = async (c: FieldSync) => {
    setPending(await c.pending());
    setUnclaimed(await c.unclaimedCount());
    setCache(await c.cached());
  };

  useEffect(() => {
    if (!tenantId || !userId) return;
    let live = true;
    setDurable(defaultBackend().durable);
    openFieldSync({ tenantId, userId })
      .then(async (c) => {
        if (!live) return;
        setClient(c);
        await refresh(c);
      })
      .catch((e: Error) => {
        if (live) setSyncErr(e);
      });
    return () => {
      live = false;
    };
  }, [tenantId, userId]);

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

  const sync = async (c: FieldSync | null = client) => {
    if (!c) return;
    setSyncErr(undefined);
    try {
      await c.pull();
    } catch (e) {
      // 404: the field module is not deployed (FieldRestConfiguration is conditional); task visits still sync
      if (!(e instanceof ApiError && e.status === 404)) setSyncErr(e as Error);
    }
    const r = await c.sync();
    if (r.wiped) setNotice(t('field.wiped'));
    else if (r.revoked) setNotice(t('field.revoked'));
    if (r.error) setSyncErr(r.error as Error);
    if (r.wiped) {
      setPending([]);
      setUnclaimed(0);
      setCache(null);
      setClient(null);
      return;
    }
    await refresh(c);
    if (!r.error && r.pending === 0 && r.kept === 0) announce(t('field.synced'));
  };

  useEffect(() => {
    if (online && client) void sync(client);
  }, [online, client]); // eslint-disable-line react-hooks/exhaustive-deps

  const assignments = Object.values(cache?.assignments ?? {}).filter((a) => OPEN_ASSIGNMENT.has(String(a.status)));
  const assignment = open?.kind === 'assignment' ? cache?.assignments[open.id] : undefined;
  const visit = open?.kind === 'visit' ? visits.data?.find((v) => v.id === open.id) : undefined;

  // the assignment's checklist template: pulled with the assignment, else fetched while online
  const templateKey = assignment ? `${String(assignment.templateKey)}:${String(assignment.templateVersion)}` : null;
  useEffect(() => {
    setTemplate(null);
    if (!assignment || !templateKey) return;
    const held = cache?.templates[templateKey];
    if (held) setTemplate(held);
    else if (online) void api.field.template(String(assignment.templateKey), Number(assignment.templateVersion)).then(setTemplate, (e: Error) => setSyncErr(e));
  }, [templateKey, online]); // eslint-disable-line react-hooks/exhaustive-deps

  const start = (kind: 'visit' | 'assignment', id: string) => {
    setOpen({ kind, id });
    setAnswers({});
    setNotes('');
  };

  const saveAssignment = async () => {
    if (!client || !assignment) return;
    try {
      const { answers: filled, invalid } = capturedAnswers(items, answers);
      if (invalid.length) {
        const item = items.find((i) => i.id === invalid[0]);
        const problem = item ? numberProblem(item, String(answers[invalid[0]])) : undefined;
        setSyncErr(new Error(`${label(item?.label, i18n.language, invalid[0])}: ${problem ? numberProblemText(problem, t) : t('form.notANumber')}`));
        return;
      }
      await client.captureVisit({ assignmentId: String(assignment.id), baseVersion: Number(assignment.version), answers: filled, notes, fix: await locate() });
      setOpen(null);
      announce(t('field.saved'));
      await refresh(client);
      if (online) void sync();
    } catch (e) {
      setSyncErr(e instanceof FieldQueueError ? new Error(t(`field.error.${e.code}`)) : (e as Error));
    }
  };

  const saveVisit = async () => {
    if (!client || !visit) return;
    const body = { answers: Object.fromEntries(Object.entries(answers).map(([k, v]) => [k, typeof v === 'boolean' ? v : String(v ?? '')])), notes, recordedAt: new Date().toISOString() };
    await client.captureRecord(visit.id, body, newIdempotencyKey());
    setOpen(null);
    announce(t('field.saved'));
    await refresh(client);
    if (online) void sync();
  };

  const discard = async (id: string) => {
    if (!client) return;
    await client.discard(id);
    setConfirmDiscard(null);
    await refresh(client);
  };

  const claim = async () => {
    if (!client) return;
    await client.claimUnclaimed();
    await refresh(client);
    if (online) void sync();
  };

  const queued = pending.filter((p) => p.state === 'PENDING');
  const kept = pending.filter((p) => p.state === 'KEPT');
  const items = (template?.template.sections ?? []).flatMap((s) => s.items ?? []);
  return (
    <>
      <PageHeader title={t('field.title')} description={t('field.description')} />
      <Notice tone={online ? 'ok' : 'warn'}>{online ? t('field.online') : t('field.offline')}</Notice>
      {!durable && <Notice tone="warn">{t('field.notDurable')}</Notice>}
      {notice && <Notice tone="warn">{notice}</Notice>}
      {unclaimed > 0 && client && (
        <Notice tone="warn">
          {t('field.unclaimed', { n: unclaimed })}{' '}
          <button type="button" className="link" onClick={() => void claim()}>
            {t('field.claim')}
          </button>
        </Notice>
      )}
      {queued.length > 0 && (
        <Notice tone="warn">
          {t('field.queued', { n: queued.length })}{' '}
          <button type="button" className="link" disabled={!online} onClick={() => void sync()}>
            {t('field.syncNow')}
          </button>
        </Notice>
      )}
      {kept.length > 0 && (
        <Section title={t('field.keptTitle')}>
          <p className="hint">{t('field.keptHint')}</p>
          <ul>
            {kept.map((k) => (
              <li key={k.id}>
                <Badge value={k.code ?? 'REJECTED'} /> {k.target} {k.at && <span className="small muted">{fmtDate(k.at)}</span>}{' '}
                {confirmDiscard === k.id ? (
                  <button type="button" className="link" onClick={() => void discard(k.id)}>
                    {t('field.discardConfirm')}
                  </button>
                ) : (
                  <button type="button" className="link" onClick={() => setConfirmDiscard(k.id)}>
                    {t('field.discard')}
                  </button>
                )}
              </li>
            ))}
          </ul>
        </Section>
      )}
      <ProblemAlert error={visits.error ?? syncErr} onRetry={visits.reload} />
      {visits.loading && <Loading />}
      {!open && (
        <>
          {assignments.length > 0 && (
            <Section title={t('field.assignments')}>
              <ul className="cards">
                {assignments.map((a) => (
                  <li key={String(a.id)} className="card">
                    <h2>{String(a.businessKey ?? a.targetLabel ?? a.id)}</h2>
                    <p>{String(a.address ?? a.targetLabel ?? '')}</p>
                    {Boolean(a.windowStart ?? a.dueAt) && <p className="small">{fmtDate(String(a.windowStart ?? a.dueAt))}</p>}
                    <button type="button" className="primary" onClick={() => start('assignment', String(a.id))}>
                      {t('field.record')}
                    </button>
                  </li>
                ))}
              </ul>
            </Section>
          )}
          {visits.data?.length === 0 && assignments.length === 0 && <Empty message={t('field.empty')} />}
          <ul className="cards">
            {visits.data?.map((v) => (
              <li key={v.id} className="card">
                <h2>{v.businessKey}</h2>
                <p>{v.address}</p>
                {v.scheduledAt && <p className="small">{fmtDate(v.scheduledAt)}</p>}
                <button type="button" className="primary" onClick={() => start('visit', v.id)}>
                  {t('field.record')}
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
      {assignment && (
        <Section title={`${String(assignment.businessKey ?? assignment.targetLabel ?? assignment.id)} — ${String(assignment.address ?? '')}`}>
          {!template && <Loading />}
          {template && (
            <fieldset className="field">
              <legend>{t('field.checklist')}</legend>
              {items.map((it) => (
                <ItemInput key={it.id} item={it} lang={i18n.language} value={answers[it.id]} onChange={(v) => setAnswers({ ...answers, [it.id]: v })} />
              ))}
            </fieldset>
          )}
          <TextArea label={t('field.notes')} value={notes} onChange={setNotes} />
          <div className="toolbar">
            <button type="button" onClick={() => setOpen(null)}>
              {t('common.cancel')}
            </button>
            <button type="button" className="primary" disabled={!template || !client} onClick={() => void saveAssignment()}>
              {t('field.save')}
            </button>
          </div>
        </Section>
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
            <button type="button" className="primary" disabled={!client} onClick={() => void saveVisit()}>
              {t('field.save')}
            </button>
          </div>
        </Section>
      )}
    </>
  );
}
