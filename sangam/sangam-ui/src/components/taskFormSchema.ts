// SPDX-License-Identifier: Apache-2.0
/**
 * The form a task shows, resolved once for every screen that renders one (the tasklist's TaskDetail and the embeddable
 * <sangam-task-form>, M-043): the Sangam form schema the task carries; else a deployed embedded/Operaton form read through
 * the Sangam API (GET /tasks/{id}/form, SL-37) and parsed inertly; else a read-only schema built from the variables.
 * The tenant's custom fields for the form are appended (M-031). An external form key yields a link, never an iframe.
 */
import { useMemo } from 'react';
import { api } from '../api/endpoints';
import type { FormSchema, TaskDetail } from '../api/types';
import { useWithCustomFields } from '../api/customFields';
import { useAsync } from '../lib/hooks';
import { classifyFormKey, externalFormUrl, taskFormToSchema, type FormKeyKind } from './embeddedForm';
import { schemaFromVariables } from './FormRenderer';

export interface TaskFormSchema {
  schema: FormSchema;
  formKind: FormKeyKind;
  /** An external form's link (http(s) or same-origin only), or null. */
  externalUrl: string | null;
  /** The deployed embedded form contained scripts, which were not run. */
  scriptsIgnored: boolean;
  /** Loading the deployed form failed. */
  error: Error | undefined;
}

export function useTaskFormSchema(task: TaskDetail | undefined, taskId: string): TaskFormSchema {
  const formKind: FormKeyKind = task?.form ? 'other' : classifyFormKey(task?.formKey);
  // Deployed HTML is read as data (Sangam API, task-level check, SL-37) and parsed inertly; its scripts never run.
  const embedded = useAsync(async () => taskFormToSchema(await api.tasks.form(taskId)), [taskId, task?.formKey], formKind === 'embedded');
  const shipped = useMemo(
    () => task?.form ?? embedded.data?.schema ?? (task ? schemaFromVariables(task.variables) : { components: [] }),
    [task, embedded.data],
  );
  const schema = useWithCustomFields(shipped, task?.form?.key);
  const externalUrl = task?.formKey && formKind === 'external' ? externalFormUrl(task.formKey, taskId) : null;
  return { schema, formKind, externalUrl, scriptsIgnored: !!embedded.data?.scriptsIgnored, error: embedded.error };
}
