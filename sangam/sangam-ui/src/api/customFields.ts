// SPDX-License-Identifier: Apache-2.0
/**
 * M-031: tenant custom fields rendered in object forms.
 *
 * The server keeps custom field values as top-level `x_<name>` keys of the form's `data` and validates them with the
 * form's data schema (OperatonFormAdapter.java:153-164, TenantCustomFieldRegistry.extendSchema :301-322, shipped fields
 * win), but GET /forms/{key} returns the shipped form only (OperatonFormAdapter.java:110-132). So a form screen asks
 * GET /custom-fields?objectType=form:<formKey>&on=<today> (form.read, TenantCustomFieldController.java:165-175) and
 * appends the fields in force that the form does not already have. Only `form:<formKey>` object types exist
 * (TenantCustomFieldsWiring.java:21-34).
 *
 * The field-schema presence check: nothing changes for a form without custom fields, or when the caller may not read
 * them (403), the module is absent (501) or the list fails; the server stays the judge of the submitted data.
 * Schema profile (TenantCustomFieldRegistry.java:44-48): string (format decimal, integer-string, date, date-time,
 * email; enum, minLength, maxLength, pattern), integer (minimum, maximum), boolean, array (items with an enum become a
 * checklist; other arrays have no form control and are left to the server).
 */
import { useMemo } from 'react';
import { http } from './client';
import { useAsync } from '../lib/hooks';
import { todayIso } from '../i18n';
import type { FormField, FormSchema } from './types';

/** CustomField (sangam-api.yaml; TenantCustomFieldController.java:53-60). */
export interface CustomField {
  objectType: string;
  key: string;
  version: number;
  label?: string;
  required?: boolean;
  readOnly?: boolean;
  schema: Record<string, unknown>;
  effectiveFrom: string;
  effectiveTo?: string | null;
}

/**
 * Client hints for the string formats the server checks (FormSchemaValidator.java:138-174). date-time is an
 * OffsetDateTime (:160-162), so it is typed as an ISO instant with its offset rather than picked as a local time.
 */
const PATTERNS: Record<string, string> = {
  decimal: '-?\\d+(\\.\\d+)?',
  'integer-string': '-?\\d+',
  'date-time': '\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})',
};

/** One custom field as a form-js field, or null when the schema has no form control. */
export function customFieldToFormField(cf: CustomField): FormField | null {
  const s = cf.schema ?? {};
  const type = s.type;
  const label = cf.label || (typeof s.title === 'string' ? s.title : '') || cf.key;
  const base: FormField = {
    key: cf.key,
    label,
    type: 'textfield',
    description: typeof s.description === 'string' ? s.description : undefined,
    readonly: cf.readOnly || undefined,
  };
  const validate: NonNullable<FormField['validate']> = {};
  if (cf.required) validate.required = true;
  const num = (v: unknown) => (typeof v === 'number' ? v : undefined);
  const enumValues = Array.isArray(s.enum) ? (s.enum as unknown[]).map(String) : null;
  let out: FormField;
  if (type === 'boolean') out = { ...base, type: 'checkbox' };
  else if (type === 'integer') {
    if (num(s.minimum) !== undefined) validate.min = num(s.minimum);
    if (num(s.maximum) !== undefined) validate.max = num(s.maximum);
    out = { ...base, type: 'number' };
  } else if (type === 'array') {
    const items = (s.items ?? {}) as Record<string, unknown>;
    if (!Array.isArray(items.enum)) return null;
    out = { ...base, type: 'checklist', values: (items.enum as unknown[]).map((v) => ({ value: String(v), label: String(v) })) };
  } else if (type === 'string') {
    if (enumValues) out = { ...base, type: 'select', values: enumValues.map((v) => ({ value: v, label: v })) };
    else if (s.format === 'date') out = { ...base, type: 'datetime', subtype: 'date' };
    else {
      out = { ...base, type: 'textfield' };
      const pattern = typeof s.pattern === 'string' ? s.pattern : typeof s.format === 'string' ? PATTERNS[s.format] : undefined;
      if (pattern) validate.pattern = pattern;
    }
    if (num(s.minLength) !== undefined) validate.minLength = num(s.minLength);
    if (num(s.maxLength) !== undefined) validate.maxLength = num(s.maxLength);
  } else return null;
  return Object.keys(validate).length ? { ...out, validate } : out;
}

function keysOf(components: FormField[]): Set<string> {
  const out = new Set<string>();
  const walk = (cs: FormField[]) => cs.forEach((c) => (c.key && out.add(c.key), c.components && walk(c.components)));
  walk(components);
  return out;
}

/** The form with the custom fields it does not already have appended (shipped fields win, as on the server). */
export function withCustomFields(schema: FormSchema, fields: CustomField[]): FormSchema {
  if (!fields.length) return schema;
  const have = keysOf(schema.components);
  const extra = fields
    .filter((f) => !have.has(f.key))
    .sort((a, b) => a.key.localeCompare(b.key))
    .map(customFieldToFormField)
    .filter((x): x is FormField => !!x);
  return extra.length ? { ...schema, components: [...schema.components, ...extra] } : schema;
}

/** Custom fields in force today for a form; empty when there are none or they cannot be read. */
export async function customFieldsFor(formKey: string, signal?: AbortSignal): Promise<CustomField[]> {
  try {
    const r = await http.get<CustomField[]>('/custom-fields', { objectType: `form:${formKey}`, on: todayIso(), size: 500 }, signal);
    return Array.isArray(r) ? r.filter((x) => x.objectType === `form:${formKey}`) : [];
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    return [];
  }
}

/** The form with its custom fields; the shipped form is shown as is until (and unless) they arrive. */
export function useWithCustomFields(schema: FormSchema, formKey: string | undefined): FormSchema {
  const cfs = useAsync((signal) => customFieldsFor(formKey!, signal), [formKey], !!formKey);
  return useMemo(() => (cfs.data?.length ? withCustomFields(schema, cfs.data) : schema), [schema, cfs.data]);
}
