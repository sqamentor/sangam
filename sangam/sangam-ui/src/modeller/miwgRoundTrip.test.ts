// SPDX-License-Identifier: Apache-2.0
/**
 * R10-bpmn MIWG harness, modeller level: every BPMN MIWG reference model (bpmn-miwg-test-suite Reference/*.bpmn,
 * CC BY 3.0, sangam-flowable-ports/src/test/resources/miwg/reference) is read and written by bpmn-moddle, the
 * serialisation layer of the bpmn-js Modeler (importXML -> moddle.fromXML, saveXML -> moddle.toXML), and read again.
 * The round trip must keep every element (id and BPMN type), every sequence/message flow end, every diagram shape and
 * edge with its bounds, and add no new import warning. bpmn-js cannot lay out SVG in jsdom, so rendering is not covered.
 */
import { BpmnModdle } from 'bpmn-moddle'; // bpmn-moddle 10 (bpmn-js 18): named export
import { describe, expect, it } from 'vitest';

// Vite reads the files (vite.config.ts allows this folder under Vitest only)
const SOURCES = import.meta.glob('../../../sangam-flowable-ports/src/test/resources/miwg/reference/*.bpmn', {
  query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const MODELS = Object.keys(SOURCES).sort();

type El = { $type: string; id?: string; [k: string]: unknown };

/** id -> type, flow ends and DI bounds: what a lossless round trip must keep. */
function signature(root: El): string[] {
  const out: string[] = [];
  const seen = new Set<unknown>();
  const visit = (e: unknown) => {
    if (!e || typeof e !== 'object' || seen.has(e)) return;
    seen.add(e);
    const el = e as El;
    if (typeof el.$type === 'string' && el.id) {
      let s = `${el.id}:${el.$type}`;
      const ref = (k: string) => ((el[k] as El | undefined)?.id ?? '');
      if (el.$type === 'bpmn:SequenceFlow' || el.$type === 'bpmn:MessageFlow') s += ` ${ref('sourceRef')}->${ref('targetRef')}`;
      if (el.$type === 'bpmndi:BPMNShape' || el.$type === 'bpmndi:BPMNEdge') s += ` ${ref('bpmnElement')}`;
      const b = el.bounds as { x: number; y: number; width: number; height: number } | undefined;
      if (b) s += ` [${b.x},${b.y},${b.width},${b.height}]`;
      out.push(s);
    }
    for (const [k, v] of Object.entries(el)) {
      if (k.startsWith('$') || k.endsWith('Ref') || k === 'bpmnElement' || k === 'sourceElement' || k === 'targetElement') continue;
      if (Array.isArray(v)) v.forEach(visit);
      else if (v && typeof v === 'object') visit(v);
    }
  };
  visit(root);
  return out.sort();
}

describe('MIWG reference models: bpmn-js (bpmn-moddle) round trip', () => {
  it('finds the 22 reference models', () => {
    expect(MODELS).toHaveLength(22);
  });

  for (const file of MODELS) {
    const name = file.substring(file.lastIndexOf('/') + 1);
    it(`${name} survives import -> export -> import without loss`, async () => {
      const moddle = new BpmnModdle();
      const xml = SOURCES[file];
      const first = await moddle.fromXML(xml, 'bpmn:Definitions');
      const { xml: written } = await moddle.toXML(first.rootElement, { format: true });
      const second = await new BpmnModdle().fromXML(written, 'bpmn:Definitions');
      // No new warning. Warnings of the source file itself (an ISO-8859-1 declaration read as UTF-8 in A.*/B.*, three
      // references to an undefined <triso:unspecified> in C.8.1) are allowed; a reference that resolves to nothing is
      // not written back, which is the only change bpmn-moddle makes to these files.
      const before = new Set(first.warnings.map((w: { message: string }) => w.message));
      expect(second.warnings.map((w: { message: string }) => w.message).filter((m: string) => !before.has(m))).toEqual([]);
      const a = signature(first.rootElement as El);
      const b = signature(second.rootElement as El);
      expect(a.length).toBeGreaterThan(0);
      expect(b).toEqual(a);
    });
  }
});
