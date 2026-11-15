// SPDX-License-Identifier: Apache-2.0
/**
 * Owner decision 342: the compliance evidence report period. The officer picks calendar dates; the UI turns them into
 * instants in the tenant's time zone (`/me` locale.zone, tenantLocale()): `from` is the start of the first day and `to`
 * the start of the day after the last, so the half-open period [from, to) covers every instant of the chosen days in
 * that zone, whatever the reader's own zone. No zone, no report: the period is never guessed in another zone.
 */

const DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/** The wall-clock fields of an instant in an IANA zone. */
function wall(ms: number, zone: string): { y: number; m: number; d: number; h: number; min: number; s: number } {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: zone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(new Date(ms));
  const n = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  return { y: n('year'), m: n('month'), d: n('day'), h: n('hour'), min: n('minute'), s: n('second') };
}

/** The zone's offset from UTC at an instant, in milliseconds (local wall time minus UTC). */
function offset(ms: number, zone: string): number {
  const w = wall(ms, zone);
  return Date.UTC(w.y, w.m - 1, w.d, w.h, w.min, w.s) - Math.floor(ms / 1000) * 1000;
}

function dayKey(y: number, m: number, d: number): number {
  return y * 10000 + m * 100 + d;
}

/** Whether `zone` is an IANA zone this browser knows. */
export function isZone(zone: string | undefined): zone is string {
  if (!zone) return false;
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

/**
 * The first instant of the calendar day `date` (yyyy-MM-dd) in `zone`, as an ISO-8601 UTC instant. Where the day does
 * not start at 00:00 (a daylight-saving change at midnight), it is the first instant whose local date is that day.
 */
export function startOfDayInZone(date: string, zone: string): string {
  const m = DATE.exec(date);
  if (!m) throw new Error(`not a calendar date: ${date}`);
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const target = dayKey(y, mo, d);
  const localMidnight = Date.UTC(y, mo - 1, d);
  let t = localMidnight - offset(localMidnight, zone);
  t = localMidnight - offset(t, zone);
  const at = (ms: number) => {
    const w = wall(ms, zone);
    return dayKey(w.y, w.m, w.d);
  };
  // the local date only moves forward with time, so the start of the day is the first instant whose local date is
  // not before the day; t is within an hour or two of it (offset changes are at most a few hours)
  let lo = t - 6 * 3600_000;
  let hi = t + 6 * 3600_000;
  if (at(lo) >= target || at(hi) < target) throw new Error(`cannot find the start of ${date} in ${zone}`);
  while (hi - lo > 1000) {
    const mid = lo + Math.floor((hi - lo) / 2000) * 1000;
    if (at(mid) >= target) hi = mid;
    else lo = mid;
  }
  return new Date(hi).toISOString();
}

/** The calendar day after `date` (yyyy-MM-dd), in the proleptic Gregorian calendar. */
export function nextDay(date: string): string {
  const m = DATE.exec(date);
  if (!m) throw new Error(`not a calendar date: ${date}`);
  return new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]) + 1)).toISOString().slice(0, 10);
}

export type ReportPeriod = { from: string; to: string } | { error: 'zone' | 'dates' | 'order' };

/**
 * The report period [from, to) for the chosen first and last days in the tenant's zone (decision 342). Refused when
 * the zone is not known, a date is missing or malformed, or the last day is before the first.
 */
export function reportPeriod(firstDay: string, lastDay: string, zone: string | undefined): ReportPeriod {
  if (!isZone(zone)) return { error: 'zone' };
  if (!DATE.test(firstDay) || !DATE.test(lastDay)) return { error: 'dates' };
  if (lastDay < firstDay) return { error: 'order' };
  return { from: startOfDayInZone(firstDay, zone), to: startOfDayInZone(nextDay(lastDay), zone) };
}
