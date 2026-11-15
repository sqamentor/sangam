// SPDX-License-Identifier: Apache-2.0
/**
 * CERT-In / DPDP matrix D-23 and C-03: the rights-request and contact-role fields of the compliance screens offer the
 * codes of the tenant's jurisdiction pack, the one source the server checks against (JurisdictionPack.right and
 * contactRole look a code up exactly; DataSubjectRightsService.submit and ContactDirectory.set refuse any other code).
 * The UI used to keep its own list, which offered UPDATE where the India pack defines UPDATING, so the s.12(2)(c)
 * "updating" right could never be filed from the operator screen. These tests read the shipped India pack itself.
 */
import { describe, expect, it } from 'vitest';
import inPack from '../../../sangam-compliance/src/main/resources/compliance/jurisdictions/IN/pack.json';
import en from '../i18n/locales/en.json';
import { contactsScreen, packCodeField, packCodes, rightsScreen, type JurisdictionView } from './compliance';
import type { FormField } from '../api/types';

const enOpt = (en as unknown as { opt: Record<string, string> }).opt;
const hasLabel = (k: string) => k.startsWith('opt.') && typeof enOpt[k.slice(4)] === 'string';
// the jurisdiction endpoint returns the pack's rights and contactRoles records (ComplianceApiImpl.jurisdiction)
const india: JurisdictionView = { code: inPack.code, rights: inPack.rights, contactRoles: inPack.contactRoles };
const rightCodes = inPack.rights.map((r) => r.code);
const roleCodes = inPack.contactRoles.map((r) => r.code);

function field(fields: FormField[] | undefined, key: string): FormField {
  const fd = (fields ?? []).find((x) => x.key === key);
  if (!fd) throw new Error(`no field ${key}`);
  return fd;
}

describe('compliance fields take their codes from the jurisdiction pack (D-23, C-03)', () => {
  it('the rights request offers exactly the India pack right codes, in pack order', () => {
    const right = field(rightsScreen(india, hasLabel).create?.fields, 'right');
    expect(right.type).toBe('select');
    expect(right.values?.map((v) => v.value)).toEqual(rightCodes);
    expect(rightCodes).toContain('UPDATING');
    expect(right.values?.map((v) => v.value)).not.toContain('UPDATE');
    expect(right.validate?.required).toBe(true);
  });

  it('the contact form offers exactly the India pack contact roles', () => {
    const role = field(contactsScreen(india, hasLabel).toolbar?.[0]?.fields, 'role');
    expect(role.type).toBe('select');
    expect(role.values?.map((v) => v.value)).toEqual(roleCodes);
    expect(roleCodes).toEqual(expect.arrayContaining(['DPO', 'CONTACT_PERSON', 'GRIEVANCE_OFFICER', 'CERT_IN_POC']));
  });

  it('every India right and contact role has an English label (Q-06: English through en.json)', () => {
    const missing = [...rightCodes, ...roleCodes].filter((code) => typeof enOpt[code] !== 'string');
    expect(missing).toEqual([]);
    const right = field(rightsScreen(india, hasLabel).create?.fields, 'right');
    expect(right.values?.find((v) => v.value === 'UPDATING')?.label).toBe('opt.UPDATING');
  });

  it('a pack code the UI has no label for is shown as the code (pack data, not UI copy)', () => {
    const fd = packCodeField('right', ['ACCESS', 'PORTABILITY'], hasLabel, true);
    expect(fd.values).toEqual([
      { value: 'ACCESS', label: 'opt.ACCESS' },
      { value: 'PORTABILITY', label: 'PORTABILITY' },
    ]);
  });

  it('without a readable pack the code is typed and the server checks it (no list of the UI\'s own)', () => {
    for (const pack of [undefined, {}, { rights: 'x', contactRoles: [{}] }] as JurisdictionView[]) {
      const right = field(rightsScreen(pack, hasLabel).create?.fields, 'right');
      expect(right.type).toBe('textfield');
      expect(right.values).toBeUndefined();
      expect(right.validate?.required).toBe(true);
      expect(field(contactsScreen(pack, hasLabel).toolbar?.[0]?.fields, 'role').type).toBe('textfield');
    }
  });

  it('packCodes keeps only string codes, in order', () => {
    expect(packCodes([{ code: 'A' }, { code: '' }, null, { code: 3 }, { code: 'B' }])).toEqual(['A', 'B']);
    expect(packCodes(undefined)).toEqual([]);
  });
});
