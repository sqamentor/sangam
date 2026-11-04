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

export function DmnView({ xml, title }: { xml: string; title: string }) {
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
