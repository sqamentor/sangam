// SPDX-License-Identifier: Apache-2.0
/**
 * Sign-in (S01) and seat context (S02).
 * - OIDC authorization-code + PKCE through oidc-client-ts (R50, R67); tokens kept in sessionStorage only.
 * - Dev token mode for the server's dev profile (ARCHITECTURE §4).
 * - The selected seat travels as X-Sangam-Seat on every call; the server re-validates it on every command (K7).
 * Role-aware menus are a convenience only; the server is the security boundary (X.4.7).
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { UserManager, WebStorageStateStore, type User as OidcUser } from 'oidc-client-ts';
import { addHeaderProvider, setTokenProvider, setUnauthenticatedHandler, ApiError } from '../api/client';
import { api } from '../api/endpoints';
import type { Me, Role, SeatHolding } from '../api/types';
import { config } from '../config';
import { setTenantLocale } from '../i18n';

type Status = 'loading' | 'anonymous' | 'authenticated' | 'error';

interface AuthState {
  status: Status;
  mode: 'oidc' | 'dev';
  me: Me | null;
  error: ApiError | Error | null;
  seat: SeatHolding | null;
  login: (devToken?: string) => Promise<void>;
  logout: () => Promise<void>;
  /** Decision 349: ends the session without asking (idle timeout); the sign-in screen then says why. */
  endSession: (reason: SignedOutReason) => Promise<void>;
  /** Why the last session ended, when the UI ended it (shown on the sign-in screen until the next sign-in). */
  signedOutReason: SignedOutReason | null;
  completeCallback: () => Promise<string>;
  setSeat: (seatId: string | null) => void;
  hasRole: (...roles: Role[]) => boolean;
  reload: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

const DEV_TOKEN_KEY = 'sangam.devToken';
const SEAT_KEY = 'sangam.seat';
/** sessionStorage: why the UI ended the last session (survives the OIDC end-session redirect back to the app). */
const SIGNED_OUT_KEY = 'sangam.signedOut';

export type SignedOutReason = 'idle';

