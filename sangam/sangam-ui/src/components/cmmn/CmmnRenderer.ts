// SPDX-License-Identifier: Apache-2.0
/**
 * Owner decision 161 (M-041): draws CMMN 1.1 shapes (CMMN 1.1 section 7, notation) on diagram-js 15.30.0.
 * Case plan model: a folder with a tab. Stage: a rectangle with cut corners. Tasks: rounded rectangles with the type
 * marker in the top-left corner. Milestone: a fully rounded rectangle. Event listeners: double circles. Entry
 * criterion: a white diamond; exit criterion: a black diamond. Sentry on-parts: dashed connectors.
 * Colours come from CSS custom properties, so the console's light and dark themes apply.
 */
import BaseRenderer from 'diagram-js/lib/draw/BaseRenderer';
import type EventBus from 'diagram-js/lib/core/EventBus';
import type { NodeKind } from './cmmnModel';

type Box = { id: string; x: number; y: number; width: number; height: number; cmmnKind?: NodeKind; cmmnName?: string };
type Line = { id: string; waypoints: { x: number; y: number }[] };

const STROKE = 'var(--cmmn-stroke, currentColor)';
const FILL = 'var(--cmmn-fill, transparent)';
const TEXT = 'var(--cmmn-text, currentColor)';

function el(parent: SVGElement, tag: string, attrs: Record<string, string | number>): SVGElement {
  const e = document.createElementNS('http://www.w3.org/2000/svg', tag) as SVGElement;
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, String(v));
  parent.appendChild(e);
  return e;
}

/** Splits a name into lines of at most `max` characters, at most `lines` lines. */
export function wrap(text: string, max: number, lines: number): string[] {
  const out: string[] = [];
  let cur = '';
  for (const word of text.split(/\s+/).filter(Boolean)) {
    const next = cur ? `${cur} ${word}` : word;
    if (next.length <= max) cur = next;
    else {
      if (cur) out.push(cur);
      cur = word.length > max ? `${word.slice(0, max - 1)}…` : word;
    }
  }
  if (cur) out.push(cur);
  if (out.length > lines) {
    out.length = lines;
    out[lines - 1] = `${out[lines - 1].slice(0, Math.max(0, max - 1))}…`;
  }
  return out;
}

function label(g: SVGElement, text: string, cx: number, cy: number, max: number, lines: number): void {
  const rows = wrap(text, max, lines);
  const t = el(g, 'text', { 'text-anchor': 'middle', 'font-size': 12, fill: TEXT, 'font-family': 'inherit' });
  rows.forEach((row, i) => {
    const span = el(t, 'tspan', { x: cx, y: cy + (i - (rows.length - 1) / 2) * 14 + 4 });
    span.textContent = row;
  });
}

const DIAMOND = (w: number, h: number) => `M ${w / 2} 0 L ${w} ${h / 2} L ${w / 2} ${h} L 0 ${h / 2} Z`;
const OCTAGON = (w: number, h: number, c: number) => `M ${c} 0 H ${w - c} L ${w} ${c} V ${h - c} L ${w - c} ${h} H ${c} L 0 ${h - c} V ${c} Z`;

function marker(g: SVGElement, kind: NodeKind): void {
  // a small type marker in the top-left corner (CMMN 1.1 table 7.x)
  switch (kind) {
    case 'humanTask': // person
      el(g, 'circle', { cx: 16, cy: 12, r: 4, fill: 'none', stroke: STROKE });
      el(g, 'path', { d: 'M 9 24 Q 16 14 23 24', fill: 'none', stroke: STROKE });
      break;
    case 'processTask': // chevron
      el(g, 'path', { d: 'M 8 8 H 20 L 25 14 L 20 20 H 8 L 13 14 Z', fill: 'none', stroke: STROKE });
      break;
    case 'caseTask': // folder
      el(g, 'path', { d: 'M 8 11 H 13 L 15 9 H 24 V 21 H 8 Z', fill: 'none', stroke: STROKE });
      break;
    case 'decisionTask': // table
      el(g, 'rect', { x: 8, y: 8, width: 16, height: 12, fill: 'none', stroke: STROKE });
      el(g, 'path', { d: 'M 8 12 H 24 M 13 12 V 20', fill: 'none', stroke: STROKE });
      break;
    default:
      break;
  }
}

export default class CmmnRenderer extends BaseRenderer {
  static $inject = ['eventBus'];

