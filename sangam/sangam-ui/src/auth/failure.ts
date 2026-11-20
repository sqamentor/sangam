// SPDX-License-Identifier: Apache-2.0
/**
 * Register E-23: what the UI writes to the browser console about a failed sign-in step (start, silent renew), so that
 * a failure the user is told about in plain words can still be diagnosed by whoever runs the installation.
 *
 * Text only, never the error object: an oidc-client-ts ErrorResponse carries the token request's form (authorization
 * code, PKCE verifier, refresh token), and an object written to the console can be opened there. The text is the
 * error's name, the OAuth error code when there is one, and its message, with any query string or fragment cut from
 * it (the library names the address it asked, e.g. the discovery document) and a bounded length.
 */
export function failureText(e: unknown): string {
  const name = e instanceof Error ? e.name : typeof e;
  const code = (e as { error?: unknown } | null)?.error;
  const message = e instanceof Error ? e.message : String(e ?? '');
  return `${name}${typeof code === 'string' && code ? ` [${code}]` : ''}: ${message}`.replace(/[?#]\S*/g, '').slice(0, 300);
}
