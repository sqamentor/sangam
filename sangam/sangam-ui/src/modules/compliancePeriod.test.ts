// SPDX-License-Identifier: Apache-2.0
/**
 * Owner decision 342 (CERT-In / DPDP matrix row of the evidence report): the report period is the chosen calendar days
 * in the tenant's time zone, sent as instants: `from` = the start of the first day, `to` = the start of the day after
 * the last day. The UI used to send the bare dates, which the server (ISO date-time parameters) refused, and a date read
 * in the reader's zone would shift the period by the zone difference.
 */
import { describe, expect, it } from 'vitest';
import { isZone, nextDay, reportPeriod, startOfDayInZone } from './compliancePeriod';

describe('evidence report period in the tenant zone (decision 342)', () => {
  it('India: a month is [start of the 1st, start of the day after the 30th) in IST', () => {
    expect(reportPeriod('2026-11-01', '2026-11-30', 'Asia/Kolkata')).toEqual({ from: '2026-10-31T18:30:00.000Z', to: '2026-11-30T18:30:00.000Z' });
  });

  it('one day is a whole day of the zone', () => {
    expect(reportPeriod('2026-11-15', '2026-11-15', 'Asia/Kolkata')).toEqual({ from: '2026-11-14T18:30:00.000Z', to: '2026-11-15T18:30:00.000Z' });
    expect(reportPeriod('2026-11-15', '2026-11-15', 'UTC')).toEqual({ from: '2026-11-15T00:00:00.000Z', to: '2026-11-16T00:00:00.000Z' });
  });

  it('a zone west of UTC and a daylight-saving change inside the period', () => {
    // London: 29 March 2026 the clocks go forward at 01:00 UTC, so that day has 23 hours
    expect(reportPeriod('2026-03-29', '2026-03-29', 'Europe/London')).toEqual({ from: '2026-03-29T00:00:00.000Z', to: '2026-03-29T23:00:00.000Z' });
    expect(reportPeriod('2026-07-01', '2026-07-01', 'America/New_York')).toEqual({ from: '2026-07-01T04:00:00.000Z', to: '2026-07-02T04:00:00.000Z' });
  });

  it('a day that does not start at 00:00 starts at its first instant', () => {
    // Cuba moves the clocks from 00:00 to 01:00 on the second Sunday of March (8 March 2026): the day starts at
    // 01:00 CDT (UTC-4), 05:00 UTC
    expect(startOfDayInZone('2026-03-08', 'America/Havana')).toBe('2026-03-08T05:00:00.000Z');
    expect(startOfDayInZone('2026-03-07', 'America/Havana')).toBe('2026-03-07T05:00:00.000Z');
  });

  it('the day after crosses months and years', () => {
    expect(nextDay('2026-12-31')).toBe('2027-01-01');
    expect(nextDay('2028-02-28')).toBe('2028-02-29');
    expect(reportPeriod('2026-12-01', '2026-12-31', 'Asia/Kolkata')).toEqual({ from: '2026-11-30T18:30:00.000Z', to: '2026-12-31T18:30:00.000Z' });
  });

  it('without the tenant zone, or with bad dates, nothing is sent', () => {
    expect(reportPeriod('2026-11-01', '2026-11-30', undefined)).toEqual({ error: 'zone' });
    expect(reportPeriod('2026-11-01', '2026-11-30', 'Not/AZone')).toEqual({ error: 'zone' });
    expect(reportPeriod('', '2026-11-30', 'Asia/Kolkata')).toEqual({ error: 'dates' });
    expect(reportPeriod('2026-11-30', '2026-11-01', 'Asia/Kolkata')).toEqual({ error: 'order' });
    expect(isZone('Asia/Kolkata')).toBe(true);
    expect(isZone('')).toBe(false);
  });
});
