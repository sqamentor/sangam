// SPDX-License-Identifier: Apache-2.0
/**
 * Decision 162 (M-109): sign-in for the optional Microsoft Teams app is a Teams pop-up that runs the UI's own OIDC
 * login (code + PKCE through oidc-client-ts, any IdP, air-gapped Keycloak included). No Microsoft identity is used.
 *
 * Flow (all on this origin, one page, teams.html):
 * 1. The tab calls authentication.authenticate({url: teams.html?view=auth-start}); Teams opens that URL in a pop-up.
 * 2. auth-start: signinRedirect to the IdP with redirect_uri = teams.html (register it at the IdP as one more redirect
 *    URI of the UI client).
 * 3. auth-end (teams.html?code=...&state=...): signinRedirectCallback, then authentication.notifySuccess(user) hands
 *    the signed-in user to the tab. Hand-over by message, not by storage: the tab is a third-party frame inside Teams
 *    whose storage is partitioned away from the first-party pop-up.
 * 4. The tab checks the result (an access token that has not expired) and stores it where AuthProvider reads it.
 *
 * Nothing here imports @microsoft/teams-js; the SDK calls are passed in (sdk.ts), so this logic is testable and the
 * console never loads the SDK.
 */
import { User, type UserManager } from 'oidc-client-ts';

export type TeamsView = 'tab' | 'auth-start' | 'auth-end';
export const AUTH_START = 'auth-start';
export const TEAMS_PAGE = 'teams.html';

/** Which part of the Teams page this load is: the tab, the pop-up start, or the IdP's answer to the pop-up. */
export function teamsView(search: string): TeamsView {
  const q = new URLSearchParams(search);
  if (q.has('state') && (q.has('code') || q.has('error'))) return 'auth-end';
  if (q.get('view') === AUTH_START) return 'auth-start';
  return 'tab';
}

/** The Teams page URL (the pop-up's OIDC redirect URI), honouring the UI base path. */
export function teamsPageUrl(origin: string, base: string): string {
  const b = base.endsWith('/') ? base : `${base}/`;
  return `${origin}${b}${TEAMS_PAGE}`;
}

export function authStartUrl(origin: string, base: string): string {
  return `${teamsPageUrl(origin, base)}?view=${AUTH_START}`;
}

/** Pop-up, step 2. */
export async function startPopupLogin(mgr: Pick<UserManager, 'signinRedirect'>): Promise<void> {
  await mgr.signinRedirect({ state: 'teams' });
}

/** Pop-up, step 3: completes the code exchange and returns what notifySuccess carries to the tab. */
export async function completePopupLogin(mgr: Pick<UserManager, 'signinRedirectCallback'>): Promise<string> {
  const user = await mgr.signinRedirectCallback();
  return user.toStorageString();
}

export class TeamsSignInError extends Error {
  constructor(readonly reason: 'cancelled' | 'invalid' | 'failed', detail?: string) {
    super(detail ? `${reason}: ${detail}` : reason);
  }
}

/** Tab, step 4: the pop-up's result as a signed-in user, refused unless it carries an access token still valid. */
export function readPopupResult(result: string, nowSeconds = Math.floor(Date.now() / 1000)): User {
  let user: User;
  try {
    user = User.fromStorageString(result);
  } catch {
    throw new TeamsSignInError('invalid');
  }
  if (!user.access_token) throw new TeamsSignInError('invalid');
  if (typeof user.expires_at === 'number' && user.expires_at <= nowSeconds) throw new TeamsSignInError('invalid', 'expired');
  return user;
}

/** Maps an authenticate() rejection: Teams rejects with "CancelledByUser" when the pop-up is closed. */
export function signInFailure(e: unknown): TeamsSignInError {
  if (e instanceof TeamsSignInError) return e;
  const msg = e instanceof Error ? e.message : String(e ?? '');
  if (/CancelledByUser/i.test(msg)) return new TeamsSignInError('cancelled');
  return new TeamsSignInError('failed', msg);
}

/** Tab: authenticate in the pop-up, then store the user for AuthProvider. */
export async function signInFromTab(
  authenticate: (url: string) => Promise<string>,
  store: Pick<UserManager, 'storeUser'>,
  origin: string,
  base: string,
): Promise<User> {
  let result: string;
  try {
    result = await authenticate(authStartUrl(origin, base));
  } catch (e) {
    throw signInFailure(e);
  }
  const user = readPopupResult(result);
  await store.storeUser(user);
  return user;
}
