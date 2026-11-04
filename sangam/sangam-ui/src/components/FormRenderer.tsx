// SPDX-License-Identifier: Apache-2.0
/**
 * Sangam form renderer (R47). Renders the form-js JSON schema subset (textfield, textarea, number, select, radio,
 * checkbox, checklist, datetime, text, group, filepicker, separator) with bilingual labels.
 *
 * Why not form-js-viewer at runtime: PK-11 / LIC-8 leave open whether citizen-facing forms may carry the bpmn.io
 * watermark. This renderer is Apache-2.0 Sangam code, so citizen and staff forms need no watermark, while the form
 * *builder* in the modeller uses form-js-editor with the watermark kept visible.
 *
 * Client validation is a hint; the server validates authoritatively (S04).
 */
import { useMemo, useRef, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { FieldChange, FormField, FormSchema } from '../api/types';

export type FormValues = Record<string, unknown>;

function labelOf(f: FormField, lang: string): string {
  return (lang === 'hi' ? f.i18n?.hi?.label : undefined) ?? f.label ?? f.key ?? '';
}
function descOf(f: FormField, lang: string): string | undefined {
  return (lang === 'hi' ? f.i18n?.hi?.description : undefined) ?? f.description;
}

function flatten(components: FormField[]): FormField[] {
  return components.flatMap((c) => (c.type === 'group' ? flatten(c.components ?? []) : [c]));
}

export function validateForm(schema: FormSchema, values: FormValues, t: (k: string, o?: Record<string, unknown>) => string): Record<string, string> {
  const errs: Record<string, string> = {};
  for (const f of flatten(schema.components)) {
    if (!f.key || f.readonly || f.disabled) continue;
    const v = values[f.key];
    const empty = v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0);
    const val = f.validate ?? {};
    if (val.required && empty) {
      errs[f.key] = t('form.required');
      continue;
    }
    if (empty) continue;
    if (typeof v === 'number') {
      if (val.min !== undefined && v < val.min) errs[f.key] = t('form.min', { n: val.min });
      if (val.max !== undefined && v > val.max) errs[f.key] = t('form.max', { n: val.max });
    }
    if (typeof v === 'string') {
      if (val.minLength !== undefined && v.length < val.minLength) errs[f.key] = t('form.minLength', { n: val.minLength });
      if (val.maxLength !== undefined && v.length > val.maxLength) errs[f.key] = t('form.maxLength', { n: val.maxLength });
      if (val.pattern) {
        try {
          if (!new RegExp(`^(?:${val.pattern})$`).test(v)) errs[f.key] = t('form.pattern');
        } catch {
          /* invalid pattern from the model: server decides */
        }
      }
    }
  }
  return errs;
}

