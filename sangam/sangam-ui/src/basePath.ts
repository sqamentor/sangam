// SPDX-License-Identifier: Apache-2.0
/**
 * R8-present: the app may be served under a path prefix (Vite `base`, from SANGAM_UI_BASE at build time, e.g.
 * /sangam/ behind a host that forwards <host>/sangam/* unchanged). Every same-origin URL the app builds itself
 * (config.json, fonts, the OIDC redirect) and the router's base name come from here, so one build works at / and
 * under a prefix. Paths inside the router (routes, oidc.redirectPath, oidc.postLogoutPath) stay prefix-free.
 */

/** The prefix without its trailing slash: "" at the site root, "/sangam" under /sangam/. */
export function basePrefix(base: string = import.meta.env.BASE_URL ?? '/'): string {
  const b = base.startsWith('/') ? base : '/' + base;
  return b.replace(/\/+$/, '');
}

/** An absolute same-origin path under the prefix: appPath('/config.json') is "/sangam/config.json" under /sangam/. */
export function appPath(path: string, base?: string): string {
  return basePrefix(base) + (path.startsWith('/') ? path : '/' + path);
}

/** The router's base name: undefined at the site root, "/sangam" under /sangam/. */
export function routerBasename(base?: string): string | undefined {
  return basePrefix(base) || undefined;
}
