// SPDX-License-Identifier: Apache-2.0
/**
 * sangam-resources screens (contract /resources/**, /queues/**, /rosters/**):
 * - resources (types, items, calendars, blackouts, slot templates), a **booking calendar** (week view of slots,
 *   availability and bookings for one resource, book a free slot), bookings, waitlist, verifiable draws, allotments;
 * - queues: service points and counters, a **counter console** (call next, recall, skip, serve, complete) and a
 *   **queue display** for a hall screen (/display/queue/:servicePointId, no sign-in chrome, polite live region);
 * - rosters: shifts, rotations, periods and a **roster grid** (person × day) with generate / submit / publish, swaps.
 */
import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { http } from '../api/client';
import { announce, useAction, useAsync, useInterval } from '../lib/hooks';
import { fmtDate, locale } from '../i18n';
import { Badge, Empty, Loading, Notice, ProblemAlert, Section, TextField } from '../components/ui';
import { ActionDialog, ModulePage, c, f, type Act, type Row, type ScreenDef } from './engine';

// ------------------------------------------------------------------ resources and bookings

/**
 * HoldRequest's boolean bump and waitlistIfFull (BookingModel.java:59-62) are primitives: a missing one is refused (Jackson 3,
 * 400 VALIDATION_FAILED). The booking screens do not offer them, so they are sent as an explicit false; "More fields" may set them.
 */
export const HOLD_FLAGS = { bump: false, waitlistIfFull: false } as const;

const resTypes: ScreenDef = {
  id: 'types',
  title: 'mod.resources.types',
  list: '/resources/types',
  unpaged: true,
  columns: [c('typeId'), c('name'), c('bookable'), c('approvalRequired')],
  rowKey: ['typeId', 'id'],
  // M-051: Map body read by ResourceType.fromMap (ResourceType.java:112-139; bookingMode SLOT | ALLOTMENT | BOTH | NONE, :58-66).
  // Further keys (refundTiers, attributes, decisions, limits) go in "More fields".
  create: { id: 'define', label: 'act.defineType', path: '/resources/types', fields: [f.text('typeId', undefined, true), f.text('name', undefined, true), f.text('kind'), f.select('bookingMode', ['SLOT', 'ALLOTMENT', 'BOTH', 'NONE'], 'fld.bookingMode'), f.num('slotMinutes', 'fld.slotMinutes'), f.num('defaultCapacity', 'fld.defaultCapacity'), f.bool('requiresApproval', 'fld.requiresApproval'), f.text('approvalProcessKey', 'fld.approvalProcessKey'), f.text('currency')] },
  actions: [{ id: 'retire', label: 'act.retire', method: 'DELETE', path: '/resources/types/{typeId}', reason: 'query', danger: true }],
};

const resItems: ScreenDef = {
  id: 'items',
  title: 'mod.resources.items',
  description: 'mod.resources.itemsDesc',
  list: '/resources/items',
  filters: [f.text('typeId'), f.text('orgUnitId'), f.text('locationId'), f.num('minCapacity'), f.text('status'), f.text('text', 'fld.search')],
  columns: [c('name'), c('typeId'), c('capacity'), c('status'), c('locationId'), c('orgUnitId')],
  rowKey: ['resourceId', 'id'],
  detail: '/resources/items/{resourceId}',
  sections: [{ id: 'children', label: 'mod.registry.children', path: '/resources/items/{resourceId}/children' }],
  create: { id: 'create', label: 'act.create', path: '/resources/items', fields: [f.text('typeId', undefined, true), f.text('name', undefined, true), f.num('capacity'), f.text('parentId'), f.text('orgUnitId'), f.text('locationId'), f.text('calendarId'), f.json('attributes')] },
  toolbar: [
    // M-051: {name, zone, businessCalendarId, closedOnHolidays, hours: {MONDAY: ["09:00-17:00"]}} (AvailabilityCalendar.java:58-79)
    { id: 'calendar', label: 'act.saveCalendar', method: 'PUT', path: '/resources/calendars/{calendarId}', fields: [f.path(f.text('calendarId', undefined, true)), f.text('name'), f.text('zone', 'fld.zone'), f.json('hours', 'fld.openingHours', true), f.text('businessCalendarId', 'fld.businessCalendarId'), f.bool('closedOnHolidays', 'fld.closedOnHolidays')], hint: 'mod.resources.calendarHint' },
    { id: 'blackout', label: 'act.addBlackout', path: '/resources/blackouts', fields: [f.text('resourceId', undefined, true), f.dt('from', undefined, true), f.dt('to', undefined, true)], reason: 'body' },
    // M-051: SlotTemplate(templateId, resourceId, weekdays, from, to, slotMinutes, unitsPerSlot, validFrom, validTo, attributes) (CalendarService.java:51-54)
    { id: 'slots', label: 'act.saveSlotTemplate', method: 'PUT', path: '/resources/slot-templates', fields: [f.text('templateId', 'fld.templateId'), f.text('resourceId', undefined, true), f.json('weekdays', 'fld.weekdays', true), f.text('from', 'fld.fromTime', true), f.text('to', 'fld.toTime', true), f.num('slotMinutes', 'fld.slotMinutes', true), f.num('unitsPerSlot', 'fld.unitsPerSlot', true), f.date('validFrom'), f.date('validTo')], hint: 'mod.resources.slotHint' },
    // M-051: AvailableSearchRequest(filter, from, to, quantity) (ResourcesController.java:66); filter is Resource.Query (Resource.java:40)
    { id: 'search', label: 'act.searchAvailable', path: '/resources/search-available', fields: [f.text('filter.typeId', 'fld.typeId'), f.num('filter.minCapacity', 'fld.minCapacity'), f.dt('from', undefined, true), f.dt('to', undefined, true), f.num('quantity', 'fld.quantity')], showResult: true },
  ],
  actions: [
    { id: 'status', label: 'act.setStatus', path: '/resources/items/{resourceId}/status', fields: [f.select('status', ['ACTIVE', 'MAINTENANCE', 'INACTIVE'], undefined, true)], reason: 'body' },
    // M-051: MoveRequest(newParentId) (ResourcesController.java:63, :162)
    { id: 'move', label: 'act.move', path: '/resources/items/{resourceId}/move', fields: [f.text('newParentId', 'fld.newParentId', true)], reason: 'body' },
  ],
};

