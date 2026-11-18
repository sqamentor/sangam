// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Request members bound to a Java primitive (boolean / int / long) are refused when missing or null: the server binds JSON
 * with Jackson 3, 400 VALIDATION_FAILED "x is missing, null or not a valid boolean". Every UI caller sends each of them
 * explicitly, every time:
 * - the generic ActionDialog sends an untouched body checkbox as false (or its declared default), shows a declared default
 *   in its control, and leaves the box out only where the server documents a default for a missing value (omitUntouched),
 *   for a GET (query parameters) and for the unchanged Operaton REST API at /engine-rest;
 * - a required number left empty is refused in the dialog, never invented;
 * - a version is the one the screen read (the selected row, the open period), never a constant;
 * - an operator flag a screen does not offer goes as an explicit false (bodyDefaults), which "More fields" may still set;
 * - the roster publication decision sends DecisionRequest.approved (RosterRestPort.java:98), not a 'decision' string.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

const api = vi.hoisted(() => ({ get: vi.fn(), page: vi.fn(), post: vi.fn(), request: vi.fn() }));
vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: api.get, page: api.page, post: api.post }, request: api.request };
});

import { api as endpoints } from '../api/endpoints';
import { idpTimeoutProblem } from '../areas/admin/Admin';
import { initI18n, tr } from '../i18n';
import { validateForm, type FormValues } from '../components/FormRenderer';
import { ActionDialog, applyBodyDefaults, buildRequest, f, fieldDefaults, type Act, type Row, type ScreenDef } from './engine';
import { caseworkScreens as CW } from './casework';
import { financeScreens as FI } from './finance';
import { programmeScreens as PG } from './programme';
import { RosterGrid, resourceScreens as RS } from './resources';

const T = tr;

beforeAll(() => initI18n('en'));
beforeEach(() => {
  for (const fn of Object.values(api)) fn.mockReset();
  api.request.mockResolvedValue({});
});
afterEach(cleanup);

/** The request an action's dialog sends for these typed values (the dialog's own steps, without rendering it). */
function send(act: Act, typed: FormValues = {}, row?: Row, moreJson = ''): Row {
  const fields = act.fields ?? [];
  const values = { ...fieldDefaults(fields), ...(act.defaults?.(row) ?? {}), ...typed };
  const refused = Object.keys(validateForm({ components: fields }, values, T));
  if (refused.length) throw new Error(`refused: ${refused.join(', ')}`);
  const req = buildRequest(fields, values, moreJson);
  if (req.error) throw new Error(`invalid JSON: ${req.error}`);
  applyBodyDefaults(req.body, act.bodyDefaults);
  Object.assign(req.body, act.bodyExtra ?? {}, row && act.bodyFromRow ? act.bodyFromRow(row) : {});
  return req.body;
}

const action = (s: ScreenDef, where: 'actions' | 'toolbar' | 'create', id: string): Act => {
  const list = where === 'create' ? (s.create ? [s.create] : []) : (s[where] ?? []);
  const a = list.find((x) => x.id === id);
  if (!a) throw new Error(`no ${where} action ${id} on ${s.id}`);
  return a;
};

