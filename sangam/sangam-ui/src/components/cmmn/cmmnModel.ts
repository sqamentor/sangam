// SPDX-License-Identifier: Apache-2.0
/**
 * Owner decision 161 (M-041): the model behind Sangam's own CMMN 1.1 editor. Pure DOM code (no diagram library), so
 * it is testable on its own.
 *
 * - parseCmmn reads a CMMN 1.1 file (OMG namespace http://www.omg.org/spec/CMMN/20151109/MODEL, default or prefixed)
 *   into nodes (the case plan model, plan items with their definitions, entry and exit criteria) and links (a sentry's
 *   planItemOnPart drawn from the source plan item to the criterion). Bounds come from CMMNDI; without CMMNDI (the
 *   Sangam packs carry none) a layout is generated.
 * - serializeCmmn writes the edited diagram back into the same document: names, added and removed plan items and
 *   criteria, sentry on-parts, and a regenerated CMMNDI section. Everything the editor does not show (item controls,
 *   rules, ifParts, extension attributes such as operaton:candidateGroups, planning tables, case file) is kept as it
 *   was. Element order follows the CMMN 1.1 XSD sequences, because the engine parses with schema validation.
 */

export const CMMN_NS = 'http://www.omg.org/spec/CMMN/20151109/MODEL';
export const CMMNDI_NS = 'http://www.omg.org/spec/CMMN/20151109/CMMNDI';
export const DC_NS = 'http://www.omg.org/spec/CMMN/20151109/DC';
export const DI_NS = 'http://www.omg.org/spec/CMMN/20151109/DI';
const XMLNS_NS = 'http://www.w3.org/2000/xmlns/';

export const PLAN_ITEM_KINDS = ['stage', 'humanTask', 'processTask', 'caseTask', 'decisionTask', 'task', 'milestone', 'timerEventListener', 'userEventListener'] as const;
export type PlanItemKind = (typeof PLAN_ITEM_KINDS)[number];
export type CriterionKind = 'entryCriterion' | 'exitCriterion';
export type NodeKind = 'casePlanModel' | PlanItemKind | CriterionKind;

/** What the editor offers to add. Event listeners are not offered: the engine rejects them (EC-04, D-43, E16-b). */
export const CREATABLE_KINDS: PlanItemKind[] = ['humanTask', 'processTask', 'caseTask', 'decisionTask', 'milestone', 'stage'];
/** Shown when present in a file, but the engine refuses them at deployment. */
export const ENGINE_REJECTED_KINDS: PlanItemKind[] = ['timerEventListener', 'userEventListener'];

export interface Bounds {
  x: number;
  y: number;
  width: number;
  height: number;
}
export interface Point {
  x: number;
  y: number;
}

export interface CmmnNode {
  /** The plan item id (the casePlanModel id, or the criterion id). */
  id: string;
  kind: NodeKind;
  name: string;
  /** The containing node: the case plan model or a stage plan item. Undefined for the case plan model. */
  parent?: string;
  /** For a criterion: the plan item (or the case plan model) it belongs to. */
  host?: string;
  bounds: Bounds;
  /** For a plan item: its definition's id. */
  definitionId?: string;
  /** For a criterion: its sentry's id. */
  sentryId?: string;
}

/** A sentry on-part: drawn from the source plan item to the criterion that uses the sentry. */
export interface CmmnLink {
  id: string;
  source: string;
  target: string;
  onPartId?: string;
  waypoints: Point[];
}

export interface CmmnDiagramModel {
  caseId: string;
  nodes: CmmnNode[];
  links: CmmnLink[];
  /** Parts of the file the diagram does not show; they are kept unchanged. */
  notShown: string[];
  /** False when the file had no (complete) CMMNDI and the layout was generated. */
  hadLayout: boolean;
  diagramId?: string;
}

export class CmmnParseError extends Error {
  constructor(readonly code: 'xml' | 'not-cmmn' | 'no-case' | 'no-plan-model') {
    super(code);
  }
}

