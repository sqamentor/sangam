// SPDX-License-Identifier: Apache-2.0
/**
 * M-037 (NG-28): the action menu of a task or a case, on the modeller's action models (ModellerController, sangam-rest
 * .../modules/modeller):
 * - GET /actions ?scope=TASK|CASE &targetRef=<id> lists the active actions of that scope that the caller may invoke,
 *   each filtered by its own permission (:297-308; ActionModels.available, ActionModels.java:81-85). A caller without
 *   modeller.action.invoke (:304) gets 403, and the menu is simply not shown.
 * - POST /actions/{key}/run {subjectRef, input} runs one (:326-334). It is subject-bound (subjectRef is the task or case
 *   id) and idempotent: the Idempotency-Key is the client key (:332), so a retry after a timeout replays the stored
 *   result instead of acting twice (ActionModels.invoke, :91-110; `replayed` in the answer).
 * - An action with a form (formKey) is filled in through that form (GET /forms/by-key?key=); otherwise the input is optional
 *   JSON.
 * The scopeType filter is not sent: an action bound to a scopeType is offered only once its meaning (process, task or
 * case definition key) is confirmed; until then the menu lists the actions without one (ActionModels.java:83).
 */
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, http } from '../api/client';
import { api } from '../api/endpoints';
import type { FormSchema } from '../api/types';
import { announce, useAction, useAsync } from '../lib/hooks';
import { FormRenderer, validateForm, type FormValues } from './FormRenderer';
import { Dialog, JsonView, KeyValues, Loading, Notice, ProblemAlert, TextArea } from './ui';

export type ActionScope = 'TASK' | 'CASE' | 'PROCESS';

/** ActionView (ModellerController.java:99-101). */
export interface ActionModel {
  key: string;
  version?: number;
  name?: string;
  label?: string;
  scope?: ActionScope;
  scopeType?: string | null;
  permission?: string;
  formKey?: string | null;
  targetType?: 'BPMN' | 'REST' | 'SCRIPT';
}

/** ActionModels.Invocation (ActionModels.java:44). */
export interface Invocation {
  operationId: string;
  result?: Record<string, unknown>;
  replayed?: boolean;
}

/** The menu's list: an action without permission or module is no error for the page, just no menu. */
export async function availableActions(scope: ActionScope, subjectRef: string, signal?: AbortSignal): Promise<ActionModel[]> {
  try {
    const r = await http.get<ActionModel[]>('/actions', { scope, targetRef: subjectRef }, signal);
    return Array.isArray(r) ? r : [];
  } catch (e) {
    if (e instanceof ApiError && (e.status === 403 || e.status === 404 || e.status === 501)) return [];
    throw e;
  }
}

function RunDialog({ action, subjectRef, onClose }: { action: ActionModel; subjectRef: string; onClose: () => void }) {
  const { t } = useTranslation();
  // Only an action with a form has something to load. Without a formKey the JSON input is there at once: no loading
  // state and no disabled Run button for a request that is never made.
  const form = useAsync<FormSchema | null>(() => api.forms.get(action.formKey ?? ''), [action.formKey], !!action.formKey);
  const [values, setValues] = useState<FormValues>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [json, setJson] = useState('');
  const [jsonErr, setJsonErr] = useState(false);
  const [done, setDone] = useState<Invocation>();
  const act = useAction();
  const submit = () => {
    let input: Record<string, unknown> = {};
    if (form.data) {
      const errs = validateForm(form.data, values, t);
      setErrors(errs);
      if (Object.keys(errs).length) return;
      input = values;
    } else if (json.trim()) {
      try {
        const v = JSON.parse(json) as unknown;
        if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('object');
        input = v as Record<string, unknown>;
      } catch {
        setJsonErr(true);
        return;
      }
    }
    setJsonErr(false);
    void act.run((k) => http.post<Invocation>(`/actions/${encodeURIComponent(action.key)}/run`, { subjectRef, input }, k)).then((r) => {
      if (r) {
        setDone(r);
        announce(r.replayed ? t('actions.replayed') : t('mod.common.done'));
      }
    });
  };
  const label = action.label ?? action.name ?? action.key;
  return (
    <Dialog
      open
      wide
      title={label}
      onClose={onClose}
      footer={
        done ? (
          <button type="button" className="primary" onClick={onClose}>{t('common.close')}</button>
        ) : (
          <>
            <button type="button" onClick={onClose}>{t('common.cancel')}</button>
            <button type="button" className="primary" disabled={act.busy || form.loading} onClick={submit}>
              {act.busy ? t('common.working') : t('actions.run')}
            </button>
          </>
        )
      }
    >
      {done ? (
        <>
          <Notice tone="ok">{done.replayed ? t('actions.replayed') : t('actions.done')}</Notice>
          <KeyValues items={[[t('actions.operationId'), <code key="o">{done.operationId}</code>]]} />
          {done.result && Object.keys(done.result).length > 0 && <JsonView value={done.result} />}
        </>
      ) : (
        <>
          <p className="hint">{t('actions.runHint', { subject: subjectRef })}</p>
          {form.loading && <Loading />}
          <ProblemAlert error={form.error} />
          {form.data ? (
            <FormRenderer schema={form.data} values={values} onChange={setValues} errors={errors} />
          ) : (
            !form.loading && <TextArea label={t('actions.input')} hint={t('actions.inputHint')} className="code" value={json} onChange={setJson} />
          )}
          {jsonErr && <Notice tone="bad">{t('mod.common.invalidJson')}</Notice>}
          <ProblemAlert error={act.error} />
        </>
      )}
    </Dialog>
  );
}

/** The actions the caller may run on this task or case; nothing at all when there are none. */
export function ActionMenu({ scope, subjectRef }: { scope: ActionScope; subjectRef: string }) {
  const { t } = useTranslation();
  const list = useAsync((signal) => availableActions(scope, subjectRef, signal), [scope, subjectRef], !!subjectRef);
  const [open, setOpen] = useState<ActionModel | null>(null);
  const items = useMemo(() => list.data ?? [], [list.data]);
  if (!items.length) return null;
  return (
    <>
      <details className="menu">
        <summary>{t('actions.menu', { n: items.length })}</summary>
        <ul role="list">
          {items.map((a) => (
            <li key={a.key}>
              <button type="button" className="link" onClick={() => setOpen(a)}>
                {a.label ?? a.name ?? a.key}
              </button>
            </li>
          ))}
        </ul>
      </details>
      {open && <RunDialog action={open} subjectRef={subjectRef} onClose={() => setOpen(null)} />}
    </>
  );
}
