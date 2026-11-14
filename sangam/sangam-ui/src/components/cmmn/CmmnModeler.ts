// SPDX-License-Identifier: Apache-2.0
/**
 * Owner decision 161 (M-041): Sangam's own CMMN 1.1 modeler, built from diagram-js 14.11.3 feature modules (no cmmn-js).
 * Shapes carry the CMMN kind, name and ids; CmmnRules keep the diagram valid CMMN:
 * - plan items live inside the case plan model or a stage;
 * - criteria sit on a host's border (attach support moves them with it): entry criteria on tasks, stages and
 *   milestones; exit criteria on tasks, stages and the case plan model;
 * - a sentry on-part connects a plan item to a criterion of another plan item;
 * - the case plan model cannot be deleted.
 * Event listeners in a file are shown, but cannot be added: the engine rejects them (EC-04, D-43, E16-b).
 */
import Diagram from 'diagram-js';
import ModelingModule from 'diagram-js/lib/features/modeling';
import MoveModule from 'diagram-js/lib/features/move';
import ResizeModule from 'diagram-js/lib/features/resize';
import SelectionModule from 'diagram-js/lib/features/selection';
import KeyboardModule from 'diagram-js/lib/features/keyboard';
import EditorActionsModule from 'diagram-js/lib/features/editor-actions';
import KeyboardMoveSelectionModule from 'diagram-js/lib/features/keyboard-move-selection';
import AttachSupportModule from 'diagram-js/lib/features/attach-support';
import RulesModule from 'diagram-js/lib/features/rules';
import ZoomScrollModule from 'diagram-js/lib/navigation/zoomscroll';
import MoveCanvasModule from 'diagram-js/lib/navigation/movecanvas';
import RuleProvider from 'diagram-js/lib/features/rules/RuleProvider';
import type EventBus from 'diagram-js/lib/core/EventBus';
import CmmnRenderer from './CmmnRenderer';
import { criterionBounds, isContainer, isCriterion, newId, SIZE, type CmmnLink, type CmmnNode, type CriterionKind, type NodeKind, type PlanItemKind } from './cmmnModel';

/** A diagram-js shape as this modeler uses it. */
export interface CmmnShape {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
  cmmnKind?: NodeKind;
  cmmnName?: string;
  definitionId?: string;
  sentryId?: string;
  parent?: CmmnShape;
  host?: CmmnShape;
  attachers: CmmnShape[];
  children: (CmmnShape | CmmnConnection)[];
  waypoints?: undefined;
}
export interface CmmnConnection {
  id: string;
  waypoints: { x: number; y: number }[];
  source?: CmmnShape;
  target?: CmmnShape;
  onPartId?: string;
  cmmnOnPart: true;
}

const ENTRY_HOSTS: NodeKind[] = ['stage', 'humanTask', 'processTask', 'caseTask', 'decisionTask', 'task', 'milestone'];
const EXIT_HOSTS: NodeKind[] = ['casePlanModel', 'stage', 'humanTask', 'processTask', 'caseTask', 'decisionTask', 'task'];
const RESIZABLE: NodeKind[] = ['casePlanModel', 'stage', 'humanTask', 'processTask', 'caseTask', 'decisionTask', 'task', 'milestone'];

export function canHostCriterion(host: NodeKind | undefined, kind: CriterionKind): boolean {
  return !!host && (kind === 'entryCriterion' ? ENTRY_HOSTS : EXIT_HOSTS).includes(host);
}

/** True when a sentry on-part may go from `source` to the criterion `target` (not the source's own criterion). */
export function canLink(source: Pick<CmmnShape, 'id' | 'cmmnKind'> | undefined, target: Pick<CmmnShape, 'cmmnKind' | 'host'> | undefined): boolean {
  if (!source?.cmmnKind || !target?.cmmnKind) return false;
  if (source.cmmnKind === 'casePlanModel' || isCriterion(source.cmmnKind) || !isCriterion(target.cmmnKind)) return false;
  return target.host?.id !== source.id;
}

/** Whether `shapes` may be moved into `target`. Criteria move only with their host. */
export function canMove(shapes: Pick<CmmnShape, 'cmmnKind'>[], target: Pick<CmmnShape, 'cmmnKind'> | undefined): boolean {
  if (!target) return false;
  return shapes.every((s) => {
    if (!s.cmmnKind || isCriterion(s.cmmnKind)) return false;
    if (s.cmmnKind === 'casePlanModel') return !target.cmmnKind;
    return !!target.cmmnKind && isContainer(target.cmmnKind);
  });
}

