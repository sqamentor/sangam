// SPDX-License-Identifier: Apache-2.0
/**
 * C3-14 / M-036 (owner decision 58): composite form components in the form-js editor. A FORM_COMPONENT template is
 * {id, name, components: [form-js component JSON]} (GET /templates ?kind=FORM_COMPONENT, package.read). Inserting one
 * appends its components to the edited form (modeller/templates.ts insertComponents): ids are renewed, keys are kept,
 * and a key the form already uses refuses the insert and names the key, because a key binds a process variable.
 * The editor re-imports the merged schema (form-js FormEditor.importSchema), so the insert is one step the user sees
 * at once; the form is then marked changed like any other edit.
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { announce, useAsync } from '../lib/hooks';
import { formComponentTemplates, insertComponents, type FormComponentTemplate } from '../modeller/templates';
import { Notice, ProblemAlert, SelectField } from './ui';
import { templatesOfKind } from './ElementTemplatePanel';

/** The parts of a form-js FormEditor the panel uses (public API of @bpmn-io/form-js-editor 1.12). */
export interface FormEditorLike {
  saveSchema: () => unknown;
  importSchema: (schema: never) => Promise<unknown>;
}

export const newFieldId = (): string => `Field_${Math.random().toString(36).slice(2, 9)}`;

/** Inserts a template into the editor; returns the conflicting keys (empty when it was inserted). */
export async function insertTemplate(editor: FormEditorLike, tpl: FormComponentTemplate, newId: () => string = newFieldId): Promise<string[]> {
  const current = editor.saveSchema();
  const schema = current && typeof current === 'object' ? (current as Record<string, unknown>) : { components: [] };
  const r = insertComponents(schema, tpl.components, newId);
  if (!r.schema) return r.conflicts;
  await editor.importSchema(r.schema as never);
  return [];
}

export function FormComponentPanel({ editor, onInserted }: { editor: FormEditorLike | null; onInserted: () => void }) {
  const { t } = useTranslation();
  const list = useAsync((signal) => templatesOfKind('FORM_COMPONENT', signal), []);
  const [key, setKey] = useState('');
  const [conflicts, setConflicts] = useState<string[]>([]);
  const [err, setErr] = useState<Error>();
  const [busy, setBusy] = useState(false);
  const options = formComponentTemplates(list.data ?? []);
  if (!editor || (options.length === 0 && !list.error)) return null;
  const chosen = options.find((x) => x.key === key);

  const insert = () => {
    if (!chosen) return;
    setBusy(true);
    setErr(undefined);
    void insertTemplate(editor, chosen)
      .then((c) => {
        setConflicts(c);
        if (c.length === 0) {
          onInserted();
          announce(t('tpl.componentInserted', { name: chosen.name }));
        }
      })
      .catch((e: Error) => setErr(e))
      .finally(() => setBusy(false));
  };

  return (
    <section className="card" aria-label={t('tpl.components')}>
      <h3>{t('tpl.components')}</h3>
      <SelectField
        label={t('tpl.component')}
        value={key}
        onChange={(k) => {
          setKey(k);
          setConflicts([]);
        }}
        placeholder={t('tpl.choose')}
        options={options.map((x) => ({ value: x.key, label: `${x.name} v${x.version} (${x.components.length})` }))}
      />
      <button type="button" className="primary" disabled={!chosen || busy} onClick={insert}>
        {t('tpl.componentInsert')}
      </button>
      {conflicts.length > 0 && <Notice tone="bad">{t('tpl.componentKeyConflict', { keys: conflicts.join(', ') })}</Notice>}
      <ProblemAlert error={err ?? list.error} />
    </section>
  );
}
