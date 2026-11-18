// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * UP-L6b (form-js 2 gate): every shipped pack form (sangam-packs and sangam-compliance, all packs) is turned into a
 * form schema by the same path the UI uses (normForm on the /forms/by-key answer, layout = the pack form document),
 * and that schema must import into the form-js viewer without warnings, keep every field, and validate. The runtime
 * renderer is Sangam's FormRenderer (LIC-8 / PK-11), but the modeller opens and edits these schemas with form-js, so a
 * form-js major that cannot read them breaks the authoring path. The schema carries the viewer's own schemaVersion;
 * a migration happens only inside form-js's importSchema (its documented path), never in this test.
 */
import { afterAll, describe, expect, it } from 'vitest';
import { Form, schemaVersion } from '@bpmn-io/form-js-viewer';
import { normForm } from './adapters';

const docs = import.meta.glob(
  ['../../../sangam-packs/src/main/resources/packs/**/*.form.json', '../../../sangam-compliance/src/main/resources/packs/**/*.form.json'],
  { import: 'default', eager: true },
) as Record<string, { key: string; fields?: unknown[] }>;
const files = Object.keys(docs).sort();

interface Field { id: string; key?: string; type: string }

/**
 * Every shipped form must pass, the 13 compliance forms included: they named fields with "key" instead of "id" until
 * fix/compliance-form-ids (2026-11-18), which this test found (handoff 2026-11-18\PACKFORM-FIELD-KEY-DEFECT.md).
 */

const host = document.createElement('div');
document.body.appendChild(host);
afterAll(() => host.remove());

describe('pack forms load in the form-js viewer', () => {
  it('finds the shipped pack forms', () => {
    // 629 on 2026-11-18 (608 sangam-packs, 21 sangam-compliance); a lower count means the glob lost a pack root
    expect(files.length).toBeGreaterThanOrEqual(629);
  });

  const names = files.map((f) => f.replace('../../../', ''));

  const check = async (name: string) => {
    const doc = docs[`../../../${name}`];
    const schema = { type: 'default', schemaVersion, ...normForm({ key: doc.key, layout: doc }) };
    const form = new Form({ container: host });
    try {
      const { warnings } = await form.importSchema(schema as never);
      expect(warnings).toEqual([]);
      const fields = (form.get('formFieldRegistry') as { getAll(): Field[] }).getAll().filter((f) => f.type !== 'default');
      expect(fields.map((f) => f.key).sort()).toEqual(schema.components.map((c) => c.key).sort());
      expect(fields.map((f) => f.type).sort()).toEqual(schema.components.map((c) => c.type).sort());
      const keyOf = new Map(fields.map((f) => [f.id, f.key]));
      const errors = form.validate() as Record<string, unknown>;
      const required = schema.components.filter((c) => c.validate?.required).map((c) => c.key);
      // empty data: exactly the required fields are reported (the validator ran on every field)
      expect(Object.keys(errors).map((id) => keyOf.get(id)).sort()).toEqual(required.sort());
    } finally {
      form.destroy();
    }
  };
  it.each(names.map((n) => [n]))('%s', check);
});
