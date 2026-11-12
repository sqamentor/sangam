// SPDX-License-Identifier: Apache-2.0
/**
 * Number-series form and columns of the registry screen (SL-126). The field names are the server's own:
 * {@code NumberSeriesDefinition.from} reads {@code reset} and {@code fiscalStartMonth}, and every response
 * ({@code toMap}) writes the same names, so the form, the list and the API use one name end to end (the form used to
 * send {@code resetPolicy}, which the server ignored: a FISCAL_YEAR series silently became NEVER).
 * A FISCAL_YEAR reset or a {fy} / {fyStart} pattern token needs its start month (SL-125: no fiscal year is assumed);
 * the month is offered only then (X1-02).
 */
import type { FormField } from '../api/types';
import type { FormValues } from '../components/FormRenderer';
import { fiscalStartDayField } from './fiscalDay';

/** The server's resets (NumberSeriesDefinition.java:72); DAILY is used by billing's RECEIPT series (X1-02). */
export const NUMBER_SERIES_RESETS = ['NEVER', 'YEARLY', 'FISCAL_YEAR', 'MONTHLY', 'DAILY'];

const MONTHS = Array.from({ length: 12 }, (_, i) => String(i + 1));

/** Pattern tokens, as the server reads them (NumberSeriesDefinition.TOKEN, :59). */
const TOKEN = /\{([A-Za-z]+)(?::([A-Za-z0-9_.-]+))?\}/g;

/**
 * Whether the series has fiscal-year semantics, exactly as NumberSeriesDefinition.usesFiscalYear (:47-57): the
 * FISCAL_YEAR reset, or a {fy} / {fyStart} token in the pattern. Then fiscalStartMonth is required (:40-43).
 */
export function usesFiscalYear(values: FormValues): boolean {
  if (values.reset === 'FISCAL_YEAR') return true;
  const pattern = typeof values.pattern === 'string' ? values.pattern : '';
  for (const m of pattern.matchAll(TOKEN)) if (m[1] === 'fy' || m[1] === 'fyStart') return true;
  return false;
}

/** The fiscal start month choices: offered only when the series uses the fiscal year (otherwise none, and a picked one is dropped). */
export function fiscalStartMonthOptions(values: FormValues): { value: string; label: string }[] {
  return usesFiscalYear(values) ? MONTHS.map((m) => ({ value: m, label: `opt.fiscalMonth.${m}` })) : [];
}

/** Fields of the define-series toolbar action (keys are the API's names). */
export function numberSeriesFields(): FormField[] {
  return [
    { key: 'seriesKey', label: 'fld.seriesKey', type: 'textfield', validate: { required: true } },
    { key: 'pattern', label: 'fld.pattern', type: 'textfield', validate: { required: true } },
    { key: 'reset', label: 'fld.resetPolicy', type: 'select', values: NUMBER_SERIES_RESETS.map((o) => ({ value: o, label: `opt.${o}` })) },
    // json: the select's text '7' is sent as the integer 7 (contract 217C21BE: fiscalStartMonth and fiscalStartDay are integers)
    { key: 'fiscalStartMonth', label: 'fld.fiscalStartMonth', description: 'fld.fiscalStartMonthHint', type: 'select', values: [], json: true },
    fiscalStartDayField(),
    { key: 'scope', label: 'fld.scope', type: 'textfield' },
  ];
}
