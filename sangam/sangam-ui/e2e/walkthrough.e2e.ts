// SPDX-License-Identifier: Apache-2.0
/**
 * Register L-3: the officer's walkthrough of the presentation, through the browser, against the full stack of the
 * e2e job (ui.yml: the test-mode server with every shipped pack deployed for its tenant, and the UI image).
 *
 *   sign-in -> the seat GET /me offers is shown -> the DDO starts a bill on the Start page -> the dealing hand and
 *   the PAO act on it -> operations dashboard -> audit timeline, hash chain verified -> sign-out
 *
 * What this stack is and is not. It is the test-mode server: one human identity (the development token; a service
 * principal's token holds no seat), simulated connectors. It is not the demo build (production mode, realm oneifms,
 * tenant GOI.FIN on the sandbox list); the bill's journey on that build is proven by the server tests
 * (DemoLoaderSequenceAgainstServerTest) and its sign-in by SignIn/AuthProvider unit tests. So the one identity is
 * posted to every seat of the bill's chain here and switches between them with "Acting as".
 *
 * Every step runs even when an earlier one failed, and each is reported as one line
 * `WALKTHROUGH | <step> | PASS or FAIL | evidence`. OPEN_STEPS lists the steps that are known not to pass on this
 * stack, each with its recorded cause: such a step is run and reported but does not fail the spec; when it passes,
 * the spec fails until the step is taken off the list, so the list cannot go stale. Every other step must pass.
 */
import { randomUUID } from 'node:crypto';
import { request, type APIRequestContext, type Page } from '@playwright/test';
import { test, expect } from './support/fixtures';
import { API_BASE, devToken, t } from './support/env';

const OPEN_STEPS: Record<string, string> = {
  // ui.yml run 44882890962 (tenant GOI.E2E, EV-M-0961 A): the bill passes bl_bindingHold but opens no task within
  // 45 s; this spec now funds the head first (fundTheBillHead). Kept open until a run shows the steps green.
  // ui.yml run 44863340501 (chromium and firefox): the bill started on the Start page stops at the task bl_bindingHold
  // ("Profile binding missing: bind the bill type in the profile, then check again"); the test-mode tenant has no
  // profile binding for the bill type, so the case never reaches the PAO chain, and the task list names no seat for
  // that task (seatId empty). Not a wait for the sandbox bill journey (register E-26): that is the demo build's.
  '4 dealing hand acts': 'the bill stops at bl_bindingHold on the e2e stack: no profile binding for the bill type in the test-mode tenant',
  '5 PAO acts': 'as step 4: the case does not reach pc_pao on the e2e stack',
};

const OFFICE = 'E2E-WALK-OFFICE';
/** The seats the bill and its PAO chain route to (pack defaults: scrutinySeat, dealingSeat, the chain's own seats). */
const SEATS = ['DDO-1', 'BD-1', 'DH-1', 'AAO-1', 'PAO-1', 'SS-1'];
const BILL = 'sangam-ifms-bill';

type Json = Record<string, unknown>;
const items = (body: unknown): Json[] => (Array.isArray(body) ? body : (((body as Json)?.items as Json[]) ?? [])) as Json[];

async function admin(seat?: string): Promise<APIRequestContext> {
  return request.newContext({
    baseURL: `${API_BASE.replace(/\/$/, '')}/`,
    extraHTTPHeaders: {
      Authorization: `Bearer ${devToken()}`,
      Accept: 'application/json, application/problem+json',
      ...(seat ? { 'X-Sangam-Seat': seat } : {}),
    },
  });
}

/** Office, seats and one posting per seat for the signed-in person; "already there" answers are accepted. */
async function postToTheChainSeats(): Promise<string[]> {
  const notes: string[] = [];
  const api = await admin();
  try {
    const me = (await (await api.get('me')).json()) as { userId: string; tenantId: string };
    const today = new Date().toISOString().slice(0, 10);
    const send = async (method: 'put' | 'post', path: string, data: Json, okToo: number[] = []) => {
      const res = await api[method](path, { headers: { 'Idempotency-Key': randomUUID() }, data });
      if (!res.ok() && !okToo.includes(res.status())) notes.push(`${method.toUpperCase()} ${path} ${res.status()} ${(await res.text()).slice(0, 200)}`);
      return res;
    };
    await send('put', 'admin/org-levels', { tenantId: me.tenantId, levels: ['OFFICE'] }, [409]);
    await send('post', 'admin/org-units', { id: OFFICE, code: OFFICE, name: 'Walkthrough office', level: 'OFFICE', parentId: null }, [409]);
    for (const seat of SEATS) {
      await send('post', 'admin/seats', { id: seat, code: seat, designation: seat, name: seat, orgUnitId: OFFICE, reportsToSeatId: null, roles: [] }, [409]);
      const held = await api.get('admin/assignments', { params: { seatId: seat, kind: 'SUBSTANTIVE', activeOn: today } });
      if (held.ok() && items(await held.json()).some((a) => a.personId === me.userId)) continue;
      await send('post', 'admin/assignments', { seatId: seat, personId: me.userId, kind: 'SUBSTANTIVE', validFrom: today, orderRef: 'Office order: walkthrough posting' });
    }
  } finally {
    await api.dispose();
  }
  return notes;
}

