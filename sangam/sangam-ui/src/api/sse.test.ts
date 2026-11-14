// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-040: the fetch-stream SSE client. Parsing per the WHATWG event-stream rules; the stream request carries the bearer
 * token and, on reconnect, Last-Event-ID; refusals are final; the poll fallback lives in the screen. Written, not run.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setTokenProvider } from './client';
import { SseParser, backoff, mergeById, openEventStream, type SseEvent } from './sse';

describe('SseParser', () => {
  it('dispatches on a blank line, joins data lines with LF and defaults the event name to message', () => {
    const p = new SseParser();
    expect(p.push('data: a\ndata: b\n')).toEqual([]);
    expect(p.push('\n')).toEqual([{ event: 'message', data: 'a\nb', lastEventId: '' }]);
  });

  it('reads event and id, keeps the last id for later events without one, and ignores comments', () => {
    const p = new SseParser();
    const out = p.push(': keep-alive\nevent: message\nid: m1\ndata: {"id":"m1"}\n\ndata: x\n\n');
    expect(out).toEqual([
      { event: 'message', data: '{"id":"m1"}', lastEventId: 'm1' },
      { event: 'message', data: 'x', lastEventId: 'm1' },
    ]);
    expect(p.lastEventId).toBe('m1');
  });

  it('accepts CRLF and CR line ends, also when CRLF is split across chunks', () => {
    const p = new SseParser();
    expect(p.push('data: a\r')).toEqual([]);
    expect(p.push('\n\r\n')).toEqual([{ event: 'message', data: 'a', lastEventId: '' }]);
    expect(p.push('data: b\r\r')).toEqual([{ event: 'message', data: 'b', lastEventId: '' }]);
  });

  it('does not dispatch an event without data, but an id alone still moves the last event id', () => {
    const p = new SseParser();
    expect(p.push('id: 7\n\n')).toEqual([]);
    expect(p.lastEventId).toBe('7');
  });

  it('ignores an id with NUL, takes retry only when it is all digits, and removes one leading space only', () => {
    const p = new SseParser('k');
    p.push('id: a\u0000b\nretry: 1x\n\n');
    expect(p.lastEventId).toBe('k');
    expect(p.retry).toBeUndefined();
    p.push('retry: 2500\n\n');
    expect(p.retry).toBe(2500);
    expect(p.push('data:  two\n\n')[0].data).toBe(' two');
  });

  it('handles a field split across chunks', () => {
    const p = new SseParser();
    expect(p.push('da')).toEqual([]);
    expect(p.push('ta: hel')).toEqual([]);
    expect(p.push('lo\n\n')).toEqual([{ event: 'message', data: 'hello', lastEventId: '' }]);
  });
});

describe('backoff and mergeById', () => {
  it('doubles per failure and stops at the cap', () => {
    expect([0, 1, 2, 3, 10].map((n) => backoff(3000, n, 30000))).toEqual([3000, 6000, 12000, 24000, 30000]);
  });

  it('appends a new id and replaces a known one', () => {
    const a = [{ id: 'm1', body: 'x' }];
    expect(mergeById(a, { id: 'm2', body: 'y' })).toEqual([{ id: 'm1', body: 'x' }, { id: 'm2', body: 'y' }]);
    expect(mergeById(a, { id: 'm1', body: 'z' })).toEqual([{ id: 'm1', body: 'z' }]);
    expect(mergeById(undefined, { id: 'm3' })).toEqual([{ id: 'm3' }]);
  });
});

function streamResponse(chunks: string[]): Response {
  const enc = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      for (const ch of chunks) c.enqueue(enc.encode(ch));
      c.close();
    },
  });
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

