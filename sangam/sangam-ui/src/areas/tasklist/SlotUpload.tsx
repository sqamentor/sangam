// SPDX-License-Identifier: Apache-2.0
/**
 * Owner decisions 267-269 (contract 1D188888, uploadDocument and addDocumentVersion): the assignee uploads a document,
 * or a new version of a task document, with a documentType. When the pack of the process instance declares a slot for
 * that type (W4-K packs: NOTING to notingDocumentId, SANCTION_NOTE to sanctionDocumentId) and the file is a PDF, the
 * server writes it into the slot in the same transaction; otherwise the type is stored as metadata only.
 * - 403 DOCUMENT_SLOT_NOT_TASK_HOLDER: only the assignee of an open task of that instance, with task.write, may set or
 *   replace a slot (nothing stored);
 * - 409 DOCUMENT_SLOT_OCCUPIED: the slot holds another document (nothing stored). The officer is asked, and only on
 *   their confirmation is the same file sent again with replaceSlot=true (a new Idempotency-Key: a different request).
 * A slot changed after a signing task was created is refused at that task's completion (by design).
 * The UI offers the two types the packs publish; no route lists a pack's documentSlots, and the server decides.
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import { ApiError, newIdempotencyKey } from '../../api/client';
import { DOCUMENT_SLOT_TYPES, type Attachment } from '../../api/types';
import { Notice, ProblemAlert, SelectField } from '../../components/ui';
import { announce } from '../../lib/hooks';

interface Pending {
  file: File;
  documentType: string;
  /** '' = a new document; otherwise a new version of this document. */
  target: string;
}

/** Sends one slot upload: a new document for the task, or a new version of an existing one. */
export function sendSlotUpload(taskId: string, p: Pending, replaceSlot: boolean) {
  const key = newIdempotencyKey();
  return p.target
    ? api.documents.addVersion(p.target, p.file, { documentType: p.documentType, replaceSlot }, key)
    : api.documents.upload(p.file, { taskId, documentType: p.documentType, replaceSlot }, key);
}

export function SlotUpload({ taskId, documents, onStored }: { taskId: string; documents: Attachment[]; onStored?: () => void }) {
  const { t } = useTranslation();
  const [documentType, setDocumentType] = useState('');
  const [target, setTarget] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error>();
  const [occupied, setOccupied] = useState<Pending | null>(null);

  const send = async (p: Pending, replaceSlot: boolean) => {
    setBusy(true);
    setError(undefined);
    try {
      await sendSlotUpload(taskId, p, replaceSlot);
      setOccupied(null);
      announce(t('dsc.slot.stored'));
      onStored?.();
    } catch (e) {
      if (!replaceSlot && e instanceof ApiError && e.problem.code === 'DOCUMENT_SLOT_OCCUPIED') setOccupied(p);
      else {
        setOccupied(null);
        setError(e as Error);
      }
    } finally {
      setBusy(false);
    }
  };

  const notHolder = error instanceof ApiError && error.problem.code === 'DOCUMENT_SLOT_NOT_TASK_HOLDER';
  return (
    <fieldset className="field">
      <legend>{t('dsc.slot.title')}</legend>
      <p className="hint">{t('dsc.slot.hint')}</p>
      <SelectField
        label={t('dsc.slot.type')}
        value={documentType}
        onChange={setDocumentType}
        placeholder={t('form.choose')}
        options={DOCUMENT_SLOT_TYPES.map((v) => ({ value: v, label: v === 'NOTING' ? t('dsc.slot.noting') : t('dsc.slot.sanctionNote') }))}
      />
      <SelectField
        label={t('dsc.slot.target')}
        value={target}
        onChange={setTarget}
        options={[{ value: '', label: t('dsc.slot.newDocument') }, ...documents.map((d) => ({ value: d.id, label: t('dsc.slot.newVersionOf', { name: d.name, version: d.version }) }))]}
      />
      <label htmlFor="slot-upload">{t('dsc.slot.file')}</label>
      <input
        id="slot-upload"
        type="file"
        accept="application/pdf,.pdf"
        disabled={!documentType || busy || !!occupied}
        onChange={(e) => {
          const file = e.target.files?.[0];
          e.target.value = '';
          if (file && documentType) void send({ file, documentType, target }, false);
        }}
      />
      {busy && <span aria-live="polite">{t('common.working')}</span>}
      {occupied && (
        <Notice tone="warn">
          {t('dsc.slot.occupied', { type: occupied.documentType })}{' '}
          <span className="toolbar inline">
            <button type="button" className="danger" disabled={busy} onClick={() => void send(occupied, true)}>
              {t('dsc.slot.replace')}
            </button>
            <button type="button" disabled={busy} onClick={() => setOccupied(null)}>
              {t('common.cancel')}
            </button>
          </span>
        </Notice>
      )}
      {notHolder ? <Notice tone="bad">{t('dsc.slot.notHolder')}</Notice> : <ProblemAlert error={error} />}
    </fieldset>
  );
}
