// SPDX-License-Identifier: Apache-2.0
/**
 * Decision 399 Q3: the modules this installation has wired, read once per signed-in user (GET /me/capabilities), so
 * the menu hides the screens of modules that are off. Until the read answers, or when it fails, the result is null and
 * nothing is hidden: the menu is a convenience and the server remains the judge (X.4.7).
 */
import { useEffect, useState } from 'react';
import { api } from '../api/endpoints';
import { useAuth } from '../auth/AuthProvider';

let cache: { user: string; modules: ReadonlySet<string> } | null = null;

/** Clears the per-user cache (sign-out and tests). */
export function resetInstalledModules(): void {
  cache = null;
}

export function useInstalledModules(): ReadonlySet<string> | null {
  const { me } = useAuth();
  const user = me?.userId ?? null;
  const [modules, setModules] = useState<ReadonlySet<string> | null>(cache && cache.user === user ? cache.modules : null);
  useEffect(() => {
    if (!user) return;
    if (cache && cache.user === user) {
      setModules(cache.modules);
      return;
    }
    let live = true;
    Promise.resolve()
      .then(() => api.me.capabilities())
      .then((r) => {
        if (!Array.isArray(r?.modules)) return;
        const set: ReadonlySet<string> = new Set(r.modules.filter((m): m is string => typeof m === 'string'));
        cache = { user, modules: set };
        if (live) setModules(set);
      })
      .catch(() => {
        /* an older server without the read, or a failure: nothing is hidden */
      });
    return () => {
      live = false;
    };
  }, [user]);
  return user ? modules : null;
}
