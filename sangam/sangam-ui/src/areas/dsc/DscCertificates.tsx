// SPDX-License-Identifier: Apache-2.0
/**
 * Owner decision 227 (W4-M contract sangam-rest/api/routes/dsc.py, tag DSC certificates): DSC certificate enrolment.
 * - MyDscCertificates (/my/dsc-certificates, task.write): a signer enrols the certificate of their own token, read
 *   through the loopback DSC agent (the signer certificate, first in the agent's chain) or from a certificate file
 *   (DER or PEM); lists their enrolments in every status; revokes their own PENDING or APPROVED enrolment with a reason.
 * - DscCertificateQueue (/admin/dsc-certificates, dsc.certificate.approve): the approval queue by status; approve
 *   (reason optional), reject and revoke (reason required, at most 512 characters). An admin never decides their own
 *   enrolment (403 SELF_APPROVAL).
 * The signer is bound by the certificate itself, never by the display name or the certificate CN. Only APPROVED binds a
 * signer at prepare and complete; a revocation voids signatures that are not completed yet: the task step is refused
 * at completion and must be signed again, while the recorded signature evidence stays (decision 241).
 * A server without the optional module answers 501 CAPABILITY_NOT_INSTALLED; the screens then say so.
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import { ApiError } from '../../api/client';
import type { DscEnrolment, DscEnrolmentStatus } from '../../api/types';
import { config } from '../../config';
import { fmtDate } from '../../i18n';
import { DscAgentError, dscAgent, type DscCertificate } from '../../lib/dscAgent';
import { announce, useAction, useAsync } from '../../lib/hooks';
import { Badge, DataTable, Dialog, Empty, Loading, Notice, PageHeader, ProblemAlert, Section, SelectField, TextArea, type Column } from '../../components/ui';

export const DSC_ENROLMENT_STATUSES: DscEnrolmentStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'REVOKED'];
/** DscCertificateDecision.reason maxLength (422 REASON_TOO_LONG above it). */
export const DSC_REASON_MAX = 512;
/** DscCertificateEnrolmentRequest: at most 16 KiB decoded. */
export const DSC_CERTIFICATE_MAX_BYTES = 16 * 1024;

type T = (k: string, o?: Record<string, unknown>) => string;

function toBase64(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

/**
 * The Base64 DER of a certificate file: DER as it is, or the first PEM CERTIFICATE block. undefined when the file is
 * neither, is empty or is larger than the contract allows; the server still parses and checks it (CERTIFICATE_INVALID).
 */
export function certificateFileToBase64(bytes: Uint8Array): string | undefined {
  if (bytes.length === 0) return undefined;
  let der: Uint8Array = bytes;
  if (bytes[0] !== 0x30) {
    const text = new TextDecoder('ascii').decode(bytes);
    const m = /-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\s]+)-----END CERTIFICATE-----/.exec(text);
    if (!m) return undefined;
    const b64 = m[1].replace(/\s+/g, '');
    if (!/^[A-Za-z0-9+/]+={0,2}$/.test(b64) || b64.length % 4 !== 0) return undefined;
    const bin = atob(b64);
    der = Uint8Array.from(bin, (c) => c.charCodeAt(0));
    if (der.length === 0 || der[0] !== 0x30) return undefined;
  }
  if (der.length > DSC_CERTIFICATE_MAX_BYTES) return undefined;
  return toBase64(der);
}

/** The reason check of the contract: required for reject and revoke, trimmed, at most 512 characters. */
export function reasonProblem(reason: string, required: boolean, t: T): string | undefined {
  const r = reason.trim();
  if (required && !r) return t('form.required');
  if (r.length > DSC_REASON_MAX) return t('form.maxLength', { n: DSC_REASON_MAX });
  return undefined;
}

