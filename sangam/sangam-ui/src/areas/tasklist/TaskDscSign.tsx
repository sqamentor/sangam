// SPDX-License-Identifier: Apache-2.0
/**
 * Decisions 165, 227, 228, 236-238, 241 (W4-M contract sangam-rest/api/routes/dsc.py): the task's assignee signs a
 * PDF attachment of the task with their DSC token. DscSigner finds the certificate through the loopback agent; this
 * component supplies the two server steps:
 * - prepare: POST /tasks/{id}/dsc/prepare {documentId, certificateChain}. The server binds the signer by the chain's
 *   signer certificate to the caller's APPROVED enrolment (decision 227; the display name and CN are never used) and
 *   records that approval with the prepared signature (approvalSeq, server side only);
 * - complete: POST /tasks/{id}/dsc/complete {prepareId, signature, algorithm, certificateChain}. The answer is the
 *   verification outcome; only VERIFIED_VALID stores the signed PDF, by a compare-and-set on the prepared version
 *   (decision 228, 409 DOCUMENT_CHANGED otherwise).
 * The signer is the officer who completes the task (decision 237) and there is no second dsc.sign for the step
 * (decision 238): the task is completed with the ordinary Complete action afterwards, which also re-checks that the
 * enrolment is still approved (decision 241; 422 DSC_ENROLMENT_REVOKED, see CompletionProblem). The PDF is preselected
 * from TaskDetail.dscDocument (the step's slot and the document it held at task creation, decision 236); without it
 * the officer picks the PDF, and either way the server refuses a wrong one.
 * Shown only when the DSC agent is configured (config.json dsc.agentUrl); a server without the optional module answers
 * 501 CAPABILITY_NOT_INSTALLED and the panel then says so instead of offering signing.
 */
import { useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import { ApiError } from '../../api/client';
import type { Attachment, DscCompleteResponse, DscPrepareResponse, TaskDscDocument } from '../../api/types';
import { config } from '../../config';
import { DscSigner } from '../../components/DscSigner';
import { Notice, ProblemAlert, SelectField } from '../../components/ui';
import type { DscCertificate, DscSignature, DscToSign } from '../../lib/dscAgent';
import { announce } from '../../lib/hooks';

/** A verification outcome other than VERIFIED_VALID, raised so the signer screen offers signing again. */
export class DscOutcomeError extends Error {
  constructor(readonly result: DscCompleteResponse) {
    super(result.reasonCode ?? result.outcome);
  }
}

/** A PDF attachment (the only kind the server prepares; DOCUMENT_NOT_PDF otherwise). */
export function isPdf(a: Pick<Attachment, 'name' | 'contentType'>): boolean {
  return (a.contentType ?? '').toLowerCase().split(';')[0].trim() === 'application/pdf' || /\.pdf$/i.test(a.name ?? '');
}

/** The user's words for a refused DSC step (contract problem codes of prepare and complete) or a failed outcome. */
export function explainDscProblem(e: unknown, t: (k: string, o?: Record<string, unknown>) => string): string {
  if (e instanceof DscOutcomeError) {
    const r = e.result;
    if (r.reasonCode === 'CERTIFICATE_NOT_ENROLLED') return t('dsc.task.revokedMeanwhile');
    if (r.reasonCode === 'SIGNER_MISMATCH' || r.reasonCode === 'DSC_SIGNER_CHANGED') return t('dsc.task.signerMismatch');
    if (r.outcome === 'INDETERMINATE' && r.retryable) return t('dsc.task.indeterminateRetry', { reason: r.reasonCode ?? '-' });
    if (r.outcome === 'INDETERMINATE') return t('dsc.task.indeterminate', { reason: r.reasonCode ?? '-' });
    return t('dsc.task.invalid', { reason: r.reasonCode ?? '-' });
  }
  if (e instanceof ApiError) {
    switch (e.problem.code) {
      case 'NOT_ASSIGNEE':
        return t('dsc.task.notAssignee');
      case 'TASK_SUSPENDED':
        return t('dsc.task.suspended');
      case 'DOCUMENT_NOT_CLEAN':
        return t('dsc.task.notClean');
      case 'DOCUMENT_CHANGED':
        return t('dsc.task.documentChanged');
      case 'DSC_PREPARE_NOT_USABLE':
        return t('dsc.task.prepareNotUsable');
      case 'CERTIFICATE_NOT_ENROLLED':
        return t('dsc.task.notEnrolled');
      case 'CERTIFICATE_CHAIN_INVALID':
      case 'INVALID_INPUT':
        return t('dsc.task.chainInvalid');
      case 'DOCUMENT_NOT_OF_TASK':
      case 'DOCUMENT_NOT_PDF':
      case 'DOCUMENT_EMPTY':
      case 'PDF_INVALID':
        return t('dsc.task.notTaskPdf');
      case 'ALREADY_SIGNED':
        return t('dsc.task.alreadySigned');
      case 'DOCUMENT_UNREADABLE':
        return t('dsc.task.unreadable');
      case 'CAPABILITY_NOT_INSTALLED':
        return t('dsc.task.notInstalled');
      default:
        break;
    }
  }
  return t('dsc.error.server', { message: e instanceof Error ? e.message : String(e) });
}

/**
 * The problem of a task completion: 422 DSC_ENROLMENT_REVOKED (decision 241: the person signed, but the enrolment the
 * signature was bound to was revoked afterwards) gets its own words; everything else, DSC_SIGNATURE_NOT_VERIFIED
 * included, keeps the generic problem display.
 */
export function CompletionProblem({ error }: { error?: Error | null }) {
  const { t } = useTranslation();
  if (error instanceof ApiError && error.problem.code === 'DSC_ENROLMENT_REVOKED') {
    return (
      <div className="alert alert-bad" role="alert">
        <strong>{t('dsc.task.enrolmentRevokedTitle')}</strong>
        <p>{t('dsc.task.enrolmentRevoked')}</p>
      </div>
    );
  }
  return <ProblemAlert error={error} />;
}

/** The PDF to preselect: the step's document (dscDocument.documentId) when it is among the task's PDFs. */
export function preselectedPdf(pdfs: Attachment[], dsc?: TaskDscDocument | null): Attachment | undefined {
  const id = dsc?.documentId;
  return id ? pdfs.find((d) => d.id === id) : undefined;
}

export function TaskDscSign({
  taskId,
  documents,
  onSigned,
  agentUrl,
  dscDocument,
}: {
  taskId: string;
  documents: Attachment[];
  onSigned?: () => void;
  agentUrl?: string;
  dscDocument?: TaskDscDocument | null;
}) {
  const { t } = useTranslation();
  const url = agentUrl ?? config().dsc.agentUrl;
  const pdfs = useMemo(() => documents.filter(isPdf), [documents]);
  const [docId, setDocId] = useState('');
  const [notInstalled, setNotInstalled] = useState(false);
  const [result, setResult] = useState<DscCompleteResponse | null>(null);
  const prepared = useRef<DscPrepareResponse | null>(null);

  if (!url) return null;
  const preselected = preselectedPdf(pdfs, dscDocument);
  const chosen = pdfs.find((d) => d.id === docId) ?? preselected ?? (pdfs.length === 1 ? pdfs[0] : undefined);
  // the step names a document that is not among the task's PDFs: say so and keep the manual pick
  const stepDocMissing = !!dscDocument?.documentId && !preselected;

  const explain = (e: unknown): string => {
    if (e instanceof ApiError && (e.status === 501 || e.problem.code === 'CAPABILITY_NOT_INSTALLED')) setNotInstalled(true);
    return explainDscProblem(e, t);
  };

  const prepare = async (cert: DscCertificate): Promise<DscToSign> => {
    if (!chosen) throw new Error(t('dsc.task.chooseDocument'));
    prepared.current = null;
    setResult(null);
    // the agent's chain, signer first: the server matches its first certificate to the caller's approved enrolment
    const res = await api.tasks.dscPrepare(taskId, { documentId: chosen.id, certificateChain: cert.certificateChain });
    if (res.documentId !== chosen.id) throw new Error(t('dsc.task.notTaskPdf'));
    prepared.current = res;
    return { signedAttributes: res.signedAttributes, preparedDigestSha256: res.preparedDigestSha256, algorithm: res.algorithm };
  };

  const complete = async (sig: DscSignature & { certificateSha256: string }) => {
    const p = prepared.current;
    if (!p) throw new Error(t('dsc.task.prepareNotUsable'));
    // a prepare is used up by its complete call, whatever the outcome
    prepared.current = null;
    const res = await api.tasks.dscComplete(taskId, { prepareId: p.prepareId, signature: sig.signature, algorithm: sig.algorithm, certificateChain: sig.certificateChain });
    setResult(res);
    if (res.outcome !== 'VERIFIED_VALID') throw new DscOutcomeError(res);
    announce(t('dsc.task.verified'));
    onSigned?.();
  };

  if (notInstalled) return <Notice tone="info">{t('dsc.task.notInstalled')}</Notice>;

  return (
    <div className="dsc-task">
      {pdfs.length === 0 ? (
        <p className="muted">{t('dsc.task.noPdf')}</p>
      ) : (
        <>
          {dscDocument && preselected && <p className="hint">{t('dsc.task.stepDocument', { slot: dscDocument.slot, name: preselected.name })}</p>}
          {stepDocMissing && <Notice tone="warn">{t('dsc.task.stepDocumentMissing', { slot: dscDocument?.slot ?? '' })}</Notice>}
          {pdfs.length > 1 && (
            <SelectField
              label={t('dsc.task.document')}
              value={chosen?.id ?? docId}
              onChange={setDocId}
              placeholder={t('form.choose')}
              options={pdfs.map((d) => ({ value: d.id, label: `${d.name} (v${d.version})` }))}
            />
          )}
          {chosen && <DscSigner key={chosen.id} documentName={chosen.name} agentUrl={url} prepare={prepare} onSigned={complete} explainServer={explain} />}
        </>
      )}
      {result?.outcome === 'VERIFIED_VALID' && (
        <Notice tone="ok">
          {t('dsc.task.verifiedVersion', { version: result.signedDocumentVersion ?? '-' })} {t('dsc.task.completeAfter')}
        </Notice>
      )}
      <p className="hint">{t('dsc.task.revokeNote')}</p>
    </div>
  );
}
