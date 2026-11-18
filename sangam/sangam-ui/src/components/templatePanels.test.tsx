// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * C3-14 / M-036 (owner decision 58): the template palette (PALETTE through POST /templates/{id}/apply, then the element
 * is created) and the form-component panel (FORM_COMPONENT inserted into the form-js editor). bpmn-js and form-js are
 * replaced by fakes of the services the panels call. Written, not run.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: api.get, post: api.post } };
});

import { initI18n, tr } from '../i18n';
import { TemplatePalette } from './TemplatePalette';
import { FormComponentPanel, insertTemplate, type FormEditorLike } from './FormComponentPanel';
import { blockingA11yIssues } from '../test/axe';

const T = tr;

function fakeModeler() {
  const created: { type: string }[] = [];
  const updates: [unknown, Record<string, unknown>][] = [];
  const selected: unknown[] = [];
  const defs = { $attrs: {} as Record<string, string> };
  const services: Record<string, unknown> = {
    canvas: { viewbox: () => ({ x: 0, y: 0, width: 400, height: 200 }), getRootElement: () => 'root' },
    elementFactory: { createShape: (a: { type: string }) => ({ ...a }) },
    modeling: {
      createShape: (shape: { type: string }, pos: { x: number; y: number }, parent: unknown) => {
        const s = { ...shape, pos, parent };
        created.push(s);
        return s;
      },
      updateProperties: (e: unknown, p: Record<string, unknown>) => updates.push([e, p]),
    },
    selection: { select: (e: unknown) => selected.push(e) },
  };
  return { modeler: { get: (n: string) => services[n], getDefinitions: () => defs }, created, updates, selected, defs };
}

beforeAll(() => initI18n('en'));
beforeEach(() => {
  api.get.mockReset();
  api.post.mockReset();
});
afterEach(cleanup);

const paletteTpl = { key: 'notify', name: 'Notify', kind: 'PALETTE', version: 3, appliesTo: ['bpmn:ServiceTask'], document: { fields: [{ name: 'topic', binding: 'operaton:topic', required: true, default: 'mail.send' }] } };

describe('C3-14 template palette', () => {
  it('lists PALETTE entries, applies on the server, then creates the element at the canvas centre and writes the properties', async () => {
    api.get.mockResolvedValue([paletteTpl, { ...paletteTpl, key: 'bad', appliesTo: [] }]);
    api.post.mockResolvedValue({ properties: { 'operaton:topic': 'mail.send', 'bpmn:name': 'Notify', 'sangam:templateId': 'notify', 'sangam:templateVersion': 3 }, errors: [] });
    const f = fakeModeler();
    const { container } = render(<TemplatePalette modeler={f.modeler} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Notify v3' }));
    expect(screen.queryByRole('button', { name: 'bad v3' })).toBeNull();
    expect(api.get).toHaveBeenCalledWith('/templates', { kind: 'PALETTE' }, expect.anything());
    fireEvent.click(screen.getByRole('button', { name: T('tpl.paletteAdd', { name: 'Notify' }) }));
    await waitFor(() => expect(f.created).toHaveLength(1));
    expect(api.post).toHaveBeenCalledWith('/templates/notify/apply?version=3', { elementType: 'bpmn:ServiceTask', values: { topic: 'mail.send' } });
    expect(f.created[0]).toMatchObject({ type: 'bpmn:ServiceTask', pos: { x: 200, y: 100 }, parent: 'root' });
    expect(f.selected).toEqual([f.created[0]]);
    expect(f.updates[0][1]).toEqual({ name: 'Notify', 'operaton:topic': 'mail.send', 'sangam:templateId': 'notify', 'sangam:templateVersion': '3' });
    expect(f.defs.$attrs['xmlns:operaton']).toBeDefined();
    expect(await blockingA11yIssues(container)).toEqual([]);
  });

  it('a refused apply creates nothing and shows the server errors', async () => {
    api.get.mockResolvedValue([paletteTpl]);
    api.post.mockResolvedValue({ properties: {}, errors: ['topic does not match [a-z.]+'] });
    const f = fakeModeler();
    render(<TemplatePalette modeler={f.modeler} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Notify v3' }));
    fireEvent.click(screen.getByRole('button', { name: T('tpl.paletteAdd', { name: 'Notify' }) }));
    expect(await screen.findByText('topic does not match [a-z.]+')).toBeTruthy();
    expect(f.created).toHaveLength(0);
  });

  it('shows nothing without PALETTE templates', async () => {
    api.get.mockResolvedValue([]);
    const { container } = render(<TemplatePalette modeler={fakeModeler().modeler} />);
    await waitFor(() => expect(api.get).toHaveBeenCalled());
    expect(container.textContent).toBe('');
  });
});

const componentTpl = { key: 'address', name: 'Address', kind: 'FORM_COMPONENT', version: 1, document: { components: [{ id: 'x', type: 'textfield', key: 'street', label: 'Street' }] } };

function fakeEditor(components: Record<string, unknown>[] = []) {
  let schema: Record<string, unknown> = { type: 'default', schemaVersion: 16, components };
  const editor: FormEditorLike = {
    saveSchema: () => schema,
    importSchema: async (s: never) => {
      schema = s as Record<string, unknown>;
      return {};
    },
  };
  return { editor, schema: () => schema };
}

describe('C3-14 form component templates', () => {
  it('insertTemplate appends with a fresh id and re-imports the schema', async () => {
    const f = fakeEditor([{ id: 'Field_1', type: 'textfield', key: 'name' }]);
    const conflicts = await insertTemplate(f.editor, { key: 'address', name: 'Address', version: 1, components: componentTpl.document.components }, () => 'Field_2');
    expect(conflicts).toEqual([]);
    expect(f.schema().components).toEqual([
      { id: 'Field_1', type: 'textfield', key: 'name' },
      { id: 'Field_2', type: 'textfield', key: 'street', label: 'Street' },
    ]);
  });

  it('the panel inserts the chosen template and marks the form changed', async () => {
    api.get.mockResolvedValue([componentTpl]);
    const f = fakeEditor();
    const changed = vi.fn();
    render(<FormComponentPanel editor={f.editor} onInserted={changed} />);
    fireEvent.change(await screen.findByLabelText(new RegExp(T('tpl.component'))), { target: { value: 'address' } });
    fireEvent.click(screen.getByRole('button', { name: T('tpl.componentInsert') }));
    await waitFor(() => expect(changed).toHaveBeenCalledTimes(1));
    expect(api.get).toHaveBeenCalledWith('/templates', { kind: 'FORM_COMPONENT' }, expect.anything());
    expect((f.schema().components as { key: string }[]).map((c) => c.key)).toEqual(['street']);
  });

  it('a key the form already uses is refused by name and nothing changes', async () => {
    api.get.mockResolvedValue([componentTpl]);
    const f = fakeEditor([{ id: 'Field_1', type: 'textfield', key: 'street' }]);
    const changed = vi.fn();
    render(<FormComponentPanel editor={f.editor} onInserted={changed} />);
    fireEvent.change(await screen.findByLabelText(new RegExp(T('tpl.component'))), { target: { value: 'address' } });
    fireEvent.click(screen.getByRole('button', { name: T('tpl.componentInsert') }));
    expect(await screen.findByText(T('tpl.componentKeyConflict', { keys: 'street' }))).toBeTruthy();
    expect(changed).not.toHaveBeenCalled();
    expect(f.schema().components).toHaveLength(1);
  });
});
