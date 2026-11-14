// SPDX-License-Identifier: Apache-2.0
/**
 * R78 / EC-06, owner decisions 265, 284 and 289 (contract 1D188888, createDataObjectRecord): create a record of a data
 * object.
 * - Only a holder of lowcode.record.write sees the button (RECORD_WRITERS, decision 284); the server checks it again.
 * - DB backing answers 201 with the record: it is shown at once.
 * - REST backing answers 202 {operationId, status PENDING}: the screen says PENDING and polls
 *   GET /data-objects/{key}/records/operations/{operationId} with bounded backoff (lib/recordOperation.ts) until
 *   COMPLETED (the record is shown), FAILED (the failureCode is shown) or UNKNOWN (the outcome is not confirmed and is in
 *   reconciliation). When the bound is reached it says the outcome is not confirmed yet and offers a manual check. It
 *   never says a record was created without a COMPLETED answer.
 * - 503 DATA_OBJECT_CONNECTOR_FAILED: the retry is offered only after its Retry-After, with the same Idempotency-Key
 *   (the same request), so a create the server already ran is replayed, not repeated. 501: not installed.
 * Numbers travel as exact decimal text (decision 138), never as a float.
 */
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, type DataObjectDef, type DataObjectField } from '../../api/endpoints';
import { ApiError, newIdempotencyKey } from '../../api/client';
import type { DataObjectRecordOperation } from '../../api/types';
import { RECORD_WRITERS, canAccess } from '../../app/access';
import { useAuth } from '../../auth/AuthProvider';
import { DecimalValue, fromNumberInput } from '../../lib/decimal';
import { pollRecordOperation, type Sleep } from '../../lib/recordOperation';
import { Checkbox, JsonView, Notice, ProblemAlert, Section, TextArea, TextField } from '../../components/ui';
import { announce } from '../../lib/hooks';

type Raw = Record<string, string | boolean>;

/** The form values as a record: numbers as exact decimals (typed Decimal / BigInteger, or a safe integer), JSON parsed. */
export function recordFromInputs(fields: DataObjectField[], raw: Raw): { record: Record<string, unknown>; invalid: string[] } {
  const record: Record<string, unknown> = {};
  const invalid: string[] = [];
  for (const f of fields) {
    const v = raw[f.name];
    if (f.type === 'boolean') {
      if (v === true || v === false) record[f.name] = v;
      else if (f.required) record[f.name] = false;
      continue;
    }
    const s = typeof v === 'string' ? v.trim() : '';
    if (!s) {
      if (f.required) invalid.push(f.name);
      continue;
    }
    if (f.type === 'number') {
      const n = fromNumberInput(s);
      if (n instanceof DecimalValue) record[f.name] = { type: n.type, value: n.text };
      else if (typeof n === 'number') record[f.name] = n;
      else invalid.push(f.name);
    } else if (f.type === 'json') {
      try {
        record[f.name] = JSON.parse(s);
      } catch {
        invalid.push(f.name);
      }
    } else record[f.name] = s;
  }
  return { record, invalid };
}

type Phase = 'edit' | 'sending' | 'pending' | 'completed' | 'failed' | 'unknown' | 'unconfirmed';

/** Whether the signed-in user may create records (decision 284). */
export function useCanWriteRecords(): boolean {
  const { me } = useAuth();
  return canAccess(me, RECORD_WRITERS);
}