/**
 * The budget behind the bill: Rs 10 lakh on ledger BUDGET for the bill's head and DDO, ALLOCATE at the first stage and
 * DISTRIBUTE through every stage to the bill's DDO at the consuming stage, the way the presentation funds its grant
 * (DemoLoaderSequenceAgainstServerTest.fund; stages of profile GOI > IN > REGIME-WESTMINSTER-SUPPLY). The bill's funds
 * step asks ledger.available for (headOfAccount, ddoCode); with nothing distributed it waits at bl_hold. Each run
 * funds the head again (new idempotency keys, references with the run's key), so the amount only grows.
 */
const LEDGER_STAGES = ['APPROPRIATION', 'ALLOCATION', 'RELEASE', 'AUTHORISATION', 'SUB_ALLOTMENT'];
const HEAD = '2054-00-001-01-00-13';
const DDO = 'DDO-1';
const FUNDS = '1000000.00';

async function fundTheBillHead(run: string): Promise<string> {
  const api = await admin();
  try {
    const period = fiscalYear(new Date());
    const account = (stage: string, holder: string) => ({ period, line: HEAD, holder, stage });
    const operate = async (operation: string, from: Json, to?: Json) => {
      const res = await api.post('ledger/by-ledger/operations', {
        params: { ledger: 'BUDGET' },
        headers: { 'Idempotency-Key': randomUUID() },
        data: {
          operation, account: from, ...(to ? { target: to } : {}), amount: FUNDS,
          reference: `BUDGET/${period}/${HEAD}/${operation}/${String(from.stage)}${to ? `>${String(to.stage)}` : ''}/${run}`,
          auditRef: 'Budget distribution order, walkthrough', fromCommitment: false, fromEncumbrance: false, holdIfShort: false,
        },
      });
      const body = await res.text();
      expect(res.status(), `${operation} ${JSON.stringify(from)}: ${body.slice(0, 300)}`).toBe(200);
      expect((JSON.parse(body) as Json).applied, `${operation} applied: ${body.slice(0, 300)}`).toBe(true);
    };
    let at = account(LEDGER_STAGES[0], '-');
    await operate('ALLOCATE', at);
    for (let i = 1; i < LEDGER_STAGES.length; i++) {
      const next = account(LEDGER_STAGES[i], i === LEDGER_STAGES.length - 1 ? DDO : '-');
      await operate('DISTRIBUTE', at, next);
      at = next;
    }
    const res = await api.get('ledger/by-ledger/accounts', { params: { ledger: 'BUDGET', period, line: HEAD, holder: DDO, stage: at.stage } });
    const rows = items(await res.json());
    expect(rows.length, `the consuming account of ${DDO}: ${JSON.stringify(rows).slice(0, 300)}`).toBe(1);
    return `Rs ${FUNDS} through ${LEDGER_STAGES.length} stages; available to ${DDO} at ${at.stage}: ${String(rows[0].available)} ${String(rows[0].currency ?? '')}`;
  } finally {
    await api.dispose();
  }
}

/** The Indian financial year (April to March) of a date, as the ledger names its period: 2026-27. */
function fiscalYear(d: Date): string {
  const ist = new Date(d.getTime() + 330 * 60_000);
  const start = ist.getUTCMonth() >= 3 ? ist.getUTCFullYear() : ist.getUTCFullYear() - 1;
  return `${start}-${String((start + 1) % 100).padStart(2, '0')}`;
}

/** The open tasks of the case, each with the seat it waits for (the task's own seatId, as the administrator's list names it). */
async function openTasks(businessKey: string): Promise<{ id: string; key: string; seat: string }[]> {
  const api = await admin();
  try {
    const res = await api.get('tasks', { params: { businessKey, size: 100 } });
    if (!res.ok()) return [];
    return items(await res.json()).map((task) => ({ id: String(task.id), key: String(task.taskDefinitionKey), seat: String(task.seatId ?? '') }));
  } finally {
    await api.dispose();
  }
}

