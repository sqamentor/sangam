// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * M-044: trace links from a recorded trace id to the configured trace viewer (/config.json traceUrlTemplate).
 * Written, not run.
 */
import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { initI18n, tr } from '../i18n';
import { normalise } from '../config';
import { ApiError } from '../api/client';
import { ProblemAlert, TraceRef } from '../components/ui';
import { isTraceId, traceIdOf, traceUrl } from './trace';
import { blockingA11yIssues } from '../test/axe';

const ID = '4bf92f3577b34da6a3ce929d0e0e4736';
const T = tr;

beforeAll(() => initI18n('en'));
afterEach(cleanup);

describe('M-044 trace ids and URLs', () => {
  it('accepts only a W3C trace id', () => {
    expect(isTraceId(ID)).toBe(true);
    expect(isTraceId('0'.repeat(32))).toBe(false);
    expect(isTraceId(ID.toUpperCase())).toBe(false);
    expect(isTraceId(ID.slice(1))).toBe(false);
    expect(isTraceId(undefined)).toBe(false);
  });

  it('reads the trace id of a traceparent header', () => {
    expect(traceIdOf(`00-${ID}-00f067aa0ba902b7-01`)).toBe(ID);
    expect(traceIdOf('garbage')).toBeUndefined();
  });

  it('fills the template, and gives no link without a template, a placeholder, an id or an http(s) result', () => {
    expect(traceUrl(ID, 'https://traces.example/trace/{traceId}?x=1')).toBe(`https://traces.example/trace/${ID}?x=1`);
    expect(traceUrl(ID.toUpperCase(), 'https://t.example/{traceId}')).toBe(`https://t.example/${ID}`);
    expect(traceUrl(ID, '')).toBeUndefined();
    expect(traceUrl(ID, 'https://t.example/search')).toBeUndefined();
    expect(traceUrl('not-a-trace', 'https://t.example/{traceId}')).toBeUndefined();
    expect(traceUrl(ID, 'javascript:alert(1)//{traceId}')).toBeUndefined();
    expect(traceUrl(ID, '/relative/{traceId}')).toBeUndefined();
  });

  it('the runtime config reads traceUrlTemplate, trimmed, and defaults to none', () => {
    expect(normalise({ traceUrlTemplate: ' https://t.example/{traceId} ' }).traceUrlTemplate).toBe('https://t.example/{traceId}');
    expect(normalise({}).traceUrlTemplate).toBe('');
  });
});

describe('M-044 TraceRef and the problem reference', () => {
  it('without a configured viewer the id is plain text', () => {
    render(<TraceRef id={ID} />);
    expect(screen.getByText(ID).closest('a')).toBeNull();
  });

  it('a problem shows its trace id as the reference', () => {
    render(<ProblemAlert error={new ApiError({ status: 500, title: 'x', traceId: ID })} />);
    expect(screen.getByRole('alert').textContent).toContain(ID);
    expect(screen.getByRole('alert').textContent).toContain(T('error.reference'));
  });

  it('with a configured viewer the id links to it in a new tab, safely and accessibly', async () => {
    const cfg = await import('../config');
    // loadConfig is the only setter: feed it the runtime config through fetch
    const fetchBefore = globalThis.fetch;
    globalThis.fetch = (async () => new Response(JSON.stringify({ traceUrlTemplate: 'https://traces.example/trace/{traceId}' }), { status: 200 })) as typeof fetch;
    try {
      await cfg.loadConfig();
      const { container } = render(<TraceRef id={ID} />);
      const a = screen.getByRole('link', { name: T('common.openTrace', { id: ID }) });
      expect(a.getAttribute('href')).toBe(`https://traces.example/trace/${ID}`);
      expect(a.getAttribute('target')).toBe('_blank');
      expect(a.getAttribute('rel')).toBe('noopener noreferrer');
      expect(await blockingA11yIssues(container)).toEqual([]);
    } finally {
      // back to the defaults (no viewer) for any later test
      globalThis.fetch = (async () => new Response('', { status: 404 })) as typeof fetch;
      await cfg.loadConfig();
      globalThis.fetch = fetchBefore;
    }
  });
});