export function RecordCreate({ def, onClose, onCreated, pollSleep }: { def: DataObjectDef; onClose: () => void; onCreated?: () => void; /** Tests only: the wait between reads. */ pollSleep?: Sleep }) {
  const { t } = useTranslation();
  const [raw, setRaw] = useState<Raw>({});
  const [invalid, setInvalid] = useState<string[]>([]);
  const [phase, setPhase] = useState<Phase>('edit');
  const [record, setRecord] = useState<Record<string, unknown>>();
  const [op, setOp] = useState<DataObjectRecordOperation>();
  const [error, setError] = useState<Error>();
  const [retryAt, setRetryAt] = useState(0);
  const [, setTick] = useState(0);
  // one Idempotency-Key per request body: a retry of the same values replays, an edit starts a new request
  const keyRef = useRef(newIdempotencyKey());
  const abort = useRef<AbortController | null>(null);
  useEffect(() => () => abort.current?.abort(), []);
  useEffect(() => {
    if (!retryAt) return;
    const id = window.setTimeout(() => setTick((n) => n + 1), Math.max(0, retryAt - Date.now()) + 50);
    return () => window.clearTimeout(id);
  }, [retryAt]);

  const set = (name: string, v: string | boolean) => {
    keyRef.current = newIdempotencyKey();
    setRaw({ ...raw, [name]: v });
  };

  const settle = (o: DataObjectRecordOperation) => {
    setOp(o);
    if (o.status === 'COMPLETED') {
      setRecord(o.record);
      setPhase('completed');
      announce(t('data.recordCreated'));
      onCreated?.();
    } else if (o.status === 'FAILED') setPhase('failed');
    else if (o.status === 'UNKNOWN') setPhase('unknown');
    else setPhase('unconfirmed');
  };

  const poll = async (first: DataObjectRecordOperation) => {
    abort.current?.abort();
    const ctl = new AbortController();
    abort.current = ctl;
    setPhase('pending');
    try {
      const r = await pollRecordOperation((signal) => api.dataObjects.recordOperation(def.key, first.operationId, signal), first, { signal: ctl.signal, onRead: setOp, sleep: pollSleep });
      if (!ctl.signal.aborted) settle(r.operation);
    } catch (e) {
      if (ctl.signal.aborted || (e as Error).name === 'AbortError') return;
      setError(e as Error);
      setPhase('unconfirmed');
    }
  };

  const checkAgain = async () => {
    if (!op) return;
    setError(undefined);
    try {
      const o = await api.dataObjects.recordOperation(def.key, op.operationId);
      if (o.status === 'PENDING') {
        setOp(o);
        void poll(o);
      } else settle(o);
    } catch (e) {
      setError(e as Error);
    }
  };

  const submit = async () => {
    const r = recordFromInputs(def.fields, raw);
    setInvalid(r.invalid);
    if (r.invalid.length) return;
    setError(undefined);
    setPhase('sending');
    try {
      const answer = await api.dataObjects.createRecord(def.key, r.record, keyRef.current);
      if (answer.kind === 'created') {
        keyRef.current = newIdempotencyKey();
        setRecord(answer.record);
        setPhase('completed');
        announce(t('data.recordCreated'));
        onCreated?.();
        return;
      }
      keyRef.current = newIdempotencyKey();
      setOp(answer.operation);
      if (answer.operation.status === 'PENDING') void poll(answer.operation);
      else settle(answer.operation);
    } catch (e) {
      setError(e as Error);
      if (e instanceof ApiError && e.status === 503) setRetryAt(Date.now() + (e.retryAfterMs ?? 0));
      setPhase('edit');
    }
  };

  const notInstalled = error instanceof ApiError && (error.status === 501 || error.problem.code === 'CAPABILITY_NOT_INSTALLED');
  const connectorFailed = error instanceof ApiError && error.problem.code === 'DATA_OBJECT_CONNECTOR_FAILED';
  const waitSeconds = Math.ceil(Math.max(0, retryAt - Date.now()) / 1000);
  const editing = phase === 'edit' || phase === 'sending';

  return (
    <Section title={t('data.newRecord', { key: def.key })}>
      {editing &&
        def.fields.map((f) =>
          f.type === 'boolean' ? (
            <Checkbox key={f.name} label={f.name} checked={raw[f.name] === true} onChange={(v) => set(f.name, v)} />
          ) : f.type === 'json' ? (
            <TextArea key={f.name} label={f.name} required={f.required} value={String(raw[f.name] ?? '')} onChange={(v) => set(f.name, v)} error={invalid.includes(f.name) ? t('mod.common.invalidJsonField', { field: f.name }) : undefined} />
          ) : (
            <TextField
              key={f.name}
              label={f.name}
              type={f.type === 'date' ? 'date' : 'text'}
              inputMode={f.type === 'number' ? 'decimal' : undefined}
              required={f.required}
              value={String(raw[f.name] ?? '')}
              onChange={(v) => set(f.name, v)}
              error={invalid.includes(f.name) ? (f.type === 'number' && String(raw[f.name] ?? '').trim() ? t('form.notANumber') : t('form.required')) : undefined}
            />
          ),
        )}
      {notInstalled ? (
        <Notice tone="info">{t('data.restNotInstalled')}</Notice>
      ) : connectorFailed ? (
        <Notice tone="bad">{waitSeconds > 0 ? t('data.connectorFailedWait', { n: waitSeconds }) : t('data.connectorFailed')}</Notice>
      ) : (
        <ProblemAlert error={error} />
      )}
      {phase === 'pending' && (
        <p role="status">
          {t('data.recordPending')} <span className="muted small">{op?.operationId}</span>
        </p>
      )}
      {phase === 'completed' && (
        <>
          <Notice tone="ok">{t('data.recordCreated')}</Notice>
          {record && <JsonView value={record} />}
        </>
      )}
      {phase === 'failed' && <Notice tone="bad">{t('data.recordFailed', { code: op?.failureCode ?? '-' })}</Notice>}
      {phase === 'unknown' && <Notice tone="warn">{t('data.recordUnknown', { id: op?.operationId ?? '' })}</Notice>}
      {phase === 'unconfirmed' && (
        <Notice tone="warn">
          {t('data.recordUnconfirmed', { id: op?.operationId ?? '' })}{' '}
          <button type="button" onClick={() => void checkAgain()}>
            {t('data.checkAgain')}
          </button>
        </Notice>
      )}
      <div className="toolbar">
        <button type="button" onClick={onClose}>
          {t('common.close')}
        </button>
        {editing && (
          <button type="button" className="primary" disabled={phase === 'sending' || notInstalled || waitSeconds > 0} onClick={() => void submit()}>
            {connectorFailed ? t('common.retry') : t('data.createRecord')}
          </button>
        )}
      </div>
    </Section>
  );
}
