// SPDX-License-Identifier: Apache-2.0
/**
 * Number-series form and columns of the registry screen (SL-126). The field names are the server's own:
 * {@code NumberSeriesDefinition.from} reads {@code reset} and {@code fiscalStartMonth}, and every response
 * ({@code toMap}) writes the same names, so the form, the list and the API use one name end to end (the form used to
 * send {@code resetPolicy}, which the server ignored: a FISCAL_YEAR series silently became NEVER).
 * A FISCAL_YEAR reset needs its start month (SL-125: no fiscal year is assumed); the month is offered only then.
 */
import type { FormField } from '../api/types';
import type { FormValues } from '../components/FormRenderer';

export const NUMBER_SERIES_RESETS = ['NEVER', 'YEARLY', 'FISCAL_YEAR', 'MONTHLY'];

const MONTHS = Array.from({ length: 12 }, (_, i) => String(i + 1));

/** The fiscal start month choices: offered only for the FISCAL_YEAR reset (otherwise none, and a picked one is dropped). */
export function fiscalStartMonthOptions(values: FormValues): { value: string; label: string }[] {
  return values.reset === 'FISCAL_YEAR' ? MONTHS.map((m) => ({ value: m, label: `opt.fiscalMonth.${m}` })) : [];
}

/** Fields of the define-series toolbar action (keys are the API's names). */
export function numberSeriesFields(): FormField[] {
  return [
    { key: 'seriesKey', label: 'fld.seriesKey', type: 'textfield', validate: { required: true } },
    { key: 'pattern', label: 'fld.pattern', type: 'textfield', validate: { required: true } },
    { key: 'reset', label: 'fld.resetPolicy', type: 'select', values: NUMBER_SERIES_RESETS.map((o) => ({ value: o, label: `opt.${o}` })) },
    { key: 'fiscalStartMonth', label: 'fld.fiscalStartMonth', description: 'fld.fiscalStartMonthHint', type: 'select', values: [] },
    { key: 'scope', label: 'fld.scope', type: 'textfield' },
  ];
}