class CmmnRules extends RuleProvider {
  static $inject = ['eventBus'];

  constructor(eventBus: EventBus) {
    super(eventBus);
  }

  init(): void {
    this.addRule('elements.move', (ctx: { shapes: CmmnShape[]; target?: CmmnShape }) => canMove(ctx.shapes, ctx.target));
    this.addRule('shape.resize', (ctx: { shape: CmmnShape }) => !!ctx.shape.cmmnKind && RESIZABLE.includes(ctx.shape.cmmnKind));
    this.addRule('connection.create', (ctx: { source?: CmmnShape; target?: CmmnShape }) => canLink(ctx.source, ctx.target));
    this.addRule(['connection.reconnect', 'connection.reconnectStart', 'connection.reconnectEnd'], () => false);
    this.addRule('elements.delete', (ctx: { elements: CmmnShape[] }) => ctx.elements.filter((e) => e.cmmnKind !== 'casePlanModel'));
  }
}

/** Command `cmmn.updateName`: renames a shape, with undo. */
class UpdateNameHandler {
  execute(ctx: { element: CmmnShape; name: string; oldName?: string }) {
    ctx.oldName = ctx.element.cmmnName;
    ctx.element.cmmnName = ctx.name;
    return [ctx.element];
  }

  revert(ctx: { element: CmmnShape; name: string; oldName?: string }) {
    ctx.element.cmmnName = ctx.oldName;
    return [ctx.element];
  }
}

const CmmnModule = {
  __init__: ['cmmnRenderer', 'cmmnRules'],
  cmmnRenderer: ['type', CmmnRenderer],
  cmmnRules: ['type', CmmnRules],
};

interface Services {
  canvas: {
    getRootElement(): CmmnShape;
    setRootElement(r: unknown): unknown;
    addShape(s: unknown, p?: unknown): CmmnShape;
    addConnection(c: unknown, p?: unknown): CmmnConnection;
    zoom(z: number | 'fit-viewport', c?: unknown): number;
    scrollToElement(e: unknown, padding?: number): void;
  };
  elementFactory: { createRoot(a: object): unknown; createShape(a: object): CmmnShape; createConnection(a: object): CmmnConnection };
  elementRegistry: { get(id: string): (CmmnShape | CmmnConnection) | undefined; getAll(): (CmmnShape | CmmnConnection)[] };
  modeling: {
    createShape(s: object, position: { x: number; y: number }, target: CmmnShape, hints?: object): CmmnShape;
    connect(source: CmmnShape, target: CmmnShape, attrs?: object): CmmnConnection;
    removeElements(e: unknown[]): void;
    resizeShape(s: CmmnShape, bounds: { x: number; y: number; width: number; height: number }): void;
  };
  selection: { select(e: unknown): void; get(): (CmmnShape | CmmnConnection)[] };
  commandStack: { registerHandler(name: string, h: unknown): void; execute(name: string, ctx: object): void };
  eventBus: { on(e: string, f: (ev: { newSelection?: unknown[] }) => void): void };
  editorActions: { trigger(a: string): void };
}

export interface CmmnModelerOptions {
  /** Ids already used in the file (definitions, sentries, on-parts, ...), so new ids never clash. */
  takenIds: Set<string>;
  onChange?: () => void;
  onSelect?: (node: CmmnNode | undefined) => void;
}

export interface CmmnModelerApi {
  exportModel(): { nodes: CmmnNode[]; links: CmmnLink[] };
  select(id: string): void;
  selected(): CmmnNode | undefined;
  /** Adds a plan item to the selected stage or the case plan model; returns its id. */
  addPlanItem(kind: PlanItemKind, name: string): string | undefined;
  /** Adds a criterion to the selected plan item; undefined when the selection cannot host it. */
  addCriterion(kind: CriterionKind): string | undefined;
  /** The criteria a sentry on-part from `sourceId` may go to. */
  linkTargets(sourceId: string): CmmnNode[];
  link(sourceId: string, criterionId: string): boolean;
  removeSelected(): void;
  rename(id: string, name: string): void;
  undo(): void;
  redo(): void;
  fit(): void;
  destroy(): void;
}