  constructor(eventBus: EventBus) {
    super(eventBus, 2000);
  }

  canRender(element: { id: string; cmmnKind?: unknown }): boolean {
    return !!element.cmmnKind;
  }

  drawShape(visuals: SVGElement, shape: Box): SVGElement {
    const { width: w, height: h } = shape;
    const kind = shape.cmmnKind!;
    const name = shape.cmmnName ?? '';
    const g = el(visuals, 'g', { class: `cmmn-shape cmmn-${kind}` });
    const line = { stroke: STROKE, 'stroke-width': 1.5, fill: FILL };
    switch (kind) {
      case 'casePlanModel': {
        const tabW = Math.min(Math.max(name.length * 7 + 24, 120), w);
        el(g, 'path', { d: `M 0 20 V 0 H ${tabW - 10} L ${tabW} 20 H ${w} V ${h} H 0 Z`, ...line, fill: 'none' });
        el(g, 'path', { d: `M 0 20 H ${tabW}`, stroke: STROKE });
        const t = el(g, 'text', { x: 8, y: 14, 'font-size': 12, 'font-weight': 600, fill: TEXT });
        t.textContent = wrap(name, Math.floor(tabW / 7), 1)[0] ?? '';
        break;
      }
      case 'stage': {
        el(g, 'path', { d: OCTAGON(w, h, 12), ...line, fill: 'none' });
        const t = el(g, 'text', { x: 16, y: 20, 'font-size': 12, 'font-weight': 600, fill: TEXT });
        t.textContent = wrap(name, Math.floor((w - 32) / 7), 1)[0] ?? '';
        break;
      }
      case 'milestone':
        el(g, 'rect', { x: 0, y: 0, width: w, height: h, rx: h / 2, ry: h / 2, ...line });
        label(g, name, w / 2, h / 2, Math.floor(w / 7), 2);
        break;
      case 'timerEventListener':
      case 'userEventListener':
        el(g, 'circle', { cx: w / 2, cy: h / 2, r: w / 2, ...line });
        el(g, 'circle', { cx: w / 2, cy: h / 2, r: w / 2 - 3, ...line, fill: 'none' });
        if (kind === 'timerEventListener') el(g, 'path', { d: `M ${w / 2} ${h / 2 - 8} V ${h / 2} H ${w / 2 + 6}`, stroke: STROKE, fill: 'none' });
        else el(g, 'circle', { cx: w / 2, cy: h / 2 - 3, r: 3, stroke: STROKE, fill: 'none' });
        label(g, name, w / 2, h + 14, 18, 2);
        break;
      case 'entryCriterion':
        el(g, 'path', { d: DIAMOND(w, h), stroke: STROKE, 'stroke-width': 1.5, fill: 'var(--cmmn-entry, #ffffff)' });
        break;
      case 'exitCriterion':
        el(g, 'path', { d: DIAMOND(w, h), stroke: STROKE, 'stroke-width': 1.5, fill: 'var(--cmmn-exit, #000000)' });
        break;
      default:
        el(g, 'rect', { x: 0, y: 0, width: w, height: h, rx: 10, ry: 10, ...line });
        marker(g, kind);
        label(g, name, w / 2, h / 2 + 4, Math.floor((w - 16) / 7), 3);
    }
    return g;
  }

  drawConnection(visuals: SVGElement, connection: Line): SVGElement {
    const d = connection.waypoints.map((p, i) => `${i ? 'L' : 'M'} ${p.x} ${p.y}`).join(' ');
    return el(visuals, 'path', { d, fill: 'none', stroke: STROKE, 'stroke-width': 1.5, 'stroke-dasharray': '6 4', class: 'cmmn-on-part' });
  }

  getShapePath(shape: Box): string {
    const { x, y, width: w, height: h } = shape;
    if (shape.cmmnKind === 'entryCriterion' || shape.cmmnKind === 'exitCriterion') {
      return `M ${x + w / 2} ${y} L ${x + w} ${y + h / 2} L ${x + w / 2} ${y + h} L ${x} ${y + h / 2} Z`;
    }
    return `M ${x} ${y} H ${x + w} V ${y + h} H ${x} Z`;
  }

  getConnectionPath(connection: Line): string {
    return connection.waypoints.map((p, i) => `${i ? 'L' : 'M'} ${p.x} ${p.y}`).join(' ');
  }
}