export function FormRenderer({
  schema,
  values,
  onChange,
  errors = {},
  readOnly,
  changed,
  onFiles,
}: {
  schema: FormSchema;
  values: FormValues;
  onChange: (v: FormValues) => void;
  errors?: Record<string, string>;
  readOnly?: boolean;
  changed?: FieldChange[];
  onFiles?: (key: string, files: File[]) => void;
}) {
  const { t, i18n } = useTranslation();
  const lang = i18n.language;
  const changedKeys = useMemo(() => new Set((changed ?? []).map((c) => c.field)), [changed]);
  const summaryRef = useRef<HTMLDivElement>(null);
  const errorKeys = Object.keys(errors);
  const set = (k: string, v: unknown) => onChange({ ...values, [k]: v });

  const render = (f: FormField, idx: number): ReactNode => {
    const k = f.key ?? f.id ?? `f${idx}`;
    const id = `field-${k}`;
    const label = labelOf(f, lang);
    const desc = descOf(f, lang);
    const err = f.key ? errors[f.key] : undefined;
    const ro = readOnly || f.readonly || f.disabled;
    const req = !!f.validate?.required;
    const describedBy = [desc ? `${id}-d` : '', err ? `${id}-e` : ''].filter(Boolean).join(' ') || undefined;
    const marker = f.key && changedKeys.has(f.key) ? (
      <span className="badge badge-warn" title={t('task.changedSinceSeen')}>
        {t('task.changed')}
      </span>
    ) : null;
    const wrap = (control: ReactNode, asFieldset = false) =>
      asFieldset ? (
        <fieldset key={k} className={`field${err ? ' has-error' : ''}`} aria-describedby={describedBy}>
          <legend>
            {label}
            {req && <span className="req"> *</span>} {marker}
          </legend>
          {desc && (
            <p className="hint" id={`${id}-d`}>
              {desc}
            </p>
          )}
          {control}
          {err && (
            <p className="field-error" id={`${id}-e`}>
              {err}
            </p>
          )}
        </fieldset>
      ) : (
        <div key={k} className={`field${err ? ' has-error' : ''}`}>
          <label htmlFor={id}>
            {label}
            {req && <span className="req"> *</span>} {marker}
          </label>
          {desc && (
            <p className="hint" id={`${id}-d`}>
              {desc}
            </p>
          )}
          {control}
          {err && (
            <p className="field-error" id={`${id}-e`}>
              {err}
            </p>
          )}
        </div>
      );
    const common = { id, 'aria-describedby': describedBy, 'aria-invalid': !!err, required: req, disabled: !!ro };
    const value = f.key ? values[f.key] : undefined;
    switch (f.type) {
      case 'text':
        return (
          <p key={k} className="form-text">
            {(lang === 'hi' ? f.i18n?.hi?.text : undefined) ?? f.text}
          </p>
        );
      case 'separator':
        return <hr key={k} />;
      case 'group':
        return (
          <fieldset key={k} className="group">
            <legend>{label}</legend>
            {(f.components ?? []).map(render)}
          </fieldset>
        );
      case 'textarea':
        return wrap(<textarea {...common} rows={4} value={String(value ?? '')} onChange={(e) => set(f.key!, e.target.value)} />);
      case 'number':
        return wrap(
          <input
            {...common}
            type="number"
            inputMode="decimal"
            min={f.validate?.min}
            max={f.validate?.max}
            value={value === undefined || value === null ? '' : String(value)}
            onChange={(e) => set(f.key!, e.target.value === '' ? undefined : Number(e.target.value))}
          />,
        );
      case 'select':
        return wrap(
          <select {...common} value={String(value ?? '')} onChange={(e) => set(f.key!, e.target.value)}>
            <option value="">{t('form.choose')}</option>
            {(f.values ?? []).map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>,
        );
      case 'radio':
        return wrap(
          <div role="radiogroup" className="choices">
            {(f.values ?? []).map((o) => (
              <label key={o.value} className="choice">
                <input type="radio" name={k} value={o.value} checked={value === o.value} disabled={!!ro} onChange={() => set(f.key!, o.value)} /> {o.label}
              </label>
            ))}
          </div>,
          true,
        );
      case 'checklist': {
        const arr = Array.isArray(value) ? (value as string[]) : [];
        return wrap(
          <div className="choices">
            {(f.values ?? []).map((o) => (
              <label key={o.value} className="choice">
                <input
                  type="checkbox"
                  checked={arr.includes(o.value)}
                  disabled={!!ro}
                  onChange={(e) => set(f.key!, e.target.checked ? [...arr, o.value] : arr.filter((x) => x !== o.value))}
                />{' '}
                {o.label}
              </label>
            ))}
          </div>,
          true,
        );
      }
      case 'checkbox':
        return (
          <div key={k} className={`check${err ? ' has-error' : ''}`}>
            <input {...common} type="checkbox" checked={Boolean(value)} onChange={(e) => set(f.key!, e.target.checked)} />
            <label htmlFor={id}>
              {label}
              {req && <span className="req"> *</span>} {marker}
            </label>
            {err && (
              <p className="field-error" id={`${id}-e`}>
                {err}
              </p>
            )}
          </div>
        );
      case 'datetime': {
        const type = f.subtype === 'time' ? 'time' : f.subtype === 'datetime' ? 'datetime-local' : 'date';
        return wrap(<input {...common} type={type} value={String(value ?? '')} onChange={(e) => set(f.key!, e.target.value)} />);
      }
      case 'filepicker':
        return wrap(
          <input
            {...common}
            type="file"
            multiple
            onChange={(e) => {
              const files = Array.from(e.target.files ?? []);
              onFiles?.(f.key!, files);
              set(f.key!, files.map((x) => x.name));
            }}
          />,
        );
      case 'textfield':
      default:
        return wrap(
          <input
            {...common}
            type="text"
            autoComplete={f.sensitive ? 'off' : undefined}
            maxLength={f.validate?.maxLength}
            value={String(value ?? '')}
            onChange={(e) => set(f.key!, e.target.value)}
          />,
        );
    }
  };

  return (
    <div className="form-renderer">
      {errorKeys.length > 0 && (
        <div className="alert alert-bad" role="alert" tabIndex={-1} ref={summaryRef}>
          <strong>{t('form.errorSummary', { n: errorKeys.length })}</strong>
          <ul>
            {errorKeys.map((k) => (
              <li key={k}>
                <a href={`#field-${k}`}>
                  {labelOf(flatten(schema.components).find((f) => f.key === k) ?? { type: 'textfield', key: k }, lang)}: {errors[k]}
                </a>
              </li>
            ))}
          </ul>
        </div>
      )}
      {schema.components.map(render)}
    </div>
  );
}

/** Builds a simple read-only schema from plain variables (fallback when a task has no form). */
export function schemaFromVariables(vars: Record<string, unknown>): FormSchema {
  return {
    components: Object.keys(vars).map((k) => ({
      key: k,
      label: k,
      type: typeof vars[k] === 'number' ? 'number' : typeof vars[k] === 'boolean' ? 'checkbox' : 'textfield',
    })),
  };
}