const bookings: ScreenDef = {
  id: 'bookings',
  title: 'mod.resources.bookings',
  list: '/resources/bookings',
  filters: [f.text('resourceId'), f.text('requesterId'), f.text('state'), f.date('from'), f.date('to'), f.bool('mine')],
  columns: [c('bookingRef'), c('resourceId'), c('from'), c('to'), c('state'), c('requesterId')],
  rowKey: ['bookingId', 'id', 'bookingRef'],
  detail: '/resources/bookings/{bookingId}',
  sections: [{ id: 'history', label: 'mod.common.history', path: '/resources/history/booking/{bookingId}' }],
  toolbar: [
    // M-051: HoldRequest(resourceId, startsAt, endsAt, quantity, requesterId, beneficiaryId, partyRef, purpose, ..., boolean bump, ...,
    // boolean waitlistIfFull, ...) (BookingModel.java:60-63); bump and waitlistIfFull are primitives the screen does not offer, sent false
    // unless "More fields" sets them (HOLD_FLAGS)
    { id: 'quote', label: 'act.quote', path: '/resources/bookings/quote', fields: [f.text('resourceId', undefined, true), f.dt('startsAt', 'fld.startsAt', true), f.dt('endsAt', undefined, true), f.num('quantity', 'fld.quantity')], bodyDefaults: HOLD_FLAGS, showResult: true },
    { id: 'hold', label: 'act.hold', path: '/resources/bookings/hold', fields: [f.text('resourceId', undefined, true), f.dt('startsAt', 'fld.startsAt', true), f.dt('endsAt', undefined, true), f.num('quantity', 'fld.quantity'), f.text('purpose'), f.text('requesterId'), f.text('beneficiaryId', 'fld.beneficiaryId')], bodyDefaults: HOLD_FLAGS, showResult: true },
    // M-051: SeriesRequest(first: HoldRequest, recurrence: Recurrence(frequency DAILY|WEEKLY|MONTHLY, int interval, byDays, count, until,
    // boolean allOrNothing)) (ResourcesController.java:69; BookingModel.java:74-77); the first hold carries the same flags
    { id: 'series', label: 'act.holdSeries', path: '/resources/bookings/hold-series', bodyDefaults: { 'first.bump': false, 'first.waitlistIfFull': false }, fields: [f.text('first.resourceId', 'fld.resourceId', true), f.dt('first.startsAt', 'fld.startsAt', true), f.dt('first.endsAt', 'fld.endsAt', true), f.text('first.purpose', 'fld.purpose'), f.select('recurrence.frequency', ['DAILY', 'WEEKLY', 'MONTHLY'], 'fld.frequency', true), f.num('recurrence.interval', 'fld.interval', true), f.json('recurrence.byDays', 'fld.byDays'), f.num('recurrence.count', 'fld.count'), f.date('recurrence.until', 'fld.until'), f.bool('recurrence.allOrNothing', 'fld.allOrNothing')], showResult: true },
  ],
  actions: [
    { id: 'confirm', label: 'act.confirm', path: '/resources/bookings/{bookingId}/confirm' },
    // M-051: ApprovalRequest(approved, reason) (ResourcesController.java:72, :286)
    { id: 'approval', label: 'act.decideApproval', path: '/resources/bookings/{bookingId}/approval', fields: [f.bool('approved', 'fld.approved')], reason: 'body' },
    { id: 'checkIn', label: 'act.checkIn', path: '/resources/bookings/{bookingId}/check-in' },
    { id: 'checkOut', label: 'act.checkOut', path: '/resources/bookings/{bookingId}/check-out' },
    { id: 'bump', label: 'act.bump', path: '/resources/bookings/{bookingId}/bump', reason: 'body', danger: true },
    { id: 'cancel', label: 'act.cancel', path: '/resources/bookings/{bookingId}/cancel', reason: 'body', danger: true },
  ],
};

