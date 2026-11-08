// SPDX-License-Identifier: Apache-2.0
/** Read-only DMN view (decision tables, DRD, literal expressions) on dmn-js. The bpmn.io watermark stays (LIC-8). */
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import DmnViewer from 'dmn-js/lib/Viewer';
import 'dmn-js/dist/assets/diagram-js.css';
import 'dmn-js/dist/assets/dmn-js-shared.css';
import 'dmn-js/dist/assets/dmn-js-drd.css';
import 'dmn-js/dist/assets/dmn-js-decision-table.css';
import 'dmn-js/dist/assets/dmn-js-literal-expression.css';
import 'dmn-js/dist/assets/dmn-font/css/dmn-embedded.css';
import { ProblemAlert } from './ui';

export function DmnView({ xml, title, highlightRules = [] }: { xml: string; title: string; highlightRules?: string[] }) {
  const { t } = useTranslation();
  const host = useRef<HTMLDivElement>(null);
  const viewer = useRef<DmnViewer | null>(null);
  const [views, setViews] = useState<{ type: string; element: { id: string; name?: string } }[]>([]);
  const [err, setErr] = useState<Error>();
  useEffect(() => {
    if (!host.current) return;
    const v = new DmnViewer({ container: host.current });
    viewer.current = v;
    v.importXML(xml)
      .then(() => setViews(v.getViews()))
      .catch((e: Error) => setErr(e));
    return () => v.destroy();
  }, [xml]);
  // Matched-rule highlighting (Operaton highlightRules): mark the cells of the rules that fired.
  useEffect(() => {
    const run = () => {
      host.current?.querySelectorAll('.sg-rule-hit').forEach((e) => e.classList.remove('sg-rule-hit'));
      for (const id of highlightRules) host.current?.querySelectorAll(`[data-row-id="${CSS.escape(id)}"]`).forEach((e) => e.classList.add('sg-rule-hit'));
    };
    const timer = window.setTimeout(run, 300);
    return () => window.clearTimeout(timer);
  }, [highlightRules, views]);
  return (
    <figure className="diagram">
      <figcaption>{title}</figcaption>
      <ProblemAlert error={err} />
      {views.length > 1 && (
        <div className="toolbar" role="group" aria-label={t('dmn.views')}>
          {views.map((vw) => (
            <button key={vw.element.id} type="button" onClick={() => void viewer.current?.open(vw)}>
              {vw.element.name ?? vw.element.id} ({vw.type})
            </button>
          ))}
        </div>
      )}
      <div ref={host} className="dmn-host" tabIndex={0} aria-label={t('dmn.canvas', { title })} />
    </figure>
  );
}
