// SPDX-License-Identifier: Apache-2.0
/**
 * sangam-ledger and sangam-billing screens.
 * Ledger (contract /ledger/**): accounts with every chain stage (total → distributed → committed → encumbered →
 * consumed) and availability, idempotent operations with "hold if short", freeze orders, held requests, period close,
 * reconciliation against the system of record. Amounts are decimal strings (R33), never floats.
 * Billing (contract /billing/**): accounts per cycle, invoices from tariffs, collections with receipts, arrears,
 * penalties, waivers under an approved order, refunds over the payment rail (one operation per Idempotency-Key).
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { http } from '../api/client';
import { useAsync } from '../lib/hooks';
import { fmtMoney } from '../i18n';
import { BarChart } from '../components/Charts';
import { Empty, ProblemAlert, Section, TextField } from '../components/ui';
import { ModulePage, c, f, type Row, type ScreenDef } from './engine';

const money = (key: string) => c(key, `fld.${key}`, (r: Row) => (r[key] === undefined || r[key] === null ? '—' : <span className="num">{fmtMoney(Number(r[key]), String(r.currency ?? 'INR'))}</span>));
const ledgerFilter = f.text('ledger', 'fld.ledgerId', true, { description: 'mod.ledger.ledgerHint' });
const accountFields = [f.text('period'), f.text('line', 'fld.headOfAccount', true), f.text('holder'), f.text('stage'), f.text('sub')];

const OPS = ['ALLOCATE', 'DISTRIBUTE', 'WITHDRAW', 'COMMIT', 'ENCUMBER', 'RELEASE', 'CONSUME', 'TRANSFER', 'ADJUST', 'REVERSE', 'WRITE_OFF'];

const accounts: ScreenDef = {
  id: 'accounts',
  title: 'mod.ledger.accounts',
  description: 'mod.ledger.accountsDesc',
  list: '/ledger/{ledger}/accounts',
  filters: [ledgerFilter, f.text('period'), f.text('line', 'fld.headOfAccount'), f.text('holder'), f.text('stage')],
  requireFilters: ['ledger'],
  emptyHint: 'mod.ledger.pickLedger',
  columns: [
    c('accountId'),
    c('key', 'fld.account', (r) => {
      const k = (r.key ?? {}) as Row;
      return [k.period, k.line, k.holder, k.stage].filter((x) => x && x !== '-').join(' / ');
    }),
    money('total'),
    money('distributed'),
    money('committed'),
    money('encumbered'),
    money('consumed'),
    money('available'),
    c('state'),
  ],
  rowKey: ['accountId'],
  detail: '/ledger/accounts/{accountId}?ledger={ledger}',
  toolbar: [
    {
      id: 'operation',
      label: 'act.ledgerOperation',
      path: '/ledger/{ledger}/operations',
      fields: [
        f.select('operation', OPS, undefined, true),
        { key: 'account', label: 'fld.account', type: 'group', components: accountFields.map((x) => ({ ...x, key: `account.${x.key}` })) },
        f.money('amount', undefined, true),
        f.text('currency'),
        f.text('reference', undefined, false, { description: 'mod.ledger.referenceHint' }),
        f.text('auditRef'),
        f.date('asOf'),
        f.bool('fromCommitment'),
        f.bool('fromEncumbrance'),
        f.bool('holdIfShort'),
        f.json('target', 'fld.targetAccount'),
      ],
      showResult: true,
      hint: 'mod.ledger.operationHint',
    },
    { id: 'close', label: 'act.closePeriod', path: '/ledger/{ledger}/periods/{period}/close', fields: [f.text('period', undefined, true), f.text('nextPeriod'), f.date('asOf')], showResult: true, danger: true, hint: 'mod.ledger.closeHint' },
    { id: 'reconcile', label: 'act.reconcile', path: '/ledger/{ledger}/reconcile', fields: [f.text('period', undefined, true)], showResult: true },
  ],
};

const freezes: ScreenDef = {
  id: 'freezes',
  title: 'mod.ledger.freezes',
  list: '/ledger/{ledger}/freeze-orders',
  unpaged: true,
  filters: [ledgerFilter],
  requireFilters: ['ledger'],
  columns: [c('id'), c('period'), c('linePrefix'), c('holderPrefix'), c('stage'), c('reason'), c('orderedBy'), c('at')],
  rowKey: ['id'],
  create: { id: 'freeze', label: 'act.freeze', path: '/ledger/{ledger}/freeze-orders', fields: [f.text('id', 'fld.orderRef'), f.text('period'), f.text('linePrefix'), f.text('holderPrefix'), f.text('stage')], reason: 'body', danger: true, hint: 'mod.ledger.freezeHint' },
  actions: [{ id: 'release', label: 'act.releaseFreeze', method: 'DELETE', path: '/ledger/{ledger}/freeze-orders/{id}', reason: 'query' }],
};

const holds: ScreenDef = {
  id: 'holds',
  title: 'mod.ledger.holds',
  description: 'mod.ledger.holdsDesc',
  list: '/ledger/{ledger}/holds',
  unpaged: true,
  filters: [ledgerFilter, f.text('period'), f.text('line', 'fld.headOfAccount'), f.text('holder')],
  requireFilters: ['ledger'],
  columns: [c('reference'), c('operation'), money('amount'), c('actor'), c('asOf')],
  rowKey: ['reference'],
};

const STAGES = ['total', 'distributed', 'committed', 'encumbered', 'consumed', 'available'] as const;

/** Limit → stage chain → availability for the accounts of one head of account (bars per stage, table alternative). */
export function LedgerChain() {
  const { t } = useTranslation();
  const [ledger, setLedger] = useState('');
  const [line, setLine] = useState('');
  const [period, setPeriod] = useState('');
  const [q, setQ] = useState<{ ledger: string; line: string; period: string } | null>(null);
  const data = useAsync(() => http.page<Row>(`/ledger/${encodeURIComponent(q!.ledger)}/accounts`, { line: q!.line, period: q!.period || undefined, size: 100 }), [JSON.stringify(q)], !!q);
  const rows = data.data?.items ?? [];
  return (
    <Section title={t('mod.ledger.chain')}>
      <p className="hint">{t('mod.ledger.chainHint')}</p>
      <form
        className="filters"
        onSubmit={(e) => {
          e.preventDefault();
          setQ({ ledger, line, period });
        }}
      >
        <TextField label={t('fld.ledgerId')} value={ledger} onChange={setLedger} required />
        <TextField label={t('fld.headOfAccount')} value={line} onChange={setLine} required />
        <TextField label={t('fld.period')} value={period} onChange={setPeriod} />
        <button type="submit">{t('common.search')}</button>
      </form>
      <ProblemAlert error={data.error} onRetry={data.reload} />
      {q && data.data && rows.length === 0 && <Empty message={t('common.emptyNone')} />}
      {rows.map((r) => {
        const k = (r.key ?? {}) as Row;
        const label = [k.period, k.line, k.holder, k.stage].filter((x) => x && x !== '-').join(' / ') || String(r.accountId);
        const cur = String(r.currency ?? 'INR');
        return (
          <BarChart
            key={String(r.accountId)}
            title={`${label}${r.state && r.state !== 'OPEN' ? ` — ${t(`enum.${String(r.state)}`, { defaultValue: String(r.state) })}` : ''}`}
            rows={STAGES.map((s) => ({ label: t(`fld.${s}`), value: Number(r[s] ?? 0), tone: s === 'available' ? (Number(r[s] ?? 0) <= 0 ? 'bad' : 'ok') : undefined }))}
            format={(n) => fmtMoney(n, cur)}
          />
        );
      })}
    </Section>
  );
}