const waitlist: ScreenDef = {
  id: 'waitlist',
  title: 'mod.resources.waitlist',
  list: '/resources/waitlist',
  filters: [f.text('scopeKey'), f.text('state')],
  columns: [c('entryId'), c('scopeKey'), c('applicantId'), c('position'), c('state')],
  rowKey: ['entryId', 'id'],
  // M-051: WaitlistRequest(typeId, resourceId, requesterId, beneficiaryId, category, priorityLevel, seniorityAt, wantedFrom, wantedTo, quantity,
  // attributes) (BookingModel.java:112-115; ResourcesController.java:328)
  create: { id: 'enqueue', label: 'act.enqueue', path: '/resources/waitlist', fields: [f.text('typeId'), f.text('resourceId'), f.text('requesterId'), f.text('beneficiaryId', 'fld.beneficiaryId'), f.text('category'), f.num('priorityLevel', 'fld.priorityLevel'), f.dt('seniorityAt', 'fld.seniorityAt'), f.dt('wantedFrom', 'fld.wantedFrom'), f.dt('wantedTo', 'fld.wantedTo'), f.num('quantity', 'fld.quantity')] },
  actions: [
    { id: 'accept', label: 'act.acceptOffer', path: '/resources/waitlist/{entryId}/accept-offer' },
    { id: 'withdraw', label: 'act.withdraw', path: '/resources/waitlist/{entryId}/withdraw', reason: 'body' },
  ],
};

const allotments: ScreenDef = {
  id: 'allotments',
  title: 'mod.resources.allotments',
  description: 'mod.resources.allotmentsDesc',
  list: '/resources/allotments',
  filters: [f.text('resourceId'), f.text('holderId'), f.text('state')],
  columns: [c('allotmentId'), c('resourceId'), c('holderId'), c('from'), c('to'), c('state')],
  rowKey: ['allotmentId', 'id'],
  detail: '/resources/allotments/{allotmentId}',
  // M-051: AllotRequest(resourceId, unitNo, holderId, beneficiaryId, validFrom, validTo, orderRef, waitlistId, entitlementClass, attributes)
  // (BookingModel.java:133-136; instants)
  create: { id: 'allot', label: 'act.allot', path: '/resources/allotments', fields: [f.text('resourceId', undefined, true), f.num('unitNo', 'fld.unitNo'), f.text('holderId', undefined, true), f.text('beneficiaryId', 'fld.beneficiaryId'), f.dt('validFrom', 'fld.validFrom', true), f.dt('validTo', 'fld.validTo'), f.text('orderRef'), f.text('waitlistId', 'fld.waitlistId')] },
  toolbar: [
    { id: 'next', label: 'act.allotNext', path: '/resources/allotments/next', fields: [f.text('scopeKey', undefined, true), f.text('resourceId', undefined, true)], showResult: true },
    // M-051: DrawRequest(scopeKey, seed) and DrawVerifyRequest(entryIds, seed) (ResourcesController.java:84, :87)
    { id: 'draw', label: 'act.draw', path: '/resources/draws', fields: [f.text('scopeKey', undefined, true), f.text('seed', undefined, true)], showResult: true, hint: 'mod.resources.drawHint' },
    { id: 'verifyDraw', label: 'act.verifyDraw', path: '/resources/draws/verify', fields: [f.json('entryIds', 'fld.entryIds', true), f.text('seed', undefined, true)], showResult: true },
    { id: 'overdue', label: 'act.overdue', method: 'GET', path: '/resources/allotments/overdue', showResult: true, noJson: true },
  ],
  actions: [
    // M-051: NoticeRequest(dueAt, reason, authorityRef) (ResourcesController.java:93)
    { id: 'notice', label: 'act.noticeToVacate', path: '/resources/allotments/{allotmentId}/notice-to-vacate', fields: [f.dt('dueAt', undefined, true), f.text('authorityRef', 'fld.authorityRef')], reason: 'body' },
    { id: 'vacate', label: 'act.vacate', path: '/resources/allotments/{allotmentId}/vacate' },
    // M-051: ExtendRequest(newValidTo, long version, reason) (ResourcesController.java:99, :426-431)
    { id: 'extend', label: 'act.extend', path: '/resources/allotments/{allotmentId}/extend', fields: [f.dt('newValidTo', 'fld.newValidTo', true), f.num('version', undefined, true)], defaults: (r) => ({ version: r?.version as number }), reason: 'body' },
    { id: 'cancel', label: 'act.cancel', path: '/resources/allotments/{allotmentId}/cancel', reason: 'body', danger: true },
  ],
};

interface Slot {
  from: string;
  to: string;
  free?: boolean;
  available?: boolean;
  state?: string;
}

