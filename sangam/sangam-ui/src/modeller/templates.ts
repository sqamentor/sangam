// SPDX-License-Identifier: Apache-2.0
/**
 * M-036 (NG-19, R46): element templates in the BPMN editor, on the modeller's template service
 * (sangam-modeller .../template/ElementTemplates.java; REST in ModellerController.java:248-295).
 *
 * A template is Sangam's own versioned format, not the bpmn-js element-templates schema (ElementTemplates.java:18-23):
 *   {"id", "version", "appliesTo": ["bpmn:ServiceTask"], "fields": [{"name", "binding", "required", "pattern",
 *    "default", "choices"}]}
 * POST /templates/{id}/apply ?version {elementType, values} validates the values on the server and answers
 * Applied {properties: {binding → value, plus sangam:templateId and sangam:templateVersion}, errors}
 * (ElementTemplates.apply, :80-105). Bindings start with operaton:, bpmn:, input:, output: or form: (:38).
 *
 * The editor writes what it can write faithfully without an Operaton moddle descriptor:
 * - bpmn:<property> → the BPMN property itself (bpmn-js modeling.updateProperties);
 * - operaton:<attribute> → an extension attribute in the Operaton namespace that every shipped model declares
 *   (xmlns:operaton="http://operaton.org/schema/1.0/bpmn");
 * - sangam:<attribute> → an extension attribute in the Sangam namespace (RoutingPolicy.java:32).
 * input:, output: and form: bindings need Operaton extension elements (inputOutput etc.) that bpmn-js cannot build
 * without that descriptor; they are listed for the modeller to add and are not written silently.
 */

export const OPERATON_NS = 'http://operaton.org/schema/1.0/bpmn';
export const SANGAM_NS = 'http://sangam.org/schema/1.0/bpmn';

/** TemplateView (ModellerController.java:96). */
export interface TemplateView {
  key: string;
  id?: string;
  name?: string;
  kind: 'ELEMENT' | 'PALETTE' | 'FORM_COMPONENT' | 'PROCESS' | 'DMN';
  version: number;
  appliesTo?: string[];
  document?: Record<string, unknown>;
}

/** ElementTemplates.Field (ElementTemplates.java:29-30), as written in the template document. */
export interface TemplateField {
  name: string;
  binding: string;
  required?: boolean;
  pattern?: string;
  default?: unknown;
  choices?: string[];
}

/** ElementTemplates.Applied (ElementTemplates.java:35). */
export interface Applied {
  properties: Record<string, unknown>;
  errors: string[];
}

/** ELEMENT templates for this element type: those that name it, or name no type at all (ElementTemplates.java:82). */
export function applicable(list: TemplateView[], elementType: string): TemplateView[] {
  return list.filter((t) => t.kind === 'ELEMENT' && (!t.appliesTo?.length || t.appliesTo.includes(elementType)));
}

export function templateFields(t: TemplateView): TemplateField[] {
  const raw = t.document?.fields;
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((f): f is Record<string, unknown> => !!f && typeof f === 'object')
    .map((f) => ({
      name: String(f.name),
      binding: String(f.binding),
      required: f.required === true,
      pattern: typeof f.pattern === 'string' ? f.pattern : undefined,
      default: f.default,
      choices: Array.isArray(f.choices) ? f.choices.map(String) : undefined,
    }));
}

export interface Writes {
  /** BPMN properties of the element (bpmn:name → name). */
  bpmn: Record<string, unknown>;
  /** Extension attributes, qualified (operaton:topic, sangam:templateId). */
  attrs: Record<string, string>;
  /** Bindings the editor does not write (input:, output:, form:), with their values. */
  manual: Record<string, unknown>;
}

/** Splits the server's applied properties into what the editor writes and what is left to the modeller. */
export function planWrites(properties: Record<string, unknown>): Writes {
  const out: Writes = { bpmn: {}, attrs: {}, manual: {} };
  for (const [k, v] of Object.entries(properties)) {
    const i = k.indexOf(':');
    const prefix = i > 0 ? k.slice(0, i) : '';
    const name = k.slice(i + 1);
    if (prefix === 'bpmn' && name) out.bpmn[name] = v;
    else if ((prefix === 'operaton' || prefix === 'sangam') && name) out.attrs[k] = typeof v === 'string' ? v : JSON.stringify(v);
    else out.manual[k] = v;
  }
  return out;
}

/** The namespace declarations the written attributes need on bpmn:Definitions. */
export function namespacesFor(attrs: Record<string, string>): Record<string, string> {
  const ns: Record<string, string> = {};
  for (const k of Object.keys(attrs)) {
    if (k.startsWith('operaton:')) ns['xmlns:operaton'] = OPERATON_NS;
    if (k.startsWith('sangam:')) ns['xmlns:sangam'] = SANGAM_NS;
  }
  return ns;
}

