// SPDX-License-Identifier: Apache-2.0
/**
 * M-043: entry point of the embeddable web components (library build: `npm run build:embed`, vite.embed.config.ts,
 * output dist-embed/sangam-elements.js). A PHP, .NET, Java or React application shows a Sangam task form or process
 * diagram without building its own UI:
 *
 *   <script type="module">
 *     import { configure } from '/assets/sangam-elements.js';
 *     configure({ apiBase: 'https://sangam.example.org/api/v1', getAccessToken: () => myApp.currentAccessToken() });
 *   </script>
 *   <sangam-task-form task-id="…"></sangam-task-form>
 *
 * Token handoff: the host application already holds the user's OIDC access token for Sangam (same issuer, audience
 * accepted by the server). getAccessToken is called before every request and its result is only placed in the
 * Authorization header; it is never stored, logged or written to an attribute. CSP and CORS guidance: README.md
 * "Embeddable web components".
 */
import i18n from 'i18next';
import { setConfig } from '../config';
import { setTokenProvider, setUnauthenticatedHandler } from '../api/client';
import { initI18n, type Lang } from '../i18n';
import { defineSangamElements, refreshSangamElements, setConfiguredCheck } from './elements';

export interface SangamEmbedOptions {
  /** The Sangam API base: an absolute http(s) URL (another origin needs sangam.security.cors.allowed-origins) or a same-origin path. */
  apiBase: string;
  /** Returns the user's current access token, or null when there is none. Called before every request. */
  getAccessToken: () => string | null | undefined | Promise<string | null | undefined>;
  /** UI language of the components; default "en" (0.1.0 is English-only, Q-06; "hi" uses the optional catalogue). */
  language?: Lang;
  /** Called when the server answers 401 (the host should refresh its session). */
  onUnauthenticated?: () => void;
}

let configured = false;
let i18nReady = false;

/** Throws a TypeError for an unusable apiBase: only http(s) URLs and same-origin paths are accepted. */
export function checkApiBase(apiBase: unknown): string {
  if (typeof apiBase !== 'string' || !apiBase.trim()) throw new TypeError('sangam: configure() needs apiBase');
  const v = apiBase.trim();
  if (v.startsWith('/') && !v.startsWith('//')) return v;
  let u: URL;
  try {
    u = new URL(v);
  } catch {
    throw new TypeError('sangam: apiBase must be an http(s) URL or a path starting with /');
  }
  if (u.protocol !== 'https:' && u.protocol !== 'http:') throw new TypeError('sangam: apiBase must be an http(s) URL or a path starting with /');
  if (u.username || u.password) throw new TypeError('sangam: apiBase must not carry credentials');
  return v;
}

/** Configures Sangam for this page and registers the elements. Calling it again replaces the previous options. */
export function configure(options: SangamEmbedOptions): void {
  if (!options || typeof options.getAccessToken !== 'function') throw new TypeError('sangam: configure() needs getAccessToken');
  const apiBase = checkApiBase(options.apiBase);
  const language: Lang = options.language === 'hi' ? 'hi' : 'en';
  setConfig({ apiBase, authMode: 'oidc', environment: 'embedded', defaultLanguage: language, locales: ['en', 'hi'] });
  const get = options.getAccessToken;
  setTokenProvider(async () => (await get()) || null);
  setUnauthenticatedHandler(options.onUnauthenticated ?? (() => undefined));
  if (!i18nReady) {
    initI18n(language, { embedded: true });
    i18nReady = true;
  } else if (i18n.language !== language) {
    void i18n.changeLanguage(language);
  }
  configured = true;
  setConfiguredCheck(() => configured);
  defineSangamElements();
  refreshSangamElements();
}

export { defineSangamElements, PROCESS_DIAGRAM_TAG, TASK_FORM_TAG } from './elements';
