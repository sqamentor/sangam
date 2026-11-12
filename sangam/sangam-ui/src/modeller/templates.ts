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
