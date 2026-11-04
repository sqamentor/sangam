// SPDX-License-Identifier: Apache-2.0
/**
 * BPMN viewer with runtime token badges, history heatmap and duration overlays (R63, PF-02, PF-01).
 * Built on bpmn-js NavigatedViewer. The bpmn.io watermark rendered by bpmn-js stays visible (LIC-8): this component
 * never hides `.bjs-powered-by`.
 * For accessibility, the same figures are always offered as a data table below the canvas.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer';
import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css';
import type { ActivityStatistic, FlowStatistic } from '../api/types';
import { fmtDuration, fmtNumber } from '../i18n';
import { DataTable, ProblemAlert } from './ui';

export type OverlayMode = 'tokens' | 'heat' | 'duration';

interface Shape {
  id: string;
  width?: number;
  height?: number;
  type?: string;
  businessObject?: { name?: string };
  waypoints?: unknown[];
}
interface Overlays {
  add: (id: string, type: string, o: { position: Record<string, number>; html: HTMLElement | string }) => string;
  clear: () => void;
}
interface Canvas {
  zoom: (z: string | number) => void;
  addMarker: (id: string, m: string) => void;
  removeMarker: (id: string, m: string) => void;
}
interface Registry {
  get: (id: string) => Shape | undefined;
  getAll: () => Shape[];
}
interface EventBus {
  on: (e: string, cb: (ev: { element: Shape }) => void) => void;
  off: (e: string, cb: (ev: { element: Shape }) => void) => void;
}

function heatColour(v: number): string {
  // perceptually ordered blue → yellow → red; 0.45 alpha keeps labels readable
  const hue = 220 - Math.round(220 * Math.min(1, Math.max(0, v)));
  return `hsla(${hue}, 85%, 50%, 0.45)`;
}

export function BpmnDiagram({
  xml,
  stats = [],
  flows = [],
  mode = 'tokens',
  selected,
  onSelect,
  height = 460,
  title,
}: {
  xml: string;
  stats?: ActivityStatistic[];
  flows?: FlowStatistic[];
  mode?: OverlayMode;
  selected?: string[];
  onSelect?: (activityId: string, name?: string) => void;
  height?: number;
  title: string;
}) {
  const { t } = useTranslation();
  const host = useRef<HTMLDivElement>(null);
  const viewer = useRef<NavigatedViewer | null>(null);
  const [error, setError] = useState<Error>();
  const [ready, setReady] = useState(0);
  const onSelectRef = useRef(onSelect);
  onSelectRef.current = onSelect;

  useEffect(() => {
    if (!host.current) return;
    const v = new NavigatedViewer({ container: host.current });
    viewer.current = v;
    const bus = v.get<EventBus>('eventBus');
    const click = (ev: { element: Shape }) => {
      if (ev.element?.id && !ev.element.waypoints) onSelectRef.current?.(ev.element.id, ev.element.businessObject?.name);
    };
    bus.on('element.click', click);
    return () => {
      bus.off('element.click', click);
      v.destroy();
      viewer.current = null;
    };
  }, []);

  useEffect(() => {
    const v = viewer.current;
    if (!v || !xml) return;
    let cancelled = false;
    v.importXML(xml)
      .then(() => {
        if (cancelled) return;
        v.get<Canvas>('canvas').zoom('fit-viewport');
        setError(undefined);
        setReady((n) => n + 1);
      })
      .catch((e: Error) => !cancelled && setError(e));
    return () => {
      cancelled = true;
    };
  }, [xml]);

  useEffect(() => {
    const v = viewer.current;
    if (!v || ready === 0) return;
    const overlays = v.get<Overlays>('overlays');
    const reg = v.get<Registry>('elementRegistry');
    const canvas = v.get<Canvas>('canvas');
    overlays.clear();
    reg.getAll().forEach((s) => {
      canvas.removeMarker(s.id, 'sg-incident');
      canvas.removeMarker(s.id, 'sg-selected');
    });
    const max = Math.max(1, ...stats.map((s) => (mode === 'duration' ? s.avgDurationMs ?? 0 : s.count)));
    for (const s of stats) {
      const shape = reg.get(s.activityId);
      if (!shape) continue;
      if (mode === 'heat' || mode === 'duration') {
        const value = mode === 'duration' ? (s.avgDurationMs ?? 0) / max : s.count / max;
        const box = document.createElement('div');
        box.className = 'sg-heat';
        box.style.width = `${shape.width ?? 100}px`;
        box.style.height = `${shape.height ?? 80}px`;
        box.style.background = heatColour(value);
        overlays.add(s.activityId, 'sg-heat', { position: { top: 0, left: 0 }, html: box });
      }
      const label = mode === 'duration' ? fmtDuration(s.avgDurationMs) : fmtNumber(s.count);
      if (s.count > 0 || mode === 'duration') {
        const b = document.createElement('span');
        b.className = 'sg-badge';
        b.textContent = label;
        b.setAttribute('aria-hidden', 'true');
        overlays.add(s.activityId, 'sg-count', { position: { bottom: 8, left: -8 }, html: b });
      }
      if (s.incidents > 0) {
        canvas.addMarker(s.activityId, 'sg-incident');
        const b = document.createElement('span');
        b.className = 'sg-badge sg-badge-bad';
        b.textContent = `! ${fmtNumber(s.incidents)}`;
        b.setAttribute('aria-hidden', 'true');
        overlays.add(s.activityId, 'sg-inc', { position: { bottom: 8, right: 8 }, html: b });
      }
    }
    if (mode !== 'tokens') {
      for (const f of flows) {
        if (!reg.get(f.flowId) || f.count === 0) continue;
        const b = document.createElement('span');
        b.className = 'sg-badge sg-badge-flow';
        b.textContent = fmtNumber(f.count);
        overlays.add(f.flowId, 'sg-flow', { position: { top: 0, left: 0 }, html: b });
      }
    }
    for (const id of selected ?? []) if (reg.get(id)) canvas.addMarker(id, 'sg-selected');
  }, [stats, flows, mode, ready, selected]);

  const rows = useMemo(() => stats.filter((s) => s.count > 0 || s.incidents > 0 || s.avgDurationMs), [stats]);

  return (
    <figure className="diagram">
      <figcaption className="sr-only">{title}</figcaption>
      <ProblemAlert error={error} />
      <div
        ref={host}
        className="bpmn-host"
        style={{ height }}
        role="img"
        aria-label={t('diagram.canvasLabel', { title })}
        tabIndex={0}
        onKeyDown={(e) => {
          const c = viewer.current?.get<Canvas>('canvas');
          if (!c) return;
          if (e.key === '+' || e.key === '=') c.zoom(1.2);
          if (e.key === '-') c.zoom(0.8);
          if (e.key === '0') c.zoom('fit-viewport');
        }}
      />
      <p className="hint">{t('diagram.keyboardHint')}</p>
      {rows.length > 0 && (
        <details>
          <summary>{t('diagram.tableAlternative')}</summary>
          <DataTable
            caption={t('diagram.statsCaption')}
            rows={rows}
            rowKey={(r) => r.activityId}
            columns={[
              {
                key: 'activityName',
                header: t('diagram.activity'),
                render: (r) =>
                  onSelect ? (
                    <button type="button" className="link" onClick={() => onSelect(r.activityId, r.activityName)}>
                      {r.activityName ?? r.activityId}
                    </button>
                  ) : (
                    (r.activityName ?? r.activityId)
                  ),
              },
              { key: 'count', header: mode === 'tokens' ? t('diagram.tokens') : t('diagram.executions'), render: (r) => fmtNumber(r.count) },
              { key: 'incidents', header: t('diagram.incidents'), render: (r) => fmtNumber(r.incidents) },
              { key: 'avg', header: t('diagram.avgDuration'), render: (r) => fmtDuration(r.avgDurationMs) },
              { key: 'p95', header: t('diagram.p95'), render: (r) => fmtDuration(r.p95DurationMs) },
            ]}
          />
        </details>
      )}
    </figure>
  );
}
