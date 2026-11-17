// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// M-101 presence UI over GET /models/by-file/presence/stream?file= (streamModelPresence, event 'presence', data ModelPresence):
// parsing, who is shown, and the clean degrade (no presence, no error banner) on 404, 501 and an unreachable server.
// Written for the gate; not run in Wave 4.
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { initI18n } from '../i18n';
import { setTokenProvider } from '../api/client';
import { ModelPresenceBar, PresenceList, otherViewers, parsePresence } from './presence';

beforeAll(() => initI18n('en'));
afterEach(cleanup);

const P = {
  fileId: 'pkg-1',
  viewers: [
    { userId: 'me', displayName: 'Me', editing: false, since: '2026-11-12T10:00:00Z' },
    { userId: 'u2', displayName: 'Asha', editing: false, since: '2026-11-12T10:05:00Z' },
    { userId: 'u3', editing: true, since: '2026-11-12T10:01:00Z' },
  ],
};

describe('M-101 parsePresence', () => {
  it('reads a ModelPresence and drops viewers that break the contract', () => {
    const p = parsePresence(JSON.stringify({ ...P, viewers: [...P.viewers, { userId: '', editing: true, since: 'x' }, { userId: 'u9', since: 'x' }, 7] }));
    expect(p?.fileId).toBe('pkg-1');
    expect(p?.viewers.map((v) => v.userId)).toEqual(['me', 'u2', 'u3']);
    expect(p?.viewers[2].displayName).toBeUndefined();
  });

  it('returns undefined for text that is not a ModelPresence', () => {
    expect(parsePresence('not json')).toBeUndefined();
    expect(parsePresence('{"viewers":[]}')).toBeUndefined();
    expect(parsePresence('{"fileId":"f"}')).toBeUndefined();
  });
});

describe('M-101 PresenceList', () => {
  it('leaves the signed-in user out and lists editors first, with an editing mark', () => {
    expect(otherViewers(P, 'me').map((v) => v.userId)).toEqual(['u3', 'u2']);
    render(<PresenceList presence={P} selfId="me" />);
    const list = screen.getByRole('list', { name: 'Also open by:' });
    expect(list.textContent).toContain('Asha');
    expect(list.textContent).toContain('u3');
    expect(list.textContent).not.toContain('Me');
    expect(screen.getByText('editing')).toBeTruthy();
    expect(screen.getByRole('status')).toBeTruthy();
  });

  it('renders nothing when only the signed-in user has the file open, or before any event', () => {
    const { container, rerender } = render(<PresenceList presence={{ fileId: 'f', viewers: [P.viewers[0]] }} selfId="me" />);
    expect(container.innerHTML).toBe('');
    rerender(<PresenceList selfId="me" />);
    expect(container.innerHTML).toBe('');
  });
});

function sse(chunks: string[]): Response {
  const enc = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      for (const ch of chunks) c.enqueue(enc.encode(ch));
      // left open: the stream stays live
    },
  });
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

function problem(status: number): Response {
  return new Response(JSON.stringify({ title: 'x', status }), { status, headers: { 'Content-Type': 'application/problem+json' } });
}

describe('M-101 ModelPresenceBar against the stream', () => {
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

  it('opens GET /models/by-file/presence/stream?file= with the bearer token and shows the other viewers', async () => {
    fetchMock.mockResolvedValueOnce(sse([`event: presence\ndata: ${JSON.stringify(P)}\n\n`])).mockImplementation(() => new Promise(() => undefined));
    render(<ModelPresenceBar file="pkg/1" selfId="me" />);
    await waitFor(() => expect(screen.getByText(/Asha/)).toBeTruthy());
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toBe('/api/v1/models/pkg%2F1/presence/stream');
    expect((init.headers as Headers).get('Authorization')).toBe('Bearer tok-1');
    expect((init.headers as Headers).get('Accept')).toBe('text/event-stream');
  });

  it('ignores events with another name', async () => {
    fetchMock.mockResolvedValueOnce(sse([`event: message\ndata: ${JSON.stringify(P)}\n\n`])).mockImplementation(() => new Promise(() => undefined));
    const { container } = render(<ModelPresenceBar file="pkg-1" selfId="me" />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 20));
    expect(container.innerHTML).toBe('');
  });

  it.each([404, 501])('degrades silently on %i: nothing shown, no alert, no retry', async (status) => {
    fetchMock.mockImplementation(async () => problem(status));
    const { container } = render(<ModelPresenceBar file="pkg-1" selfId="me" />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await new Promise((r) => setTimeout(r, 50));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(container.innerHTML).toBe('');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('degrades silently when the server is unreachable', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    const { container } = render(<ModelPresenceBar file="pkg-1" selfId="me" />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(container.innerHTML).toBe('');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('clears the list when the stream ends, so a stale list is never shown', async () => {
    const enc = new TextEncoder();
    let end: () => void = () => undefined;
    const body = new ReadableStream<Uint8Array>({
      start(c) {
        c.enqueue(enc.encode(`event: presence\ndata: ${JSON.stringify(P)}\n\n`));
        end = () => c.close();
      },
    });
    fetchMock.mockResolvedValueOnce(new Response(body, { status: 200 })).mockImplementation(() => new Promise(() => undefined));
    render(<ModelPresenceBar file="pkg-1" selfId="me" />);
    await waitFor(() => expect(screen.getByText(/Asha/)).toBeTruthy());
    end();
    await waitFor(() => expect(screen.queryByText(/Asha/)).toBeNull());
  });
});