export const SIZE: Record<NodeKind, { width: number; height: number }> = {
  casePlanModel: { width: 800, height: 400 },
  stage: { width: 360, height: 220 },
  humanTask: { width: 140, height: 80 },
  processTask: { width: 140, height: 80 },
  caseTask: { width: 140, height: 80 },
  decisionTask: { width: 140, height: 80 },
  task: { width: 140, height: 80 },
  milestone: { width: 140, height: 40 },
  timerEventListener: { width: 36, height: 36 },
  userEventListener: { width: 36, height: 36 },
  entryCriterion: { width: 20, height: 28 },
  exitCriterion: { width: 20, height: 28 },
};

export function isPlanItemKind(k: string): k is PlanItemKind {
  return (PLAN_ITEM_KINDS as readonly string[]).includes(k);
}
export function isCriterion(k: NodeKind): k is CriterionKind {
  return k === 'entryCriterion' || k === 'exitCriterion';
}
export function isContainer(k: NodeKind): boolean {
  return k === 'casePlanModel' || k === 'stage';
}

function children(el: Element, local?: string, ns = CMMN_NS): Element[] {
  const out: Element[] = [];
  for (let c = el.firstElementChild; c; c = c.nextElementSibling) if (c.namespaceURI === ns && (!local || c.localName === local)) out.push(c);
  return out;
}

function indexById(doc: Document): Map<string, Element> {
  const map = new Map<string, Element>();
  const all = doc.getElementsByTagNameNS(CMMN_NS, '*');
  for (let i = 0; i < all.length; i++) {
    const id = all[i].getAttribute('id');
    if (id) map.set(id, all[i]);
  }
  return map;
}

function num(el: Element | null | undefined, a: string): number {
  return Number(el?.getAttribute(a) ?? 'NaN');
}

export interface ParsedCmmn {
  doc: Document;
  model: CmmnDiagramModel;
  hadDeclaration: boolean;
}

