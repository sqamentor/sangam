// SPDX-License-Identifier: Apache-2.0
/**
 * Runtime configuration, read from /config.json at boot (WS-DEPLOY contract, sangam/deploy/README.md §10). Nothing
 * environment-specific is baked into the build, so one image serves every installation, air-gapped included (R56).
 *
 * Contract fields: apiBase, oidc.issuer, oidc.clientId, defaultLocale, locales, environment.
 * Sangam UI extras (optional): authMode, devToken, oidc.scope, oidc.redirectPath, oidc.postLogoutPath,
 * fonts (per-tenant / state font override, see FONTS.md and src/fonts.ts).
 * The API is called on the same origin (/api/v1, /engine-rest are proxied by the UI image).
 */
import type { FontsConfig } from './fonts';

export type AuthMode = 'oidc' | 'dev';
export type Locale = 'en' | 'hi';

export interface SangamConfig {
  apiBase: string;
  authMode: AuthMode;
  environment: string;
  oidc: {
    authority: string;
    clientId: string;
    scope: string;
    redirectPath: string;
    postLogoutPath: string;
  };
  /** Static token accepted only by the server's dev profile (ARCHITECTURE §4). */
  devToken: string;
  defaultLanguage: Locale;
  locales: Locale[];
  /** Per-tenant font override (state-government fonts first); FONTS.md. */
  fonts: FontsConfig;
}

interface RawConfig {
  apiBase?: string;
  authMode?: AuthMode;
  environment?: string;
  oidc?: { issuer?: string; authority?: string; clientId?: string; scope?: string; redirectPath?: string; postLogoutPath?: string };
  devToken?: string;
  defaultLocale?: string;
  defaultLanguage?: string;
  /** A list, or the comma-separated string the nginx template renders (SANGAM_LOCALES="en,hi"). */
  locales?: string[] | string;
  fonts?: FontsConfig;
}

const defaults: SangamConfig = {
  apiBase: '/api/v1',
  authMode: 'dev',
  environment: 'dev',
  oidc: {
    authority: 'http://localhost:8180/realms/sangam',
    clientId: 'sangam-ui',
    scope: 'openid profile email',
    redirectPath: '/auth/callback',
    postLogoutPath: '/',
  },
  devToken: 'dev-token',
  defaultLanguage: 'en',
  locales: ['en', 'hi'],
  fonts: {},
};

let current: SangamConfig = defaults;

function asLocale(v: unknown): Locale | undefined {
  return v === 'en' || v === 'hi' ? v : undefined;
}

export function normalise(raw: RawConfig): SangamConfig {
  const environment = raw.environment ?? defaults.environment;
  const rawLocales = typeof raw.locales === 'string' ? raw.locales.split(',') : (raw.locales ?? []);
  const locales = rawLocales.map((l) => asLocale(l.trim())).filter((x): x is Locale => !!x);
  return {
    apiBase: raw.apiBase ?? defaults.apiBase,
    environment,
    // Dev-token mode only when asked for, or when the environment is "dev" and no mode is given.
    authMode: raw.authMode ?? (environment === 'dev' ? 'dev' : 'oidc'),
    oidc: {
      authority: raw.oidc?.issuer ?? raw.oidc?.authority ?? defaults.oidc.authority,
      clientId: raw.oidc?.clientId ?? defaults.oidc.clientId,
      scope: raw.oidc?.scope ?? defaults.oidc.scope,
      redirectPath: raw.oidc?.redirectPath ?? defaults.oidc.redirectPath,
      postLogoutPath: raw.oidc?.postLogoutPath ?? defaults.oidc.postLogoutPath,
    },
    devToken: raw.devToken ?? defaults.devToken,
    defaultLanguage: asLocale(raw.defaultLocale) ?? asLocale(raw.defaultLanguage) ?? defaults.defaultLanguage,
    locales: locales.length ? locales : defaults.locales,
    fonts: raw.fonts && typeof raw.fonts === 'object' ? raw.fonts : defaults.fonts,
  };
}

export async function loadConfig(): Promise<SangamConfig> {
  try {
    const res = await fetch('/config.json', { cache: 'no-store', credentials: 'same-origin' });
    current = res.ok ? normalise((await res.json()) as RawConfig) : defaults;
  } catch {
    current = defaults;
  }
  // Local development override only (vite dev server); never needed in the image.
  const envMode = import.meta.env.VITE_AUTH_MODE as AuthMode | undefined;
  if (envMode === 'oidc' || envMode === 'dev') current = { ...current, authMode: envMode };
  return current;
}

export function config(): SangamConfig {
  return current;
}
