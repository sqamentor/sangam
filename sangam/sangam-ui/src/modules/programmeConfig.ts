// SPDX-License-Identifier: Apache-2.0
/**
 * X1-01: the programme-configuration publish form (PUT /programme-config, ProgrammeController.java:125-129). The body is
 * the configuration itself (ProgrammeConfig.parse, ProgrammeConfig.java:53-78). fiscalYearStartMonth (1..12) is
 * REQUIRED: without it every publish is refused (SL-125, ProgrammeConfig.java:221-235, no fiscal year assumed).
 * The other top-level keys (units, variance, physicalProgress) go through the form's "more fields" JSON.
 *
 * Not prefilled: the contract has no read of the current configuration. GET /programme-config/history returns only
 * version, contentHash, current, definedBy and definedAt (IndicatorRegistry.java:167-171). See the W2-C REQUEST on the
 * sync board.
 */
import type { FormField } from '../api/types';
import { fiscalStartDayField } from './fiscalDay';

export const MONTHS = Array.from({ length: 12 }, (_, i) => String(i + 1));

export function programmeConfigFields(): FormField[] {
  return [
    {
      key: 'fiscalYearStartMonth',
      label: 'fld.fiscalYearStartMonth',
      description: 'fld.fiscalYearStartMonthHint',
      type: 'select',
      values: MONTHS.map((m) => ({ value: m, label: `opt.fiscalMonth.${m}` })),
      validate: { required: true },
      // sent as the integer the contract types (ProgrammePublishConfigBody.fiscalYearStartMonth, contract 217C21BE)
      json: true,
    },
    fiscalStartDayField(),
    { key: 'indicators', label: 'fld.indicators', type: 'textarea', json: true, description: 'fld.jsonHint', validate: { required: true } },
    { key: 'returns', label: 'fld.returns', type: 'textarea', json: true, description: 'fld.jsonHint' },
    { key: 'programmeKinds', label: 'fld.kinds', type: 'textarea', json: true, description: 'fld.jsonHint' },
  ];
}