export function parseCmmn(xml: string): ParsedCmmn {
  const doc = new DOMParser().parseFromString(xml, 'application/xml');
  if (doc.getElementsByTagName('parsererror').length) throw new CmmnParseError('xml');
  const root = doc.documentElement;
  if (root.namespaceURI !== CMMN_NS || root.localName !== 'definitions') throw new CmmnParseError('not-cmmn');
  const cases = children(root, 'case');
  if (!cases.length) throw new CmmnParseError('no-case');
  const cpm = children(cases[0], 'casePlanModel')[0];
  if (!cpm) throw new CmmnParseError('no-plan-model');
  const byId = indexById(doc);
  const notShown = new Set<string>();
  if (cases.length > 1) notShown.add('case');
  const nodes: CmmnNode[] = [];
  const zero = { x: 0, y: 0, width: 0, height: 0 };

  const criteria = (owner: Element, ownerId: string, parent: string | undefined) => {
    for (const kind of ['entryCriterion', 'exitCriterion'] as const) {
      for (const c of children(owner, kind)) {
        const id = c.getAttribute('id');
        if (!id) continue;
        nodes.push({ id, kind, name: c.getAttribute('name') ?? '', host: ownerId, parent, bounds: zero, sentryId: c.getAttribute('sentryRef') ?? undefined });
      }
    }
  };
  const walk = (container: Element, containerNodeId: string) => {
    for (const c of children(container)) {
      if (c.localName === 'planningTable') notShown.add('planningTable');
    }
    for (const pi of children(container, 'planItem')) {
      const id = pi.getAttribute('id');
      const def = byId.get(pi.getAttribute('definitionRef') ?? '');
      if (!id || !def) continue;
      if (!isPlanItemKind(def.localName)) {
        notShown.add(def.localName);
        continue;
      }
      nodes.push({ id, kind: def.localName, name: pi.getAttribute('name') ?? def.getAttribute('name') ?? '', parent: containerNodeId, bounds: zero, definitionId: def.getAttribute('id') ?? undefined });
      criteria(pi, id, containerNodeId);
      if (def.localName === 'stage') walk(def, id);
    }
  };
  const cpmId = cpm.getAttribute('id') ?? 'CasePlanModel_1';
  nodes.push({ id: cpmId, kind: 'casePlanModel', name: cpm.getAttribute('name') ?? '', bounds: zero, definitionId: cpmId });
  walk(cpm, cpmId);
  criteria(cpm, cpmId, undefined);
  if (doc.getElementsByTagNameNS(CMMN_NS, 'caseFileModel').length) notShown.add('caseFileModel');

  const ids = new Set(nodes.map((n) => n.id));
  const links: CmmnLink[] = [];
  for (const n of nodes) {
    if (!isCriterion(n.kind) || !n.sentryId) continue;
    const sentry = byId.get(n.sentryId);
    if (!sentry) continue;
    for (const op of children(sentry)) {
      if (op.localName === 'caseFileItemOnPart') notShown.add('caseFileItemOnPart');
      if (op.localName !== 'planItemOnPart') continue;
      const src = op.getAttribute('sourceRef') ?? '';
      if (!ids.has(src)) continue;
      const opId = op.getAttribute('id') ?? undefined;
      links.push({ id: `${opId ?? `${src}_on`}__${n.id}`, source: src, target: n.id, onPartId: opId, waypoints: [] });
    }
  }

  // CMMNDI: shapes by cmmnElementRef, edges by (cmmnElementRef, target)
  const shapes = new Map<string, Bounds>();
  const edges = new Map<string, Point[]>();
  let diagramId: string | undefined;
  const diagrams = doc.getElementsByTagNameNS(CMMNDI_NS, 'CMMNDiagram');
  if (diagrams.length) {
    diagramId = diagrams[0].getAttribute('id') ?? undefined;
    for (const s of Array.from(diagrams[0].getElementsByTagNameNS(CMMNDI_NS, 'CMMNShape'))) {
      const b = s.getElementsByTagNameNS(DC_NS, 'Bounds')[0];
      const r = { x: num(b, 'x'), y: num(b, 'y'), width: num(b, 'width'), height: num(b, 'height') };
      if (Object.values(r).every(Number.isFinite)) shapes.set(s.getAttribute('cmmnElementRef') ?? '', r);
    }
    for (const e of Array.from(diagrams[0].getElementsByTagNameNS(CMMNDI_NS, 'CMMNEdge'))) {
      const pts = Array.from(e.getElementsByTagNameNS(DI_NS, 'waypoint')).map((w) => ({ x: num(w, 'x'), y: num(w, 'y') }));
      if (pts.length >= 2 && pts.every((p) => Number.isFinite(p.x) && Number.isFinite(p.y))) edges.set(`${e.getAttribute('cmmnElementRef')}__${e.getAttribute('targetCMMNElementRef')}`, pts);
    }
  }
  const hadLayout = nodes.every((n) => shapes.has(n.id));
  if (hadLayout) for (const n of nodes) n.bounds = shapes.get(n.id)!;
  else layout(nodes);
  for (const l of links) l.waypoints = (hadLayout && l.onPartId && edges.get(`${l.onPartId}__${l.target}`)) || straight(nodes, l.source, l.target);
  return { doc, model: { caseId: cases[0].getAttribute('id') ?? '', nodes, links, notShown: [...notShown], hadLayout, diagramId }, hadDeclaration: /^\s*<\?xml/.test(xml) };
}

function centre(b: Bounds): Point {
  return { x: Math.round(b.x + b.width / 2), y: Math.round(b.y + b.height / 2) };
}

/** A straight connector between two nodes' centres. */
export function straight(nodes: CmmnNode[], source: string, target: string): Point[] {
  const s = nodes.find((n) => n.id === source);
  const t = nodes.find((n) => n.id === target);
  return s && t ? [centre(s.bounds), centre(t.bounds)] : [];
}