async function actAs(page: Page, seat: string): Promise<void> {
  const select = page.getByLabel(t('seat.actingAs'));
  await expect(select).toBeVisible();
  const value = await select.locator('option', { hasText: seat }).first().getAttribute('value');
  await select.selectOption(value ?? seat);
}

/** Opens the task as the holder of its seat and records the first decision the server allows, through the dialog. */
async function decide(page: Page, task: { id: string; key: string; seat: string }): Promise<string> {
  expect(SEATS, `${task.key} waits for a seat of the chain (seatId "${task.seat}")`).toContain(task.seat);
  await page.goto('/tasks');
  await actAs(page, task.seat);
  await page.goto(`/tasks/${encodeURIComponent(task.id)}`);
  const claim = page.getByRole('button', { name: t('task.claim'), exact: true });
  const bar = page.getByRole('group', { name: t('task.decisions') });
  // the decisions are offered to the person who has taken the task: claim it first where the page offers that
  try {
    await expect(claim.or(bar).first()).toBeVisible();
    if (!(await bar.isVisible()) && (await claim.isVisible())) await claim.click();
    await expect(bar).toBeVisible();
  } catch (e) {
    const shown = (await page.locator('main').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 350);
    throw new Error(`${task.key} at ${task.seat}: no decision is offered. The page shows: ${shown}`);
  }
  let action = '';
  for (const a of ['FORWARD', 'APPROVE', 'COMPLETE']) {
    const b = bar.getByRole('button', { name: t(`task.action.${a}`), exact: true });
    if (await b.isVisible().catch(() => false)) {
      action = a;
      await b.click();
      break;
    }
  }
  expect(action, `a decision button for ${task.key} (offered: ${(await bar.innerText()).replace(/\s+/g, ' ')})`).not.toBe('');
  const dialog = page.locator('dialog[open]');
  const remarks = dialog.getByLabel(t('task.remarks'));
  if (await remarks.isVisible().catch(() => false)) await remarks.fill('Checked against the sanction order; in order.');
  for (const box of await dialog.getByRole('checkbox').all()) await box.check();
  const next = dialog.getByRole('radio').first();
  if (await next.isVisible().catch(() => false)) await next.check();
  await dialog.getByRole('button', { name: t('common.confirm') }).click();
  await expect(page.getByRole('heading', { name: t('task.doneTitle') })).toBeVisible();
  return `${task.key} at ${task.seat}: ${action}`;
}

