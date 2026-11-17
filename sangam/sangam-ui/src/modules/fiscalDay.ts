// SPDX-License-Identifier: Apache-2.0
/**
 * Owner decision 2026-11-12 (X1-03b): the optional day of the month the financial year starts on, 1..28, absent = the
 * 1st. One field definition for every form that sends it:
 * - PUT /registry/number-series/by-series-key?seriesKey= (RegistryRegistryDefineSeriesBody.fiscalStartDay; NumberSeriesDefinition);
 * - PUT /programme-config (ProgrammePublishConfigBody.fiscalStartDay; ProgrammeConfig, default 1).
 * The validity block of POST /registry/types (ValidityRule.fiscalStartDay) has no form: it is part of the JSON type
 * definition, so its publish dialog says so in its hint (mod.registry.publishTypeHintFiscal).
 * Contract sangam-api.yaml 217C21BE…: optional integer; the server enforces 1..28 (ValidityRule.java:45-46).
 */
import type { FormField } from '../api/types';

export const FISCAL_START_DAY_MIN = 1;
export const FISCAL_START_DAY_MAX = 28;

export function fiscalStartDayField(): FormField {
  return {
    key: 'fiscalStartDay',
    label: 'fld.fiscalStartDay',
    description: 'fld.fiscalStartDayHint',
    type: 'number',
    validate: { min: FISCAL_START_DAY_MIN, max: FISCAL_START_DAY_MAX },
  };
}
