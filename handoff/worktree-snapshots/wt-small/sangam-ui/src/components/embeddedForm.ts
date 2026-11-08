// SPDX-License-Identifier: Apache-2.0
/**
 * CSP-safe handling of Operaton form keys. The script CSP forbids inline script, so an embedded HTML form is never
 * executed: it is parsed inertly with DOMParser, its cam-variable-name inputs become a Sangam form schema, and its
 * scripts are reported as ignored. External form keys are links only, restricted to http(s) or same-origin paths.
 */
import type { FormField, FormSchema, TaskForm } from '../api/types';

export type FormKeyKind = 'embedded' | 'external' | 'other';

export function classifyFormKey(key?: string): FormKeyKind {
  if (!key) return 'other';
  if (/^embedded:/.test(key)) return 'embedded';
  if (/^(app:|https?:|\/)/.test(key)) return 'external';
  return 'other';
}

export function externalFormUrl(key: string, taskId: string): string | null {
  const raw = key.replace(/^app:/, '/');
  let u: URL;
  try {
    u = new URL(raw, window.location.origin);
  } catch {
    return null;
  }
  if (u.protocol !== 'https:' && u.protocol !== 'http:') return null;
  u.searchParams.set('taskId', taskId);
  return u.href;
}

const TYPE: Record<string, FormField['type']> = { Boolean: 'checkbox', Long: 'number', Integer: 'number', Short: 'number', Double: 'number', Date: 'datetime' };

export function embeddedHtmlToSchema(html: string): { schema: FormSchema; scriptsIgnored: boolean } {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const scriptsIgnored = doc.querySelector('script, [cam-script]') !== null;
  const components: FormField[] = [];
  doc.querySelectorAll('[cam-variable-name]').forEach((el) => {
    const key = el.getAttribute('cam-variable-name')!;
    const id = el.getAttribute('id');
    const label = (id && doc.querySelector(`label[for="${CSS.escape(id)}"]`)?.textContent?.trim()) || el.closest('label')?.textContent?.trim() || key;
    const required = el.hasAttribute('required');
    const readonly = el.hasAttribute('readonly') || el.hasAttribute('disabled');
    const validate = { required };
    if (el.tagName === 'SELECT') {
      const values = [...el.querySelectorAll('option')].map((o) => ({ value: o.value, label: o.textContent ?? o.value }));
      components.push({ key, label, type: 'select', values, validate, readonly });
    } else if (el.tagName === 'TEXTAREA') {
      components.push({ key, label, type: 'textarea', validate, readonly });
    } else {
      const type = TYPE[el.getAttribute('cam-variable-type') ?? ''] ?? (el.getAttribute('type') === 'checkbox' ? 'checkbox' : 'textfield');
      components.push({ key, label, type, subtype: type === 'datetime' ? 'datetime' : undefined, validate, readonly });
    }
  });
  return { schema: { components }, scriptsIgnored };
}