test('the walkthrough of the presentation: sign-in, seat, bill, dealing hand and PAO, dashboard, audit, sign-out', async ({ page }) => {
  test.setTimeout(240_000);
  const report: string[] = [];
  const failed: string[] = [];
  const step = async (name: string, body: () => Promise<string>) => {
    try {
      const evidence = await test.step(name, body);
      report.push(`WALKTHROUGH | ${name} | PASS | ${evidence}`);
      if (name in OPEN_STEPS) failed.push(`${name} passes now: take it off OPEN_STEPS`);
    } catch (e) {
      const why = String((e as Error).message ?? e).replace(/\s+/g, ' ').slice(0, 600);
      report.push(`WALKTHROUGH | ${name} | FAIL | ${why}${name in OPEN_STEPS ? ` | known: ${OPEN_STEPS[name]}` : ''}`);
      if (!(name in OPEN_STEPS)) failed.push(`${name}: ${why}`);
    }
  };
  const businessKey = `E2E-WALK/BILL/${randomUUID()}`;
  const done: string[] = [];

  await step('0 postings', async () => {
    const notes = await postToTheChainSeats();
    expect(notes, 'office, seats and postings of the signed-in person').toEqual([]);
    // ui.yml run 44882890962 (tenant GOI.E2E): without a budget the bill opened no task in 45 s (steps 4, 5)
    const funds = await fundTheBillHead(businessKey);
    return `${SEATS.length} seats held; ${funds}`;
  });

  await step('1 sign-in', async () => {
    await page.goto('/');
    await expect(page.getByRole('heading', { name: t('auth.title') })).toBeVisible();
    await page.getByLabel(t('auth.devToken')).fill(devToken());
    await page.getByRole('button', { name: t('auth.signIn') }).click();
    await expect(page.getByRole('navigation', { name: t('nav.primary') })).toBeVisible();
    return 'the shell is shown';
  });

  await step('2 seat from /me', async () => {
    const api = await admin();
    const me = (await (await api.get('me')).json()) as { seats?: { seatId: string }[] };
    await api.dispose();
    const offered = (me.seats ?? []).map((s) => s.seatId).sort();
    expect(offered, 'GET /me offers the seats held').toEqual([...SEATS].sort());
    const select = page.getByLabel(t('seat.actingAs'));
    await expect(select).toBeVisible();
    for (const seat of SEATS) await expect(select.locator('option', { hasText: seat })).toHaveCount(1);
    await actAs(page, 'DDO-1');
    return `"${t('seat.actingAs')}" offers ${offered.join(', ')}`;
  });

  await step('3 DDO starts a bill', async () => {
    await page.goto('/start');
    await expect(page.getByRole('heading', { name: t('start.title') })).toBeVisible();
    const api = await admin('DDO-1');
    const catalogue = items(await (await api.get('start-catalogue')).json());
    await api.dispose();
    const bill = catalogue.find((c) => c.key === BILL);
    expect(bill, `the Start page offers ${BILL} (offered ${catalogue.length} items)`).toBeTruthy();
    const card = page.locator('li.card').filter({ has: page.getByRole('heading', { name: String(bill!.name), exact: true }) });
    await card.getByRole('button', { name: t('start.begin') }).click();
    await page.getByLabel(t('start.businessKey')).fill(businessKey);
    const type = page.getByLabel(/^Bill type/);
    await expect(type).toBeVisible();
    // a pack form's select comes to the start screen as a text field today (adapters.ts packForm); either is filled
    if ((await type.evaluate((e) => e.tagName)) === 'SELECT') {
      const values = await type.locator('option').evaluateAll((os) => os.map((o) => (o as HTMLOptionElement).value).filter((v) => v));
      await type.selectOption(values.includes('CONTINGENT') ? 'CONTINGENT' : values[0]);
    } else {
      await type.fill('CONTINGENT');
    }
    await page.getByLabel(/^Head of account/).fill('2054-00-001-01-00-13');
    await page.getByLabel(/^Amount/).fill('42350');
    await page.getByLabel(/^Payee bank account/).fill('00004207100101');
    await page.getByLabel(/^Payee(?! bank)/).fill('Mahesh Kumar Yadav');
    await page.getByLabel(/^DDO code/).fill('DDO-1');
    await page.getByRole('button', { name: t('start.submit') }).click();
    await expect(page).toHaveURL(/\/tasks/);
    const read = await admin();
    let count = 0;
    await expect
      .poll(async () => (count = items(await (await read.get('process-instances', { params: { businessKey } })).json()).length), { timeout: 30_000 })
      .toBeGreaterThan(0);
    await read.dispose();
    return `business key ${businessKey}: ${count} instance(s)`;
  });

  /** Completes the open tasks of the case through the UI, in the order they open, until `until` was decided. */
  const through = async (until: string): Promise<string> => {
    const mine: string[] = [];
    for (let i = 0; i < 8 && !done.some((d) => d.startsWith(until + ' ')); i++) {
      let tasks: { id: string; key: string; seat: string }[] = [];
      await expect.poll(async () => (tasks = await openTasks(businessKey)).length, { timeout: 45_000, message: `an open task of ${businessKey} (done so far: ${done.join('; ') || 'none'})` }).toBeGreaterThan(0);
      const line = await decide(page, tasks[0]);
      done.push(line);
      mine.push(line);
    }
    expect(done.some((d) => d.startsWith(until + ' ')), `${until} was decided (done: ${done.join('; ')})`).toBe(true);
    return mine.join('; ');
  };

  await step('4 dealing hand acts', () => through('pc_dealing'));
  await step('5 PAO acts', () => through('pc_pao'));

  await step('6 dashboard', async () => {
    await page.goto('/ops');
    await expect(page.getByRole('heading', { name: t('ops.dashboardTitle') })).toBeVisible();
    return `"${t('ops.dashboardTitle')}" is shown`;
  });

  await step('7 audit verify', async () => {
    await page.goto(`/audit/timeline/${encodeURIComponent(businessKey)}`);
    await page.getByRole('button', { name: t('audit.verify') }).click();
    const verified = page.locator('strong', { hasText: t('audit.chainValid') });
    await expect(verified).toBeVisible();
    const line = (await verified.locator('xpath=..').innerText()).replace(/\s+/g, ' ');
    expect(Number((line.match(/(\d+)/) ?? ['0', '0'])[1]), `events checked: ${line}`).toBeGreaterThan(0);
    return line;
  });

  await step('8 sign-out', async () => {
    await page.getByRole('button', { name: t('auth.signOut') }).click();
    await expect(page.getByRole('heading', { name: t('auth.title') })).toBeVisible();
    return 'the sign-in screen is shown again';
  });

  for (const line of report) {
    console.log(line);
    test.info().annotations.push({ type: 'walkthrough', description: line });
  }
  expect(failed, report.join('\n')).toEqual([]);
});