describe('ActionDialog: body checkboxes are always sent (generic root fix)', () => {
  const outcome: Act = { id: 'outcome', label: 'act.recordOutcome', path: '/hearings/{hearingId}/outcome', fields: [f.select('outcome', ['HEARD', 'ADJOURNED'], undefined, true), f.bool('adjourned', 'fld.adjourned')], noJson: true };
  const confirm = () => fireEvent.click(screen.getByRole('button', { name: T('common.confirm') }));
  const sentBody = async () => {
    await waitFor(() => expect(api.request).toHaveBeenCalled());
    return api.request.mock.calls[0][2].body as Row;
  };

  it('sends an untouched checkbox as false', async () => {
    render(<ActionDialog act={outcome} row={{ hearingId: 'h1' }} onClose={() => undefined} onDone={() => undefined} />);
    fireEvent.change(screen.getByLabelText(new RegExp(T('fld.outcome'))), { target: { value: 'HEARD' } });
    confirm();
    expect(await sentBody()).toEqual({ outcome: 'HEARD', adjourned: false });
    expect(api.request.mock.calls[0].slice(0, 2)).toEqual(['POST', '/hearings/h1/outcome']);
  });

  it('sends a checked box as true', async () => {
    render(<ActionDialog act={outcome} row={{ hearingId: 'h1' }} onClose={() => undefined} onDone={() => undefined} />);
    fireEvent.change(screen.getByLabelText(new RegExp(T('fld.outcome'))), { target: { value: 'ADJOURNED' } });
    fireEvent.click(screen.getByLabelText(T('fld.adjourned')));
    confirm();
    expect(await sentBody()).toEqual({ outcome: 'ADJOURNED', adjourned: true });
  });

  it('shows a declared default in the control and sends it unless changed', async () => {
    const act: Act = { id: 'c', label: 'act.constraints', method: 'PUT', path: '/x', noJson: true, fields: [f.bool('requireSkill', 'fld.requireSkill', { defaultValue: true }), f.num('rotationOffset', 'fld.rotationOffset', true, { defaultValue: 0 })] };
    render(<ActionDialog act={act} onClose={() => undefined} onDone={() => undefined} />);
    expect((screen.getByLabelText(T('fld.requireSkill')) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText(new RegExp(T('fld.rotationOffset'))) as HTMLInputElement).value).toBe('0');
    confirm();
    expect(await sentBody()).toEqual({ requireSkill: true, rotationOffset: 0 });
  });

  it('refuses a required number left empty instead of inventing one', async () => {
    const act: Act = { id: 'd', label: 'act.addDemand', path: '/x', noJson: true, fields: [f.num('requiredCount', 'fld.requiredCount', true)] };
    render(<ActionDialog act={act} onClose={() => undefined} onDone={() => undefined} />);
    confirm();
    expect(await screen.findByText(T('form.required'))).toBeTruthy();
    expect(api.request).not.toHaveBeenCalled();
  });

  it('leaves the box out where the server documents a default, for a GET and for /engine-rest', async () => {
    const omit: Act = { id: 'm', label: 'act.addMember', path: '/x', noJson: true, fields: [f.text('displayName'), f.bool('voting', undefined, { omitUntouched: true })] };
    render(<ActionDialog act={omit} onClose={() => undefined} onDone={() => undefined} />);
    confirm();
    expect(await sentBody()).toEqual({});
    cleanup();
    api.request.mockClear();
    render(<ActionDialog act={{ id: 'g', label: 'act.searchAvailable', method: 'GET', path: '/y', noJson: true, fields: [f.bool('history')] }} onClose={() => undefined} onDone={() => undefined} />);
    confirm();
    await waitFor(() => expect(api.request).toHaveBeenCalled());
    expect(api.request.mock.calls[0][2].query).toEqual({});
    cleanup();
    api.request.mockClear();
    render(<ActionDialog act={{ id: 'e', label: 'act.update', path: '/engine-rest/process-definition/d1/suspended', method: 'PUT', noJson: true, fields: [f.bool('suspended')] }} onClose={() => undefined} onDone={() => undefined} />);
    confirm();
    expect(await sentBody()).toEqual({});
  });

  it('sends an operator flag the screen does not offer as false, nested by a dotted key, unless "More fields" sets it', () => {
    const series = action(RS.bookings, 'toolbar', 'series');
    const typed = { 'first.resourceId': 'r1', 'first.startsAt': '2026-11-20T10:00', 'first.endsAt': '2026-11-20T11:00', 'recurrence.frequency': 'WEEKLY', 'recurrence.interval': 1 };
    const body = send(series, typed);
    expect(body.first).toMatchObject({ resourceId: 'r1', bump: false, waitlistIfFull: false });
    expect(body.recurrence).toMatchObject({ frequency: 'WEEKLY', interval: 1, allOrNothing: false });
    expect(send(action(RS.bookings, 'toolbar', 'hold'), { resourceId: 'r1', startsAt: '2026-11-20T10:00', endsAt: '2026-11-20T11:00' }, undefined, '{"waitlistIfFull": true}')).toMatchObject({ bump: false, waitlistIfFull: true });
  });
});

describe('roster publication decision sends DecisionRequest(version, approved, reason)', () => {
  it('approves with approved: true and the version of the open period, never a decision string', async () => {
    api.get.mockResolvedValue({ periodId: 'p1', state: 'SUBMITTED', version: 3 });
    api.page.mockResolvedValue({ items: [], page: 0, size: 500, total: 0, capped: false });
    render(<RosterGrid />);
    fireEvent.change(screen.getByLabelText(new RegExp(T('fld.periodId'))), { target: { value: 'p1' } });
    fireEvent.click(screen.getByRole('button', { name: T('common.open') }));
    fireEvent.click(await screen.findByRole('button', { name: T('act.decidePublication') }));
    fireEvent.click(screen.getByLabelText(T('fld.approved')));
    fireEvent.change(screen.getByLabelText(new RegExp(T('common.reason'))), { target: { value: 'Fair and complete' } });
    fireEvent.click(screen.getByRole('button', { name: T('common.confirm') }));
    await waitFor(() => expect(api.request).toHaveBeenCalled());
    const [method, path, opts] = api.request.mock.calls[0];
    expect([method, path]).toEqual(['POST', '/rosters/periods/p1/publication-decision']);
    expect(opts.body).toEqual({ approved: true, version: 3, reason: 'Fair and complete' });
  });

  it('rejects with approved: false when the box is left unchecked', () => {
    const grid = { id: 'decide', label: 'act.decidePublication', path: '/rosters/periods/{periodId}/publication-decision', fields: [f.bool('approved', 'fld.approved'), f.num('version', 'fld.version', true)] } as Act;
    expect(send({ ...grid, defaults: () => ({ version: 3 }) })).toEqual({ approved: false, version: 3 });
  });
});

