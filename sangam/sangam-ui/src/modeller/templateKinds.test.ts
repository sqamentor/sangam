// SPDX-License-Identifier: Apache-2.0
/**
 * C3-14 / M-036 (owner decision 58): PALETTE templates are the ELEMENT format with exactly one BPMN type;
 * FORM_COMPONENT templates are {id, name, components}. Written, not run.
 */
import { describe, expect, it } from 'vitest';
import { formComponentTemplates, insertComponents, paletteEntries, paletteType, type TemplateView } from './templates';

const tpl = (key: string, kind: TemplateView['kind'], appliesTo: string[] = [], document: Record<string, unknown> = {}): TemplateView => ({ key, name: key, kind, version: 1, appliesTo, document });

describe('PALETTE entries', () => {
  it('keep PALETTE templates that name exactly one BPMN type', () => {
    const list = [
      tpl('ok', 'PALETTE', ['bpmn:ServiceTask']),
      tpl('none', 'PALETTE', []),
      tpl('two', 'PALETTE', ['bpmn:ServiceTask', 'bpmn:UserTask']),
      tpl('notBpmn', 'PALETTE', ['dmn:Decision']),
      tpl('element', 'ELEMENT', ['bpmn:ServiceTask']),
    ];
    expect(paletteEntries(list).map((x) => x.key)).toEqual(['ok']);
    expect(paletteType(list[0])).toBe('bpmn:ServiceTask');
    expect(paletteType(list[2])).toBeUndefined();
  });

  it('read the type from the document when the view has no appliesTo', () => {
    expect(paletteType({ key: 'd', kind: 'PALETTE', version: 2, document: { appliesTo: ['bpmn:UserTask'] } })).toBe('bpmn:UserTask');
  });
});

describe('FORM_COMPONENT templates', () => {
  it('keep only templates with a non-empty list of typed components', () => {
    const list = [
      tpl('addr', 'FORM_COMPONENT', [], { components: [{ type: 'textfield', key: 'street' }] }),
      tpl('empty', 'FORM_COMPONENT', [], { components: [] }),
      tpl('untyped', 'FORM_COMPONENT', [], { components: [{ key: 'x' }] }),
      tpl('notList', 'FORM_COMPONENT', [], { components: 'x' }),
      tpl('pal', 'PALETTE', ['bpmn:Task'], { components: [{ type: 'textfield' }] }),
    ];
    expect(formComponentTemplates(list)).toEqual([{ key: 'addr', name: 'addr', version: 1, components: [{ type: 'textfield', key: 'street' }] }]);
  });

  it('append the components with fresh ids, nested ones included, and keep keys', () => {
    let n = 0;
    const ids = ['Field_a', 'Field_new1', 'Field_new2', 'Field_new3'];
    const schema = { type: 'default', schemaVersion: 16, components: [{ id: 'Field_a', type: 'textfield', key: 'name' }] };
    const r = insertComponents(schema, [{ id: 'Field_a', type: 'group', components: [{ id: 'x', type: 'textfield', key: 'street' }] }, { type: 'number', key: 'pin' }], () => ids[n++]);
    expect(r.conflicts).toEqual([]);
    expect(r.schema).toEqual({
      type: 'default',
      schemaVersion: 16,
      components: [
        { id: 'Field_a', type: 'textfield', key: 'name' },
        { id: 'Field_new1', type: 'group', components: [{ id: 'Field_new2', type: 'textfield', key: 'street' }] },
        { id: 'Field_new3', type: 'number', key: 'pin' },
      ],
    });
    expect(schema.components).toHaveLength(1);
  });

  it('refuse a key the form already uses, or a key used twice by the template, and insert nothing', () => {
    const schema = { components: [{ id: 'a', type: 'group', components: [{ id: 'b', type: 'textfield', key: 'street' }] }] };
    expect(insertComponents(schema, [{ type: 'textfield', key: 'street' }], () => 'z')).toEqual({ conflicts: ['street'] });
    expect(insertComponents({ components: [] }, [{ type: 'textfield', key: 'k' }, { type: 'textfield', key: 'k' }], () => 'z')).toEqual({ conflicts: ['k'] });
  });
});
