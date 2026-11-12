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
import { applicable, fieldError, namespacesFor, planWrites, templateFields, type Applied, type TemplateView } from '../modeller/templates';
import { JsonView, Notice, ProblemAlert, SelectField, TextField } from './ui';

/** The parts of a bpmn-js Modeler the panel uses (stable public services of bpmn-js 17). */
export interface ModelerLike {
  get: (name: string) => unknown;
  getDefinitions?: () => { $attrs?: Record<string, string> } | undefined;
}

export interface Selected {
  id: string;
  type: string;
}

async function elementTemplates(signal?: AbortSignal): Promise<TemplateView[]> {
  try {
    const r = await http.get<TemplateView[]>('/templates', { kind: 'ELEMENT' }, signal);
    return Array.isArray(r) ? r : [];
  } catch (e) {
    if (e instanceof ApiError && (e.status === 403 || e.status === 404 || e.status === 501)) return [];
    throw e;
  }
}

export function ElementTemplatePanel({ modeler, selected }: { modeler: ModelerLike | null; selected: Selected | null }) {
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
      .run(() => http.post<Applied>(`/templates/${encodeURIComponent(chosen!.key)}/apply?version=${chosen!.version}`, { elementType: selected.type, values }))
      .then((r) => {
        if (!r) return;
        setErrors(r.errors ?? []);
        if (r.errors?.length) return;
        const w = planWrites(r.properties ?? {});
        const defs = modeler.getDefinitions?.();
        if (defs) defs.$attrs = { ...(defs.$attrs ?? {}), ...namespacesFor(w.attrs) };
        const el = (modeler.get('elementRegistry') as { get: (id: string) => unknown }).get(selected.id);
        if (el) (modeler.get('modeling') as { updateProperties: (e: unknown, p: Record<string, unknown>) => void }).updateProperties(el, { ...w.bpmn, ...w.attrs });
        setManual(Object.keys(w.manual).length ? w.manual : undefined);
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
          const tpl = options.find((x) => x.key === k);
          setValues(Object.fromEntries((tpl ? templateFields(tpl) : []).filter((f) => f.default !== undefined && f.default !== null).map((f) => [f.name, String(f.default)])));
          setErrors([]);
          setManual(undefined);
        }}
        placeholder={t('tpl.choose')}
        options={options.map((x) => ({ value: x.key, label: `${x.name ?? x.key} v${x.version}` }))}
      />
      {fields.map((f) =>
        f.choices?.length ? (
          <SelectField key={f.name} label={f.name} required={f.required} value={values[f.name] ?? ''} onChange={(v) => setValues({ ...values, [f.name]: v })} placeholder="" options={f.choices.map((c) => ({ value: c, label: c }))} />
        ) : (
          <TextField
            key={f.name}
            label={f.name}
            hint={f.binding}
            required={f.required}
            value={values[f.name] ?? ''}
            onChange={(v) => setValues({ ...values, [f.name]: v })}
            error={(() => {
              const e = values[f.name] !== undefined ? fieldError(f, values[f.name]) : null;
              return e ? t(`tpl.err.${e}`) : undefined;
            })()}
          />
        ),
      )}
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
