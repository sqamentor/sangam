// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/** Decision 397 group 2: page frame (6.1), status chips (6.5) and first-run checklists (6.4). */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { initI18n } from '../i18n';
import { MemoryRouter } from 'react-router-dom';
import { show, T } from '../test/screens';
import { PageHeader } from './ui';
import { StatusChip } from './StatusChip';
import { STATUS, statusOf, toneOf } from './status';
import { Checklist, checklistsFor, loadProgress, PERSONAS } from './Checklist';
import { visibleNav } from '../app/nav';
import type { Role } from '../api/types';

beforeAll(() => initI18n('en'));
afterEach(cleanup);

const me = (roles: Role[], permissions: string[] = []) => ({ roles, permissions });

describe('page frame', () => {
  it('shows Group > Entry and the entry description on a menu page', () => {
    show(<PageHeader title="Stuck requests" />, '/ops/incidents');
    const crumbs = screen.getByRole('navigation', { name: T('frame.breadcrumb') });
    expect(crumbs.textContent).toContain(T('menu.monitor.title'));
    expect(screen.getAllByText(T('menu.monitor.stuck.label')).length).toBeGreaterThan(0);
    expect(crumbs.querySelector('[aria-current="page"]')?.textContent).toBe(T('menu.monitor.stuck.label'));
    expect(screen.getByText(T('menu.monitor.stuck.help'))).toBeTruthy();
  });
  it('on an item page links back to the entry and marks the item current', () => {
    show(<PageHeader title="REQ-1" />, '/ops/incidents/42');
    const crumbs = screen.getByRole('navigation', { name: T('frame.breadcrumb') });
    expect(crumbs.querySelector('a')?.getAttribute('href')).toBe('/ops/incidents');
    expect(crumbs.querySelector('[aria-current="page"]')?.textContent).toBe('REQ-1');
    expect(screen.queryByText(T('menu.monitor.stuck.help'))).toBeNull();
  });
  it("keeps the page's own description", () => {
    show(<PageHeader title="x" description="Own words" />, '/ops/incidents');
    expect(screen.getByText('Own words')).toBeTruthy();
    expect(screen.queryByText(T('menu.monitor.stuck.help'))).toBeNull();
  });
  it('draws no breadcrumb outside the menu or outside a router', () => {
    show(<PageHeader title="x" />, '/account');
    expect(screen.queryByRole('navigation')).toBeNull();
    render(<PageHeader title="Embedded" description="d" />);
    expect(screen.getByText('d')).toBeTruthy();
  });
});

