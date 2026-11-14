// SPDX-License-Identifier: Apache-2.0
/**
 * M-125: compare two versions of one model file by meaning, over POST /models/diff (operationId diffModels,
 * package.read; sangam-rest/api/routes/modeller.py, ModellerController.diffModels). The server reads only and stores
 * nothing. Shown: the format, the number of changes per level (ELEMENT, FLOW, EXPRESSION, PROPERTY) and the changes
 * list as accessible tables (DataTable: caption, column headers, a focusable scroll region). A 400 MODEL_UNREADABLE
 * (a side not XML/JSON, a DOCTYPE, mixed formats, or both sides empty) is explained in plain English; every other
 * problem goes through ProblemAlert.
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import { ApiError } from '../../api/client';
import { MODEL_DIFF_LEVELS, type ModelDiffChange, type ModelDiffLevel, type ModelDiffReport } from '../../api/types';
import { announce, useAction } from '../../lib/hooks';
import { Badge, DataTable, Notice, ProblemAlert, Section, TextArea, TextField } from '../../components/ui';

/** Contract limit of ModelDiffRequest.resource. */
export const MAX_RESOURCE_LENGTH = 255;

/** True for the 400 MODEL_UNREADABLE answer of diffModels. */
export function isModelUnreadable(e: unknown): e is ApiError {
  return e instanceof ApiError && e.status === 400 && e.problem.code === 'MODEL_UNREADABLE';
}

/** The request body: an empty side is sent as null (contract: null or empty = a new or a deleted file). */
export function diffRequest(resource: string, before: string, after: string) {
  return { resource: resource.trim(), before: before === '' ? null : before, after: after === '' ? null : after };
}

function useLabels() {
  const { t } = useTranslation();
  const level: Record<ModelDiffLevel, string> = {
    ELEMENT: t('mod.modeller2.diffLevelElement'),
    FLOW: t('mod.modeller2.diffLevelFlow'),
    EXPRESSION: t('mod.modeller2.diffLevelExpression'),
    PROPERTY: t('mod.modeller2.diffLevelProperty'),
  };
  const kind: Record<ModelDiffChange['kind'], string> = {
    ADDED: t('mod.modeller2.diffKindAdded'),
    REMOVED: t('mod.modeller2.diffKindRemoved'),
    CHANGED: t('mod.modeller2.diffKindChanged'),
  };
  return { level, kind };
}

const KIND_TONE: Record<ModelDiffChange['kind'], 'ok' | 'bad' | 'info'> = { ADDED: 'ok', REMOVED: 'bad', CHANGED: 'info' };

function Value({ v }: { v?: string | null }) {
  const { t } = useTranslation();
  if (v === null || v === undefined || v === '') {
    return (
      <>
        <span aria-hidden="true">—</span>
        <span className="sr-only">{t('mod.modeller2.diffNoValue')}</span>
      </>
    );
  }
  return <code>{v}</code>;
}

/** The report of one comparison: summary per level, then the changes. */
export function ModelDiffView({ report }: { report: ModelDiffReport }) {
  const { t } = useTranslation();
  const labels = useLabels();
  const summary = MODEL_DIFF_LEVELS.map((l) => ({ level: l, count: report.summary[l] ?? 0 }));
  return (
    <div className="model-diff">
      <p>
        {t('mod.modeller2.diffFormat')}: <Badge value={report.format} tone="neutral" /> · {report.resource}
      </p>
      {!report.changed && <Notice tone="ok">{t('mod.modeller2.diffNoChanges')}</Notice>}
      <DataTable
        caption={t('mod.modeller2.diffSummary')}
        rows={summary}
        rowKey={(r) => r.level}
        columns={[
          { key: 'level', header: t('mod.modeller2.diffLevel'), render: (r) => labels.level[r.level] },
          { key: 'count', header: t('mod.modeller2.diffCount'), render: (r) => String(r.count), className: 'num' },
        ]}
      />
      {report.changes.length > 0 && (
        <DataTable
          caption={t('mod.modeller2.diffChanges', { n: report.changes.length })}
          rows={report.changes.map((c, i) => ({ ...c, i }))}
          rowKey={(r) => String(r.i)}
          columns={[
            { key: 'level', header: t('mod.modeller2.diffLevel'), render: (r) => labels.level[r.level] ?? r.level },
            { key: 'kind', header: t('mod.modeller2.diffKind'), render: (r) => <Badge value={labels.kind[r.kind] ?? r.kind} tone={KIND_TONE[r.kind] ?? 'neutral'} /> },
            { key: 'elementId', header: t('mod.modeller2.diffElementId'), render: (r) => <code>{r.elementId}</code> },
            { key: 'elementType', header: t('mod.modeller2.diffElementType') },
            { key: 'name', header: t('common.name'), render: (r) => r.name || '—' },
            { key: 'path', header: t('mod.modeller2.diffPath'), render: (r) => (r.path ? <code>{r.path}</code> : t('mod.modeller2.diffWholeElement')) },
            { key: 'before', header: t('mod.modeller2.diffBeforeValue'), render: (r) => <Value v={r.before} /> },
            { key: 'after', header: t('mod.modeller2.diffAfterValue'), render: (r) => <Value v={r.after} /> },
          ]}
        />
      )}
    </div>
  );
}