/** The user's words for a refused enrolment call (contract problem codes). */
export function explainEnrolmentProblem(e: unknown, t: T): string | undefined {
  if (!(e instanceof ApiError)) return undefined;
  switch (e.problem.code) {
    case 'SELF_APPROVAL':
      return t('dsc.cert.selfApproval');
    case 'ENROLMENT_NOT_PENDING':
    case 'ENROLMENT_NOT_LIVE':
      return t('dsc.cert.alreadyDecided');
    case 'CERTIFICATE_ALREADY_ENROLLED':
      return t('dsc.cert.alreadyEnrolled');
    case 'CERTIFICATE_EXPIRED':
      return t('dsc.cert.expired');
    case 'CERTIFICATE_INVALID':
      return t('dsc.cert.invalid');
    case 'CERTIFICATE_KEY_UNSUPPORTED':
      return t('dsc.cert.keyUnsupported');
    case 'REASON_REQUIRED':
      return t('form.required');
    case 'REASON_TOO_LONG':
      return t('form.maxLength', { n: DSC_REASON_MAX });
    case 'ENROLMENT_NOT_FOUND':
      return t('dsc.cert.notFound');
    case 'CAPABILITY_NOT_INSTALLED':
      return t('dsc.task.notInstalled');
    default:
      return undefined;
  }
}

const notInstalled = (e: unknown): boolean => e instanceof ApiError && (e.status === 501 || e.problem.code === 'CAPABILITY_NOT_INSTALLED');

function Problem({ error, onRetry }: { error?: Error; onRetry?: () => void }) {
  const { t } = useTranslation();
  if (!error) return null;
  const said = explainEnrolmentProblem(error, t);
  return said ? <Notice tone="bad">{said}</Notice> : <ProblemAlert error={error} onRetry={onRetry} />;
}

function enrolmentColumns(t: T, extra: Column<DscEnrolment>[] = []): Column<DscEnrolment>[] {
  return [
    { key: 'subject', header: t('dsc.cert.subject'), render: (r) => r.subject },
    { key: 'issuer', header: t('dsc.cert.issuer'), render: (r) => `${r.issuer} (${r.serialHex})` },
    { key: 'valid', header: t('dsc.cert.valid'), render: (r) => `${fmtDate(r.notBefore, false)} - ${fmtDate(r.notAfter, false)}` },
    { key: 'sha', header: t('dsc.cert.fingerprint'), render: (r) => <code className="hash">{r.certificateSha256.slice(0, 16)}…</code> },
    { key: 'status', header: t('dsc.cert.status'), render: (r) => <Badge value={r.status} /> },
    {
      key: 'decided',
      header: t('dsc.cert.decision'),
      render: (r) => (r.decidedAt ? `${r.decidedBy ?? ''}, ${fmtDate(r.decidedAt)}${r.decisionReason ? `: ${r.decisionReason}` : ''}` : '—'),
    },
    ...extra,
  ];
}

/** The reason dialog of a revoke, reject or approve. */
function ReasonDialog({
  title,
  confirm,
  required,
  busy,
  error,
  onCancel,
  onConfirm,
}: {
  title: string;
  confirm: string;
  required: boolean;
  busy: boolean;
  error?: Error;
  onCancel: () => void;
  onConfirm: (reason: string) => void;
}) {
  const { t } = useTranslation();
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const problem = reasonProblem(reason, required, t);
  return (
    <Dialog
      open
      title={title}
      onClose={onCancel}
      footer={
        <>
          <button type="button" onClick={onCancel}>
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="primary"
            disabled={busy}
            onClick={() => {
              setTouched(true);
              if (!problem) onConfirm(reason.trim());
            }}
          >
            {confirm}
          </button>
        </>
      }
    >
      <Problem error={error} />
      <TextArea label={t('dsc.cert.reason')} required={required} value={reason} onChange={setReason} maxLength={DSC_REASON_MAX} error={touched ? problem : undefined} />
    </Dialog>
  );
}