function toNode(s: CmmnShape): CmmnNode {
  return {
    id: s.id,
    kind: s.cmmnKind!,
    name: s.cmmnName ?? '',
    parent: s.cmmnKind && isCriterion(s.cmmnKind) ? s.host?.parent?.cmmnKind ? s.host.parent.id : undefined : s.parent?.cmmnKind ? s.parent.id : undefined,
    host: s.host?.id,
    bounds: { x: s.x, y: s.y, width: s.width, height: s.height },
    definitionId: s.definitionId,
    sentryId: s.sentryId,
  };
}

const isShape = (e: CmmnShape | CmmnConnection | undefined): e is CmmnShape => !!e && !('cmmnOnPart' in e) && !!(e as CmmnShape).cmmnKind;
const isLink = (e: CmmnShape | CmmnConnection | undefined): e is CmmnConnection => !!e && 'cmmnOnPart' in e;

export function createCmmnModeler(container: HTMLElement, model: { nodes: CmmnNode[]; links: CmmnLink[] }, opts: CmmnModelerOptions): CmmnModelerApi {
  const diagram = new Diagram({
    canvas: { container },
    keyboard: { bindTo: container },
    modules: [
      ModelingModule,
      MoveModule,
      ResizeModule,
      SelectionModule,
      KeyboardModule,
      EditorActionsModule,
      KeyboardMoveSelectionModule,
      AttachSupportModule,
      RulesModule,
      ZoomScrollModule,
      MoveCanvasModule,
      CmmnModule,
    ],
  });
  const get = <K extends keyof Services>(name: K) => diagram.get(name as string) as Services[K];
  const canvas = get('canvas');
  const factory = get('elementFactory');
  const registry = get('elementRegistry');
  const modeling = get('modeling');
  const selection = get('selection');
  const commandStack = get('commandStack');
  const eventBus = get('eventBus');
  commandStack.registerHandler('cmmn.updateName', UpdateNameHandler);

  // import
  const root = factory.createRoot({ id: '__cmmn_root' });
  canvas.setRootElement(root);
  const byId = new Map<string, CmmnShape>();
  const depth = (n: CmmnNode): number => {
    let d = 0;
    for (let p = n.parent; p && d < 100; p = model.nodes.find((m) => m.id === p)?.parent) d++;
    return d;
  };
  const items = model.nodes.filter((n) => !isCriterion(n.kind)).sort((a, b) => depth(a) - depth(b));
  for (const n of items) {
    const parent = n.parent ? byId.get(n.parent) : undefined;
    const shape = factory.createShape({ id: n.id, ...n.bounds, cmmnKind: n.kind, cmmnName: n.name, definitionId: n.definitionId });
    byId.set(n.id, canvas.addShape(shape, parent ?? root));
  }
  for (const n of model.nodes.filter((m) => isCriterion(m.kind))) {
    const host = n.host ? byId.get(n.host) : undefined;
    if (!host) continue;
    const shape = factory.createShape({ id: n.id, ...n.bounds, cmmnKind: n.kind, cmmnName: n.name, sentryId: n.sentryId, host });
    host.attachers.push(shape);
    byId.set(n.id, canvas.addShape(shape, host.parent ?? root));
  }
  for (const l of model.links) {
    const source = byId.get(l.source);
    const target = byId.get(l.target);
    if (!source || !target || l.waypoints.length < 2) continue;
    canvas.addConnection(factory.createConnection({ id: l.id, source, target, waypoints: l.waypoints, onPartId: l.onPartId, cmmnOnPart: true }), root);
  }
  canvas.zoom('fit-viewport');

  const taken = (id: string) => opts.takenIds.has(id) || !!registry.get(id);
  const selectedShape = (): CmmnShape | undefined => selection.get().find(isShape);
  eventBus.on('commandStack.changed', () => opts.onChange?.());
  eventBus.on('selection.changed', (ev) => {
    const s = (ev.newSelection ?? []).find((e) => isShape(e as CmmnShape)) as CmmnShape | undefined;
    opts.onSelect?.(s ? toNode(s) : undefined);
  });

  /** The first free slot for a shape of `size` inside `target`; grows the target when it is full. */
  const freeSlot = (target: CmmnShape, size: { width: number; height: number }) => {
    const kids = target.children.filter(isShape).filter((c) => !isCriterion(c.cmmnKind!));
    const overlaps = (x: number, y: number) => kids.some((k) => x < k.x + k.width + 20 && x + size.width + 20 > k.x && y < k.y + k.height + 20 && y + size.height + 20 > k.y);
    for (let y = target.y + 50; y + size.height <= target.y + target.height - 20; y += 30) {
      for (let x = target.x + 40; x + size.width <= target.x + target.width - 20; x += 30) if (!overlaps(x, y)) return { x, y };
    }
    const y = Math.max(target.y + 50, ...kids.map((k) => k.y + k.height + 40));
    modeling.resizeShape(target, { x: target.x, y: target.y, width: Math.max(target.width, size.width + 80), height: y - target.y + size.height + 40 });
    return { x: target.x + 40, y };
  };

  return {
    exportModel() {
      const all = registry.getAll();
      const nodes = all.filter(isShape).map(toNode);
      const links = all.filter(isLink).map((c) => ({ id: c.id, source: c.source!.id, target: c.target!.id, onPartId: c.onPartId, waypoints: c.waypoints.map((p) => ({ x: p.x, y: p.y })) }));
      return { nodes, links };
    },
    select(id) {
      const e = registry.get(id);
      if (!e) return;
      selection.select(e);
      if (isShape(e)) canvas.scrollToElement(e, 60);
    },
    selected() {
      const s = selectedShape();
      return s ? toNode(s) : undefined;
    },
    addPlanItem(kind, name) {
      const sel = selectedShape();
      const target = sel && (sel.cmmnKind === 'stage' || sel.cmmnKind === 'casePlanModel') ? sel : registry.getAll().filter(isShape).find((s) => s.cmmnKind === 'casePlanModel');
      if (!target) return undefined;
      const size = SIZE[kind];
      const at = freeSlot(target, size);
      const prefix = 'PlanItem';
      const id = newId(prefix, taken);
      const definitionId = newId(kind.charAt(0).toUpperCase() + kind.slice(1), (x) => taken(x) || x === id);
      const shape = modeling.createShape(
        { id, width: size.width, height: size.height, cmmnKind: kind, cmmnName: name, definitionId },
        { x: at.x + size.width / 2, y: at.y + size.height / 2 },
        target,
      );
      selection.select(shape);
      return shape.id;
    },
    addCriterion(kind) {
      const host = selectedShape();
      if (!host || !canHostCriterion(host.cmmnKind, kind)) return undefined;
      const index = host.attachers.filter((a) => a.cmmnKind === kind).length;
      const b = criterionBounds(host, kind, index);
      const id = newId(kind === 'entryCriterion' ? 'EntryCriterion' : 'ExitCriterion', taken);
      const sentryId = newId('Sentry', (x) => taken(x) || x === id);
      const shape = modeling.createShape(
        { id, width: b.width, height: b.height, cmmnKind: kind, cmmnName: '', sentryId },
        { x: b.x + b.width / 2, y: b.y + b.height / 2 },
        host,
        { attach: true },
      );
      selection.select(shape);
      return shape.id;
    },
    linkTargets(sourceId) {
      const source = registry.get(sourceId);
      if (!isShape(source)) return [];
      const already = new Set(registry.getAll().filter(isLink).filter((c) => c.source?.id === sourceId).map((c) => c.target?.id));
      return registry.getAll().filter(isShape).filter((t) => canLink(source, t) && !already.has(t.id)).map(toNode);
    },
    link(sourceId, criterionId) {
      const source = registry.get(sourceId);
      const target = registry.get(criterionId);
      if (!isShape(source) || !isShape(target) || !canLink(source, target)) return false;
      modeling.connect(source, target, { id: newId('OnPart', taken), cmmnOnPart: true });
      return true;
    },
    removeSelected() {
      get('editorActions').trigger('removeSelection');
    },
    rename(id, name) {
      const e = registry.get(id);
      if (isShape(e) && (e.cmmnName ?? '') !== name) commandStack.execute('cmmn.updateName', { element: e, name });
    },
    undo() {
      get('editorActions').trigger('undo');
    },
    redo() {
      get('editorActions').trigger('redo');
    },
    fit() {
      canvas.zoom('fit-viewport');
    },
    destroy() {
      diagram.destroy();
    },
  };
}
