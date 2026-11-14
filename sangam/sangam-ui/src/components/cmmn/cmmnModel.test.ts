// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// Decision 161 (M-041): CMMN 1.1 parse, generated layout and write-back. Written, not run in this phase.
import { describe, expect, it } from 'vitest';
import { CMMN_NS, CMMNDI_NS, CmmnParseError, DC_NS, criterionBounds, layout, parseCmmn, serializeCmmn, type CmmnNode } from './cmmnModel';
import { canHostCriterion, canLink, canMove } from './CmmnModeler';

/** Shaped like the Sangam packs (sangam-packs .../grievance/sangam-grievance-case.cmmn): default namespace, no CMMNDI. */
const PACK = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="${CMMN_NS}" xmlns:operaton="http://operaton.org/schema/1.0/cmmn" id="Defs" targetNamespace="http://sangam.org/cmmn">
  <case id="grievance" name="Grievance">
    <casePlanModel id="CasePlan" name="Grievance case" autoComplete="true">
      <planItem id="PI_Review" definitionRef="Review">
        <itemControl><repetitionRule /></itemControl>
        <exitCriterion id="Exit_Review" sentryRef="S_Close" />
      </planItem>
      <planItem id="PI_Notify" definitionRef="Notify">
        <entryCriterion id="Entry_Notify" sentryRef="S_Reviewed" />
      </planItem>
      <planItem id="PI_Done" definitionRef="Done" />
      <planItem id="PI_Stage" definitionRef="Escalation" />
      <sentry id="S_Reviewed"><planItemOnPart id="OP_Reviewed" sourceRef="PI_Review"><standardEvent>complete</standardEvent></planItemOnPart></sentry>
      <sentry id="S_Close"><planItemOnPart id="OP_Close" sourceRef="PI_Done"><standardEvent>occur</standardEvent></planItemOnPart></sentry>
      <humanTask id="Review" name="Review grievance" operaton:candidateGroups="cell" />
      <processTask id="Notify" name="Notify citizen" processRef="notify" />
      <milestone id="Done" name="Resolved" />
      <stage id="Escalation" name="Escalation">
        <planItem id="PI_Senior" definitionRef="Senior" />
        <humanTask id="Senior" name="Senior review" />
      </stage>
    </casePlanModel>
  </case>
