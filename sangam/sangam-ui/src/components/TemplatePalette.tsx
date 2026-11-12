// SPDX-License-Identifier: Apache-2.0
/**
 * C3-14 / M-036 (owner decision 58): the template palette of the BPMN editor. A PALETTE template is the ELEMENT format
 * whose appliesTo names exactly one BPMN type (modeller/templates.ts paletteEntries). Adding one asks for its fields,
 * lets the server validate and resolve them (POST /templates/{id}/apply ?version, package.read, ModellerController.java
 * :287-295) and only then creates the element in the middle of the visible canvas and writes the resolved properties
 * into it (bpmn-js elementFactory / modeling, undoable). A refused apply creates nothing.
 *
 * The entries are a labelled list of buttons next to the canvas, not icons inside the bpmn-js palette, so they are
 * reachable by keyboard and screen reader (M-046). Without package.read, or without PALETTE templates, nothing shows.
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { announce, useAction, useAsync } from '../lib/hooks';
import { paletteEntries, paletteType, templateFields, type TemplateView } from '../modeller/templates';
import { JsonView, Notice, ProblemAlert } from './ui';
import { applyTemplate, defaultValues, TemplateFieldInputs, templatesOfKind, writeApplied, type ModelerLike } from './ElementTemplatePanel';

interface Viewbox {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** Creates a shape of `type` at the centre of the visible canvas, selects it, and returns it. */
export function createAtCentre(modeler: ModelerLike, type: string): unknown {
  const canvas = modeler.get('canvas') as { viewbox: () => Viewbox; getRootElement: () => unknown };
  const factory = modeler.get('elementFactory') as { createShape: (attrs: { type: string }) => unknown };
  const modeling = modeler.get('modeling') as { createShape: (shape: unknown, pos: { x: number; y: number }, parent: unknown) => unknown };
  const vb = canvas.viewbox();
  const shape = modeling.createShape(factory.createShape({ type }), { x: Math.round(vb.x + vb.width / 2), y: Math.round(vb.y + vb.height / 2) }, canvas.getRootElement());
  (modeler.get('selection') as { select: (e: unknown) => void }).select(shape);
  return shape;
}

export function TemplatePalette({ modeler, packageId }: { modeler: ModelerLike | null; packageId?: string }) {
  const { t } = useTranslation();
  const list = useAsync((signal) => templatesOfKind('PALETTE', signal), []);
  const [chosen, setChosen] = useState<TemplateView>();
  const [values, setValues] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<string[]>([]);
  const [manual, setManual] = useState<Record<string, unknown>>();
  const act = useAction();
  const entries = paletteEntries(list.data ?? []);
  if (!modeler || (entries.length === 0 && !list.error)) return null;

  const pick = (tpl: TemplateView) => {
    setChosen(tpl);
    setValues(defaultValues(tpl));
    setErrors([]);
    setManual(undefined);
  };
  const add = () => {
    const tpl = chosen!;
    const type = paletteType(tpl)!;
    void act.run(() => applyTemplate(tpl, type, values)).then((r) => {
      if (!r) return;
      setErrors(r.errors ?? []);
      if (r.errors?.length) return;
      const shape = createAtCentre(modeler, type);
      setManual(writeApplied(modeler, shape, r.properties ?? {}));
      announce(t('tpl.paletteAdded', { name: tpl.name ?? tpl.key }));
    });
  };

  return (
    <section className="card" aria-label={t('tpl.palette')}>
      <h3>{t('tpl.palette')}</h3>
      <p className="hint">{t('tpl.paletteHint')}</p>
      <ul className="plain">
        {entries.map((x) => (
          <li key={x.key}>
            <button type="button" aria-pressed={chosen?.key === x.key} onClick={() => pick(x)}>
              {`${x.name ?? x.key} v${x.version}`}
            </button>{' '}
            <span className="muted small">{paletteType(x)}</span>
          </li>
        ))}
      </ul>
      {chosen && (
        <>
          <TemplateFieldInputs fields={templateFields(chosen)} values={values} onChange={setValues} packageId={packageId} />
          <button type="button" className="primary" disabled={act.busy} onClick={add}>
            {t('tpl.paletteAdd', { name: chosen.name ?? chosen.key })}
          </button>
        </>
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
