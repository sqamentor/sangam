// SPDX-License-Identifier: Apache-2.0
/**
 * M-043: the React views behind the embeddable web components (src/embed/elements.tsx). They reuse the tasklist's form
 * resolution (useTaskFormSchema), FormRenderer and BpmnDiagram unchanged, so an embedded form or diagram behaves exactly
 * like the same screen in the Sangam UI:
 * - the form completes the task with the payload hash and case version it was loaded with (VI.4 rule 5, R43); a 409
 *   stale answer keeps the user's input, shows the stale notice and blocks submit until the user continues (CX-2);
 * - only a plain COMPLETE is offered. Decisions that need the tasklist's dialogs (approve with the declaration, reject
 *   with a reason code, forward to a seat, return) are not offered here; the form is then read-only;
 * - the diagram keeps the bpmn.io watermark visible (LIC-8), with the data-table alternative below it.
 */
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/endpoints';
import { ApiError } from '../api/client';
import type { CompleteTaskResult } from '../api/types';
import { useAction, useAsync } from '../lib/hooks';
import { FormRenderer, validateForm, type FormValues } from '../components/FormRenderer';
import { useTaskFormSchema } from '../components/taskFormSchema';
import { BpmnDiagram, type OverlayMode } from '../components/BpmnDiagram';
import { Loading, Notice, ProblemAlert } from '../components/ui';

export interface TaskFormCallbacks {
  onCompleted: (result: CompleteTaskResult) => void;
  onError: (error: Error) => void;
}

export function EmbeddedTaskForm({ taskId, callbacks }: { taskId: string; callbacks: TaskFormCallbacks }) {
  const { t } = useTranslation();
  const task = useAsync(() => api.tasks.get(taskId), [taskId]);
  const d = task.data;
  const { schema, externalUrl, scriptsIgnored, error: formError } = useTaskFormSchema(d, taskId);
  const [values, setValues] = useState<FormValues>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [stale, setStale] = useState(false);
  const [done, setDone] = useState<CompleteTaskResult | null>(null);
  const cmd = useAction();
  const cb = useRef(callbacks);
  cb.current = callbacks;

  // A newly loaded task resets the form during render, not in an effect: React 19 may run a passive effect only after
  // the next event, so the first render would show (and submit) empty values.
  const [shown, setShown] = useState(d);
  if (d && shown !== d) {
    setShown(d);
    setValues({ ...d.variables });
    setErrors({});
  }
  useEffect(() => {
    if (task.error) cb.current.onError(task.error);
  }, [task.error]);

  if (task.loading && !d) return <Loading slow={task.slow} />;
  if (task.error || !d) return <ProblemAlert error={task.error} onRetry={task.reload} />;

  const canComplete = d.allowedActions.includes('COMPLETE');
  const editable = canComplete && !done;

  const submit = async () => {
    const errs = validateForm(schema, values, t);
    setErrors(errs);
    if (Object.keys(errs).length) return;
    let failure: unknown;
    const r = await cmd.run(async (key) => {
      try {
        return await api.tasks.complete(
          d.id,
          {
            action: 'COMPLETE',
            variables: values,
            payloadHash: d.payloadHash,
            version: d.version,
            formVersion: d.formVersion !== undefined ? String(d.formVersion) : undefined,
          },
          key,
        );
      } catch (e) {
        failure = e;
        throw e;
      }
    });
    if (r) {
      setDone(r);
      cb.current.onCompleted(r);
      return;
    }
    if (failure instanceof ApiError && failure.isStale) {
      // Keep the user's input on top of the new version (same rule as TaskDetail, CX-2).
      const fresh = await api.tasks.get(taskId).catch(() => null);
      if (fresh) task.setData({ ...fresh, variables: { ...fresh.variables, ...values } });
      setStale(true);
    }
    if (failure) cb.current.onError(failure as Error);
  };

  return (
    <form
      className="sangam-embed-form"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        if (editable && !stale && !cmd.busy) void submit();
      }}
    >
      {scriptsIgnored && <Notice tone="warn">{t('task.formScriptsIgnored')}</Notice>}
      {formError && <ProblemAlert error={formError} />}
      {externalUrl && (
        <Notice>
          <a href={externalUrl} target="_blank" rel="noopener noreferrer">
            {t('task.externalForm')}
          </a>
        </Notice>
      )}
      {!canComplete && !done && <Notice>{t('embed.decisionInTasklist')}</Notice>}
      {stale && (
        <Notice tone="warn">
          {t('error.stale')}{' '}
          <button type="button" onClick={() => setStale(false)}>
            {t('task.staleContinue')}
          </button>
        </Notice>
      )}
      {done ? (
        <Notice tone="ok">
          <strong>{t('task.doneTitle')}</strong>
          {done.reference && <> {t('task.doneRef', { ref: done.reference })}</>}
        </Notice>
      ) : (
        <>
          <FormRenderer schema={schema} values={values} onChange={setValues} errors={errors} readOnly={!editable} changed={d.changedSinceLastSeen} />
          {cmd.error && !stale && <ProblemAlert error={cmd.error} />}
          {editable && (
            <div className="actions">
              <button type="submit" className="primary" disabled={cmd.busy || stale}>
                {cmd.busy ? t('common.working') : t('common.submit')}
              </button>
            </div>
          )}
        </>
      )}
    </form>
  );
}

export function EmbeddedProcessDiagram({
  definitionId,
  instanceId,
  mode,
  height,
  title,
  onSelect,
}: {
  definitionId: string;
  instanceId?: string;
  mode: OverlayMode;
  height: number;
  title: string;
  onSelect: (activityId: string, name?: string) => void;
}) {
  const xml = useAsync(() => api.processes.xml(definitionId), [definitionId]);
  const stats = useAsync(() => api.processes.instanceStatistics(instanceId!), [instanceId], !!instanceId);
  if (xml.loading && !xml.data) return <Loading slow={xml.slow} />;
  if (xml.error || !xml.data) return <ProblemAlert error={xml.error} onRetry={xml.reload} />;
  return (
    <>
      {stats.error && <ProblemAlert error={stats.error} onRetry={stats.reload} />}
      <BpmnDiagram xml={xml.data.bpmn20Xml} stats={stats.data?.activities ?? []} flows={stats.data?.flows ?? []} mode={mode} height={height} title={title} onSelect={onSelect} />
    </>
  );
}