/** Places a criterion on its host's border: entry on the left, exit on the right, stacked when there are several. */
export function criterionBounds(host: Bounds, kind: CriterionKind, index: number): Bounds {
  const { width, height } = SIZE[kind];
  const y = host.y + host.height / 2 - height / 2 + (index % 2 === 0 ? 1 : -1) * Math.ceil(index / 2) * (height + 4);
  const x = kind === 'entryCriterion' ? host.x - width / 2 : host.x + host.width - width / 2;
  return { x: Math.round(x), y: Math.round(y), width, height };
}

/**
 * Generated layout when the file has no CMMNDI: the plan items of each container in rows of up to four (stages sized
 * by their content), the case plan model around everything, criteria on their hosts' borders.
 */
export function layout(nodes: CmmnNode[]): void {
  const GAP_X = 70;
  const GAP_Y = 60;
  const PAD_TOP = 50;
  const PAD = 40;
  const PER_ROW = 4;
  const byParent = new Map<string, CmmnNode[]>();
  for (const n of nodes) if (n.parent && !isCriterion(n.kind)) byParent.set(n.parent, [...(byParent.get(n.parent) ?? []), n]);
  const sizeOf = (n: CmmnNode): { width: number; height: number } => {
    if (!isContainer(n.kind)) return SIZE[n.kind];
    const kids = byParent.get(n.id) ?? [];
    if (!kids.length) return SIZE[n.kind];
    const sizes = kids.map(sizeOf);
    let w = 0;
    let h = 0;
    for (let r = 0; r < sizes.length; r += PER_ROW) {
      const row = sizes.slice(r, r + PER_ROW);
      w = Math.max(w, row.reduce((a, s) => a + s.width, 0) + GAP_X * (row.length - 1));
      h += Math.max(...row.map((s) => s.height)) + (r ? GAP_Y : 0);
    }
    return { width: Math.max(SIZE[n.kind].width, w + 2 * PAD), height: Math.max(SIZE[n.kind].height / 2, h + PAD_TOP + PAD) };
  };
  const place = (n: CmmnNode, x: number, y: number) => {
    const s = sizeOf(n);
    n.bounds = { x, y, width: s.width, height: s.height };
    const kids = byParent.get(n.id) ?? [];
    let cy = y + PAD_TOP;
    for (let r = 0; r < kids.length; r += PER_ROW) {
      const row = kids.slice(r, r + PER_ROW);
      let cx = x + PAD;
      const rowH = Math.max(...row.map((k) => sizeOf(k).height));
      for (const k of row) {
        const ks = sizeOf(k);
        place(k, cx, cy + Math.round((rowH - ks.height) / 2));
        cx += ks.width + GAP_X;
      }
      cy += rowH + GAP_Y;
    }
  };
  const root = nodes.find((n) => n.kind === 'casePlanModel');
  if (root) place(root, 40, 40);
  const count = new Map<string, number>();
  for (const n of nodes) {
    if (!isCriterion(n.kind) || !n.host) continue;
    const host = nodes.find((h) => h.id === n.host);
    if (!host) continue;
    const key = `${n.host}:${n.kind}`;
    const i = count.get(key) ?? 0;
    count.set(key, i + 1);
    n.bounds = criterionBounds(host.bounds, n.kind, i);
  }
}

/** A new id with the given prefix, unique against the taken ids. */
export function newId(prefix: string, taken: (id: string) => boolean): string {
  for (;;) {
    const id = `${prefix}_${Math.random().toString(36).slice(2, 9)}`;
    if (!taken(id)) return id;
  }
}

const DEFINITION_KINDS = new Set<string>(['stage', 'humanTask', 'processTask', 'caseTask', 'decisionTask', 'task', 'milestone', 'timerEventListener', 'userEventListener', 'planFragment']);

