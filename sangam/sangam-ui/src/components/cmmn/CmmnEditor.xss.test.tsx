// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// Code-scanning alerts #1 and #195 (DOM text reinterpreted as HTML) on Editors.tsx and cmmnModel.ts: both parse the file
// with DOMParser as 'application/xml' into a detached document, read names with getAttribute, and show them as React
// text or as SVG text nodes (textContent). Nothing is assigned to innerHTML. This file proves it: a CMMN name that is
// HTML markup is shown as its literal text in the diagram view, in the XML view and by the real shape renderer, and no
// element is created from it.
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type EventBus from 'diagram-js/lib/core/EventBus';
import { initI18n } from '../../i18n';
import type { CmmnLink, CmmnNode, NodeKind } from './cmmnModel';
import { CMMN_NS, parseCmmn } from './cmmnModel';
import CmmnRenderer from './CmmnRenderer';

vi.mock('bpmn-js/lib/Modeler', () => ({ default: class {} }));
vi.mock('dmn-js/lib/Modeler', () => ({ default: class {} }));
vi.mock('@bpmn-io/form-js-editor', () => ({ FormEditor: class {} }));

// jsdom has no SVG layout, so the diagram-js canvas is replaced as in CmmnEditor.test.tsx; the shape renderer itself
// is exercised directly below.
const fake = vi.hoisted(() => ({ model: null as null | { nodes: CmmnNode[]; links: CmmnLink[] } }));
vi.mock('./CmmnModeler', async (orig) => ({
  ...(await orig<typeof import('./CmmnModeler')>()),
  createCmmnModeler: (_host: HTMLElement, model: { nodes: CmmnNode[]; links: CmmnLink[] }) => {
    fake.model = { nodes: model.nodes.map((n) => ({ ...n })), links: model.links };
    return {
      exportModel: () => fake.model!,
      select: () => undefined,
      selected: () => undefined,
      addPlanItem: () => undefined,
      addCriterion: () => undefined,
      linkTargets: () => [],
      link: () => false,
      removeSelected: () => undefined,
      rename: () => undefined,
      undo: () => undefined,
      redo: () => undefined,
      fit: () => undefined,
      destroy: () => undefined,
    };
  },
}));

import { CmmnEditor, CmmnSourceEditor } from '../Editors';

beforeAll(() => initI18n('en'));
afterEach(() => {
  cleanup();
  fake.model = null;
  vi.restoreAllMocks();
});

const HOSTILE = '<img src=x onerror=alert(1)>';
const ESCAPED = '&lt;img src=x onerror=alert(1)&gt;';

/** A case whose plan model, stage, task and milestone are all named with the markup (escaped, as XML requires). */
const CASE = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="${CMMN_NS}" id="D" targetNamespace="t">
  <case id="c"><casePlanModel id="CPM" name="${ESCAPED}">
    <planItem id="PI_A" definitionRef="A" />
    <planItem id="PI_M" definitionRef="M" name="${ESCAPED}" />
    <humanTask id="A" name="${ESCAPED}" />
    <milestone id="M" />
  </casePlanModel></case>
</definitions>`;

/** No element was made from the name anywhere in the page, and no script-bearing attribute exists. */
function expectNoInjectedMarkup() {
  expect(document.querySelectorAll('img')).toHaveLength(0);
  expect(document.querySelectorAll('[onerror], script')).toHaveLength(0);
}

describe('a CMMN name that is HTML markup is text, never markup', () => {
  it('is read from the XML as the literal string', () => {
    const { model } = parseCmmn(CASE);
    expect(model.nodes.find((n) => n.id === 'CPM')?.name).toBe(HOSTILE);
    expect(model.nodes.find((n) => n.id === 'PI_A')?.name).toBe(HOSTILE);
    expect(model.nodes.find((n) => n.id === 'PI_M')?.name).toBe(HOSTILE);
  });

  it('diagram view: the outline shows the literal text and no <img> exists', () => {
    const alert = vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    const { container } = render(<CmmnEditor xml={CASE} onReady={() => undefined} />);
    expect(screen.getByRole('tab', { name: 'Diagram' }).getAttribute('aria-selected')).toBe('true');
    expect(fake.model!.nodes.filter((n) => n.name === HOSTILE).length).toBe(3);
    const outline = container.querySelector('.plan-tree')!;
    const rows = [...outline.querySelectorAll('li')].map((li) => li.textContent ?? '');
    expect(rows).toHaveLength(3);
    for (const row of rows) expect(row).toContain(HOSTILE);
    expectNoInjectedMarkup();
    expect(alert).not.toHaveBeenCalled();
  });

  it('XML view: the source keeps the escaped name, the outline shows the literal text and no <img> exists', () => {
    const alert = vi.spyOn(window, 'alert').mockImplementation(() => undefined);
    const { container } = render(<CmmnEditor xml={CASE} onReady={() => undefined} />);
    fireEvent.click(screen.getByRole('tab', { name: 'XML' }));
    expect(screen.getByRole('tab', { name: 'XML' }).getAttribute('aria-selected')).toBe('true');
    const source = screen.getByLabelText('CMMN source') as HTMLTextAreaElement;
    expect(source.value).toContain(`name="${ESCAPED}"`);
    expect(source.value).not.toContain(HOSTILE);
    const rows = [...container.querySelectorAll('.plan-tree li')].map((li) => li.textContent ?? '');
    expect(rows.filter((r) => r.includes(HOSTILE)).length).toBeGreaterThanOrEqual(2); // plan model and human task
    expectNoInjectedMarkup();
    expect(alert).not.toHaveBeenCalled();
  });

  it('the XML view alone (CmmnSourceEditor) shows the name as text, also for markup typed into the source', () => {
    const typed = CASE.replace('<milestone id="M" />', `<milestone id="M" name="&lt;script&gt;alert(2)&lt;/script&gt;" />`);
    const { container } = render(<CmmnSourceEditor text={typed} onText={() => undefined} />);
    const text = container.querySelector('.plan-tree')!.textContent ?? '';
    expect(text).toContain(HOSTILE);
    expect(text).toContain('<script>alert(2)</script>');
    expectNoInjectedMarkup();
  });

  it('the real shape renderer writes the name as SVG text nodes for every kind', () => {
    const renderer = new CmmnRenderer({ on: () => undefined } as unknown as EventBus);
    const kinds: NodeKind[] = ['casePlanModel', 'stage', 'humanTask', 'processTask', 'caseTask', 'decisionTask', 'milestone',
      'timerEventListener', 'userEventListener'];
    for (const kind of kinds) {
      const visuals = document.createElementNS('http://www.w3.org/2000/svg', 'g') as SVGElement;
      document.body.appendChild(visuals);
      // wide enough for the whole name on one line, so the text can be compared as written
      renderer.drawShape(visuals, { id: `s-${kind}`, x: 0, y: 0, width: 600, height: 80, cmmnKind: kind, cmmnName: HOSTILE });
      const texts = [...visuals.querySelectorAll('text')].map((t) => t.textContent ?? '').join('|');
      expect(texts.replace(/\s+/g, ' '), kind).toContain(kind === 'timerEventListener' || kind === 'userEventListener' ? '<img' : HOSTILE);
      expect(visuals.querySelectorAll('img, image, foreignObject, script'), kind).toHaveLength(0);
      expect(visuals.querySelectorAll('[onerror]'), kind).toHaveLength(0);
      for (const node of visuals.querySelectorAll('*')) expect(node.namespaceURI, kind).toBe('http://www.w3.org/2000/svg');
      visuals.remove();
    }
    expectNoInjectedMarkup();
  });
});
