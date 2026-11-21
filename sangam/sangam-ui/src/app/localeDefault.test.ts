// SPDX-License-Identifier: Apache-2.0
/**
 * Q6 (owner, 2026-11-21 "Yes, English only"): the standard UI image and the Helm chart offer English only by default,
 * so the language switch is hidden unless a deployment asks for more (decisions Q-06 and 397 Q11). The switch code
 * stays: a deployment that sets SANGAM_LOCALES=en,hi gets it back.
 */
import { describe, expect, it } from 'vitest';
import dockerfile from '../../../deploy/docker/ui.Dockerfile?raw';
import nginxTemplate from '../../../deploy/docker/ui/default.conf.template?raw';
import chartTemplate from '../../../deploy/helm/sangam/templates/ui.yaml?raw';
import chartValues from '../../../deploy/helm/sangam/values.yaml?raw';
import layoutSource from './Layout.tsx?raw';
import { config, normalise, setConfig } from '../config';

/** The value of a key directly under the top-level `ui:` block of values.yaml. */
function uiValue(key: string): string | undefined {
  const lines = chartValues.split(/\r?\n/);
  const start = lines.findIndex((l) => /^ui:\s*$/.test(l));
  expect(start).toBeGreaterThan(-1);
  for (let i = start + 1; i < lines.length && !/^\S/.test(lines[i]); i++) {
    const m = new RegExp(`^  ${key}:\\s*(.*)$`).exec(lines[i]);
    if (m) return m[1].trim().replace(/^"|"$/g, '');
  }
  return undefined;
}

describe('Q6: English only by default', () => {
  it('the standard UI image sets SANGAM_LOCALES=en (ui.Dockerfile)', () => {
    const values = [...dockerfile.matchAll(/^\s*(?:ENV\s+)?SANGAM_LOCALES=(\S+)/gm)].map((m) => m[1]);
    expect(values).toEqual(['en']);
    expect([...dockerfile.matchAll(/^\s*(?:ENV\s+)?SANGAM_DEFAULT_LOCALE=(\S+)/gm)].map((m) => m[1])).toEqual(['en']);
  });

  it('the Helm chart sets ui.locales: en (values.yaml), and passes it to the image as SANGAM_LOCALES', () => {
    expect(uiValue('locales')).toBe('en');
    expect(uiValue('defaultLocale')).toBe('en');
    expect(chartTemplate).toMatch(/- name: SANGAM_LOCALES\s+value: \{\{ \.Values\.ui\.locales \| quote \}\}/);
  });

  it('with that default the UI offers one language and shows no language switch', () => {
    // the nginx template renders the variable as a string into /config.json
    expect(nginxTemplate).toContain('"locales":"${SANGAM_LOCALES}"');
    expect(normalise({ locales: 'en', defaultLocale: 'en' }).locales).toEqual(['en']);
    expect(layoutSource).toContain('if (offered.length < 2) return null;');
  });

  it('a /config.json that names no languages (the dev server, a failed read) gives English only too (src/config.ts)', () => {
    expect(normalise({}).locales).toEqual(['en']);
    expect(normalise({ locales: '' }).locales).toEqual(['en']);
    expect(normalise({ locales: 'xx' }).locales).toEqual(['en']);
    expect(setConfig({}).locales).toEqual(['en']);
    expect(config().locales).toEqual(['en']);
    expect(normalise({}).defaultLanguage).toBe('en');
  });

  it('the switch stays for a deployment that sets en,hi', () => {
    expect(normalise({ locales: 'en,hi', defaultLocale: 'en' }).locales).toEqual(['en', 'hi']);
    expect(normalise({ locales: ' en , hi ' }).locales).toEqual(['en', 'hi']);
  });
});