</definitions>`;

const node = (nodes: CmmnNode[], id: string) => nodes.find((n) => n.id === id)!;
const els = (doc: Document, local: string) => Array.from(doc.getElementsByTagNameNS(CMMN_NS, local));
const reparse = (xml: string) => new DOMParser().parseFromString(xml, 'application/xml');

describe('parseCmmn', () => {
  it('reads plan items, definitions, criteria and on-parts of a pack file without CMMNDI', () => {
    const { model } = parseCmmn(PACK);
    expect(model.caseId).toBe('grievance');
    expect(model.hadLayout).toBe(false);
    expect(node(model.nodes, 'CasePlan').kind).toBe('casePlanModel');
    expect(node(model.nodes, 'PI_Review')).toMatchObject({ kind: 'humanTask', name: 'Review grievance', parent: 'CasePlan', definitionId: 'Review' });
    expect(node(model.nodes, 'PI_Senior')).toMatchObject({ kind: 'humanTask', parent: 'PI_Stage' });
    expect(node(model.nodes, 'Entry_Notify')).toMatchObject({ kind: 'entryCriterion', host: 'PI_Notify', sentryId: 'S_Reviewed' });
    expect(model.links.map((l) => [l.source, l.target, l.onPartId])).toEqual([
      ['PI_Review', 'Entry_Notify', 'OP_Reviewed'],
      ['PI_Done', 'Exit_Review', 'OP_Close'],
    ]);
  });

  it('reads a prefixed file the same way', () => {
    const prefixed = PACK.replace(`xmlns="${CMMN_NS}"`, `xmlns:cmmn="${CMMN_NS}"`).replace(/<(\/?)(?!\?|operaton)(\w+)/g, '<$1cmmn:$2');
    const { model } = parseCmmn(prefixed);
    expect(model.nodes.map((n) => n.id).sort()).toEqual(parseCmmn(PACK).model.nodes.map((n) => n.id).sort());
  });

  it('refuses what it cannot show, with a reason', () => {
    const code = (xml: string) => {
      try {
        parseCmmn(xml);
        return 'ok';
      } catch (e) {
        return (e as CmmnParseError).code;
      }
    };
    expect(code('<definitions')).toBe('xml');
    expect(code('<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"/>')).toBe('not-cmmn');
    expect(code(`<definitions xmlns="${CMMN_NS}"/>`)).toBe('no-case');
    expect(code(`<definitions xmlns="${CMMN_NS}"><case id="c"/></definitions>`)).toBe('no-plan-model');
  });

  it('uses CMMNDI bounds when every element has them', () => {
    const first = serializeCmmn(parseCmmn(PACK), parseCmmn(PACK).model);
    const again = parseCmmn(first);
    expect(again.model.hadLayout).toBe(true);
    expect(node(again.model.nodes, 'PI_Review').bounds).toEqual(node(parseCmmn(PACK).model.nodes, 'PI_Review').bounds);
  });
});

describe('generated layout', () => {
  it('puts every plan item inside its container and every criterion on its host border', () => {
    const { model } = parseCmmn(PACK);
    const inside = (a: CmmnNode, b: CmmnNode) => a.bounds.x >= b.bounds.x && a.bounds.y >= b.bounds.y && a.bounds.x + a.bounds.width <= b.bounds.x + b.bounds.width && a.bounds.y + a.bounds.height <= b.bounds.y + b.bounds.height;
    for (const n of model.nodes) {
      if (n.kind === 'entryCriterion' || n.kind === 'exitCriterion') {
        const host = node(model.nodes, n.host!).bounds;
        const cx = n.bounds.x + n.bounds.width / 2;
        expect(cx).toBe(n.kind === 'entryCriterion' ? host.x : host.x + host.width);
      } else if (n.parent) expect(inside(n, node(model.nodes, n.parent))).toBe(true);
    }
  });

  it('stacks several criteria of one kind instead of overlapping them', () => {
    const host = { x: 0, y: 0, width: 140, height: 80 };
    const a = criterionBounds(host, 'entryCriterion', 0);
    const b = criterionBounds(host, 'entryCriterion', 1);
    expect(Math.abs(a.y - b.y)).toBeGreaterThanOrEqual(a.height);
  });

  it('is stable: the same nodes give the same layout', () => {
    const a = parseCmmn(PACK).model.nodes;
    const b = a.map((n) => ({ ...n }));
    layout(b);
    expect(b.map((n) => n.bounds)).toEqual(a.map((n) => n.bounds));
  });
});

describe('serializeCmmn', () => {
  it('without edits keeps the semantics and adds CMMNDI', () => {
    const parsed = parseCmmn(PACK);
    const out = serializeCmmn(parsed, parsed.model);
    expect(out.startsWith('<?xml')).toBe(true);
    const doc = reparse(out);
    expect(doc.getElementsByTagName('parsererror').length).toBe(0);
    expect(els(doc, 'itemControl').length).toBe(1);
    expect(els(doc, 'humanTask')[0].getAttribute('operaton:candidateGroups')).toBe('cell');
    expect(els(doc, 'standardEvent').map((e) => e.textContent)).toEqual(['complete', 'occur']);
    const shapes = Array.from(doc.getElementsByTagNameNS(CMMNDI_NS, 'CMMNShape')).map((s) => s.getAttribute('cmmnElementRef'));
    expect(shapes.sort()).toEqual(parsed.model.nodes.map((n) => n.id).sort());
    expect(doc.getElementsByTagNameNS(CMMNDI_NS, 'CMMNEdge').length).toBe(2);
    expect(doc.getElementsByTagNameNS(DC_NS, 'Bounds').length).toBe(shapes.length);
    // CMMNDI is the last child of definitions (tDefinitions sequence)
    expect(doc.documentElement.lastElementChild?.localName).toBe('CMMNDI');
  });

  it('writes a new name on the definition', () => {
    const parsed = parseCmmn(PACK);
    const nodes = parsed.model.nodes.map((n) => (n.id === 'PI_Review' ? { ...n, name: 'Review the grievance' } : n));
    const doc = reparse(serializeCmmn(parsed, { nodes, links: parsed.model.links }));
    expect(els(doc, 'humanTask').find((e) => e.getAttribute('id') === 'Review')?.getAttribute('name')).toBe('Review the grievance');
  });

  it('adds a plan item with its definition in schema order', () => {
    const parsed = parseCmmn(PACK);
    const added: CmmnNode = { id: 'PI_New', kind: 'decisionTask', name: 'Check rules', parent: 'CasePlan', bounds: { x: 100, y: 300, width: 140, height: 80 }, definitionId: 'Decision_New' };
    const doc = reparse(serializeCmmn(parsed, { nodes: [...parsed.model.nodes, added], links: parsed.model.links }));
    const cpm = els(doc, 'casePlanModel')[0];
    const order = Array.from(cpm.children).map((c) => c.localName);
    expect(order.lastIndexOf('planItem')).toBeLessThan(order.indexOf('sentry'));
    expect(order.lastIndexOf('sentry')).toBeLessThan(order.indexOf('humanTask'));
    const pi = els(doc, 'planItem').find((e) => e.getAttribute('id') === 'PI_New')!;
    expect(pi.getAttribute('definitionRef')).toBe('Decision_New');
    expect(els(doc, 'decisionTask')[0].getAttribute('name')).toBe('Check rules');
  });

  it('adds a criterion with its own sentry and an on-part from the linked item', () => {
    const parsed = parseCmmn(PACK);
    const crit: CmmnNode = { id: 'Entry_Done', kind: 'entryCriterion', name: '', host: 'PI_Done', parent: 'CasePlan', bounds: { x: 0, y: 0, width: 20, height: 28 }, sentryId: 'S_New' };
    const link = { id: 'L1', source: 'PI_Notify', target: 'Entry_Done', waypoints: [{ x: 0, y: 0 }, { x: 10, y: 10 }] };
    const doc = reparse(serializeCmmn(parsed, { nodes: [...parsed.model.nodes, crit], links: [...parsed.model.links, link] }));
    const ec = els(doc, 'entryCriterion').find((e) => e.getAttribute('id') === 'Entry_Done')!;
    expect(ec.getAttribute('sentryRef')).toBe('S_New');
    expect(ec.parentElement?.getAttribute('id')).toBe('PI_Done');
    const sentry = els(doc, 'sentry').find((e) => e.getAttribute('id') === 'S_New')!;
    const op = sentry.getElementsByTagNameNS(CMMN_NS, 'planItemOnPart')[0];
    expect(op.getAttribute('sourceRef')).toBe('PI_Notify');
    expect(op.getElementsByTagNameNS(CMMN_NS, 'standardEvent')[0].textContent).toBe('complete');
  });

  it('removing a plan item removes its definition, criteria and the on-parts that listened to it', () => {
    const parsed = parseCmmn(PACK);
    const nodes = parsed.model.nodes.filter((n) => n.id !== 'PI_Done');
    const links = parsed.model.links.filter((l) => l.source !== 'PI_Done');
    const doc = reparse(serializeCmmn(parsed, { nodes, links }));
    expect(els(doc, 'planItem').some((e) => e.getAttribute('id') === 'PI_Done')).toBe(false);
    expect(els(doc, 'milestone').length).toBe(0);
    expect(els(doc, 'planItemOnPart').some((e) => e.getAttribute('sourceRef') === 'PI_Done')).toBe(false);
  });

  it('removing a link removes only that on-part and keeps an event other than complete', () => {
    const parsed = parseCmmn(PACK);
    const links = parsed.model.links.filter((l) => l.source !== 'PI_Review');
    const doc = reparse(serializeCmmn(parsed, { nodes: parsed.model.nodes, links }));
    expect(els(doc, 'planItemOnPart').map((e) => e.getAttribute('id'))).toEqual(['OP_Close']);
    expect(els(doc, 'standardEvent').map((e) => e.textContent)).toEqual(['occur']);
  });

  it('a sentry shared by two criteria is copied before one of them changes', () => {
    const shared = PACK.replace(
      '<planItem id="PI_Done" definitionRef="Done" />',
      '<planItem id="PI_Done" definitionRef="Done"><entryCriterion id="Entry_Done" sentryRef="S_Reviewed" /></planItem>',
    );
    const parsed = parseCmmn(shared);
    const links = parsed.model.links.filter((l) => l.target !== 'Entry_Done');
    const doc = reparse(serializeCmmn(parsed, { nodes: parsed.model.nodes, links }));
    const notify = els(doc, 'entryCriterion').find((e) => e.getAttribute('id') === 'Entry_Notify')!;
    const done = els(doc, 'entryCriterion').find((e) => e.getAttribute('id') === 'Entry_Done')!;
    expect(notify.getAttribute('sentryRef')).toBe('S_Reviewed');
    expect(done.getAttribute('sentryRef')).not.toBe('S_Reviewed');
    const reviewed = els(doc, 'sentry').find((e) => e.getAttribute('id') === 'S_Reviewed')!;
    expect(reviewed.getElementsByTagNameNS(CMMN_NS, 'planItemOnPart').length).toBe(1);
  });

  it('gives the same text when saved twice from the original', () => {
    const edit = () => {
      const p = parseCmmn(PACK);
      return serializeCmmn(p, { nodes: p.model.nodes.map((n) => ({ ...n })), links: p.model.links });
    };
    expect(edit()).toBe(edit());
  });

  it('keeps a prefixed file prefixed', () => {
    const prefixed = PACK.replace(`xmlns="${CMMN_NS}"`, `xmlns:cmmn="${CMMN_NS}"`).replace(/<(\/?)(?!\?|operaton)(\w+)/g, '<$1cmmn:$2');
    const parsed = parseCmmn(prefixed);
    const added: CmmnNode = { id: 'PI_X', kind: 'milestone', name: 'M', parent: 'CasePlan', bounds: { x: 0, y: 0, width: 140, height: 40 }, definitionId: 'M_X' };
    const out = serializeCmmn(parsed, { nodes: [...parsed.model.nodes, added], links: parsed.model.links });
    expect(out).toContain('<cmmn:planItem id="PI_X"');
    expect(out).toContain('<cmmn:milestone id="M_X"');
  });
});

describe('editing rules', () => {
  it('entry criteria on tasks, stages and milestones; exit criteria also on the case plan model, never on a milestone', () => {
    expect(canHostCriterion('humanTask', 'entryCriterion')).toBe(true);
    expect(canHostCriterion('milestone', 'entryCriterion')).toBe(true);
    expect(canHostCriterion('milestone', 'exitCriterion')).toBe(false);
    expect(canHostCriterion('casePlanModel', 'entryCriterion')).toBe(false);
    expect(canHostCriterion('casePlanModel', 'exitCriterion')).toBe(true);
    expect(canHostCriterion('userEventListener', 'entryCriterion')).toBe(false);
  });

  it('links go from a plan item to a criterion of another item', () => {
    const task = { id: 'A', cmmnKind: 'humanTask' as const };
    expect(canLink(task, { cmmnKind: 'entryCriterion', host: { id: 'B' } as never })).toBe(true);
    expect(canLink(task, { cmmnKind: 'entryCriterion', host: { id: 'A' } as never })).toBe(false);
    expect(canLink(task, { cmmnKind: 'humanTask' })).toBe(false);
    expect(canLink({ id: 'C', cmmnKind: 'casePlanModel' }, { cmmnKind: 'exitCriterion', host: { id: 'B' } as never })).toBe(false);
  });

  it('plan items move only into a stage or the case plan model; criteria only with their host', () => {
    expect(canMove([{ cmmnKind: 'humanTask' }], { cmmnKind: 'stage' })).toBe(true);
    expect(canMove([{ cmmnKind: 'humanTask' }], { cmmnKind: 'humanTask' })).toBe(false);
    expect(canMove([{ cmmnKind: 'entryCriterion' }], { cmmnKind: 'stage' })).toBe(false);
    expect(canMove([{ cmmnKind: 'casePlanModel' }], {})).toBe(true);
    expect(canMove([{ cmmnKind: 'humanTask' }], undefined)).toBe(false);
  });
});
