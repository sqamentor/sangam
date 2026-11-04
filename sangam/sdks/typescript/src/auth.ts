// SPDX-License-Identifier: Apache-2.0
import { TokenError } from './errors.js';

/**
 * Supplies the `Authorization` header (ARCHITECTURE §4: `Bearer <OIDC JWT>`).
 * `authorization()` returns the full header value or `undefined` for anonymous calls.
 */
export interface TokenProvider {
  authorization(): Promise<string | undefined>;
  /** Called after a 401; the next call must not return the cached credential. */
  invalidate?(): void;
}

/** No authentication (local dev profile without a token). */
export const noAuth: TokenProvider = { authorization: async () => undefined };

/** A fixed bearer token (dev profile static token, or a token managed elsewhere). */
export class StaticTokenProvider implements TokenProvider {
  constructor(private readonly token: string) {
    if (!token) throw new TypeError('token is required');
  }
  async authorization(): Promise<string> {
    return `Bearer ${this.token}`;
  }
}

/**
 * HTTP Basic credentials (Flowable external-client parity, FC-37). Only for dev/test gateways that accept
 * Basic; the Sangam server itself expects OIDC bearer tokens.
 */
export class BasicAuthProvider implements TokenProvider {
  private readonly value: string;
  constructor(user: string, password: string) {
    this.value = `Basic ${Buffer.from(`${user}:${password}`, 'utf8').toString('base64')}`;
  }
  async authorization(): Promise<string> {
    return this.value;
  }
}

export interface ClientCredentialsOptions {
  /** IdP token endpoint, e.g. Keycloak `/realms/<realm>/protocol/openid-connect/token`. */
  tokenEndpoint: string;
  clientId: string;
  clientSecret: string;
  scope?: string;
  audience?: string;
  /** `basic` (default) sends client credentials in the Authorization header; `post` puts them in the body. */
  clientAuth?: 'basic' | 'post';
  /** Refresh this many ms before `expires_in` ends (default 30 000). */
  refreshSkewMs?: number;
  /** Request timeout in ms (default 10 000). */
  timeoutMs?: number;
  fetch?: typeof fetch;
}

/**
 * OAuth 2.0 / OIDC client-credentials grant (RFC 6749 §4.4). The token is cached and refreshed before expiry;
 * refresh is single-flight (concurrent callers share one request).
 */
export class ClientCredentialsTokenProvider implements TokenProvider {
  private cached?: string;
  private expiresAt = 0;
  private inflight?: Promise<string>;
  private readonly opts: Required<Omit<ClientCredentialsOptions, 'scope' | 'audience' | 'fetch'>> &
    Pick<ClientCredentialsOptions, 'scope' | 'audience'>;
  private readonly fetchImpl: typeof fetch;

  constructor(options: ClientCredentialsOptions) {
    if (!options.tokenEndpoint || !options.clientId || !options.clientSecret) {
      throw new TypeError('tokenEndpoint, clientId and clientSecret are required');
    }
    this.opts = {
      tokenEndpoint: options.tokenEndpoint,
      clientId: options.clientId,
      clientSecret: options.clientSecret,
      scope: options.scope,
      audience: options.audience,
      clientAuth: options.clientAuth ?? 'basic',
      refreshSkewMs: options.refreshSkewMs ?? 30_000,
      timeoutMs: options.timeoutMs ?? 10_000,
    };
    this.fetchImpl = options.fetch ?? fetch;
  }

  async authorization(): Promise<string> {
    return `Bearer ${await this.token()}`;
  }

  async token(): Promise<string> {
    if (this.cached && Date.now() < this.expiresAt - this.opts.refreshSkewMs) return this.cached;
    if (!this.inflight) {
      this.inflight = this.fetchToken().finally(() => {
        this.inflight = undefined;
      });
    }
    return this.inflight;
  }

  invalidate(): void {
    this.cached = undefined;
    this.expiresAt = 0;
  }

  private async fetchToken(): Promise<string> {
    const form = new URLSearchParams({ grant_type: 'client_credentials' });
    if (this.opts.scope) form.set('scope', this.opts.scope);
    if (this.opts.audience) form.set('audience', this.opts.audience);
    const headers: Record<string, string> = {
      'Content-Type': 'application/x-www-form-urlencoded',
      Accept: 'application/json',
    };
    if (this.opts.clientAuth === 'basic') {
      const basic = `${encodeURIComponent(this.opts.clientId)}:${encodeURIComponent(this.opts.clientSecret)}`;
      headers.Authorization = `Basic ${Buffer.from(basic, 'utf8').toString('base64')}`;
    } else {
      form.set('client_id', this.opts.clientId);
      form.set('client_secret', this.opts.clientSecret);
    }
    let resp: Response;
    try {
      resp = await this.fetchImpl(this.opts.tokenEndpoint, {
        method: 'POST',
        headers,
        body: form.toString(),
        signal: AbortSignal.timeout(this.opts.timeoutMs),
      });
    } catch (e) {
      throw new TokenError(`token request failed: ${(e as Error).message}`, { cause: e });
    }
    const text = await resp.text();
    if (!resp.ok) throw new TokenError(`token endpoint returned HTTP ${resp.status}: ${text}`);
    let body: { access_token?: string; expires_in?: number };
    try {
      body = JSON.parse(text) as typeof body;
    } catch (e) {
      throw new TokenError('token endpoint returned invalid JSON', { cause: e });
    }
    if (!body.access_token) throw new TokenError('token endpoint response has no access_token');
    this.cached = body.access_token;
    this.expiresAt = Date.now() + (typeof body.expires_in === 'number' ? body.expires_in : 300) * 1000;
    return this.cached;
  }
}