function safeSession(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

function decodeClaims(token: string | null | undefined): Record<string, unknown> {
  if (!token || token.split('.').length !== 3) return {};
  try {
    const json = atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'));
    return JSON.parse(decodeURIComponent(escape(json))) as Record<string, unknown>;
  } catch {
    return {};
  }
}

function rolesFromClaims(c: Record<string, unknown>): Role[] {
  const out = new Set<string>();
  const add = (v: unknown) => Array.isArray(v) && v.forEach((x) => typeof x === 'string' && out.add(x.toUpperCase()));
  add(c.roles);
  add((c.realm_access as { roles?: unknown } | undefined)?.roles);
  add(c.groups);
  return [...out] as Role[];
}

/**
 * The UI's OIDC client (code + PKCE, same authority, client, scope and user store) with a given redirect URI. The
 * console uses config().oidc.redirectPath; the optional Teams app (decision 162) uses its own page as the redirect of
 * its sign-in pop-up, and stores the signed-in user where this provider reads it (same store key: authority + client).
 */
export function createUserManager(redirectUri: string): UserManager {
  const c = config();
  const store = safeSession() ?? undefined;
  return new UserManager({
    authority: c.oidc.authority,
    client_id: c.oidc.clientId,
    redirect_uri: redirectUri,
    post_logout_redirect_uri: window.location.origin + c.oidc.postLogoutPath,
    response_type: 'code',
    scope: c.oidc.scope,
    automaticSilentRenew: true,
    userStore: store ? new WebStorageStateStore({ store }) : undefined,
  });
}

let userManager: UserManager | null = null;
function manager(): UserManager {
  if (!userManager) userManager = createUserManager(window.location.origin + config().oidc.redirectPath);
  return userManager;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const mode = config().authMode;
  const [status, setStatus] = useState<Status>('loading');
  const [me, setMe] = useState<Me | null>(null);
  const [error, setError] = useState<ApiError | Error | null>(null);
  const [seatId, setSeatId] = useState<string | null>(() => safeSession()?.getItem(SEAT_KEY) ?? null);
  const [nonce, setNonce] = useState(0);
  const [signedOutReason, setSignedOutReason] = useState<SignedOutReason | null>(() => (safeSession()?.getItem(SIGNED_OUT_KEY) === 'idle' ? 'idle' : null));
  const tokenRef = useRef<string | null>(null);

  useEffect(() => {
    setTokenProvider(async () => {
      if (mode === 'dev') return safeSession()?.getItem(DEV_TOKEN_KEY) ?? null;
      const u = await manager().getUser();
      return u && !u.expired ? u.access_token : null;
    });
    setUnauthenticatedHandler(() => setStatus((s) => (s === 'authenticated' ? 'anonymous' : s)));
  }, [mode]);

  // Contract headers: X-Sangam-Seat = own substantive seat; X-Sangam-Acting-For-Seat = the seat held on additional
  // charge or as leave substitute, so the audit records "acted for" (IX.5 rule 4).
  useEffect(() => {
    const held = me?.seats.find((s) => s.seatId === seatId);
    const own = me?.seats.find((s) => s.kind === 'SUBSTANTIVE');
    return addHeaderProvider((): Record<string, string> => {
      if (!seatId) return {};
      if (held && held.kind !== 'SUBSTANTIVE') return { ...(own ? { 'X-Sangam-Seat': own.seatId } : {}), 'X-Sangam-Acting-For-Seat': seatId };
      return { 'X-Sangam-Seat': seatId };
    });
  }, [seatId, me]);

  const loadMe = useCallback(async (token: string | null) => {
    tokenRef.current = token;
    if (!token) {
      setStatus('anonymous');
      setMe(null);
      setTenantLocale(null);
      return;
    }
    try {
      const m = await api.me.get();
      // M-045: money and digit grouping follow the tenant locale (no currency is assumed when it is absent)
      setTenantLocale(m.locale);
      const claimRoles = rolesFromClaims(decodeClaims(token));
      setMe({ ...m, roles: m.roles?.length ? m.roles : claimRoles, seats: m.seats ?? [] });
      setError(null);
      setStatus('authenticated');
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        setStatus('anonymous');
        return;
      }
      setError(e as Error);
      setStatus('error');
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (window.location.pathname === config().oidc.redirectPath) return; // handled by the callback route
      let token: string | null;
      if (mode === 'dev') token = safeSession()?.getItem(DEV_TOKEN_KEY) ?? null;
      else {
        const u: OidcUser | null = await manager().getUser().catch(() => null);
        token = u && !u.expired ? u.access_token : null;
      }
      if (!cancelled) await loadMe(token);
    })();
    return () => {
      cancelled = true;
    };
  }, [mode, loadMe, nonce]);

  useEffect(() => {
    if (mode !== 'oidc') return;
    const m = manager();
    const onLoaded = (u: OidcUser) => {
      tokenRef.current = u.access_token;
    };
    const onExpired = () => setStatus('anonymous');
    m.events.addUserLoaded(onLoaded);
    m.events.addAccessTokenExpired(onExpired);
    return () => {
      m.events.removeUserLoaded(onLoaded);
      m.events.removeAccessTokenExpired(onExpired);
    };
  }, [mode]);

  const login = useCallback(
    async (devToken?: string) => {
      safeSession()?.removeItem(SIGNED_OUT_KEY);
      setSignedOutReason(null);
      if (mode === 'dev') {
        const t = devToken || config().devToken;
        safeSession()?.setItem(DEV_TOKEN_KEY, t);
        setStatus('loading');
        await loadMe(t);
        return;
      }
      await manager().signinRedirect({ state: window.location.pathname + window.location.search });
    },
    [mode, loadMe],
  );

  const logout = useCallback(async () => {
    safeSession()?.removeItem(SEAT_KEY);
    setSeatId(null);
    if (mode === 'dev') {
      safeSession()?.removeItem(DEV_TOKEN_KEY);
      setMe(null);
      setStatus('anonymous');
      return;
    }
    await manager().signoutRedirect();
  }, [mode]);

  /**
   * Decision 349: the local session ends first and unconditionally (token, seat, user), so nothing signed in is left
   * even when the identity provider cannot be reached; then, in OIDC mode, the provider's session is ended too, so the
   * next sign-in asks for the credentials again instead of returning silently. Unsent field records stay encrypted and
   * bound to the user (M-001), as with "Log out anyway".
   */
  const endSession = useCallback(
    async (reason: SignedOutReason) => {
      const s = safeSession();
      s?.setItem(SIGNED_OUT_KEY, reason);
      s?.removeItem(SEAT_KEY);
      s?.removeItem(DEV_TOKEN_KEY);
      tokenRef.current = null;
      setSignedOutReason(reason);
      setSeatId(null);
      setMe(null);
      setTenantLocale(null);
      setStatus('anonymous');
      if (mode !== 'oidc') return;
      const m = manager();
      const u = await m.getUser().catch(() => null);
      await m.removeUser().catch(() => undefined);
      await m.signoutRedirect(u?.id_token ? { id_token_hint: u.id_token } : undefined).catch(() => undefined);
    },
    [mode],
  );

  const completeCallback = useCallback(async () => {
    const u = await manager().signinRedirectCallback();
    await loadMe(u.access_token);
    const target = typeof u.state === 'string' && u.state.startsWith('/') ? u.state : '/';
    return target;
  }, [loadMe]);

  const setSeat = useCallback((id: string | null) => {
    setSeatId(id);
    const s = safeSession();
    if (id) s?.setItem(SEAT_KEY, id);
    else s?.removeItem(SEAT_KEY);
  }, []);

  // Seat no longer held today (charge ended): drop it with notice handled by SeatSwitcher.
  const seat = useMemo(() => me?.seats.find((s) => s.seatId === seatId) ?? null, [me, seatId]);
  useEffect(() => {
    if (me && !seatId) {
      const first = me.seats.find((s) => s.kind === 'SUBSTANTIVE' && !s.pendingApproval) ?? me.seats[0];
      if (first) setSeat(first.seatId);
    }
  }, [me, seatId, setSeat]);

  const hasRole = useCallback(
    (...roles: Role[]) => {
      if (!me) return false;
      if (roles.length === 0) return true;
      return roles.some((r) => me.roles.includes(r));
    },
    [me],
  );

  const value: AuthState = {
    status,
    mode,
    me,
    error,
    seat,
    login,
    logout,
    endSession,
    signedOutReason,
    completeCallback,
    setSeat,
    hasRole,
    reload: () => setNonce((n) => n + 1),
  };
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const c = useContext(AuthContext);
  if (!c) throw new Error('useAuth outside AuthProvider');
  return c;
}