describe('every action posting a primitive-bearing body declares it', () => {
  it('case files: stage / assign / close / reopen carry the row version; parties, documents and hearing outcomes their booleans', () => {
    for (const id of ['stage', 'assign', 'close', 'reopen']) {
      const a = action(CW.caseFiles, 'actions', id);
      expect(send(a, { stage: 'S2', ownerSeat: 'seat-1' }, { caseId: 'c1', rowVersion: 4 })).toMatchObject({ rowVersion: 4 });
      expect(() => send(a, { stage: 'S2', ownerSeat: 'seat-1' }, { caseId: 'c1' })).toThrow(/rowVersion/);
    }
    expect(send(action(CW.caseFiles, 'actions', 'party'), { partyId: 'p1', role: 'PETITIONER' })).toEqual({ partyId: 'p1', role: 'PETITIONER', masked: false });
    expect(send(action(CW.caseFiles, 'actions', 'document'), { contentItemId: 'doc-1' })).toEqual({ contentItemId: 'doc-1', exhibit: false });
    expect(send(action(CW.hearings, 'actions', 'outcome'), { outcome: 'HEARD' })).toEqual({ outcome: 'HEARD', adjourned: false });
  });

  it('meetings and references: vote counts, minutes version, verification and duplicate override', () => {
    const decision = action(CW.meetings, 'actions', 'decision');
    expect(send(decision, { itemId: 'i1', text: 'Approved as proposed', outcome: 'APPROVED' })).toMatchObject({ votesFor: 0, votesAgainst: 0, abstentions: 0 });
    expect(send(decision, { itemId: 'i1', text: 't', outcome: 'APPROVED', votesFor: 7, votesAgainst: 2, abstentions: 1 })).toMatchObject({ votesFor: 7, votesAgainst: 2, abstentions: 1 });
    expect(() => send(decision, { itemId: 'i1', text: 't', outcome: 'APPROVED', votesFor: undefined })).toThrow(/votesFor/);
    expect(() => send(action(CW.meetings, 'actions', 'approve'), { contentHash: 'h' })).toThrow(/version/);
    expect(send(action(CW.actionItems, 'actions', 'verify'), {})).toEqual({ accept: false });
    expect(send(action(CW.references, 'create', 'register'), { kind: 'VIP', sourceName: 'MLA', subject: 'Road' })).toMatchObject({ allowDuplicate: false });
  });

  it('committees leave chairRequired and voting out when untouched: the server defaults them (CommitteeService.java:67, :113)', () => {
    expect(send(action(CW.committees, 'create', 'constitute'), { kind: 'K', name: 'N', constitutionRef: 'GO-1' })).not.toHaveProperty('chairRequired');
    expect(send(action(CW.committees, 'actions', 'member'), { role: 'MEMBER' })).not.toHaveProperty('voting');
  });

  it('ledger operation sends fromCommitment, fromEncumbrance and holdIfShort', () => {
    expect(send(action(FI.accounts, 'toolbar', 'operation'), { operation: 'CONSUME', 'account.line': '2202', amount: '10.00' })).toMatchObject({ fromCommitment: false, fromEncumbrance: false, holdIfShort: false });
  });

  it('programmes: milestone seq, period offsets, allocation, progress and return versions', () => {
    expect(() => send(action(PG.programmes, 'actions', 'milestone'), { name: 'Survey', plannedStart: '2026-12-01', plannedEnd: '2026-12-31' })).toThrow(/seq/);
    expect(send(action(PG.programmes, 'actions', 'milestone'), { name: 'Survey', seq: 1, plannedStart: '2026-12-01', plannedEnd: '2026-12-31' })).toMatchObject({ seq: 1 });
    const generate = action(PG.periods, 'toolbar', 'generate');
    expect(() => send(generate, { fiscalStartYear: 2026, frequency: 'MONTHLY' })).toThrow(/dueOffsetDays/);
    expect(send(generate, { fiscalStartYear: 2026, frequency: 'MONTHLY', dueOffsetDays: 10 })).toEqual({ fiscalStartYear: 2026, frequency: 'MONTHLY', dueOffsetDays: 10 });
    expect(send(action(PG.targets, 'actions', 'allocate'), {})).toEqual({ allowOverAllocation: false });
    expect(send(action(PG.milestones, 'actions', 'progress'), {}, { id: 'm1', rowVersion: 2 })).toEqual({ expectedVersion: 2, drop: false, milestoneId: 'm1' });
    expect(send(action(PG.returns, 'actions', 'values'), { values: '{"km": 4}' }, { returnId: 'r1', rowVersion: 5 })).toMatchObject({ expectedVersion: 5 });
    expect(send(action(PG.returns, 'actions', 'submit'), {}, { returnId: 'r1', rowVersion: 5 })).toEqual({ expectedVersion: 5 });
    expect(() => send(action(PG.returns, 'actions', 'submit'), {}, { returnId: 'r1' })).toThrow(/expectedVersion/);
  });

  it('resources, queues and rosters: hold flags, slot template, approval, extension version, service point, shifts, members, constraints, swaps', () => {
    const slot = { resourceId: 'r1', startsAt: '2026-11-20T10:00', endsAt: '2026-11-20T11:00' };
    for (const id of ['quote', 'hold']) expect(send(action(RS.bookings, 'toolbar', id), slot)).toMatchObject({ bump: false, waitlistIfFull: false });
    expect(() => send(action(RS.bookings, 'toolbar', 'series'), { 'first.resourceId': 'r1', 'first.startsAt': slot.startsAt, 'first.endsAt': slot.endsAt, 'recurrence.frequency': 'DAILY' })).toThrow(/recurrence\.interval/);
    expect(send(action(RS.bookings, 'actions', 'approval'), {})).toEqual({ approved: false });
    expect(send(action(RS.allotments, 'actions', 'extend'), { newValidTo: '2027-01-01T00:00' }, { allotmentId: 'a1', version: 3 })).toMatchObject({ version: 3 });
    const slots = action(RS.resItems, 'toolbar', 'slots');
    const tpl = { resourceId: 'r1', weekdays: '["MONDAY"]', from: '09:00', to: '17:00', slotMinutes: 30 };
    expect(() => send(slots, tpl)).toThrow(/unitsPerSlot/);
    expect(send(slots, { ...tpl, unitsPerSlot: 2 })).toMatchObject({ slotMinutes: 30, unitsPerSlot: 2 });
    expect(() => send(action(RS.servicePoints, 'toolbar', 'define'), { servicePointId: 'sp1', name: 'Hall' })).toThrow(/slaMinutes/);
    expect(send(action(RS.servicePoints, 'toolbar', 'define'), { servicePointId: 'sp1', name: 'Hall', slaMinutes: 15 })).toEqual({ name: 'Hall', slaMinutes: 15 });
    expect(send(action(RS.shifts, 'create', 'save'), { shiftId: 's1', name: 'Morning', fromTime: '06:00', durationMinutes: 480 })).toMatchObject({ durationMinutes: 480, night: false });
    expect(send(action(RS.periods, 'actions', 'member'), { personId: 'u1' })).toEqual({ rotationOffset: 0 });
    expect(() => send(action(RS.periods, 'actions', 'demand'), { shiftId: 's1' })).toThrow(/requiredCount/);
    expect(send(action(RS.periods, 'actions', 'constraints'), { version: 2 })).toEqual({ requireSkill: true });
    expect(send(action(RS.swaps, 'actions', 'decide'), {}, { swapId: 'w1', version: 6 })).toEqual({ approved: false, version: 6 });
  });
});

