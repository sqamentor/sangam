// SPDX-License-Identifier: Apache-2.0
import { useCallback, useEffect, useRef, useState, type DependencyList } from 'react';
import { newIdempotencyKey, type Page } from '../api/client';

export interface AsyncState<T> {
  data: T | undefined;
  error: Error | undefined;
  loading: boolean;
  /** true after 10 s without a result ("Still loading", X.4.7.2). */
  slow: boolean;
  reload: () => void;
  setData: (d: T) => void;
}

export function useAsync<T>(fn: (signal: AbortSignal) => Promise<T>, deps: DependencyList, enabled = true): AsyncState<T> {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<Error>();
  const [loading, setLoading] = useState<boolean>(enabled);
  const [slow, setSlow] = useState(false);
  const [n, setN] = useState(0);
  const fnRef = useRef(fn);
  fnRef.current = fn;

  useEffect(() => {
    if (!enabled) {
      setLoading(false);
      return;
    }
    const ctl = new AbortController();
    setLoading(true);
    setSlow(false);
    setError(undefined);
    const timer = window.setTimeout(() => setSlow(true), 10000);
    fnRef
      .current(ctl.signal)
      .then((d) => {
        if (!ctl.signal.aborted) setData(d);
      })
      .catch((e: Error) => {
        if (!ctl.signal.aborted && e.name !== 'AbortError') setError(e);
      })
      .finally(() => {
        window.clearTimeout(timer);
        if (!ctl.signal.aborted) setLoading(false);
      });
    return () => {
      ctl.abort();
      window.clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, n, enabled]);

  return { data, error, loading, slow, reload: () => setN((x) => x + 1), setData };
}

export interface PagedState<T> extends AsyncState<Page<T>> {
  page: number;
  size: number;
  setPage: (p: number) => void;
  setSize: (s: number) => void;
}

export function usePaged<T>(
  fn: (q: { page: number; size: number }, signal: AbortSignal) => Promise<Page<T>>,
  deps: DependencyList,
  initialSize = 25,
): PagedState<T> {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(initialSize);
  // reset to first page when filters change
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => setPage(0), deps);
  const state = useAsync((signal) => fn({ page, size }, signal), [...deps, page, size]);
  return { ...state, page, size, setPage, setSize };
}

export interface ActionState {
  busy: boolean;
  error: Error | undefined;
  /** Runs fn with an idempotency key that is kept until success, so a retry after a timeout is safe (CX-4, R41). */
  run: <R>(fn: (idempotencyKey: string) => Promise<R>) => Promise<R | undefined>;
  reset: () => void;
}

export function useAction(): ActionState {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error>();
  const keyRef = useRef<string>(newIdempotencyKey());
  const run = useCallback(async <R,>(fn: (k: string) => Promise<R>): Promise<R | undefined> => {
    setBusy(true);
    setError(undefined);
    try {
      const r = await fn(keyRef.current);
      keyRef.current = newIdempotencyKey();
      return r;
    } catch (e) {
      setError(e as Error);
      return undefined;
    } finally {
      setBusy(false);
    }
  }, []);
  const reset = useCallback(() => {
    setError(undefined);
    keyRef.current = newIdempotencyKey();
  }, []);
  return { busy, error, run, reset };
}

/** Polite live-region announcements (success messages, X.4.7.2). */
let announcer: ((msg: string) => void) | null = null;
export function registerAnnouncer(fn: (msg: string) => void): void {
  announcer = fn;
}
export function announce(msg: string): void {
  announcer?.(msg);
}

export function useInterval(fn: () => void, ms: number | null): void {
  const ref = useRef(fn);
  ref.current = fn;
  useEffect(() => {
    if (ms === null) return;
    const id = window.setInterval(() => ref.current(), ms);
    return () => window.clearInterval(id);
  }, [ms]);
}