function FileLoad({ id, label, onText }: { id: string; label: string; onText: (s: string) => void }) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        type="file"
        accept=".bpmn,.bpmn20.xml,.bpmn2,.dmn,.cmmn,.json,.form,.xml"
        onChange={(e) => {
          const file = e.target.files?.[0];
          e.target.value = '';
          if (file) void file.text().then(onText);
        }}
      />
    </div>
  );
}

/**
 * The compare form. `resource` names the file (its extension picks the format); `current` is the saved text of the
 * open model, offered as the "after" side.
 */
export function ModelCompare({ resource: initialResource = '', current }: { resource?: string; current?: string }) {
  const { t } = useTranslation();
  const [resource, setResource] = useState(initialResource);
  const [before, setBefore] = useState('');
  const [after, setAfter] = useState(current ?? '');
  const [report, setReport] = useState<ModelDiffReport>();
  const act = useAction();
  const tooLong = resource.trim().length > MAX_RESOURCE_LENGTH;
  const canRun = !!resource.trim() && !tooLong && (before !== '' || after !== '') && !act.busy;
  const unreadable = isModelUnreadable(act.error);
  return (
    <Section title={t('mod.modeller2.compareTitle')}>
      <p className="hint">{t('mod.modeller2.compareHint')}</p>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (!canRun) return;
          setReport(undefined);
          void act.run(() => api.models.diff(diffRequest(resource, before, after))).then((r) => {
            if (r) {
              setReport(r);
              announce(r.changed ? t('mod.modeller2.diffChanges', { n: r.changes.length }) : t('mod.modeller2.diffNoChanges'));
            }
          });
        }}
      >
        <TextField
          label={t('mod.modeller2.compareResource')}
          hint={t('mod.modeller2.compareResourceHint')}
          required
          maxLength={MAX_RESOURCE_LENGTH}
          error={tooLong ? t('mod.modeller2.compareResourceTooLong', { n: MAX_RESOURCE_LENGTH }) : undefined}
          value={resource}
          onChange={setResource}
        />
        <div className="grid-2">
          <div>
            <TextArea label={t('mod.modeller2.compareBefore')} hint={t('mod.modeller2.compareSideHint')} value={before} onChange={setBefore} rows={10} spellCheck={false} />
            <FileLoad id="model-compare-before-file" label={t('mod.modeller2.compareLoadBefore')} onText={setBefore} />
          </div>
          <div>
            <TextArea label={t('mod.modeller2.compareAfter')} hint={t('mod.modeller2.compareSideHint')} value={after} onChange={setAfter} rows={10} spellCheck={false} />
            <FileLoad id="model-compare-after-file" label={t('mod.modeller2.compareLoadAfter')} onText={setAfter} />
            {current !== undefined && (
              <button type="button" onClick={() => setAfter(current)}>
                {t('mod.modeller2.compareUseCurrent')}
              </button>
            )}
          </div>
        </div>
        <div className="toolbar">
          <button type="submit" className="primary" disabled={!canRun}>
            {t('mod.modeller2.compareRun')}
          </button>
        </div>
      </form>
      {unreadable ? (
        <Notice tone="bad">
          <strong>{t('mod.modeller2.compareUnreadable')}</strong>
          {act.error?.message && <span className="small"> {t('mod.modeller2.compareServerSaid', { detail: act.error.message })}</span>}
        </Notice>
      ) : (
        <ProblemAlert error={act.error} />
      )}
      {report && <ModelDiffView report={report} />}
    </Section>
  );
}