/** Decision 227: the signer's own enrolments. */
export function MyDscCertificates({ agentUrl }: { agentUrl?: string }) {
  const { t } = useTranslation();
  const url = agentUrl ?? config().dsc.agentUrl;
  const list = useAsync(() => api.dsc.mine(), []);
  const act = useAction();
  const [onToken, setOnToken] = useState<DscCertificate[] | null>(null);
  const [agentProblem, setAgentProblem] = useState<string>();
  const [fileProblem, setFileProblem] = useState<string>();
  const [revoking, setRevoking] = useState<DscEnrolment | null>(null);

  const readToken = async () => {
    setAgentProblem(undefined);
    try {
      const agent = dscAgent(url);
      await agent.status();
      setOnToken(await agent.certificates());
    } catch (e) {
      const code = e instanceof DscAgentError ? e.code : '';
      setAgentProblem(code === 'NOT_RUNNING' ? t('dsc.error.notRunning') : code === 'ORIGIN_NOT_ALLOWED' ? t('dsc.error.originNotAllowed') : t('dsc.error.other', { code: code || String(e) }));
    }
  };

  const enrol = async (certificate: string) => {
    const done = await act.run((k) => api.dsc.enrol(certificate, k));
    if (done) {
      announce(t('dsc.cert.enrolled'));
      list.reload();
    }
  };

  if (notInstalled(list.error)) {
    return (
      <>
        <PageHeader title={t('dsc.cert.myTitle')} />
        <Notice tone="info">{t('dsc.task.notInstalled')}</Notice>
      </>
    );
  }

  const live = (r: DscEnrolment) => r.status === 'PENDING' || r.status === 'APPROVED';
  return (
    <>
      <PageHeader title={t('dsc.cert.myTitle')} description={t('dsc.cert.myDesc')} />
      <Notice tone="info">{t('dsc.cert.revokeNote')}</Notice>
      <Section title={t('dsc.cert.enrolTitle')}>
        <Problem error={revoking ? undefined : act.error} />
        {url ? (
          <div className="field">
            <button type="button" onClick={() => void readToken()}>
              {t('dsc.cert.readToken')}
            </button>
            {agentProblem && (
              <p className="field-error" role="alert">
                {agentProblem}
              </p>
            )}
            {onToken && onToken.length === 0 && <p className="muted">{t('dsc.none')}</p>}
            {onToken && onToken.length > 0 && (
              <ul>
                {onToken.map((c) => (
                  <li key={c.certificateSha256}>
                    {c.subject} <span className="muted small">{t('dsc.certDetail', { issuer: c.issuer, until: fmtDate(c.notAfter, false) })}</span>{' '}
                    <button type="button" disabled={act.busy || !c.certificateChain[0]} onClick={() => void enrol(c.certificateChain[0])}>
                      {t('dsc.cert.enrol')}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ) : (
          <p className="muted">{t('dsc.notConfigured')}</p>
        )}
        <div className="field">
          <label htmlFor="dsc-cert-file">{t('dsc.cert.fromFile')}</label>
          <input
            id="dsc-cert-file"
            type="file"
            accept=".cer,.crt,.der,.pem,application/pkix-cert,application/x-x509-ca-cert"
            onChange={(e) => {
              const file = e.target.files?.[0];
              e.target.value = '';
              if (!file) return;
              setFileProblem(undefined);
              void file.arrayBuffer().then((buf) => {
                const b64 = certificateFileToBase64(new Uint8Array(buf));
                if (!b64) setFileProblem(t('dsc.cert.badFile'));
                else void enrol(b64);
              });
            }}
          />
          {fileProblem && (
            <p className="field-error" role="alert">
              {fileProblem}
            </p>
          )}
        </div>
        <p className="hint">{t('dsc.cert.enrolHint')}</p>
      </Section>
      <Section title={t('dsc.cert.mine')}>
        <ProblemAlert error={list.error} onRetry={list.reload} />
        {list.loading && <Loading />}
        {list.data && list.data.length === 0 && <Empty message={t('dsc.cert.noneEnrolled')} />}
        {list.data && list.data.length > 0 && (
          <DataTable
            caption={t('dsc.cert.mine')}
            captionHidden
            rows={list.data}
            rowKey={(r) => r.enrolmentId}
            columns={enrolmentColumns(t, [
              {
                key: 'act',
                header: t('common.actions'),
                render: (r) =>
                  live(r) ? (
                    <button type="button" className="danger" onClick={() => setRevoking(r)}>
                      {t('dsc.cert.revoke')}
                    </button>
                  ) : null,
              },
            ])}
          />
        )}
      </Section>
      {revoking && (
        <ReasonDialog
          title={t('dsc.cert.revokeTitle', { subject: revoking.subject })}
          confirm={t('dsc.cert.revoke')}
          required
          busy={act.busy}
          error={act.error}
          onCancel={() => {
            setRevoking(null);
            act.reset();
          }}
          onConfirm={(reason) =>
            void act.run((k) => api.dsc.revokeMine(revoking.enrolmentId, reason, k)).then((done) => {
              if (!done) return;
              setRevoking(null);
              announce(t('dsc.cert.revoked'));
              list.reload();
            })
          }
        />
      )}
    </>
  );
}

type Verb = 'approve' | 'reject' | 'revoke';

/** Decision 227: the admin approval queue (dsc.certificate.approve). */
export function DscCertificateQueue() {
  const { t } = useTranslation();
  const [status, setStatus] = useState<DscEnrolmentStatus>('PENDING');
  const list = useAsync(() => api.dsc.list(status), [status]);
  const act = useAction();
  const [deciding, setDeciding] = useState<{ row: DscEnrolment; verb: Verb } | null>(null);

  if (notInstalled(list.error)) {
    return (
      <>
        <PageHeader title={t('dsc.cert.queueTitle')} />
        <Notice tone="info">{t('dsc.task.notInstalled')}</Notice>
      </>
    );
  }

  const verbs = (r: DscEnrolment): Verb[] => (r.status === 'PENDING' ? ['approve', 'reject', 'revoke'] : r.status === 'APPROVED' ? ['revoke'] : []);
  const verbLabel = (v: Verb) => (v === 'approve' ? t('dsc.cert.approve') : v === 'reject' ? t('dsc.cert.reject') : t('dsc.cert.revoke'));

  return (
    <>
      <PageHeader title={t('dsc.cert.queueTitle')} description={t('dsc.cert.queueDesc')} />
      <Notice tone="info">{t('dsc.cert.revokeNote')}</Notice>
      <Section title={t('dsc.cert.queueTitle')}>
        <SelectField
          label={t('dsc.cert.status')}
          value={status}
          onChange={(v) => setStatus(v as DscEnrolmentStatus)}
          options={DSC_ENROLMENT_STATUSES.map((s) => ({ value: s, label: t(`enum.${s}`, { defaultValue: s }) }))}
        />
        <ProblemAlert error={list.error} onRetry={list.reload} />
        {list.loading && <Loading />}
        {list.data && list.data.length === 0 && <Empty message={t('dsc.cert.queueEmpty')} />}
        {list.data && list.data.length > 0 && (
          <DataTable
            caption={t('dsc.cert.queueTitle')}
            captionHidden
            rows={list.data}
            rowKey={(r) => r.enrolmentId}
            columns={[
              { key: 'principal', header: t('dsc.cert.principal'), render: (r) => r.principalId },
              { key: 'enrolled', header: t('dsc.cert.enrolledAt'), render: (r) => fmtDate(r.enrolledAt) },
              ...enrolmentColumns(t, [
                {
                  key: 'act',
                  header: t('common.actions'),
                  render: (r) => (
                    <span className="toolbar">
                      {verbs(r).map((v) => (
                        <button key={v} type="button" className={v === 'approve' ? 'primary' : 'danger'} onClick={() => setDeciding({ row: r, verb: v })}>
                          {verbLabel(v)}
                        </button>
                      ))}
                    </span>
                  ),
                },
              ]),
            ]}
          />
        )}
      </Section>
      {deciding && (
        <ReasonDialog
          title={t('dsc.cert.decideTitle', { action: verbLabel(deciding.verb), subject: deciding.row.subject, principal: deciding.row.principalId })}
          confirm={verbLabel(deciding.verb)}
          required={deciding.verb !== 'approve'}
          busy={act.busy}
          error={act.error}
          onCancel={() => {
            setDeciding(null);
            act.reset();
          }}
          onConfirm={(reason) =>
            void act.run((k) => api.dsc.decide(deciding.row.enrolmentId, deciding.verb, reason, k)).then((done) => {
              if (!done) return;
              setDeciding(null);
              announce(t('dsc.cert.decided'));
              list.reload();
            })
          }
        />
      )}
    </>
  );
}
