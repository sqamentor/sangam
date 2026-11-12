// SPDX-License-Identifier: Apache-2.0
// M-036: element templates: which apply, how the server's applied properties are written. Written, not run.
import { describe, expect, it } from 'vitest';
import { OPERATON_NS, SANGAM_NS, applicable, fieldError, namespacesFor, planWrites, templateFields, type TemplateView } from './templates';

const tpl = (key: string, appliesTo: string[], kind: TemplateView['kind'] = 'ELEMENT'): TemplateView => ({ key, kind, version: 1, appliesTo });

describe('applicable', () => {
  it('keeps ELEMENT templates naming the type or no type (ElementTemplates.java:82)', () => {
    const list = [tpl('svc', ['bpmn:ServiceTask']), tpl('any', []), tpl('user', ['bpmn:UserTask']), tpl('pal', [], 'PALETTE')];
    expect(applicable(list, 'bpmn:ServiceTask').map((t) => t.key)).toEqual(['svc', 'any']);
  });
});

describe('templateFields', () => {
  it('reads the document fields with "default" (ElementTemplates.java:164)', () => {
    const t: TemplateView = { ...tpl('n', []), document: { fields: [{ name: 'topic', binding: 'operaton:topic', required: true, pattern: '[a-z.]+', default: 'x', choices: ['x', 'y'] }, 'junk'] } };
    expect(templateFields(t)).toEqual([{ name: 'topic', binding: 'operaton:topic', required: true, pattern: '[a-z.]+', default: 'x', choices: ['x', 'y'] }]);
    expect(templateFields(tpl('none', []))).toEqual([]);
  });
});

describe('planWrites', () => {
  it('writes bpmn properties and Operaton / Sangam attributes, and leaves extension elements to the modeller', () => {
    const w = planWrites({ 'sangam:templateId': 'notify', 'sangam:templateVersion': 3, 'bpmn:name': 'Notify', 'operaton:topic': 'mail.send', 'input:to': 'a@b', 'output:ok': 'sent', 'form:key': 'f' });
    expect(w.bpmn).toEqual({ name: 'Notify' });
    expect(w.attrs).toEqual({ 'sangam:templateId': 'notify', 'sangam:templateVersion': '3', 'operaton:topic': 'mail.send' });
    expect(w.manual).toEqual({ 'input:to': 'a@b', 'output:ok': 'sent', 'form:key': 'f' });
  });
  it('declares the namespaces of the attributes it writes', () => {
    expect(namespacesFor({ 'operaton:topic': 'x', 'sangam:templateId': 'y' })).toEqual({ 'xmlns:operaton': OPERATON_NS, 'xmlns:sangam': SANGAM_NS });
    expect(namespacesFor({})).toEqual({});
  });
});

describe('fieldError', () => {
  const f = { name: 'topic', binding: 'operaton:topic', required: true, pattern: '[a-z.]+', choices: ['mail.send', 'sms.send'] };
  it('mirrors the server checks as hints', () => {
    expect(fieldError(f, '')).toBe('required');
    expect(fieldError({ ...f, default: 'mail.send' }, '')).toBeNull();
    expect(fieldError(f, 'Mail')).toBe('pattern');
    expect(fieldError(f, 'x.y')).toBe('choices');
    expect(fieldError(f, 'sms.send')).toBeNull();
  });
});
