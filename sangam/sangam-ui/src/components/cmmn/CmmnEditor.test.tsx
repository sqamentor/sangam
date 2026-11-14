// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// Decision 161 (M-041): the CMMN editor opens the diagram, falls back to XML, and carries edits between the views.
// The diagram-js canvas is replaced by a fake (jsdom has no SVG layout); cmmnModel.test.ts covers the model.
// Written, not run in this phase.
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { initI18n } from '../../i18n';
import type { CmmnLink, CmmnNode } from './cmmnModel';
import { CMMN_NS } from './cmmnModel';

vi.mock('bpmn-js/lib/Modeler', () => ({ default: class {} }));
vi.mock('dmn-js/lib/Modeler', () => ({ default: class {} }));
vi.mock('@bpmn-io/form-js-editor', () => ({ FormEditor: class {} }));

const fake = vi.hoisted(() => ({ calls: [] as string[], model: null as null | { nodes: CmmnNode[]; links: CmmnLink[] } }));
vi.mock('./CmmnModeler', async (orig) => ({
  ...(await orig<typeof import('./CmmnModeler')>()),
  createCmmnModeler: (_host: HTMLElement, model: { nodes: CmmnNode[]; links: CmmnLink[] }) => {
    fake.model = { nodes: model.nodes.map((n) => ({ ...n })), links: model.links };
    return {
      exportModel: () => fake.model!,
      select: () => undefined,
      selected: () => undefined,
      addPlanItem: (kind: string) => {
        fake.calls.push(`add:${kind}`);
        return undefined;
      },
      addCriterion: () => undefined,
      linkTargets: () => [],
      link: () => false,
      removeSelected: () => undefined,
      rename: (id: string, name: string) => {
        fake.model!.nodes = fake.model!.nodes.map((n) => (n.id === id ? { ...n, name } : n));
      },
      undo: () => undefined,
      redo: () => undefined,
      fit: () => undefined,
      destroy: () => fake.calls.push('destroy'),
    };
  },
}));

import { CmmnEditor, type EditorHandle } from '../Editors';

beforeAll(() => initI18n('en'));
afterEach(() => {
  cleanup();
  fake.calls = [];
  fake.model = null;
});

const CASE = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="${CMMN_NS}" id="D" targetNamespace="t">
  <case id="c"><casePlanModel id="CPM" name="Plan"><planItem id="PI_A" definitionRef="A" /><humanTask id="A" name="Check" /></casePlanModel></case>
</definitions>`;

function open(xml: string) {
  let handle: EditorHandle | undefined;
  render(<CmmnEditor xml={xml} onReady={(h) => (handle = h)} />);
  return () => handle!;
}

describe('CmmnEditor', () => {
  it('opens a readable file in the diagram view and saves it with the diagram edits and CMMNDI', async () => {
    const handle = open(CASE);
    expect(screen.getByRole('tab', { name: 'Diagram' }).getAttribute('aria-selected')).toBe('true');
    act(() => {
      fake.model!.nodes = fake.model!.nodes.map((n) => (n.id === 'PI_A' ? { ...n, name: 'Check papers' } : n));
    });
    const out = await handle().getContent();
    expect(out).toContain('name="Check papers"');
    expect(out).toContain('CMMNShape');
  });

  it('falls back to the XML view, with the reason, when the diagram cannot read the file', async () => {
    const broken = '<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"><case id="c"/></definitions>';
    const handle = open(broken);
    expect(screen.getByRole('tab', { name: 'XML' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('alert').textContent).toContain('its case has no case plan model');
    expect(await handle().getContent()).toBe(broken);
    expect(fake.model).toBeNull();
  });

  it('carries the diagram edits into the XML view and back', async () => {
    const handle = open(CASE);
    act(() => {
      fake.model!.nodes = fake.model!.nodes.map((n) => (n.id === 'PI_A' ? { ...n, name: 'Edited' } : n));
    });
    fireEvent.click(screen.getByRole('tab', { name: 'XML' }));
    const source = screen.getByLabelText('CMMN source') as HTMLTextAreaElement;
    expect(source.value).toContain('name="Edited"');
    expect(await handle().getContent()).toBe(source.value);
    fireEvent.change(source, { target: { value: source.value.replace('name="Edited"', 'name="From XML"') } });
    fireEvent.click(screen.getByRole('tab', { name: 'Diagram' }));
    expect(screen.getByRole('tab', { name: 'Diagram' }).getAttribute('aria-selected')).toBe('true');
    expect(fake.model!.nodes.find((n) => n.id === 'PI_A')?.name).toBe('From XML');
  });

  it('stays in the XML view while the text cannot be shown as a diagram', () => {
    open(CASE);
    fireEvent.click(screen.getByRole('tab', { name: 'XML' }));
    fireEvent.change(screen.getByLabelText('CMMN source'), { target: { value: '<definitions' } });
    fireEvent.click(screen.getByRole('tab', { name: 'Diagram' }));
    expect(screen.getByRole('tab', { name: 'XML' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('alert').textContent).toContain('the XML is not well-formed');
  });

  it('offers no way to add event listeners, which the engine rejects', () => {
    open(CASE);
    const tools = screen.getByRole('toolbar', { name: 'CMMN diagram tools' });
    expect(tools.textContent).toContain('Add Human task');
    expect(tools.textContent).not.toMatch(/listener/i);
    fireEvent.click(screen.getByRole('button', { name: 'Add Milestone' }));
    expect(fake.calls).toContain('add:milestone');
  });
});