/** Inserts child into parent after the last child whose local name is in `after`, else before the first in `before`, else at the end. */
function insertOrdered(parent: Element, child: Element, after: string[], before: string[]): void {
  const kids = children(parent);
  const last = [...kids].reverse().find((k) => after.includes(k.localName));
  if (last) {
    parent.insertBefore(child, last.nextSibling);
    return;
  }
  const first = kids.find((k) => before.includes(k.localName));
  parent.insertBefore(child, first ?? null);
}

/** tPlanFragment/tStage/casePlanModel sequence: planItem*, sentry*, planningTable?, planItemDefinition*, exitCriterion*. */
const ORDER = {
  planItem: { after: ['planItem'], before: ['sentry', 'planningTable', ...DEFINITION_KINDS, 'exitCriterion'] },
  sentry: { after: ['sentry', 'planItem'], before: ['planningTable', ...DEFINITION_KINDS, 'exitCriterion'] },
  definition: { after: [...DEFINITION_KINDS], before: ['exitCriterion'] },
  /** tPlanItem: itemControl?, entryCriterion*, exitCriterion*. */
  entryCriterion: { after: ['entryCriterion', 'itemControl'], before: ['exitCriterion'] },
  exitCriterion: { after: ['exitCriterion', 'entryCriterion', 'itemControl'], before: [] as string[] },
  /** tSentry: onPart*, ifPart?. */
  onPart: { after: ['planItemOnPart', 'caseFileItemOnPart'], before: ['ifPart', 'extensionElements'] },
};