describe('openEventStream', () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
    setTokenProvider(async () => 'tok-1');
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    setTokenProvider(async () => null);
  });

  it('sends the bearer token, reads events, and reconnects with Last-Event-ID after the stream ends', async () => {
    const events: SseEvent[] = [];
    const opened = vi.fn();
    fetchMock
      .mockResolvedValueOnce(streamResponse(['event: message\nid: m1\ndata: {"id":"m1"}\n\n']))
      .mockResolvedValueOnce(streamResponse(['event: message\nid: m2\ndata: {"id":"m2"}\n\n']))
      .mockImplementation(() => new Promise(() => undefined));
    const close = openEventStream('/channels/conversations/c1/stream', { onEvent: (e) => events.push(e), onOpen: opened }, { retryMs: 1 });
    await vi.waitFor(() => expect(events.map((e) => e.lastEventId)).toEqual(['m1', 'm2']));
    close();
    const first = fetchMock.mock.calls[0];
    expect(String(first[0])).toBe('/api/v1/channels/conversations/c1/stream');
    const h1 = first[1].headers as Headers;
    expect(h1.get('Authorization')).toBe('Bearer tok-1');
    expect(h1.get('Accept')).toBe('text/event-stream');
    expect(h1.get('Last-Event-ID')).toBeNull();
    expect((fetchMock.mock.calls[1][1].headers as Headers).get('Last-Event-ID')).toBe('m1');
    expect(opened).toHaveBeenCalledTimes(2);
  });

  it('starts from a given last event id', async () => {
    fetchMock.mockImplementation(() => new Promise(() => undefined));
    const close = openEventStream('/s', { onEvent: () => undefined }, { lastEventId: 'm9' });
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled());
    close();
    expect((fetchMock.mock.calls[0][1].headers as Headers).get('Last-Event-ID')).toBe('m9');
  });

  it('a refusal (403) is final: onFatal, not live, no retry', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ title: 'Forbidden', status: 403 }), { status: 403, headers: { 'Content-Type': 'application/problem+json' } }));
    const fatal = vi.fn();
    const live = vi.fn();
    openEventStream('/s', { onEvent: () => undefined, onFatal: fatal, onLive: live }, { retryMs: 1 });
    await vi.waitFor(() => expect(fatal).toHaveBeenCalledTimes(1));
    await new Promise((r) => setTimeout(r, 20));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(live).toHaveBeenLastCalledWith(false);
  });

  it('fatalStatuses adds a final status (501): onFatal once, no retry', async () => {
    fetchMock.mockImplementation(async () => new Response(JSON.stringify({ title: 'Not Implemented', status: 501 }), { status: 501, headers: { 'Content-Type': 'application/problem+json' } }));
    const fatal = vi.fn();
    openEventStream('/s', { onEvent: () => undefined, onFatal: fatal }, { retryMs: 1, fatalStatuses: [501] });
    await vi.waitFor(() => expect(fatal).toHaveBeenCalledTimes(1));
    await new Promise((r) => setTimeout(r, 20));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect((fatal.mock.calls[0][0] as { status: number }).status).toBe(501);
  });

  it('without fatalStatuses a 501 is retried like any server failure', async () => {
    fetchMock
      .mockResolvedValueOnce(new Response('', { status: 501 }))
      .mockImplementation(() => new Promise(() => undefined));
    const fatal = vi.fn();
    const close = openEventStream('/s', { onEvent: () => undefined, onFatal: fatal }, { retryMs: 1 });
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    close();
    expect(fatal).not.toHaveBeenCalled();
  });

  it('a network failure is retried', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch')).mockImplementation(() => new Promise(() => undefined));
    const close = openEventStream('/s', { onEvent: () => undefined }, { retryMs: 1 });
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    close();
  });

  it('close() stops reconnecting', async () => {
    fetchMock.mockImplementation(async () => streamResponse([]));
    const close = openEventStream('/s', { onEvent: () => undefined }, { retryMs: 5 });
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled());
    close();
    const n = fetchMock.mock.calls.length;
    await new Promise((r) => setTimeout(r, 30));
    expect(fetchMock.mock.calls.length).toBe(n);
  });
});