describe('status vocabulary', () => {
  it.each([
    ['ACTIVE', 'inProgress'],
    ['IN_PROGRESS', 'inProgress'],
    ['COMPLETED', 'done'],
    ['APPROVED', 'done'],
    ['RETURNED', 'sentBack'],
    ['ESCALATED', 'overdue'],
    ['SLA_BREACHED', 'overdue'],
    ['FAILED', 'attention'],
    ['INCIDENT', 'attention'],
    ['CANCELLED', 'cancelled'],
    ['WITHDRAWN', 'cancelled'],
    ['INACTIVE', 'cancelled'],
    ['DRAFT', 'draft'],
    ['PENDING', 'waiting'],
    ['UNPAID', null],
    ['UP', null],
    ['IN_REVIEW', null],
  ])('%s is %s', (raw, s) => {
    expect(statusOf(raw)).toBe(s);
  });
  it('gives one colour per word, and states outside the vocabulary keep theirs', () => {
    expect(toneOf('COMPLETED')).toBe('ok');
    expect(toneOf('CANCELLED')).toBe('neutral');
    expect(toneOf('UP')).toBe('ok');
    expect(toneOf('DEGRADED')).toBe('warn');
    expect(toneOf('SOMETHING')).toBe('neutral');
  });
  it('every word has text, a tone and an icon', () => {
    for (const s of Object.keys(STATUS)) {
      expect(STATUS[s as keyof typeof STATUS].icon).toBeTruthy();
      if (s !== 'checked') expect(T(`status.${s}`)).toBeTruthy();
    }
  });
  it('a check over nothing is "Not checked", never "Checked"', () => {
    const { container, rerender } = render(<StatusChip checked={0} />);
    expect(container.textContent).toContain(T('status.notChecked'));
    expect(container.textContent).not.toMatch(/Checked \(/);
    rerender(<StatusChip checked={3} />);
    expect(container.textContent).toContain('Checked (3 items)');
    rerender(<StatusChip checked={1} />);
    expect(container.textContent).toContain('Checked (1 item)');
  });
  it('shows a word and an icon, not colour alone', () => {
    const { container } = render(<StatusChip value="RETURNED" />);
    expect(container.textContent).toContain(T('status.sentBack'));
    expect(container.querySelector('[aria-hidden="true"]')?.textContent).toBe(STATUS.sentBack.icon);
    expect(container.querySelector('.badge-warn')).toBeTruthy();
  });
});

describe('first-run checklists', () => {
  afterEach(() => {
    window.localStorage.clear();
    vi.restoreAllMocks();
  });
  it('gives each persona its own list, only with steps the caller can open', () => {
    expect(checklistsFor(me(['CITIZEN'])).map((p) => p.id)).toEqual(['citizen']);
    expect(checklistsFor(me(['TASK_USER'])).map((p) => p.id)).toEqual(['taskUser']);
    expect(checklistsFor(me(['MODELLER'])).map((p) => p.id)).toEqual(['designer']);
    expect(checklistsFor(me(['AUDITOR'])).map((p) => p.id)).toEqual(['auditor']);
    expect(checklistsFor(me(['OPERATOR'])).map((p) => p.id)).toEqual(['operator']);
    expect(checklistsFor(me(['OPERATOR'], ['team.read'])).map((p) => p.id)).toEqual(['supervisor', 'operator']);
    // a tenant admin cannot install packs (platform admins only): that step is left out
    const admin = checklistsFor(me(['ADMIN']))[0];
    expect(admin.steps.map((s) => s.id)).not.toContain('packs');
    expect(checklistsFor(me(['ADMIN', 'PLATFORM_ADMIN']))[0].steps.map((s) => s.id)).toContain('packs');
    expect(checklistsFor(null)).toEqual([]);
  });
  it('every step of every persona has text and a page the persona can open', () => {
    for (const p of PERSONAS) {
      expect(T(`checklist.${p.id}.title`)).toBeTruthy();
      const open = new Set(['/account', ...visibleNav(me(p.roles, p.perms)).flatMap((a) => a.sections.flatMap((s) => s.items.map((i) => i.to)))]);
      for (const s of p.steps) {
        expect(T(`checklist.${p.id}.${s.id}`)).toBeTruthy();
        if (p.id !== 'admin') expect(open.has(s.to), `${p.id} ${s.to}`).toBe(true);
      }
    }
  });
  it('keeps progress across a reload and can be hidden', () => {
    const persona = checklistsFor(me(['CITIZEN']))[0];
    const first = render(<MemoryRouter><Checklist user="u1" persona={persona} /></MemoryRouter>);
    fireEvent.click(screen.getByLabelText(T('checklist.citizen.find')));
    expect(screen.getByText(T('checklist.progress', { done: 1, total: 3 }))).toBeTruthy();
    first.unmount();
    render(<MemoryRouter><Checklist user="u1" persona={persona} /></MemoryRouter>);
    expect((screen.getByLabelText(T('checklist.citizen.find')) as HTMLInputElement).checked).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: T('checklist.hide') }));
    expect(screen.queryByLabelText(T('checklist.citizen.find'))).toBeNull();
    expect(loadProgress('u1', 'citizen')).toEqual({ done: ['find'], hidden: true });
    expect(loadProgress('u2', 'citizen')).toEqual({ done: [], hidden: false });
  });
  it('works when browser storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const persona = checklistsFor(me(['CITIZEN']))[0];
    render(<MemoryRouter><Checklist user="u1" persona={persona} /></MemoryRouter>);
    fireEvent.click(screen.getByLabelText(T('checklist.citizen.apply')));
    expect((screen.getByLabelText(T('checklist.citizen.apply')) as HTMLInputElement).checked).toBe(true);
  });
  it('each step link names its step', () => {
    const persona = checklistsFor(me(['CITIZEN']))[0];
    render(<MemoryRouter><Checklist user="u1" persona={persona} /></MemoryRouter>);
    expect(screen.getByRole('link', { name: T('checklist.goTo', { step: T('checklist.citizen.track') }) }).getAttribute('href')).toBe('/portal/applications');
  });
});