/** Week view: one column per day, one row per slot start; free slots can be held directly. */
export function BookingCalendar() {
  const { t } = useTranslation(); // re-renders on a language change, so locale() below is current
  const [resourceId, setResourceId] = useState('');
  const [active, setActive] = useState('');
  const [weekStart, setWeekStart] = useState(() => {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
    return d;
  });
  const from = weekStart.toISOString();
  const to = new Date(weekStart.getTime() + 7 * 86400000).toISOString();
  const slots = useAsync(() => http.get<Slot[] | { items: Slot[] }>(`/resources/items/${encodeURIComponent(active)}/slots`, { from, to }), [active, from], !!active);
  const books = useAsync(() => http.page<Row>('/resources/bookings', { resourceId: active, from, to, size: 200 }), [active, from], !!active);
  const [hold, setHold] = useState<Act | null>(null);
  const list: Slot[] = Array.isArray(slots.data) ? slots.data : (slots.data as { items?: Slot[] } | undefined)?.items ?? [];
  const days = [...Array(7)].map((_, i) => new Date(weekStart.getTime() + i * 86400000));
  const times = [...new Set(list.map((s) => new Date(s.from).toTimeString().slice(0, 5)))].sort();
  const dayKey = (d: Date) => d.toISOString().slice(0, 10);
  const byCell = new Map<string, Slot>();
  for (const s of list) byCell.set(`${s.from.slice(0, 10)} ${new Date(s.from).toTimeString().slice(0, 5)}`, s);
  const bookedAt = (s: Slot) => (books.data?.items ?? []).find((b) => String(b.from) <= s.from && String(b.to) >= s.to && b.state !== 'CANCELLED');
  const fmtDay = new Intl.DateTimeFormat(locale(), { weekday: 'short', day: 'numeric', month: 'short' });
  return (
    <Section title={t('mod.resources.calendar')}>
      <form
        className="filters"
        onSubmit={(e) => {
          e.preventDefault();
          setActive(resourceId.trim());
        }}
      >
        <TextField label={t('fld.resourceId')} value={resourceId} onChange={setResourceId} required />
        <button type="submit">{t('common.open')}</button>
        <button type="button" onClick={() => setWeekStart(new Date(weekStart.getTime() - 7 * 86400000))}>
          {t('mod.resources.prevWeek')}
        </button>
        <button type="button" onClick={() => setWeekStart(new Date(weekStart.getTime() + 7 * 86400000))}>
          {t('mod.resources.nextWeek')}
        </button>
      </form>
      <ProblemAlert error={slots.error ?? books.error} />
      {active && slots.loading && <Loading />}
      {active && !slots.loading && times.length === 0 && <Empty message={t('mod.resources.noSlots')} />}
      {times.length > 0 && (
        <div className="table-wrap" role="region" aria-label={t('mod.resources.calendar')} tabIndex={0}>
          <table className="calendar">
            <caption className="sr-only">{t('mod.resources.calendarCaption', { id: active, from: fmtDate(from, false) })}</caption>
            <thead>
              <tr>
                <th scope="col">{t('mod.resources.time')}</th>
                {days.map((d) => (
                  <th key={dayKey(d)} scope="col">
                    {fmtDay.format(d)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {times.map((tm) => (
                <tr key={tm}>
                  <th scope="row">{tm}</th>
                  {days.map((d) => {
                    const s = byCell.get(`${dayKey(d)} ${tm}`);
                    if (!s) return <td key={dayKey(d)} className="cal-none" aria-label={t('mod.resources.noSlot')} />;
                    const b = bookedAt(s);
                    const free = !b && (s.free ?? s.available ?? s.state === 'FREE');
                    return (
                      <td key={dayKey(d)} className={free ? 'cal-free' : 'cal-busy'}>
                        {free ? (
                          <button
                            type="button"
                            className="link"
                            onClick={() =>
                              setHold({
                                id: 'hold',
                                label: 'act.hold',
                                path: '/resources/bookings/hold',
                                // HoldRequest(resourceId, startsAt, endsAt, Integer quantity, ..., purpose, ...) (BookingModel.java:59-62): the
                                // picked slot is the body; values without a field are not sent, so it goes in bodyExtra
                                fields: [f.text('purpose'), f.num('quantity', 'fld.quantity')],
                                bodyExtra: { resourceId: active, startsAt: s.from, endsAt: s.to },
                                bodyDefaults: HOLD_FLAGS,
                                showResult: true,
                              })
                            }
                          >
                            {t('mod.resources.free')}
                          </button>
                        ) : (
                          <Badge value={String(b?.state ?? s.state ?? 'BOOKED')} tone="warn" />
                        )}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {hold && (
        <ActionDialog
          act={{ ...hold, fields: [f.text('resourceId', undefined, true), f.text('from', undefined, true), f.text('to', undefined, true), ...(hold.fields ?? [])] }}
          onClose={() => setHold(null)}
          onDone={() => {
            setHold(null);
            books.reload();
            slots.reload();
          }}
        />
      )}
    </Section>
  );
}

export function ResourcesPage() {
  return (
    <ModulePage
      title="mod.resources.title"
      description="mod.resources.desc"
      screens={[resItems, resTypes, bookings, waitlist, allotments]}
      extra={[{ id: 'calendar', label: 'mod.resources.calendar', node: <BookingCalendar /> }]}
    />
  );
}

// ------------------------------------------------------------------ queues

const servicePoints: ScreenDef = {
  id: 'points',
  title: 'mod.queues.servicePoints',
  list: '/queues/service-points',
  columns: [c('servicePointId'), c('name'), c('status'), c('waiting'), c('slaMinutes')],
  rowKey: ['servicePointId', 'id'],
  detail: '/queues/service-points/{servicePointId}',
  sections: [
    { id: 'counters', label: 'mod.queues.counters', path: '/queues/service-points/{servicePointId}/counters' },
    { id: 'tokens', label: 'mod.queues.tokens', path: '/queues/service-points/{servicePointId}/tokens' },
    { id: 'stats', label: 'mod.queues.stats', path: '/queues/service-points/{servicePointId}/stats' },
    { id: 'sla', label: 'mod.queues.slaBreaches', path: '/queues/service-points/{servicePointId}/sla-breaches' },
    // M-039: the call log (GET .../call-events, resources.read, QueueController.java:215-220; after / limit default on the server)
    { id: 'calls', label: 'mod.queues.callEvents', path: '/queues/service-points/{servicePointId}/call-events', columns: [c('eventId'), c('kind'), c('tokenId'), c('counterId'), c('actorId'), c('at')] },
  ],
  // M-051: ServicePointRequest(name, resourceId, calendarId, tokenPrefix, slaMinutes, priorityDecision, routingDecision, config) (QueueRestPort.java:52-54)
  toolbar: [{ id: 'define', label: 'act.defineServicePoint', method: 'PUT', path: '/queues/service-points/{servicePointId}', fields: [f.path(f.text('servicePointId', undefined, true)), f.text('name', undefined, true), f.text('resourceId'), f.text('calendarId'), f.text('tokenPrefix', 'fld.tokenPrefix'), f.num('slaMinutes', undefined, true), f.text('priorityDecision', 'fld.priorityDecision'), f.text('routingDecision', 'fld.routingDecision'), f.json('config', 'fld.queueConfig')] }],
  actions: [
    // M-051: CounterRequest(name, skills) (QueueRestPort.java:56)
    { id: 'counter', label: 'act.defineCounter', method: 'PUT', path: '/queues/service-points/{servicePointId}/counters/{counterId}', fields: [f.path(f.text('counterId', undefined, true)), f.text('name', undefined, true), f.json('skills')] },
    // M-051: IssueTokenRequest(channel, bookingId, partyId, priorityClass, skill, routingKey, attributes) (QueueRestPort.java:59-60); channel is
    // QueueModel.Channel (QueueModel.java:28); priority classes are the service point's configuration (QueueService.java:352-361)
    { id: 'token', label: 'act.issueToken', path: '/queues/service-points/{servicePointId}/tokens', fields: [f.select('channel', ['WALK_IN', 'KIOSK', 'SMS', 'QR', 'APPOINTMENT', 'TRANSFER', 'COUNTER']), f.text('priorityClass', 'fld.priorityClass'), f.text('skill', 'fld.skill'), f.text('routingKey', 'fld.routingKey'), f.text('partyId'), f.text('bookingId', 'fld.bookingId')], showResult: true },
    { id: 'status', label: 'act.setStatus', path: '/queues/service-points/{servicePointId}/status', fields: [f.select('status', ['OPEN', 'PAUSED', 'CLOSED'], undefined, true)], reason: 'body' },
    { id: 'sweep', label: 'act.noShowSweep', path: '/queues/service-points/{servicePointId}/no-show-sweep', showResult: true },
  ],
};

/** Counter console: the officer's call / serve loop for one counter. */
export function CounterConsole() {
  const { t } = useTranslation();
  const [counterId, setCounterId] = useState('');
  const [active, setActive] = useState('');
  const [current, setCurrent] = useState<Row>();
  const act = useAction();
  const call = (verb: string, body: Row = {}) =>
    void act.run((k) => http.post<Row>(`/queues/counters/${encodeURIComponent(active)}/${verb}`, body, k)).then((r) => {
      if (r) {
        setCurrent(r);
        announce(t('mod.queues.announce', { token: String(r.tokenNo ?? r.number ?? r.tokenId ?? '') }));
      }
    });
  return (
    <Section title={t('mod.queues.console')}>
      <form
        className="filters"
        onSubmit={(e) => {
          e.preventDefault();
          setActive(counterId.trim());
        }}
      >
        <TextField label={t('fld.counterId')} value={counterId} onChange={setCounterId} required />
        <button type="submit">{t('common.open')}</button>
      </form>
      {active && (
        <>
          <div className="toolbar">
            <button type="button" onClick={() => call('open')}>
              {t('mod.queues.openCounter')}
            </button>
            <button type="button" onClick={() => call('pause')}>
              {t('mod.queues.pauseCounter')}
            </button>
            <button type="button" onClick={() => call('close')}>
              {t('mod.queues.closeCounter')}
            </button>
          </div>
          <div className="decision-bar" role="group" aria-label={t('mod.queues.console')}>
            <button type="button" className="primary" disabled={act.busy} onClick={() => call('call-next')}>
              {t('mod.queues.callNext')}
            </button>
            <button type="button" disabled={act.busy || !current} onClick={() => call('recall', { tokenId: current?.tokenId })}>
              {t('mod.queues.recall')}
            </button>
            <button type="button" disabled={act.busy || !current} onClick={() => call('start-serving', { tokenId: current?.tokenId })}>
              {t('mod.queues.startServing')}
            </button>
            <button type="button" disabled={act.busy || !current} onClick={() => call('complete', { tokenId: current?.tokenId })}>
              {t('mod.queues.complete')}
            </button>
            <button type="button" disabled={act.busy || !current} onClick={() => call('skip', { tokenId: current?.tokenId, reason: 'no-show at counter' })}>
              {t('mod.queues.skip')}
            </button>
          </div>
          <ProblemAlert error={act.error} />
          {current && (
            <p className="lead" aria-live="polite">
              {t('mod.queues.nowServing', { token: String(current.tokenNo ?? current.number ?? current.tokenId ?? '') })} <Badge value={String(current.state ?? '')} />
            </p>
          )}
        </>
      )}
    </Section>
  );
}

/** Hall display for a service point: now calling / next tokens, polled every 5 s; no navigation chrome. */
export function QueueDisplay() {
  const { servicePointId = '' } = useParams();
  const { t } = useTranslation();
  const d = useAsync(() => http.get<Row>(`/queues/service-points/${encodeURIComponent(servicePointId)}/display`, { next: 8 }), [servicePointId]);
  useInterval(() => d.reload(), 5000);
  const calling = (d.data?.calling ?? d.data?.nowServing ?? []) as Row[];
  const next = (d.data?.next ?? d.data?.waiting ?? []) as Row[];
  return (
    <main id="main" className="queue-display" tabIndex={-1}>
      <h1 id="page-title">{String(d.data?.name ?? servicePointId)}</h1>
      <ProblemAlert error={d.error} />
      <section aria-labelledby="qd-now">
        <h2 id="qd-now">{t('mod.queues.nowCalling')}</h2>
        <ul className="qd-calling" aria-live="polite">
          {calling.map((x, i) => (
            <li key={i}>
              <span className="qd-token">{String(x.tokenNo ?? x.number ?? x.tokenId)}</span>
              <span className="qd-arrow" aria-hidden="true">
                →
              </span>
              <span className="qd-counter">
                {t('mod.queues.counter')} {String(x.counterName ?? x.counterId ?? '')}
              </span>
            </li>
          ))}
        </ul>
      </section>
      <section aria-labelledby="qd-next">
        <h2 id="qd-next">{t('mod.queues.next')}</h2>
        <ol className="qd-next">
          {next.map((x, i) => (
            <li key={i}>{String(x.tokenNo ?? x.number ?? x.tokenId)}</li>
          ))}
        </ol>
      </section>
    </main>
  );
}

export function QueuesPage() {
  return <ModulePage title="mod.queues.title" description="mod.queues.desc" screens={[servicePoints]} extra={[{ id: 'console', label: 'mod.queues.console', node: <CounterConsole /> }]} />;
}

// ------------------------------------------------------------------ rosters

const shifts: ScreenDef = {
  id: 'shifts',
  title: 'mod.rosters.shifts',
  list: '/rosters/shifts',
  columns: [c('shiftId'), c('name'), c('start'), c('end'), c('minStaff')],
  rowKey: ['shiftId', 'id'],
  // M-051: ShiftRequest(shiftId, code, name, fromTime, durationMinutes, night, skills, zoneId, attributes) (RosterRestPort.java:72-73)
  create: { id: 'save', label: 'act.saveShift', method: 'PUT', path: '/rosters/shifts', fields: [f.text('shiftId', undefined, true), f.text('code'), f.text('name', undefined, true), f.text('fromTime', 'fld.startTime', true), f.num('durationMinutes', 'fld.durationMinutes', true), f.bool('night', 'fld.nightShift'), f.json('skills'), f.text('zoneId', 'fld.zone')] },
  actions: [{ id: 'delete', label: 'common.delete', method: 'DELETE', path: '/rosters/shifts/{shiftId}', danger: true }],
};

const rotations: ScreenDef = {
  id: 'rotations',
  title: 'mod.rosters.rotations',
  list: '/rosters/rotations',
  columns: [c('rotationId'), c('name'), c('pattern')],
  rowKey: ['rotationId', 'id'],
  // M-051: RotationRequest(rotationId, name, cycle) (RosterRestPort.java:76)
  create: { id: 'save', label: 'act.saveRotation', method: 'PUT', path: '/rosters/rotations', fields: [f.text('rotationId', undefined, true), f.text('name', undefined, true), f.json('cycle', undefined, true)] },
  actions: [{ id: 'delete', label: 'common.delete', method: 'DELETE', path: '/rosters/rotations/{rotationId}', danger: true }],
};

const periods: ScreenDef = {
  id: 'periods',
  title: 'mod.rosters.periods',
  list: '/rosters/periods',
  filters: [f.text('unitId')],
  columns: [c('periodId'), c('unitId'), c('from'), c('to'), c('state'), c('version')],
  rowKey: ['periodId', 'id'],
  detail: '/rosters/periods/{periodId}',
  sections: [
    { id: 'members', label: 'mod.rosters.members', path: '/rosters/periods/{periodId}/members' },
    { id: 'demand', label: 'mod.rosters.demand', path: '/rosters/periods/{periodId}/demand' },
    { id: 'onDuty', label: 'mod.rosters.onDuty', path: '/rosters/periods/{periodId}/on-duty' },
  ],
  // M-051: PeriodRequest(unitId, name, firstDay, lastDay, constraints) (RosterRestPort.java:79-80)
  create: { id: 'create', label: 'act.create', path: '/rosters/periods', fields: [f.text('unitId', undefined, true), f.text('name'), f.date('firstDay', 'fld.firstDay', true), f.date('lastDay', 'fld.lastDay', true), f.json('constraints')] },
  actions: [
    // M-051: MemberRequest(seatId, skills, rotationId, rotationOffset, attributes) (RosterRestPort.java:83-84); personId is the path
    { id: 'member', label: 'act.setMember', method: 'PUT', path: '/rosters/periods/{periodId}/members/{personId}', fields: [f.path(f.text('personId', undefined, true)), f.text('seatId'), f.json('skills'), f.text('rotationId'), f.num('rotationOffset', 'fld.rotationOffset', true, { defaultValue: 0 })] },
    // M-051: DemandRequest(shiftId, skill, weekdays, dutyDay, requiredCount, dutySeatId, locationId) (RosterRestPort.java:87-88)
    { id: 'demand', label: 'act.addDemand', path: '/rosters/periods/{periodId}/demand', fields: [f.text('shiftId', undefined, true), f.date('dutyDay', 'fld.dutyDay'), f.json('weekdays', 'fld.weekdays'), f.num('requiredCount', 'fld.requiredCount', true), f.text('skill', 'fld.skill'), f.text('dutySeatId', 'fld.dutySeatId'), f.text('locationId')] },
    // M-051: ?version plus the constraints map itself (RosterController.java:116-118), keys read by RosterConstraints.from (RosterModel.java:140-155);
    // a missing requireSkill reads as true (RosterModel.java:151), so the box opens checked and is always sent
    { id: 'constraints', label: 'act.constraints', method: 'PUT', path: '/rosters/periods/{periodId}/constraints', fields: [f.q(f.num('version', undefined, true)), f.num('minRestMinutes', 'fld.minRestMinutes'), f.num('maxMinutesPerDay', 'fld.maxMinutesPerDay'), f.num('maxMinutesPerWeek', 'fld.maxMinutesPerWeek'), f.num('weeklyOffDays', 'fld.weeklyOffDays'), f.num('maxConsecutiveDays', 'fld.maxConsecutiveDays'), f.num('maxConsecutiveNights', 'fld.maxConsecutiveNights'), f.bool('requireSkill', 'fld.requireSkill', { defaultValue: true }), f.text('constraintsDecision', 'fld.constraintsDecision'), f.text('approvalProcessKey', 'fld.approvalProcessKey'), f.text('swapApprovalProcessKey', 'fld.swapApprovalProcessKey')] },
    { id: 'leave', label: 'act.applyLeave', path: '/rosters/periods/{periodId}/apply-leave', showResult: true },
    { id: 'attendance', label: 'act.reconcileAttendance', path: '/rosters/periods/{periodId}/reconcile-attendance', showResult: true },
    { id: 'close', label: 'act.closePeriod', path: '/rosters/periods/{periodId}/close', fields: [f.q(f.num('version', undefined, true))], reason: 'body' },
    // M-039: mark on call {personId, shiftId, day} (OnCallRequest, RosterController.java:50, :238-243; resources.admin)
    { id: 'onCall', label: 'act.markOnCall', path: '/rosters/periods/{periodId}/on-call', fields: [f.text('personId', undefined, true), f.text('shiftId', undefined, true), f.date('day', undefined, true)], noJson: true },
  ],
  toolbar: [
    // M-039: one person's duties in a window (GET ?from &to, ISO instants, RosterController.java:257-265)
    { id: 'duties', label: 'act.personDuties', method: 'GET', path: '/rosters/people/{personId}/duties', fields: [f.path(f.text('personId', undefined, true)), f.dt('from', undefined, true), f.dt('to', undefined, true)], noJson: true, showResult: true },
  ],
};

const swaps: ScreenDef = {
  id: 'swaps',
  title: 'mod.rosters.swaps',
  list: '/rosters/swaps',
  filters: [f.text('periodId')],
  columns: [c('swapId'), c('fromPersonId'), c('toPersonId'), c('state'), c('version')],
  rowKey: ['swapId', 'id'],
  // M-051: SwapCreateRequest(assignmentId, counterpartId, counterpartAssignmentId, reason) (RosterRestPort.java:101)
  create: { id: 'request', label: 'act.requestSwap', path: '/rosters/swaps', fields: [f.text('assignmentId', undefined, true), f.text('counterpartId', 'fld.counterpartId', true), f.text('counterpartAssignmentId', 'fld.counterpartAssignmentId')], reason: 'body' },
  actions: [
    { id: 'accept', label: 'act.accept', path: '/rosters/swaps/{swapId}/accept', fields: [f.q(f.num('version', undefined, true))] },
    // M-051: DecisionRequest(version, approved, reason) (RosterRestPort.java:98)
    { id: 'decide', label: 'act.decide', path: '/rosters/swaps/{swapId}/decision', fields: [f.bool('approved', 'fld.approved'), f.num('version')], defaults: (r) => ({ version: r?.version as number }), reason: 'body' },
    { id: 'cancel', label: 'act.cancel', path: '/rosters/swaps/{swapId}/cancel', fields: [f.q(f.num('version', undefined, true))] },
  ],
};

/** Person × day roster grid for one period, with the publication workflow. */
export function RosterGrid() {
  const { t } = useTranslation();
  const [periodId, setPeriodId] = useState('');
  const [active, setActive] = useState('');
  const period = useAsync(() => http.get<Row>(`/rosters/periods/${encodeURIComponent(active)}`), [active], !!active);
  const asg = useAsync(() => http.page<Row>(`/rosters/periods/${encodeURIComponent(active)}/assignments`, { size: 500 }), [active], !!active);
  const [act, setAct] = useState<Act | null>(null);
  const version = Number(period.data?.version ?? 0);
  const items = asg.data?.items ?? [];
  const people = useMemo(() => [...new Set(items.map((a) => String(a.personName ?? a.personId)))].sort(), [items]);
  const dates = useMemo(() => [...new Set(items.map((a) => String(a.date ?? a.from ?? '').slice(0, 10)))].filter(Boolean).sort(), [items]);
  const cellOf = (p: string, d: string) => items.filter((a) => String(a.personName ?? a.personId) === p && String(a.date ?? a.from ?? '').slice(0, 10) === d);
  const flow: Act[] = [
    { id: 'generate', label: 'act.generate', path: `/rosters/periods/{periodId}/generate`, fields: [f.q(f.num('seed'))], defaults: () => ({ periodId: active }), showResult: true, noJson: true },
    { id: 'randomise', label: 'act.randomise', path: `/rosters/periods/{periodId}/randomise`, showResult: true, noJson: true },
    { id: 'submit', label: 'act.submit', path: `/rosters/periods/{periodId}/submit`, noJson: true },
    // DecisionRequest(Long version, boolean approved, String reason) (RosterRestPort.java:98; RosterController.java:189-192): approve when
    // checked, reject when not; the period version the grid read is sent (RosterService.java:505)
    { id: 'decide', label: 'act.decidePublication', path: `/rosters/periods/{periodId}/publication-decision`, fields: [f.bool('approved', 'fld.approved'), f.num('version', 'fld.version', true)], reason: 'body' },
    { id: 'publish', label: 'act.publish', path: `/rosters/periods/{periodId}/publish`, noJson: true },
  ];
  return (
    <Section title={t('mod.rosters.grid')}>
      <form
        className="filters"
        onSubmit={(e) => {
          e.preventDefault();
          setActive(periodId.trim());
        }}
      >
        <TextField label={t('fld.periodId')} value={periodId} onChange={setPeriodId} required />
        <button type="submit">{t('common.open')}</button>
      </form>
      <ProblemAlert error={period.error ?? asg.error} />
      {period.data && (
        <>
          <p>
            <Badge value={String(period.data.state ?? '')} /> {t('fld.version')} {version}
          </p>
          <div className="toolbar">
            {flow.map((a) => (
              <button key={a.id} type="button" onClick={() => setAct(a)}>
                {t(a.label)}
              </button>
            ))}
          </div>
        </>
      )}
      {active && asg.data && items.length === 0 && <Empty message={t('mod.rosters.noAssignments')} />}
      {items.length > 0 && (
        <div className="table-wrap" role="region" aria-label={t('mod.rosters.grid')} tabIndex={0}>
          <table className="calendar">
            <caption className="sr-only">{t('mod.rosters.grid')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('fld.person')}</th>
                {dates.map((d) => (
                  <th key={d} scope="col">
                    {fmtDate(d, false)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {people.map((p) => (
                <tr key={p}>
                  <th scope="row">{p}</th>
                  {dates.map((d) => (
                    <td key={d}>
                      {cellOf(p, d).map((a, i) => (
                        <span key={i} className="badge badge-info">
                          {String(a.shiftName ?? a.shiftId ?? '')}
                          {a.onCall ? ` · ${t('mod.rosters.onCall')}` : ''}
                        </span>
                      ))}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {act && (
        <ActionDialog
          act={{ ...act, fields: [...(act.fields ?? []), ...(['submit', 'publish', 'generate'].includes(act.id) ? [f.q(f.num('version', undefined, true))] : [])], defaults: () => ({ version }) }}
          context={{ periodId: active }}
          onClose={() => setAct(null)}
          onDone={() => {
            setAct(null);
            period.reload();
            asg.reload();
          }}
        />
      )}
      <Notice>{t('mod.rosters.fairness')}</Notice>
    </Section>
  );
}

export function RostersPage() {
  return <ModulePage title="mod.rosters.title" description="mod.rosters.desc" screens={[periods, shifts, rotations, swaps]} extra={[{ id: 'grid', label: 'mod.rosters.grid', node: <RosterGrid /> }]} />;
}

/** The screen definitions, for the tests of the request bodies their actions send. */
export const resourceScreens = { resItems, bookings, allotments, servicePoints, shifts, periods, swaps };
