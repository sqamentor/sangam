// SPDX-License-Identifier: Apache-2.0
/**
 * SL-166 (5), W3-M REQUEST 2026-11-12T12:04:42Z: a declared processing purpose is a purpose CODE, never free text. The
 * server passes the X-Purpose header to the compliance purpose binding as the purpose key (RegistryPortBridges.java
 * :143-151), and CMP_PURPOSE keys purposes by purpose_code {str64} with localized names in names_json
 * (ComplianceSchema.java:23). The field lists the tenant's purposes (GET /compliance/purposes, listCompliancePurposes,
 * compliance.read) by their name in the reader's language and gives the code. A caller who may not list purposes
 * (403) types the code; it must be printable ASCII without spaces (an HTTP header) and at most 64 characters.
 */
import { useTranslation } from 'react-i18next';
import { ApiError, http } from '../api/client';
import { useAsync } from '../lib/hooks';
import { ProblemAlert, SelectField, TextField } from '../components/ui';

/** The contract CompliancePurpose, as far as the picker reads it. */
export interface PurposeOption {
  code: string;
  names?: Record<string, string>;
}

/** A purpose code as a header can carry it and CMP_PURPOSE.purpose_code can hold it. */
export function isPurposeCode(v: string): boolean {
  return /^[\x21-\x7e]{1,64}$/.test(v);
}

/** The purpose's name in the reader's language, else English, else the code; the code is always shown too. */
export function purposeLabel(p: PurposeOption, lang: string): string {
  const n = p.names ?? {};
  const name = n[lang] ?? n[lang.split('-')[0]] ?? n.en;
  return name && name !== p.code ? `${name} (${p.code})` : p.code;
}

export function PurposeCodeField({ value, onChange }: { value: string; onChange: (code: string) => void }) {
  const { t, i18n } = useTranslation();
  const list = useAsync((signal) => http.get<PurposeOption[]>('/compliance/purposes', undefined, signal), []);
  const purposes = (Array.isArray(list.data) ? list.data : []).filter((p) => p && typeof p.code === 'string' && isPurposeCode(p.code));
  const refused = list.error instanceof ApiError && (list.error.status === 403 || list.error.status === 404);
  if (list.loading && !list.data) return null;
  if (purposes.length > 0) {
    return (
      <SelectField
        label={t('mod.common.purpose')}
        hint={t('mod.common.purposeHint')}
        required
        value={value}
        onChange={onChange}
        placeholder={t('mod.common.purposeChoose')}
        options={purposes.map((p) => ({ value: p.code, label: purposeLabel(p, i18n.language || 'en') }))}
      />
    );
  }
  const typed = value.trim();
  return (
    <>
      {list.error && !refused && <ProblemAlert error={list.error} onRetry={list.reload} />}
      <TextField
        label={t('mod.common.purposeCode')}
        hint={t('mod.common.purposeCodeHint')}
        required
        value={value}
        onChange={onChange}
        error={typed && !isPurposeCode(typed) ? t('mod.common.purposeCodeInvalid') : undefined}
        autoComplete="off"
      />
    </>
  );
}
