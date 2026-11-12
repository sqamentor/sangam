// SPDX-License-Identifier: Apache-2.0
/**
 * M-036: the element-template panel of the BPMN editor (see modeller/templates.ts for the format and the write rules).
 * Lists the ELEMENT templates that apply to the selected element (GET /templates ?kind=ELEMENT, package.read,
 * ModellerController.java:248-253), asks for the template's fields, lets the server validate and resolve them
 * (POST /templates/{id}/apply ?version, package.read, :287-295) and writes the result into the diagram as one undoable
 * command (bpmn-js modeling.updateProperties). Without package.read, or without templates, the panel is not shown.
 */
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, http } from '../api/client';
import { announce, useAction, useAsync } from '../lib/hooks';
import { applicable, fieldError, namespacesFor, planWrites, templateFields, type Applied, type TemplateField, type TemplateView } from '../modeller/templates';
import { JsonView, Notice, ProblemAlert, SelectField, TextField } from './ui';
import { OPERATION_REF_BINDING, OperationRefPicker } from './OperationRefPicker';

/** The parts of a bpmn-js Modeler the panel uses (stable public services of bpmn-js 17). */
export interface ModelerLike {
  get: (name: string) => unknown;
  getDefinitions?: () => { $attrs?: Record<string, string> } | undefined;
}

export interface Selected {
  id: string;
  type: string;
}

/** GET /templates ?kind: the latest version of each template of that kind; none without package.read (403). */
export async function templatesOfKind(kind: TemplateView['kind'], signal?: AbortSignal): Promise<TemplateView[]> {
  try {
    const r = await http.get<TemplateView[]>('/templates', { kind }, signal);
    return Array.isArray(r) ? r : [];
  } catch (e) {
    if (e instanceof ApiError && (e.status === 403 || e.status === 404 || e.status === 501)) return [];
    throw e;
  }
}

const elementTemplates = (signal?: AbortSignal) => templatesOfKind('ELEMENT', signal);

/** POST /templates/{id}/apply ?version {elementType, values}: the server validates and resolves the bindings. */
export function applyTemplate(tpl: TemplateView, elementType: string, values: Record<string, string>): Promise<Applied> {
  return http.post<Applied>(`/templates/${encodeURIComponent(tpl.key)}/apply?version=${tpl.version}`, { elementType, values });
}

/**
 * Writes the server's applied properties into one diagram element (bpmn-js modeling.updateProperties, undoable) and
 * returns the bindings the editor does not write (input:, output:, form:), or undefined when there are none.
 */
export function writeApplied(modeler: ModelerLike, element: unknown, properties: Record<string, unknown>): Record<string, unknown> | undefined {
  const w = planWrites(properties);
  const defs = modeler.getDefinitions?.();
  if (defs) defs.$attrs = { ...(defs.$attrs ?? {}), ...namespacesFor(w.attrs) };
  if (element) (modeler.get('modeling') as { updateProperties: (e: unknown, p: Record<string, unknown>) => void }).updateProperties(element, { ...w.bpmn, ...w.attrs });
  return Object.keys(w.manual).length ? w.manual : undefined;
}

/**
 * The value inputs of a template's fields, with the client-side hints (the server stays the judge). M-068: inside a
 * package (packageId), a field bound to sangam:operationRef also offers the operation picker, which fills the field.
 */
export function TemplateFieldInputs({ fields, values, onChange, packageId }: { fields: TemplateField[]; values: Record<string, string>; onChange: (v: Record<string, string>) => void; packageId?: string }) {
  const set = (name: string) => (v: string) => onChange({ ...values, [name]: v });
  return (
    <>
      {fields.map((f) =>
        f.binding === OPERATION_REF_BINDING && packageId && !f.choices?.length ? (
          <div key={f.name} className="stack">
            <OperationRefPicker packageId={packageId} onPick={set(f.name)} />
            <FieldInput field={f} value={values[f.name]} onChange={set(f.name)} />
          </div>
        ) : (
          <FieldInput key={f.name} field={f} value={values[f.name]} onChange={set(f.name)} />
        ),
      )}
    </>
  );
}

/** One field: its choices as a select, else a text input checked by the field's client-side rules. */
function FieldInput({ field: f, value, onChange }: { field: TemplateField; value: string | undefined; onChange: (v: string) => void }) {
  const { t } = useTranslation();
  if (f.choices?.length) {
    return <SelectField label={f.name} required={f.required} value={value ?? ''} onChange={onChange} placeholder="" options={f.choices.map((c) => ({ value: c, label: c }))} />;
  }
  const e = value !== undefined ? fieldError(f, value) : null;
  return <TextField label={f.name} hint={f.binding} required={f.required} value={value ?? ''} onChange={onChange} error={e ? t(`tpl.err.${e}`) : undefined} />;
}

/** The fields' defaults as the initial values of the inputs. */
export function defaultValues(tpl: TemplateView | undefined): Record<string, string> {
  return Object.fromEntries((tpl ? templateFields(tpl) : []).filter((f) => f.default !== undefined && f.default !== null).map((f) => [f.name, String(f.default)]));
}

export function ElementTemplatePanel({ modeler, selected, packageId }: { modeler: ModelerLike | null; selected: Selected | null; packageId?: string }) {
  const { t } = useTranslation();
  const list = useAsync((signal) => elementTemplates(signal), []);
  const [key, setKey] = useState('');
  const [values, setValues] = useState<Record<string, string>>({});
  const [manual, setManual] = useState<Record<string, unknown>>();
  const [errors, setErrors] = useState<string[]>([]);
  const act = useAction();
  const options = useMemo(() => (selected ? applicable(list.data ?? [], selected.type) : []), [list.data, selected]);
  const chosen = options.find((x) => x.key === key);
  const fields = chosen ? templateFields(chosen) : [];
  if (!modeler || !selected || options.length === 0) return null;

  const apply = () =>
    void act
      .run(() => applyTemplate(chosen!, selected.type, values))
      .then((r) => {
        if (!r) return;
        setErrors(r.errors ?? []);
        if (r.errors?.length) return;
        const el = (modeler.get('elementRegistry') as { get: (id: string) => unknown }).get(selected.id);
        setManual(writeApplied(modeler, el, r.properties ?? {}));
        announce(t('tpl.applied'));
      });

  return (
    <section className="card" aria-label={t('tpl.panel')}>
      <h3>{t('tpl.panelFor', { type: selected.type, id: selected.id })}</h3>
      <SelectField
        label={t('tpl.template')}
        value={key}
        onChange={(k) => {
          setKey(k);
          setValues(defaultValues(options.find((x) => x.key === k)));
          setErrors([]);
          setManual(undefined);
        }}
        placeholder={t('tpl.choose')}
        options={options.map((x) => ({ value: x.key, label: `${x.name ?? x.key} v${x.version}` }))}
      />
      <TemplateFieldInputs fields={fields} values={values} onChange={setValues} packageId={packageId} />
      {chosen && (
        <button type="button" className="primary" disabled={act.busy} onClick={apply}>
          {t('tpl.apply')}
        </button>
      )}
      {errors.length > 0 && (
        <Notice tone="bad">
          <ul>{errors.map((e) => <li key={e}>{e}</li>)}</ul>
        </Notice>
      )}
      {manual && (
        <Notice tone="warn">
          {t('tpl.manual')}
          <JsonView value={manual} />
        </Notice>
      )}
      <ProblemAlert error={act.error ?? list.error} />
    </section>
  );
}
