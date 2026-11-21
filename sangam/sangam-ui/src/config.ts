// SPDX-License-Identifier: Apache-2.0
/**
 * Runtime configuration, read from /config.json at boot (WS-DEPLOY contract, sangam/deploy/README.md §10). Nothing
 * environment-specific is baked into the build, so one image serves every installation, air-gapped included (R56).
 *
 * Contract fields: apiBase, oidc.issuer, oidc.clientId, defaultLocale, locales, environment.
 * Sangam UI extras (optional): authMode, devToken, oidc.scope, oidc.redirectPath, oidc.postLogoutPath,
 * fonts (per-tenant / state font override, see FONTS.md and src/fonts.ts),
 * traceUrlTemplate (M-044: the trace viewer's URL with {traceId}, e.g. https://traces.example/trace/{traceId}; empty = no links),
 * teams.enabled (decision 162), dsc.agentUrl (decision 165: the signer's local DSC agent, a loopback origin only),
 * session.idleMinutes (decision 349: minutes without input after which the session ends; see IDLE_MINUTES).
 * The API is called on the same origin (/api/v1, /engine-rest are proxied by the UI image).
 */
import { appPath } from './basePath';
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
  /** Configurable links on the Welcome page. */
  welcomeLinks: { url: string; label: { en: string; hi: string } }[];
  /** Per-tenant font override (state-government fonts first); FONTS.md. */
  fonts: FontsConfig;
  /** M-044: trace-viewer URL template with the placeholder {traceId}; empty when no trace viewer is configured. */
  traceUrlTemplate: string;
  /** Decision 162: the optional Microsoft Teams app (teams.html). Off unless config.json says teams.enabled = true. */
  teams: { enabled: boolean };
  /**
   * Decision 165: the DSC loopback agent on the signer's computer, as an origin such as http://127.0.0.1:47321.
   * Empty when not configured, and also when the value is not a loopback http origin (the UI never sends signing
   * requests anywhere else). The server's CSP connect-src must name the same origin.
   */
  dsc: { agentUrl: string };
  /** Decision 349: the idle period of a signed-in session, in whole minutes (IDLE_MINUTES). */
  session: { idleMinutes: number };
}

/**
 * Decision 349 (CERT-In / DPDP matrix W-09): the UI idle period is deployment configuration (/config.json
 * session.idleMinutes, rendered from SANGAM_UI_IDLE_MINUTES by the UI image, 18-sangam-session.envsh).
 * - default 15 minutes: the inactivity bound of NIST SP 800-63B (2017) section 4.3.3 for AAL3 and of OWASP ASVS 4.0.3 V3.3.2 level 3, the
 *   stricter of the two common references (AAL2 / level 2 say 30), chosen because the console shows citizens' personal
 *   data at shared office desks. These are technical references, not law: the legal source of a specific period is
 *   UNVERIFIED (matrix, carried over item 6);
 * - accepted: a whole number of minutes from MIN to MAX (one working day). Anything else, 0 and "off" included, falls
 *   back to the default: the idle timeout cannot be switched off by a bad or missing value (fail closed).
 */
export const IDLE_MINUTES = { DEFAULT: 15, MIN: 1, MAX: 480 } as const;

export function idleMinutes(v: unknown): number {
  const n = typeof v === 'number' ? v : typeof v === 'string' && /^\d{1,4}$/.test(v.trim()) ? Number(v.trim()) : NaN;
  return Number.isInteger(n) && n >= IDLE_MINUTES.MIN && n <= IDLE_MINUTES.MAX ? n : IDLE_MINUTES.DEFAULT;
}

export interface RawConfig {
  apiBase?: string;
  authMode?: AuthMode;
  environment?: string;
  oidc?: { issuer?: string; authority?: string; clientId?: string; scope?: string; redirectPath?: string; postLogoutPath?: string };
  devToken?: string;
  defaultLocale?: string;
  defaultLanguage?: string;
  /** A list, or the comma-separated string the nginx template renders (SANGAM_LOCALES="en,hi"). */
  locales?: string[] | string;
  welcomeLinks?: { url: string; label: { en: string; hi: string } }[];
  fonts?: FontsConfig;
  traceUrlTemplate?: string;
  teams?: { enabled?: boolean };
  dsc?: { agentUrl?: string };
  session?: { idleMinutes?: number | string };
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
  // Q6 (owner, 2026-11-21; decisions Q-06, 397 Q11): English only unless /config.json names more ("en,hi").
  locales: ['en'],
  welcomeLinks: [],
  fonts: {},
  traceUrlTemplate: '',
  teams: { enabled: false },
  dsc: { agentUrl: '' },
  session: { idleMinutes: IDLE_MINUTES.DEFAULT },
};

/**
 * A loopback http origin, or '' for anything else. Only 127.0.0.1 and localhost are accepted, as the DSC agent
 * specification names them (coordinator ruling, Wave 4 close-out); the IPv6 loopback [::1] is refused here even though
 * the agent itself would answer on it, so the UI and the documented agent address stay one and the same.
 */
export function loopbackOrigin(v: unknown): string {
  if (typeof v !== 'string') return '';
  const m = /^http:\/\/(127\.0\.0\.1|localhost):(\d{1,5})\/?$/i.exec(v.trim());
  if (!m) return '';
  const port = Number(m[2]);
  return port >= 1 && port <= 65535 ? `http://${m[1].toLowerCase()}:${port}` : '';
}

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
    welcomeLinks: raw.welcomeLinks ?? [],
    fonts: raw.fonts && typeof raw.fonts === 'object' ? raw.fonts : defaults.fonts,
    traceUrlTemplate: typeof raw.traceUrlTemplate === 'string' ? raw.traceUrlTemplate.trim() : defaults.traceUrlTemplate,
    teams: { enabled: raw.teams?.enabled === true },
    dsc: { agentUrl: loopbackOrigin(raw.dsc?.agentUrl) },
    session: { idleMinutes: idleMinutes(raw.session?.idleMinutes) },
  };
}

export async function loadConfig(): Promise<SangamConfig> {
  try {
    const res = await fetch(appPath('/config.json'), { cache: 'no-store', credentials: 'same-origin' });
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

/**
 * M-043: the embeddable web components run inside another application's page, which has no /config.json of Sangam's;
 * the host passes the same fields through configure() (src/embed/index.ts) and they are normalised the same way.
 */
export function setConfig(raw: RawConfig): SangamConfig {
  current = normalise(raw);
  return current;
}