// ------------------------------------------------------------------ PALETTE (owner decision 58, C3-14)

/**
 * A PALETTE template is the ELEMENT format whose appliesTo names exactly one BPMN type: the palette entry creates an
 * element of that type, and its fields go through the same POST /templates/{id}/apply. A PALETTE document that names
 * no type or several is not an entry (the server does not check this per kind yet; REQUEST to W3-B).
 */
export function paletteEntries(list: TemplateView[]): TemplateView[] {
  return list.filter((t) => t.kind === 'PALETTE' && paletteType(t) !== undefined);
}

/** The BPMN type a PALETTE template creates, e.g. bpmn:ServiceTask; undefined when it does not name exactly one. */
export function paletteType(t: TemplateView): string | undefined {
  const a = t.appliesTo?.length ? t.appliesTo : Array.isArray(t.document?.appliesTo) ? (t.document!.appliesTo as unknown[]).map(String) : [];
  return a.length === 1 && /^bpmn:[A-Za-z]+$/.test(a[0]) ? a[0] : undefined;
}

// ------------------------------------------------------------------ FORM_COMPONENT (owner decision 58, C3-14)

/** A form-js component as stored in a form schema (only the parts Sangam reads; the rest is kept as is). */
export type FormJsComponent = Record<string, unknown> & { id?: string; key?: string; type?: string; components?: FormJsComponent[] };

/** FORM_COMPONENT document: {id, name, components: [form-js component JSON]}. */
export interface FormComponentTemplate {
  key: string;
  name: string;
  version: number;
  components: FormJsComponent[];
}

export function formComponentTemplates(list: TemplateView[]): FormComponentTemplate[] {
  return list.flatMap((t) => {
    const c = t.document?.components;
    if (t.kind !== 'FORM_COMPONENT' || !Array.isArray(c) || c.length === 0) return [];
    if (!c.every((x) => !!x && typeof x === 'object' && !Array.isArray(x) && typeof (x as FormJsComponent).type === 'string')) return [];
    return [{ key: t.key, name: t.name ?? t.key, version: t.version, components: c as FormJsComponent[] }];
  });
}

function walk(cs: FormJsComponent[] | undefined, fn: (c: FormJsComponent) => void): void {
  for (const c of cs ?? []) {
    fn(c);
    walk(c.components, fn);
  }
}

export type InsertResult = { schema: Record<string, unknown>; conflicts: [] } | { schema?: undefined; conflicts: string[] };

/**
 * Appends a FORM_COMPONENT's components to a form-js schema. Ids are layout identity only, so each inserted component
 * (nested ones included) gets a fresh id that is not in the form. Keys bind process variables, so they are never
 * renamed: a key the form already uses is a conflict and nothing is inserted. The input schema is not changed.
 */
export function insertComponents(schema: Record<string, unknown>, components: FormJsComponent[], newId: () => string): InsertResult {
  const current = (Array.isArray(schema.components) ? schema.components : []) as FormJsComponent[];
  const ids = new Set<string>();
  const keys = new Set<string>();
  walk(current, (c) => {
    if (c.id) ids.add(String(c.id));
    if (c.key) keys.add(String(c.key));
  });
  const copy = JSON.parse(JSON.stringify(components)) as FormJsComponent[];
  const conflicts: string[] = [];
  const added = new Set<string>();
  walk(copy, (c) => {
    if (c.key) {
      const k = String(c.key);
      if (keys.has(k) || added.has(k)) conflicts.push(k);
      added.add(k);
    }
  });
  if (conflicts.length) return { conflicts: [...new Set(conflicts)] };
  walk(copy, (c) => {
    let id = newId();
    while (ids.has(id)) id = newId();
    ids.add(id);
    c.id = id;
  });
  return { schema: { ...schema, components: [...current, ...copy] }, conflicts: [] };
}

/** Client hint of a field value before the server's apply (which stays the judge). */
export function fieldError(f: TemplateField, v: string): 'required' | 'pattern' | 'choices' | null {
  if (!v) return f.required && (f.default === undefined || f.default === null || f.default === '') ? 'required' : null;
  if (f.pattern) {
    try {
      if (!new RegExp(`^(?:${f.pattern})$`).test(v)) return 'pattern';
    } catch {
      /* the server's java.util.regex decides */
    }
  }
  if (f.choices?.length && !f.choices.includes(v)) return 'choices';
  return null;
}
