// SPDX-License-Identifier: Apache-2.0
/** Register E-23: the console record of a failed sign-in step is bounded text without URL parameters (failure.ts). */
import { describe, expect, it } from 'vitest';
import { ErrorResponse } from 'oidc-client-ts';
import { failureText } from './failure';

describe('failureText', () => {
  it('names the error and its message', () => {
    expect(failureText(new TypeError('Failed to fetch'))).toBe('TypeError: Failed to fetch');
  });

  it('adds the OAuth error code and leaves the token request form out', () => {
    const form = new URLSearchParams({ grant_type: 'refresh_token', refresh_token: 'rt-secret' });
    const e = new ErrorResponse({ error: 'invalid_grant', error_description: 'Session not active' }, form);
    expect(failureText(e)).toBe('ErrorResponse [invalid_grant]: Session not active');
  });

  it('cuts a query string or fragment from an address in the message', () => {
    const e = new Error('Invalid response Content-Type: text/html, from URL: https://idp.example/cb?code=abc&state=def#frag next');
    expect(failureText(e)).toBe('Error: Invalid response Content-Type: text/html, from URL: https://idp.example/cb next');
  });

  it('takes what is not an Error as text, and bounds the length', () => {
    expect(failureText('boom')).toBe('string: boom');
    expect(failureText(undefined)).toBe('undefined: ');
    expect(failureText(new Error('x'.repeat(1000)))).toHaveLength(300);
  });
});
