// SPDX-License-Identifier: Apache-2.0
// Pure-logic unit tests (vitest). Not run in this phase: testing comes last (ARCHITECTURE §5).
import { describe, expect, it } from 'vitest';
import { normalise } from '../config';
import { validateForm } from '../components/FormRenderer';
import { verifyLinkage } from '../areas/audit/Audit';
import type { AuditEvent } from '../api/types';

const t = (k: string) => k;

describe('config', () => {
  it('maps the WS-DEPLOY contract fields', () => {
    const c = normalise({ apiBase: '/api/v1', environment: 'prod', oidc: { issuer: 'https://idp/realms/x', clientId: 'ui' }, defaultLocale: 'hi', locales: ['hi', 'en'] });
    expect(c.oidc.authority).toBe('https://idp/realms/x');
    expect(c.authMode).toBe('oidc');
    expect(c.defaultLanguage).toBe('hi');
  });
  it('uses dev-token mode only in the dev environment', () => {
    expect(normalise({ environment: 'dev' }).authMode).toBe('dev');
  });
});

describe('validateForm', () => {
  it('reports required and range errors', () => {
    const e = validateForm(
      { components: [{ key: 'a', type: 'textfield', validate: { required: true } }, { key: 'n', type: 'number', validate: { max: 5 } }] },
      { n: 9 },
      t,
    );
    expect(e).toEqual({ a: 'form.required', n: 'form.max' });
  });
});

describe('verifyLinkage', () => {
  const ev = (seq: number, prevHash: string, hash: string) => ({ seq, prevHash, hash }) as AuditEvent;
  it('accepts a linked chain', () => expect(verifyLinkage([ev(1, '0', 'a'), ev(2, 'a', 'b')]).ok).toBe(true));
  it('finds the first broken link', () => expect(verifyLinkage([ev(1, '0', 'a'), ev(2, 'x', 'b')]).brokenAt).toBe(2));
});
