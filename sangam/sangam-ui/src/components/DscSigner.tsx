// SPDX-License-Identifier: Apache-2.0
/**
 * Decision 165: signing a document with the signer's Digital Signature Certificate (USB token) through the DSC loopback
 * agent on the signer's computer. Steps: find the certificates on the token, choose one, the server prepares what is
 * to be signed for that certificate (prepare), the agent signs it after the signer approves in a local dialog and
 * enters the PIN there, and the result goes back to the server (onSigned), which verifies it under decision 140.
 * This screen never asks for the PIN. Without a configured agent it says so and does nothing else.
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { config } from '../config';
import { DscAgentError, dscAgent, type DscCertificate, type DscSignature, type DscToSign } from '../lib/dscAgent';
import { fmtDate } from '../i18n';
import { Notice } from './ui';

export interface DscSignerProps {
  documentName: string;
  /** Server step: prepares the signed attributes for the chosen certificate. */
  prepare: (certificate: DscCertificate) => Promise<DscToSign>;
  /** Server step: receives the signature for assembly and verification. */
  onSigned: (result: DscSignature & { certificateSha256: string }) => Promise<void> | void;
  /** The agent origin; defaults to config.json dsc.agentUrl. */
  agentUrl?: string;
  /**
   * Explains an error of the server steps (prepare, onSigned) in the user's words, e.g. by the contract's problem code.
   * Without it the server's own message is shown.
   */
  explainServer?: (e: unknown) => string;
}

type Phase = 'idle' | 'looking' | 'choose' | 'signing' | 'done';

export function DscSigner({ documentName, prepare, onSigned, agentUrl, explainServer }: DscSignerProps) {
  const { t } = useTranslation();
  const url = agentUrl ?? config().dsc.agentUrl;
  const [phase, setPhase] = useState<Phase>('idle');
  const [certs, setCerts] = useState<DscCertificate[]>([]);
  const [chosen, setChosen] = useState('');
  const [problem, setProblem] = useState<string | null>(null);

  if (!url) return <Notice tone="info">{t('dsc.notConfigured')}</Notice>;

  const serverProblem = (e: unknown): string =>
    explainServer ? explainServer(e) : t('dsc.error.server', { message: e instanceof Error ? e.message : String(e) });

  const explain = (e: unknown): string => {
    const code = e instanceof DscAgentError ? e.code : 'SERVER';
    switch (code) {
      case 'NOT_RUNNING':
        return t('dsc.error.notRunning');
      case 'ORIGIN_NOT_ALLOWED':
        return t('dsc.error.originNotAllowed');
      case 'DECLINED':
        return t('dsc.error.declined');
      case 'TOKEN_UNAVAILABLE':
        return t('dsc.error.tokenUnavailable');
      case 'CERTIFICATE_NOT_ON_TOKEN':
        return t('dsc.error.certificateGone');
      case 'TOKEN_SIGNING_FAILED':
        return t('dsc.error.signingFailed');
      case 'AGENT_UNSUPPORTED':
        return t('dsc.error.unsupported');
      case 'SERVER':
        return serverProblem(e);
      default:
        return t('dsc.error.other', { code });
    }
  };

  const find = async () => {
    setProblem(null);
    setPhase('looking');
    try {
      const agent = dscAgent(url);
      await agent.status();
      const list = await agent.certificates();
      setCerts(list);
      setChosen(list.length === 1 ? list[0].certificateSha256 : '');
      setPhase('choose');
    } catch (e) {
      setProblem(explain(e));
      setPhase('idle');
    }
  };

  const sign = async () => {
    const cert = certs.find((c) => c.certificateSha256 === chosen);
    if (!cert) return;
    setProblem(null);
    setPhase('signing');
    let toSign: DscToSign;
    try {
      toSign = await prepare(cert);
    } catch (e) {
      setProblem(serverProblem(e));
      setPhase('choose');
      return;
    }
    try {
      const signature = await dscAgent(url).sign(cert.certificateSha256, toSign, documentName);
      await onSigned({ ...signature, certificateSha256: cert.certificateSha256 });
      setPhase('done');
    } catch (e) {
      setProblem(explain(e));
      setPhase('choose');
    }
  };

  return (
    <section className="dsc-signer" aria-labelledby="dsc-title">
      <h3 id="dsc-title">{t('dsc.title')}</h3>
      <p className="hint">{t('dsc.hint')}</p>
      {problem && (
        <p className="field-error" role="alert">
          {problem}
        </p>
      )}
      {phase === 'done' ? (
        <p role="status">{t('dsc.signed')}</p>
      ) : (
        <>
          <button type="button" className="btn" onClick={() => void find()} disabled={phase === 'looking' || phase === 'signing'}>
            {phase === 'idle' || phase === 'looking' ? t('dsc.find') : t('dsc.findAgain')}
          </button>
          {phase !== 'idle' && phase !== 'looking' && (
            <fieldset className="field">
              <legend>{t('dsc.choose')}</legend>
              {certs.length === 0 && <p className="muted">{t('dsc.none')}</p>}
              {certs.map((c) => (
                <label key={c.certificateSha256} className="radio">
                  <input type="radio" name="dsc-cert" value={c.certificateSha256} checked={chosen === c.certificateSha256} onChange={() => setChosen(c.certificateSha256)} />
                  <span>
                    {c.subject} <span className="muted small">{t('dsc.certDetail', { issuer: c.issuer, until: fmtDate(c.notAfter, false) })}</span>
                  </span>
                </label>
              ))}
            </fieldset>
          )}
          {phase !== 'idle' && phase !== 'looking' && (
            <button type="button" className="btn primary" onClick={() => void sign()} disabled={!chosen || phase === 'signing'}>
              {phase === 'signing' ? t('dsc.signing') : t('dsc.sign', { name: documentName })}
            </button>
          )}
        </>
      )}
    </section>
  );
}
