// SPDX-License-Identifier: Apache-2.0
// Decision 165: the DSC loopback agent client calls only a loopback origin, never sends credentials, and checks a sign
// request the way the agent does. Written, not run in this phase.
import { describe, expect, it, vi } from 'vitest';
import { loopbackOrigin, normalise } from '../config';
import { DscAgentError, checkSignRequest, dscAgent, type DscToSign } from './dscAgent';

const HEX = 'a'.repeat(64);
const TO_SIGN: DscToSign = { signedAttributes: 'MQA=', preparedDigestSha256: 'B'.repeat(64), algorithm: 'SHA256withRSA' };

function answer(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('loopback origin', () => {
  it('accepts only an http loopback origin with a port', () => {
    expect(loopbackOrigin('http://127.0.0.1:47321')).toBe('http://127.0.0.1:47321');
    expect(loopbackOrigin('http://LOCALHOST:47321/')).toBe('http://localhost:47321');
    for (const bad of ['https://127.0.0.1:1', 'http://127.0.0.1', 'http://10.0.0.1:80', 'http://127.0.0.1.evil.example:80', 'http://127.0.0.1:0', 'http://127.0.0.1:70000', 'http://127.0.0.1:1/v1', 42]) {
      expect(loopbackOrigin(bad)).toBe('');
    }
    expect(normalise({}).dsc.agentUrl).toBe('');
    expect(normalise({ dsc: { agentUrl: 'http://example.org:80' } }).dsc.agentUrl).toBe('');
    expect(normalise({ dsc: { agentUrl: 'http://127.0.0.1:47321' } }).dsc.agentUrl).toBe('http://127.0.0.1:47321');
  });

  it('accepts only 127.0.0.1 and localhost: the IPv6 loopback [::1] is refused (coordinator ruling)', () => {
    for (const v6 of ['http://[::1]:8080', 'http://[::1]:47321/', 'http://[0:0:0:0:0:0:0:1]:47321', 'http://[::ffff:127.0.0.1]:47321']) {
      expect(loopbackOrigin(v6)).toBe('');
    }
    expect(normalise({ dsc: { agentUrl: 'http://[::1]:47321' } }).dsc.agentUrl).toBe('');
    expect(() => dscAgent('http://[::1]:47321')).toThrow(DscAgentError);
    expect(loopbackOrigin('http://localhost:47321')).toBe('http://localhost:47321');
  });

  it('refuses to build a client for anything else', () => {
    expect(() => dscAgent('https://sangam.example.org')).toThrow(DscAgentError);
  });
});

describe('dscAgent', () => {
  it('reads status and certificates without cookies or an Authorization header', async () => {
    const fetchImpl = vi.fn(async (url: string) =>
      url.endsWith('/v1/status')
        ? answer(200, { agent: 'sangam-dsc', api: 1, algorithms: ['SHA256withRSA'] })
        : answer(200, { certificates: [{ certificateSha256: HEX, subject: 'CN=A', issuer: 'CN=CA', notAfter: '2027-01-01T00:00:00Z', certificateChain: ['MII='] }] }),
    );
    const agent = dscAgent('http://127.0.0.1:47321', fetchImpl as unknown as typeof fetch);
    expect((await agent.status()).algorithms).toEqual(['SHA256withRSA']);
    expect((await agent.certificates())[0].subject).toBe('CN=A');
    for (const [url, init] of fetchImpl.mock.calls as unknown as [string, RequestInit][]) {
      expect(url.startsWith('http://127.0.0.1:47321/v1/')).toBe(true);
      expect(init.credentials).toBe('omit');
      expect(JSON.stringify(init.headers)).not.toMatch(/authorization/i);
    }
  });

  it('refuses an agent that is not the Sangam DSC agent version 1', async () => {
    const agent = dscAgent('http://127.0.0.1:1', (async () => answer(200, { agent: 'other', api: 1 })) as unknown as typeof fetch);
    await expect(agent.status()).rejects.toMatchObject({ code: 'AGENT_UNSUPPORTED' });
  });

  it('posts the sign request as JSON with lower-case hex and no PIN', async () => {
    const fetchImpl = vi.fn(async () => answer(200, { signature: 'c2ln', algorithm: 'SHA256withRSA', certificateChain: ['MII='] }));
    const out = await dscAgent('http://127.0.0.1:47321', fetchImpl as unknown as typeof fetch).sign(HEX.toUpperCase(), TO_SIGN, ' Order 12.pdf ');
    expect(out).toEqual({ signature: 'c2ln', algorithm: 'SHA256withRSA', certificateChain: ['MII='] });
    const [url, init] = (fetchImpl.mock.calls as unknown as [string, RequestInit][])[0];
    expect(url).toBe('http://127.0.0.1:47321/v1/sign');
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    const body = JSON.parse(String(init.body));
    expect(body).toEqual({ certificateSha256: HEX, algorithm: 'SHA256withRSA', signedAttributes: 'MQA=', preparedDigestSha256: 'b'.repeat(64), documentName: 'Order 12.pdf' });
    expect(Object.keys(body)).not.toContain('pin');
  });

  it('never sends a malformed request', async () => {
    const fetchImpl = vi.fn();
    const agent = dscAgent('http://127.0.0.1:47321', fetchImpl as unknown as typeof fetch);
    await expect(agent.sign('xyz', TO_SIGN, 'a.pdf')).rejects.toMatchObject({ code: 'INVALID_REQUEST', message: 'certificateSha256' });
    expect(checkSignRequest(HEX, { ...TO_SIGN, algorithm: 'MD5withRSA' as never }, 'a.pdf')).toBe('algorithm');
    expect(checkSignRequest(HEX, { ...TO_SIGN, signedAttributes: 'not base64!' }, 'a.pdf')).toBe('signedAttributes');
    expect(checkSignRequest(HEX, TO_SIGN, 'x'.repeat(201))).toBe('documentName');
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('passes the agent error code on, and NOT_RUNNING when it cannot be reached', async () => {
    const declined = dscAgent('http://127.0.0.1:1', (async () => answer(403, { error: 'DECLINED', message: 'the user declined to sign' })) as unknown as typeof fetch);
    await expect(declined.sign(HEX, TO_SIGN, 'a.pdf')).rejects.toMatchObject({ code: 'DECLINED', status: 403 });
    const down = dscAgent('http://127.0.0.1:1', (async () => {
      throw new TypeError('Failed to fetch');
    }) as unknown as typeof fetch);
    await expect(down.status()).rejects.toMatchObject({ code: 'NOT_RUNNING' });
  });
});