/** Writes the edited diagram into the parsed document and returns the XML text. */
export function serializeCmmn(parsed: ParsedCmmn, edited: { nodes: CmmnNode[]; links: CmmnLink[] }): string {
  const { doc, model } = parsed;
  const root = doc.documentElement;
  const prefix = root.prefix;
  const make = (local: string) => doc.createElementNS(CMMN_NS, prefix ? `${prefix}:${local}` : local);
  let byId = indexById(doc);
  const taken = (id: string) => byId.has(id) || edited.nodes.some((n) => n.id === id || n.definitionId === id || n.sentryId === id);
  const nodeById = new Map(edited.nodes.map((n) => [n.id, n]));
  const containerEl = (nodeId: string | undefined): Element | undefined => {
    const n = nodeId ? nodeById.get(nodeId) ?? model.nodes.find((m) => m.id === nodeId) : undefined;
    return n?.definitionId ? byId.get(n.definitionId) : undefined;
  };
  const referenced = (attr: string, id: string) => Array.from(doc.getElementsByTagNameNS(CMMN_NS, '*')).some((e) => e.getAttribute(attr) === id);

  // 1. removed nodes (criteria first, then plan items; a removed stage takes its content with it)
  const keep = new Set(edited.nodes.map((n) => n.id));
  const removed = model.nodes.filter((n) => !keep.has(n.id) && n.kind !== 'casePlanModel');
  for (const n of removed.filter((r) => isCriterion(r.kind))) {
    byId.get(n.id)?.remove();
    if (n.sentryId && !referenced('sentryRef', n.sentryId)) byId.get(n.sentryId)?.remove();
  }
  for (const n of removed.filter((r) => !isCriterion(r.kind))) {
    const pi = byId.get(n.id);
    if (!pi || !pi.isConnected) continue;
    pi.remove();
    // on-parts that listened to the removed item go too
    for (const op of Array.from(doc.getElementsByTagNameNS(CMMN_NS, 'planItemOnPart'))) if (op.getAttribute('sourceRef') === n.id) op.remove();
    if (n.definitionId && !referenced('definitionRef', n.definitionId)) byId.get(n.definitionId)?.remove();
  }
  byId = indexById(doc);

  // 2. added plan items (parents before children)
  const added = edited.nodes.filter((n) => !byId.has(n.id));
  const depth = (n: CmmnNode, seen = new Set<string>()): number => {
    const p = n.parent ? nodeById.get(n.parent) : undefined;
    if (!p || seen.has(n.id)) return 0;
    seen.add(n.id);
    return 1 + depth(p, seen);
  };
  for (const n of added.filter((a) => isPlanItemKind(a.kind)).sort((a, b) => depth(a) - depth(b))) {
    const container = containerEl(n.parent);
    if (!container) continue;
    const defId = n.definitionId && !byId.has(n.definitionId) ? n.definitionId : newId(n.kind.charAt(0).toUpperCase() + n.kind.slice(1), taken);
    n.definitionId = defId;
    const def = make(n.kind);
    def.setAttribute('id', defId);
    if (n.name) def.setAttribute('name', n.name);
    insertOrdered(container, def, ORDER.definition.after, ORDER.definition.before);
    const pi = make('planItem');
    pi.setAttribute('id', n.id);
    pi.setAttribute('definitionRef', defId);
    insertOrdered(container, pi, ORDER.planItem.after, ORDER.planItem.before);
    byId.set(defId, def);
    byId.set(n.id, pi);
  }

  // 3. added criteria, each with its own new sentry in the host's container
  for (const n of added.filter((a) => isCriterion(a.kind))) {
    const host = n.host ? byId.get(n.host) : undefined;
    const hostNode = n.host ? nodeById.get(n.host) : undefined;
    if (!host || !hostNode) continue;
    const container = hostNode.kind === 'casePlanModel' ? host : containerEl(hostNode.parent);
    if (!container) continue;
    if (n.kind === 'entryCriterion' && hostNode.kind === 'casePlanModel') continue; // the case plan model has exit criteria only
    const sentryId = n.sentryId && !byId.has(n.sentryId) ? n.sentryId : newId('Sentry', taken);
    const sentry = make('sentry');
    sentry.setAttribute('id', sentryId);
    insertOrdered(container, sentry, ORDER.sentry.after, ORDER.sentry.before);
    const crit = make(n.kind);
    crit.setAttribute('id', n.id);
    crit.setAttribute('sentryRef', sentryId);
    if (hostNode.kind === 'casePlanModel') host.appendChild(crit); // casePlanModel: exitCriterion* comes last
    else insertOrdered(host, crit, ORDER[n.kind as CriterionKind].after, ORDER[n.kind as CriterionKind].before);
    n.sentryId = sentryId;
    byId.set(sentryId, sentry);
    byId.set(n.id, crit);
  }

  // 4. names
  for (const n of edited.nodes) {
    const el = byId.get(n.id);
    if (!el) continue;
    const target = isPlanItemKind(n.kind) && n.definitionId ? byId.get(n.definitionId) : el;
    if (!target) continue;
    if (n.name) target.setAttribute('name', n.name);
    else target.removeAttribute('name');
    if (isPlanItemKind(n.kind) && el.hasAttribute('name')) {
      if (n.name) el.setAttribute('name', n.name);
      else el.removeAttribute('name');
    }
  }

  // 5. sentry on-parts per criterion (a sentry shared with another criterion is copied before it is changed)
  for (const n of edited.nodes.filter((x) => isCriterion(x.kind))) {
    const crit = byId.get(n.id);
    let sentryId = crit?.getAttribute('sentryRef') ?? '';
    let sentry = byId.get(sentryId);
    if (!crit || !sentry) continue;
    const want = new Set(edited.links.filter((l) => l.target === n.id).map((l) => l.source));
    const have = children(sentry, 'planItemOnPart').map((op) => op.getAttribute('sourceRef') ?? '');
    if (have.length === want.size && have.every((s) => want.has(s))) continue;
    const sharers = Array.from(doc.getElementsByTagNameNS(CMMN_NS, '*')).filter((e) => e.getAttribute('sentryRef') === sentryId);
    if (sharers.length > 1) {
      const copy = sentry.cloneNode(true) as Element;
      sentryId = newId('Sentry', taken);
      copy.setAttribute('id', sentryId);
      for (const e of [copy, ...Array.from(copy.getElementsByTagNameNS(CMMN_NS, '*'))]) {
        if (e !== copy && e.hasAttribute('id')) e.setAttribute('id', newId(e.localName.charAt(0).toUpperCase() + e.localName.slice(1), taken));
      }
      sentry.parentNode!.insertBefore(copy, sentry.nextSibling);
      crit.setAttribute('sentryRef', sentryId);
      sentry = copy;
      byId = indexById(doc);
    }
    for (const op of children(sentry, 'planItemOnPart')) if (!want.has(op.getAttribute('sourceRef') ?? '')) op.remove();
    const now = new Set(children(sentry, 'planItemOnPart').map((op) => op.getAttribute('sourceRef') ?? ''));
    for (const src of want) {
      if (now.has(src)) continue;
      const op = make('planItemOnPart');
      op.setAttribute('id', newId('PlanItemOnPart', taken));
      op.setAttribute('sourceRef', src);
      const ev = make('standardEvent');
      ev.textContent = 'complete';
      op.appendChild(ev);
      insertOrdered(sentry, op, ORDER.onPart.after, ORDER.onPart.before);
      byId.set(op.getAttribute('id')!, op);
    }
    n.sentryId = sentryId;
  }

  // 6. CMMNDI regenerated from the edited bounds
  for (const old of Array.from(doc.getElementsByTagNameNS(CMMNDI_NS, 'CMMNDI'))) old.remove();
  for (const [p, ns] of [['cmmndi', CMMNDI_NS], ['dc', DC_NS], ['di', DI_NS]] as const) {
    if (!root.hasAttributeNS(XMLNS_NS, p)) root.setAttributeNS(XMLNS_NS, `xmlns:${p}`, ns);
  }
  const di = doc.createElementNS(CMMNDI_NS, 'cmmndi:CMMNDI');
  const diagram = doc.createElementNS(CMMNDI_NS, 'cmmndi:CMMNDiagram');
  diagram.setAttribute('id', model.diagramId ?? 'CMMNDiagram_1');
  di.appendChild(diagram);
  const r = (v: number) => String(Math.round(v));
  for (const n of edited.nodes) {
    if (!byId.has(n.id)) continue;
    const s = doc.createElementNS(CMMNDI_NS, 'cmmndi:CMMNShape');
    s.setAttribute('id', `${n.id}_di`);
    s.setAttribute('cmmnElementRef', n.id);
    const b = doc.createElementNS(DC_NS, 'dc:Bounds');
    b.setAttribute('x', r(n.bounds.x));
    b.setAttribute('y', r(n.bounds.y));
    b.setAttribute('width', r(n.bounds.width));
    b.setAttribute('height', r(n.bounds.height));
    s.appendChild(b);
    diagram.appendChild(s);
  }
  for (const l of edited.links) {
    const crit = byId.get(l.target);
    const sentry = crit ? byId.get(crit.getAttribute('sentryRef') ?? '') : undefined;
    const op = sentry ? children(sentry, 'planItemOnPart').find((o) => o.getAttribute('sourceRef') === l.source) : undefined;
    const opId = op?.getAttribute('id');
    if (!opId || l.waypoints.length < 2) continue;
    const e = doc.createElementNS(CMMNDI_NS, 'cmmndi:CMMNEdge');
    e.setAttribute('id', `${opId}_${l.target}_di`);
    e.setAttribute('cmmnElementRef', opId);
    e.setAttribute('sourceCMMNElementRef', l.source);
    e.setAttribute('targetCMMNElementRef', l.target);
    for (const p of l.waypoints) {
      const w = doc.createElementNS(DI_NS, 'di:waypoint');
      w.setAttribute('x', r(p.x));
      w.setAttribute('y', r(p.y));
      e.appendChild(w);
    }
    diagram.appendChild(e);
  }
  root.appendChild(di); // tDefinitions: ..., CMMNDI? comes last
  const text = new XMLSerializer().serializeToString(doc);
  return parsed.hadDeclaration && !/^\s*<\?xml/.test(text) ? `<?xml version="1.0" encoding="UTF-8"?>\n${text}` : text;
}
