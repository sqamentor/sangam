// SPDX-License-Identifier: Apache-2.0
/**
 * Decision 165 (W4-H REQUEST 2026-11-12T20:15:17Z): the client for the DSC loopback agent that runs on the signer's own
 * computer (sangam-integration org.sangam.integration.dsc.DscLoopbackAgent). API version 1:
 * - GET  /v1/status       -> {agent: "sangam-dsc", api: 1, algorithms: [...]}
 * - GET  /v1/certificates -> {certificates: [{certificateSha256, subject, issuer, notAfter, certificateChain}]}
 * - POST /v1/sign (Content-Type application/json) {certificateSha256, algorithm, signedAttributes (Base64 DER),
 *   preparedDigestSha256 (hex), documentName} -> {signature (Base64), algorithm, certificateChain (Base64 DER, signer first)}
 * - errors: {error: CODE, message}.
 *
 * Rules kept here: only a loopback origin is ever called (config.loopbackOrigin); no cookie and no bearer token go to
 * the agent (credentials 'omit', no Authorization header); the UI never asks for or sends a PIN, the token asks for it
 * locally; inputs are checked before they leave so a malformed request is never sent.
 */
import { loopbackOrigin } from '../config';

export const DSC_ALGORITHMS = ['SHA256withRSA', 'SHA256withECDSA'] as const;
export type DscAlgorithm = (typeof DSC_ALGORITHMS)[number];

export interface DscCertificate {
  certificateSha256: string;
  subject: string;
  issuer: string;
  notAfter: string;
  certificateChain: string[];
}

/** What the server prepared for one signature (PadesSigning.prepare then DscCms.toSign). */
export interface DscToSign {
  signedAttributes: string;
  preparedDigestSha256: string;
  algorithm: DscAlgorithm;
}

export interface DscSignature {
  signature: string;
  algorithm: DscAlgorithm;
  certificateChain: string[];
}

/** NOT_RUNNING when the agent cannot be reached; otherwise the agent's own error code. */
export class DscAgentError extends Error {
  constructor(
    readonly code: string,
    readonly status: number,
    message?: string,
  ) {
    super(message ?? code);
  }
}

const HEX64 = /^[0-9a-f]{64}$/;
const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;

/** Checks a sign request the same way the agent does, so nothing malformed is sent. */
export function checkSignRequest(cert: string, toSign: DscToSign, documentName: string): string | undefined {
  if (!HEX64.test(cert.toLowerCase())) return 'certificateSha256';
  if (!HEX64.test(toSign.preparedDigestSha256.toLowerCase())) return 'preparedDigestSha256';
  if (!BASE64.test(toSign.signedAttributes) || toSign.signedAttributes.length % 4 !== 0) return 'signedAttributes';
  if (!(DSC_ALGORITHMS as readonly string[]).includes(toSign.algorithm)) return 'algorithm';
  const name = documentName.trim();
  if (!name || name.length > 200) return 'documentName';
  return undefined;
}

export interface DscAgent {
  readonly origin: string;
  status(): Promise<{ algorithms: string[] }>;
  certificates(): Promise<DscCertificate[]>;
  sign(certificateSha256: string, toSign: DscToSign, documentName: string): Promise<DscSignature>;
}

export function dscAgent(agentUrl: string, fetchImpl: typeof fetch = (...a) => fetch(...a)): DscAgent {
  const origin = loopbackOrigin(agentUrl);
  if (!origin) throw new DscAgentError('NOT_CONFIGURED', 0, 'the DSC agent address is not a loopback origin');

  const call = async (path: string, init?: { method: 'POST'; body: string }) => {
    let res: Response;
    try {
      res = await fetchImpl(`${origin}${path}`, {
        method: init?.method ?? 'GET',
        mode: 'cors',
        credentials: 'omit',
        cache: 'no-store',
        referrerPolicy: 'no-referrer',
        headers: init ? { 'Content-Type': 'application/json', Accept: 'application/json' } : { Accept: 'application/json' },
        body: init?.body,
      });
    } catch {
      throw new DscAgentError('NOT_RUNNING', 0);
    }
    let body: unknown;
    try {
      body = await res.json();
    } catch {
      body = undefined;
    }
    if (!res.ok) {
      const b = body as { error?: unknown; message?: unknown } | undefined;
      throw new DscAgentError(typeof b?.error === 'string' ? b.error : 'AGENT_ERROR', res.status, typeof b?.message === 'string' ? b.message : undefined);
    }
    if (!body || typeof body !== 'object') throw new DscAgentError('AGENT_ERROR', res.status, 'not a JSON object');
    return body as Record<string, unknown>;
  };

  return {
    origin,
    async status() {
      const b = await call('/v1/status');
      if (b.agent !== 'sangam-dsc' || b.api !== 1) throw new DscAgentError('AGENT_UNSUPPORTED', 200);
      return { algorithms: Array.isArray(b.algorithms) ? b.algorithms.filter((x): x is string => typeof x === 'string') : [] };
    },
    async certificates() {
      const b = await call('/v1/certificates');
      const list = Array.isArray(b.certificates) ? b.certificates : [];
      return list
        .filter((c): c is DscCertificate => !!c && typeof c === 'object' && typeof (c as DscCertificate).certificateSha256 === 'string' && typeof (c as DscCertificate).subject === 'string')
        .map((c) => ({ ...c, certificateChain: Array.isArray(c.certificateChain) ? c.certificateChain : [] }));
    },
    async sign(certificateSha256, toSign, documentName) {
      const bad = checkSignRequest(certificateSha256, toSign, documentName);
      if (bad) throw new DscAgentError('INVALID_REQUEST', 0, bad);
      const b = await call('/v1/sign', {
        method: 'POST',
        body: JSON.stringify({
          certificateSha256: certificateSha256.toLowerCase(),
          algorithm: toSign.algorithm,
          signedAttributes: toSign.signedAttributes,
          preparedDigestSha256: toSign.preparedDigestSha256.toLowerCase(),
          documentName: documentName.trim(),
        }),
      });
      if (typeof b.signature !== 'string' || b.algorithm !== toSign.algorithm || !Array.isArray(b.certificateChain)) {
        throw new DscAgentError('AGENT_ERROR', 200, 'unexpected sign answer');
      }
      return { signature: b.signature, algorithm: toSign.algorithm, certificateChain: b.certificateChain.filter((x): x is string => typeof x === 'string') };
    },
  };
}
