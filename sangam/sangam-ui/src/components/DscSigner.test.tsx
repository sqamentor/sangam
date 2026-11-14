// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
// Decision 165: the DSC signing screen. The agent is replaced by a fake fetch. Written, not run in this phase.
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { initI18n } from '../i18n';
import { DscSigner } from './DscSigner';

const HEX = 'c'.repeat(64);
const TO_SIGN = { signedAttributes: 'MQA=', preparedDigestSha256: 'd'.repeat(64), algorithm: 'SHA256withECDSA' as const };

function agent(sign: () => Response) {
  return vi.fn(async (url: string) => {
    if (url.endsWith('/v1/status')) return new Response(JSON.stringify({ agent: 'sangam-dsc', api: 1, algorithms: ['SHA256withECDSA'] }), { status: 200 });
    if (url.endsWith('/v1/certificates')) return new Response(JSON.stringify({ certificates: [{ certificateSha256: HEX, subject: 'CN=Officer', issuer: 'CN=CA', notAfter: '2027-01-01T00:00:00Z', certificateChain: ['MII='] }] }), { status: 200 });
    return sign();
  });
}

beforeAll(() => initI18n('en'));
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('DscSigner', () => {
  it('says so and calls nothing when no agent is configured', () => {
    const f = vi.fn();
    vi.stubGlobal('fetch', f);
    render(<DscSigner documentName="a.pdf" agentUrl="" prepare={vi.fn()} onSigned={vi.fn()} />);
    expect(screen.getByText(/not set up/)).toBeTruthy();
    expect(f).not.toHaveBeenCalled();
  });

  it('finds the certificate, lets the server prepare, signs through the agent and hands the result back', async () => {
    vi.stubGlobal('fetch', agent(() => new Response(JSON.stringify({ signature: 'c2ln', algorithm: 'SHA256withECDSA', certificateChain: ['MII='] }), { status: 200 })));
    const prepare = vi.fn(async () => TO_SIGN);
    const onSigned = vi.fn();
    render(<DscSigner documentName="Order.pdf" agentUrl="http://127.0.0.1:47321" prepare={prepare} onSigned={onSigned} />);
    expect(screen.queryByLabelText(/PIN/i)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Find my signing certificates' }));
    await waitFor(() => expect(screen.getByRole('radio')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Sign Order.pdf' }));
    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('Signed'));
    expect((prepare.mock.calls[0] as unknown[])[0]).toMatchObject({ certificateSha256: HEX });
    expect(onSigned).toHaveBeenCalledWith({ signature: 'c2ln', algorithm: 'SHA256withECDSA', certificateChain: ['MII='], certificateSha256: HEX });
  });

  it('explains a declined signature and does not report success', async () => {
    vi.stubGlobal('fetch', agent(() => new Response(JSON.stringify({ error: 'DECLINED', message: 'no' }), { status: 403 })));
    const onSigned = vi.fn();
    render(<DscSigner documentName="Order.pdf" agentUrl="http://127.0.0.1:47321" prepare={async () => TO_SIGN} onSigned={onSigned} />);
    fireEvent.click(screen.getByRole('button', { name: 'Find my signing certificates' }));
    await waitFor(() => expect(screen.getByRole('radio')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Sign Order.pdf' }));
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('You declined'));
    expect(onSigned).not.toHaveBeenCalled();
  });

  it('shows the explained server problem when prepare is refused, and the agent is never asked to sign', async () => {
    const f = agent(() => new Response(JSON.stringify({ signature: 'c2ln', algorithm: 'SHA256withECDSA', certificateChain: ['MII='] }), { status: 200 }));
    vi.stubGlobal('fetch', f);
    const onSigned = vi.fn();
    const refused = new Error('CERTIFICATE_NOT_ENROLLED');
    render(
      <DscSigner
        documentName="Order.pdf"
        agentUrl="http://127.0.0.1:47321"
        prepare={async () => {
          throw refused;
        }}
        onSigned={onSigned}
        explainServer={(e) => (e === refused ? 'Enrol this certificate first.' : 'other')}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Find my signing certificates' }));
    await waitFor(() => expect(screen.getByRole('radio')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Sign Order.pdf' }));
    await waitFor(() => expect(screen.getByRole('alert').textContent).toBe('Enrol this certificate first.'));
    expect(f.mock.calls.some(([u]) => String(u).endsWith('/v1/sign'))).toBe(false);
    expect(onSigned).not.toHaveBeenCalled();
  });

  it('says when the agent is not running', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    }));
    render(<DscSigner documentName="a.pdf" agentUrl="http://127.0.0.1:47321" prepare={vi.fn()} onSigned={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Find my signing certificates' }));
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('not running'));
  });
});