export function LedgerPage() {
  return <ModulePage title="mod.ledger.title" description="mod.ledger.desc" screens={[accounts, freezes, holds]} extra={[{ id: 'chain', label: 'mod.ledger.chain', node: <LedgerChain /> }]} />;
}

const billingLedger = f.text('ledger', 'fld.receivableLedger', true, { description: 'mod.billing.ledgerHint' });

const billAccounts: ScreenDef = {
  id: 'accounts',
  title: 'mod.billing.accounts',
  list: '/billing/{ledger}/accounts',
  unpaged: true,
  filters: [billingLedger, f.text('cycle')],
  requireFilters: ['ledger'],
  columns: [c('key', 'fld.consumer'), c('value', 'fld.accounts')],
  rowKey: ['key', 'consumer'],
  toolbar: [
    { id: 'invoice', label: 'act.raiseInvoice', path: '/billing/{ledger}/invoices', fields: [f.text('consumer', undefined, true), f.text('cycle', undefined, true), f.text('tariffId', undefined, true), f.text('period'), f.json('measures', 'fld.measures'), f.money('units'), f.num('dueDays')], showResult: true, hint: 'mod.billing.invoiceHint' },
    { id: 'collect', label: 'act.recordCollection', path: '/billing/{ledger}/collections', fields: [f.text('consumer', undefined, true), f.text('channel', undefined, true), f.text('paymentRef', undefined, true), f.money('amount', undefined, true)], showResult: true, hint: 'mod.billing.collectHint' },
    { id: 'waiver', label: 'act.recordWaiver', path: '/billing/{ledger}/waivers', fields: [f.text('consumer', undefined, true), f.text('head'), f.text('cycle'), f.text('period'), f.money('amount', undefined, true), f.text('orderRef', undefined, true)], showResult: true, danger: true },
    { id: 'refund', label: 'act.refund', path: '/billing/{ledger}/refunds', fields: [f.text('consumer', undefined, true), f.text('head'), f.text('cycle'), f.text('period'), f.text('channel', undefined, true), f.text('paymentRef', undefined, true), f.money('amount', undefined, true)], showResult: true, danger: true, hint: 'mod.billing.refundHint' },
  ],
};

const arrears: ScreenDef = {
  id: 'arrears',
  title: 'mod.billing.arrears',
  list: '/billing/{ledger}/consumers/{consumer}/arrears',
  unpaged: true,
  filters: [billingLedger, f.text('consumer', undefined, true)],
  requireFilters: ['ledger', 'consumer'],
  columns: [c('key', 'fld.head'), c('value', 'fld.amount')],
  rowKey: ['key'],
  toolbar: [{ id: 'penalty', label: 'act.levyPenalties', path: '/billing/{ledger}/consumers/{consumer}/penalties', fields: [f.text('tariffId', undefined, true), f.text('period')], showResult: true }],
};

export function BillingPage() {
  return <ModulePage title="mod.billing.title" description="mod.billing.desc" screens={[billAccounts, arrears]} />;
}
