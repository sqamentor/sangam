// SPDX-License-Identifier: Apache-2.0
/**
 * M-040: a server-sent-events client over fetch, so the stream carries the same Authorization: Bearer header,
 * seat headers and 401 handling as every other call (client.ts raw()). The browser EventSource cannot send headers,
 * which is why it is not used.
 *
 * - Parsing follows the WHATWG event-stream rules (HTML Living Standard §9.2.6): lines end in CRLF, LF or CR; a blank
 *   line dispatches; "event", "data" (joined with LF), "id" (ignored when it holds NUL) and "retry" (digits only);
 *   a line starting with ":" is a comment; an event with no data is not dispatched; the last event id survives
 *   events that carry no id.
 * - Reconnect: after the stream ends or the network fails, the client waits (the server's "retry" or 3 s, doubling up
 *   to 30 s while attempts keep failing) and reconnects with the Last-Event-ID request header. A refusal (400, 401,
 *   403, 404) is final: the caller is told and nothing is retried.
 * - onOpen runs after every successful (re)connect. The server streams live events only (ChannelsLiveHub.java:15-18,
 *   "a client on another node catches up from the history endpoint"), so callers re-read history there.
 */
import { ApiError, raw } from './client';

export interface SseEvent {
  /** "message" when the server named none. */
  event: string;
  data: string;
  /** The last event id at dispatch time (may come from an earlier event). */
  lastEventId: string;
}

/** Incremental event-stream parser: feed text chunks, get dispatched events. */
export class SseParser {
  private buf = '';
  private data: string[] = [];
  private event = '';
  private pendingCr = false;
  lastEventId = '';
  /** The server's reconnection time in ms, when it sent one. */
  retry: number | undefined;

  constructor(lastEventId = '') {
    this.lastEventId = lastEventId;
  }

  push(chunk: string): SseEvent[] {
    const out: SseEvent[] = [];
    let text = chunk;
    // A CR at the end of the previous chunk may be the first half of CRLF.
    if (this.pendingCr && text.startsWith('\n')) text = text.slice(1);
    this.pendingCr = false;
    this.buf += text;
    for (;;) {
      const m = /\r\n|\r|\n/.exec(this.buf);
      if (!m) break;
      if (m[0] === '\r' && m.index === this.buf.length - 1) {
        this.pendingCr = true;
      }
      const line = this.buf.slice(0, m.index);
      this.buf = this.buf.slice(m.index + m[0].length);
      const ev = this.line(line);
      if (ev) out.push(ev);
    }
    return out;
  }

  private line(line: string): SseEvent | null {
    if (line === '') return this.dispatch();
    if (line.startsWith(':')) return null;
    const i = line.indexOf(':');
    const field = i < 0 ? line : line.slice(0, i);
    let value = i < 0 ? '' : line.slice(i + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    switch (field) {
      case 'event':
        this.event = value;
        break;
      case 'data':
        this.data.push(value);
        break;
      case 'id':
        if (!value.includes('\u0000')) this.lastEventId = value;
        break;
      case 'retry':
        if (/^\d+$/.test(value)) this.retry = Number(value);
        break;
      default:
        break;
    }
    return null;
  }

  private dispatch(): SseEvent | null {
    const data = this.data;
    const event = this.event;
    this.data = [];
    this.event = '';
    if (data.length === 0) return null;
    return { event: event || 'message', data: data.join('\n'), lastEventId: this.lastEventId };
  }
}

export interface StreamHandlers {
  onEvent: (e: SseEvent) => void;
  /** After every successful connect or reconnect. */
  onOpen?: () => void;
  /** Live (true) or waiting to reconnect (false). */
  onLive?: (live: boolean) => void;
  /** A refusal that ends the stream (no retry). */
  onFatal?: (e: ApiError) => void;
}

export interface StreamOptions {
  /** Resume after this id on the first connect. */
  lastEventId?: string;
  /** First wait before a reconnect, in ms (default 3000). */
  retryMs?: number;
  /** Upper bound of the wait, in ms (default 30000). */
  maxRetryMs?: number;
  /**
   * More statuses that end the stream like a refusal, on top of 400, 401, 403 and 404. M-101 presence passes 501: a
   * server without the stream answers it on every attempt, so retrying it only adds load.
   */
  fatalStatuses?: readonly number[];
}

const FATAL = new Set([400, 401, 403, 404]);

/** The wait before reconnect attempt n (0-based) after failures: base doubled per failure, capped. */
export function backoff(base: number, failures: number, max: number): number {
  return Math.min(max, base * 2 ** Math.min(failures, 16));
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve();
    const id = setTimeout(done, ms);
    function done() {
      clearTimeout(id);
      signal.removeEventListener('abort', done);
      resolve();
    }
    signal.addEventListener('abort', done);
  });
}

/**
 * Opens an authenticated event stream on a Sangam API path and keeps it open until close() is called.
 * Returns close(); calling it aborts the current request and stops reconnecting.
 */
export function openEventStream(path: string, handlers: StreamHandlers, opts: StreamOptions = {}): () => void {
  const ctl = new AbortController();
  const base = opts.retryMs ?? 3000;
  const max = opts.maxRetryMs ?? 30000;
  const fatal = new Set([...FATAL, ...(opts.fatalStatuses ?? [])]);
  void (async () => {
    let lastEventId = opts.lastEventId ?? '';
    let serverRetry: number | undefined;
    let failures = 0;
    while (!ctl.signal.aborted) {
      const parser = new SseParser(lastEventId);
      try {
        const headers: Record<string, string> = { Accept: 'text/event-stream', 'Cache-Control': 'no-cache' };
        if (lastEventId) headers['Last-Event-ID'] = lastEventId;
        const res = await raw('GET', path, { headers, signal: ctl.signal });
        if (!res.body) throw new ApiError({ title: 'No stream body', status: 0, code: 'NETWORK' });
        failures = 0;
        handlers.onLive?.(true);
        handlers.onOpen?.();
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          for (const ev of parser.push(decoder.decode(value, { stream: true }))) handlers.onEvent(ev);
          lastEventId = parser.lastEventId;
          serverRetry = parser.retry ?? serverRetry;
        }
      } catch (e) {
        if (ctl.signal.aborted || (e as Error).name === 'AbortError') break;
        if (e instanceof ApiError && fatal.has(e.status)) {
          handlers.onLive?.(false);
          handlers.onFatal?.(e);
          return;
        }
        failures += 1;
      }
      lastEventId = parser.lastEventId || lastEventId;
      if (ctl.signal.aborted) break;
      handlers.onLive?.(false);
      await sleep(backoff(serverRetry ?? base, Math.max(0, failures - 1), max), ctl.signal);
    }
  })();
  return () => ctl.abort();
}

/** Adds a streamed record to a list by id: an id already present is replaced, a new one is appended. */
export function mergeById<T extends Record<string, unknown>>(list: T[] | undefined, item: T): T[] {
  const id = String(item.id);
  const cur = list ?? [];
  const i = cur.findIndex((x) => String(x.id) === id);
  if (i < 0) return [...cur, item];
  const next = cur.slice();
  next[i] = item;
  return next;
}