describe('direct callers send their primitives', () => {
  it('process modification and restart send the skip flags the dialogs do not offer as false', async () => {
    api.post.mockResolvedValue({});
    await endpoints.processes.modify('pi1', [{ type: 'startBeforeActivity', activityId: 'a' }], 'Move the token', 'k1');
    expect(api.post).toHaveBeenCalledWith('/process-instances/pi1/modify', { instructions: [{ type: 'startBeforeActivity', activityId: 'a' }], reason: 'Move the token', skipCustomListeners: false, skipIoMappings: false }, 'k1');
    await endpoints.processes.restart({ definitionId: 'd1', instanceIds: ['pi1'], startBeforeActivityId: 'a', initialVariables: true, reason: 'Restart it' }, 'k2');
    expect(api.post).toHaveBeenCalledWith('/ops/restarts', { processDefinitionId: 'd1', processInstanceIds: ['pi1'], startBeforeActivityIds: ['a'], initialVariables: true, skipCustomListeners: false, reason: 'Restart it' }, 'k2');
  });

  it('an identity provider session timeout must be a whole number before it is sent', () => {
    expect(idpTimeoutProblem(undefined)).toBe('form.required');
    expect(idpTimeoutProblem(Number.NaN)).toBe('form.required');
    expect(idpTimeoutProblem(12.5)).toBe('form.wholeNumber');
    expect(idpTimeoutProblem(30)).toBeUndefined();
    expect(idpTimeoutProblem(0)).toBeUndefined();
  });
});