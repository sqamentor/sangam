// SPDX-License-Identifier: Apache-2.0
/**
 * Decision 349 (CERT-In / DPDP matrix W-09): the session ends after a configured period without user input
 * (config().session.idleMinutes, config.ts IDLE_MINUTES), and the user must sign in again. A warning comes first, with
 * a way to stay signed in (WCAG 2.2.1, timing adjustable): any input counts. Background requests (polling, refresh)
 * are not input and never extend the session.
 *
 * The decision is made from timestamps, not from a timer that counts down, so a tab whose timers the browser slowed or
 * paused (background tab, sleeping laptop) ends the session as soon as it runs again (on the next tick or when it
 * becomes visible).
 */
import { useCallback, useEffect, useRef, useState } from 'react';

/** User input that counts as activity. */
export const ACTIVITY_EVENTS = ['pointerdown', 'pointermove', 'keydown', 'wheel', 'touchstart', 'scroll'] as const;

/** How long before the end the warning shows: one minute, or half the period when that is shorter. */
export function warningMs(idleMs: number): number {
  return Math.min(60_000, Math.floor(idleMs / 2));
}

export type IdlePhase = 'active' | 'warning' | 'idle';

export interface IdleState {
  phase: IdlePhase;
  /** Milliseconds left until the session ends (0 once idle). */
  remainingMs: number;
}

/** The phase at `now` for input last seen at `lastInput`. */
export function idleState(lastInput: number, now: number, idleMs: number): IdleState {
  const remainingMs = Math.max(0, lastInput + idleMs - now);
  if (remainingMs === 0) return { phase: 'idle', remainingMs };
  return { phase: remainingMs <= warningMs(idleMs) ? 'warning' : 'active', remainingMs };
}

export interface IdleOptions {
  idleMs: number;
  /** Called once when the period has passed without input. */
  onIdle: () => void;
  /** Tick period; tests may shorten it. */
  tickMs?: number;
  now?: () => number;
}

/**
 * Watches input on this window while mounted. Returns the state for the warning and `stay`, which counts as input
 * (the warning's button).
 */
export function useIdleTimeout({ idleMs, onIdle, tickMs = 1000, now = Date.now }: IdleOptions): IdleState & { stay: () => void } {
  const last = useRef(now());
  const fired = useRef(false);
  const idle = useRef(onIdle);
  idle.current = onIdle;
  const [state, setState] = useState<IdleState>(() => idleState(last.current, last.current, idleMs));

  const check = useCallback(() => {
    if (fired.current) return;
    const s = idleState(last.current, now(), idleMs);
    // re-render once per second while warning (the countdown), otherwise only when the phase changes
    setState((prev) => (prev.phase === s.phase && (s.phase !== 'warning' || Math.ceil(prev.remainingMs / 1000) === Math.ceil(s.remainingMs / 1000)) ? prev : s));
    if (s.phase === 'idle') {
      fired.current = true;
      idle.current();
    }
  }, [idleMs, now]);

  const stay = useCallback(() => {
    if (fired.current) return;
    last.current = now();
    check();
  }, [check, now]);

  useEffect(() => {
    // the session may already be over when this runs late: decide first, then count input
    const onInput = () => {
      if (fired.current) return;
      if (idleState(last.current, now(), idleMs).phase === 'idle') {
        check();
        return;
      }
      last.current = now();
      if (state.phase === 'warning') check();
    };
    const onVisible = () => {
      if (document.visibilityState === 'visible') check();
    };
    for (const e of ACTIVITY_EVENTS) window.addEventListener(e, onInput, { capture: true, passive: true });
    document.addEventListener('visibilitychange', onVisible);
    const timer = window.setInterval(check, tickMs);
    return () => {
      for (const e of ACTIVITY_EVENTS) window.removeEventListener(e, onInput, { capture: true });
      document.removeEventListener('visibilitychange', onVisible);
      window.clearInterval(timer);
    };
  }, [check, idleMs, now, tickMs, state.phase]);

  return { ...state, stay };
}
