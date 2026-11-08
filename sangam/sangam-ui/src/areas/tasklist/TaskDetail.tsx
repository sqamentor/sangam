// SPDX-License-Identifier: Apache-2.0
/**
 * S08 task detail and decision, S07 "put up to" selection, S09 return / resubmit / version compare, R47 forms,
 * attachments and comments.
 * - Decisions carry payloadHash + version (VI.4 rule 5, R43); 409 FORM_VERSION_STALE / APPROVAL_INVALIDATED show
 *   the stale banner with a field diff, keep the user's input and disable submit until acknowledged (CX-2).
 * - Maker-checker hints (IX.5 rule 5) disable approve and say why; the server re-checks in the command transaction.
 * - The acting seat (S02) is sent with every command so the audit records "acted for" (IX.5 rule 4).
 */
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import { ApiError } from '../../api/client';
import type { CompleteTaskResult, FieldChange, TaskAction, TaskDetail as TD } from '../../api/types';
import { announce, useAction, useAsync } from '../../lib/hooks';
import { fmtDate, fmtMoney, fmtNumber } from '../../i18n';
import { BpmnDiagram } from '../../components/BpmnDiagram';
import { classifyFormKey, externalFormUrl, taskFormToSchema } from '../../components/embeddedForm';
import { PluginSlot } from '../../plugins/host';
import { AddVariables, TaskDates } from '../../modules/operaton3';
import { FormRenderer, schemaFromVariables, validateForm, type FormValues } from '../../components/FormRenderer';
import {
  Badge,
  Checkbox,
  DataTable,
  Dialog,
  Empty,
  KeyValues,
  Loading,
  Notice,
  PageHeader,
  ProblemAlert,
  Section,
  SelectField,
  TabPanel,
  Tabs,
  TextArea,
} from '../../components/ui';
import { useAuth } from '../../auth/AuthProvider';

const DECISION_ACTIONS: TaskAction[] = ['APPROVE', 'FORWARD', 'SEEK_CLARIFICATION', 'REJECT', 'REASSIGN', 'RESUBMIT', 'COMPLETE'];

export function TaskDetail() {
  const { id = '' } = useParams();
  const { t, i18n } = useTranslation();
  const nav = useNavigate();
  const { me, seat, hasRole } = useAuth();
  const task = useAsync(() => api.tasks.get(id), [id, seat?.seatId]);
  const [values, setValues] = useState<FormValues>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [tab, setTab] = useState('form');
  const [dialog, setDialog] = useState<TaskAction | 'RETURN' | 'DELEGATE' | null>(null);
  const [stale, setStale] = useState<{ from: number; to: number; diff: FieldChange[] } | null>(null);
  const [staleAck, setStaleAck] = useState(false);
  const [result, setResult] = useState<CompleteTaskResult | null>(null);
  const [draftSaved, setDraftSaved] = useState<string>();
  const cmd = useAction();

  useEffect(() => {
    if (task.data) {
      setValues({ ...task.data.variables });
      setErrors({});
    }
  }, [task.data]);

  const d = task.data;
  const formKind = d?.form ? 'other' : classifyFormKey(d?.formKey);
  // Deployed HTML is read as data (Sangam API, task-level check, SL-37) and parsed inertly; its scripts never run.
  const embedded = useAsync(async () => taskFormToSchema(await api.tasks.form(id)), [id, d?.formKey], formKind === 'embedded');
  const schema = useMemo(() => d?.form ?? embedded.data?.schema ?? (d ? schemaFromVariables(d.variables) : { components: [] }), [d, embedded.data]);
  const externalUrl = d?.formKey && formKind === 'external' ? externalFormUrl(d.formKey, id) : null;
  const isMine = !!d && (d.assignee === me?.userId || d.section === 'MY_SEAT' || d.section === 'ACTING_FOR' || d.section === 'RETURNED');
  const canAct = isMine && !stale;

  const handleError = async (e: unknown) => {
    if (e instanceof ApiError && e.isStale && d) {
      const fresh = await api.tasks.get(id).catch(() => null);
      if (fresh) {
        const diff = await api.tasks.diff(id, d.version, fresh.version).catch(() => [] as FieldChange[]);
        setStale({ from: d.version, to: fresh.version, diff });
        setStaleAck(false);
        // keep the user's input: merge only fields the user did not touch
        task.setData({ ...fresh, variables: { ...fresh.variables, ...values } });
      }
    }
  };

  const claim = async (claimIt: boolean) => {
    const ok = await cmd.run((k) => (claimIt ? api.tasks.claim(id, k) : api.tasks.unclaim(id, k)).then(() => true));
    if (ok) {
      announce(claimIt ? t('task.claimed') : t('task.unclaimed'));
      task.reload();
    }
  };

  const saveDraft = async () => {
    if (!d) return;
    const r = await cmd.run((k) => api.tasks.saveDraft(id, values, d.formKey, d.formVersion !== undefined ? String(d.formVersion) : undefined, k));
    if (r) {
      setDraftSaved(r.savedAt);
      announce(t('task.draftSaved', { time: fmtDate(r.savedAt) }));
    } else await handleError(cmd.error);
  };

  const openDecision = (a: TaskAction) => {
    if (!d) return;
    if (a === 'APPROVE' || a === 'FORWARD' || a === 'COMPLETE' || a === 'RESUBMIT') {
      const errs = validateForm(schema, values, t);
      setErrors(errs);
      if (Object.keys(errs).length) {
        setTab('form');
        return;
      }
    }
    setDialog(a);
  };

  if (task.loading && !d) return <Loading slow={task.slow} onRetry={task.reload} />;
  if (task.error && !d)
    return (
      <>
        <PageHeader title={t('task.detailTitle')} />
        <ProblemAlert error={task.error} onRetry={task.reload} />
        {task.error instanceof ApiError && task.error.isNotAvailable && (me?.seats.length ?? 0) > 1 && <Notice>{t('task.trySeatSwitch')}</Notice>}
        <Link to="/tasks">{t('task.backToInbox')}</Link>
      </>
    );
  if (!d) return null;

  if (result) {
    return (
      <>
        <PageHeader title={t('task.doneTitle')} />
        <Notice tone={result.uncertain?.length ? 'warn' : 'ok'}>
          <p>
            {t('task.doneRef', { ref: result.reference ?? d.reference ?? d.id })} — <Badge value={result.state} />
          </p>
          {result.nextSeatName && <p>{t('task.doneNext', { seat: result.nextSeatName })}</p>}
          {result.slaDue && <p>{t('task.doneSla', { date: fmtDate(result.slaDue) })}</p>}
        </Notice>
        {(result.done?.length || result.pending?.length || result.uncertain?.length) && (
          <Section title={t('task.partialTitle')}>
            {(['done', 'pending', 'uncertain'] as const).map((k) =>
              result[k]?.length ? (
                <div key={k}>
                  <h3>{t(`task.partial.${k}`)}</h3>
                  <ul>
                    {result[k]!.map((x, i) => (
                      <li key={i}>{x}</li>
                    ))}
                  </ul>
                </div>
              ) : null,
            )}
            {result.uncertain?.length ? <Notice tone="warn">{t('task.partialUncertainAdvice')}</Notice> : null}
          </Section>
        )}
        <div className="toolbar">
          <button type="button" className="primary" onClick={() => nav('/tasks')}>
            {t('task.backToInbox')}
          </button>
          {d.businessKey && <Link to={`/audit/timeline/${encodeURIComponent(d.businessKey)}`}>{t('task.viewTimeline')}</Link>}
        </div>
      </>
    );
  }

  const actions = d.allowedActions ?? [];
  const sodBlocked = d.sod && !d.sod.allowed;

  return (
    <>
      <PageHeader
        title={`${d.reference ?? d.businessKey ?? d.id} — ${d.subject ?? d.name}`}
        description={d.description}
        actions={
          <>
            {d.claimable && !isMine && (
              <button type="button" className="primary" disabled={cmd.busy} onClick={() => void claim(true)}>
                {t('task.claim')}
              </button>
            )}
            {isMine && d.section !== 'RETURNED' && d.assignee && (
              <button type="button" disabled={cmd.busy} onClick={() => void claim(false)}>
                {t('task.unclaim')}
              </button>
            )}
            {d.businessKey && <Link to={`/audit/timeline/${encodeURIComponent(d.businessKey)}`}>{t('task.viewTimeline')}</Link>}
          </>
        }
      />

      {stale && (
        <div className="alert alert-warn" role="alert">
          <strong>{t('task.staleBanner', { from: stale.from, to: stale.to })}</strong>
          <DiffTable diff={stale.diff} />
          <Checkbox label={t('task.staleAck')} checked={staleAck} onChange={setStaleAck} />
          <button type="button" disabled={!staleAck} onClick={() => setStale(null)}>
            {t('task.staleContinue')}
          </button>
        </div>
      )}

      {sodBlocked && (
        <Notice tone="warn">
          <strong>{t('task.sodTitle')}</strong>
          <ul>
            {d.sod!.reasons.map((r) => (
              <li key={r}>{t(`sod.${r}`, { defaultValue: r, initiator: d.sod!.initiatorName ?? '', seat: d.sod!.initiatorSeatName ?? '' })}</li>
            ))}
          </ul>
          <p className="small">{t('task.sodHint')}</p>
        </Notice>
      )}
      {d.delegatedFrom && <Notice>{t('task.delegatedFrom', { name: d.delegatedFrom })}</Notice>}
      {seat && seat.kind !== 'SUBSTANTIVE' && (
        <Notice>{t('task.actingNotice', { seat: seat.seatName, name: seat.actingForPersonName ?? '' })}</Notice>
      )}

      <div className="grid-2">
        <Section title={t('task.summary')}>
          <KeyValues
            items={[
              [t('task.state'), <Badge key="s" value={d.section} />],
              [t('task.seat'), d.seatName],
              [t('task.fromSeat'), d.fromSeatName],
              [t('task.amount'), d.amount !== undefined ? fmtMoney(d.amount, d.currency) : undefined],
              [t('task.received'), fmtDate(d.received ?? d.created)],
              [t('task.slaDue'), <span key="sla">{fmtDate(d.slaDue ?? d.due)} {d.slaState && <Badge value={d.slaState} />}</span>],
              [t('task.version'), `v${d.version}`],
              [t('task.payloadHash'), <code key="h" className="hash">{d.payloadHash.slice(0, 16)}…</code>],
            ]}
          />
        </Section>
        <Section title={t('task.materialValues')}>
          {d.materialFields?.length ? (
            <KeyValues
              items={d.materialFields.map((m) => [
                m.label,
                <span key={m.key}>
                  {String(m.value ?? '—')}{' '}
                  {d.changedSinceLastSeen?.some((c) => c.field === m.key) && <Badge value="CHANGED" tone="warn" />}
                </span>,
              ])}
            />
          ) : (
            <p className="muted">{t('task.noMaterial')}</p>
          )}
          {d.ruleEvidence?.length ? (
            <>
              <h3>{t('task.ruleEvidence')}</h3>
              <ul className="small">
                {d.ruleEvidence.map((r, i) => (
                  <li key={i}>
                    {r.rule} v{r.version}: <strong>{r.result}</strong>
                  </li>
                ))}
              </ul>
            </>
          ) : null}
        </Section>
      </div>

      <Tabs
        label={t('task.tabs')}
        active={tab}
        onChange={setTab}
        tabs={[
          { id: 'form', label: t('task.tab.form') },
          { id: 'attachments', label: t('task.tab.attachments') },
          { id: 'comments', label: t('task.tab.comments') },
          { id: 'versions', label: t('task.tab.versions') },
          { id: 'lifecycle', label: t('task.tab.lifecycle') },
          ...(d.processInstanceId ? [{ id: 'diagram', label: t('task.tab.diagram') }] : []),
        ]}
      />
      <TabPanel id="form" active={tab}>
        <form
          onSubmit={(e) => {
            e.preventDefault();
          }}
          lang={i18n.language}
        >
          {embedded.data?.scriptsIgnored && <Notice tone="warn">{t('task.formScriptsIgnored')}</Notice>}
          {embedded.error && <ProblemAlert error={embedded.error} />}
          {externalUrl && (
            <Notice>
              <a href={externalUrl} target="_blank" rel="noopener noreferrer">{t('task.externalForm')}</a>
            </Notice>
          )}
          <FormRenderer schema={schema} values={values} onChange={setValues} errors={errors} readOnly={!canAct} changed={d.changedSinceLastSeen} />
          {canAct && !d.form && <AddVariables values={values} onChange={setValues} />}
          {canAct && (
            <div className="toolbar">
              <button type="button" onClick={() => void saveDraft()} disabled={cmd.busy}>
                {t('task.saveDraft')}
              </button>
              {draftSaved && <span className="muted">{t('task.draftSaved', { time: fmtDate(draftSaved) })}</span>}
            </div>
          )}
        </form>
      </TabPanel>
      <TabPanel id="attachments" active={tab}>
        <Attachments taskId={id} canUpload={canAct} />
      </TabPanel>
      <TabPanel id="comments" active={tab}>
        <Comments taskId={id} canComment={isMine} />
      </TabPanel>
      <TabPanel id="versions" active={tab}>
        <Versions taskId={id} processInstanceId={d.processInstanceId} current={d.version} />
      </TabPanel>
      <TabPanel id="lifecycle" active={tab}>
        <Lifecycle taskId={id} />
      </TabPanel>

      <TabPanel id="diagram" active={tab}>
        {d.processInstanceId && <TaskDiagram instanceId={d.processInstanceId} />}
      </TabPanel>

      {hasRole('PLATFORM_OPERATOR') && <TaskDates taskId={id} />}
      <PluginSlot point="task.tab" ctx={{ taskId: id }} />
      <ProblemAlert error={cmd.error} />

      {canAct && (
        <div className="decision-bar" role="group" aria-label={t('task.decisions')}>
          {DECISION_ACTIONS.filter((a) => actions.includes(a)).map((a) => (
            <button
              key={a}
              type="button"
              className={a === 'APPROVE' || a === 'RESUBMIT' || a === 'COMPLETE' ? 'primary' : a === 'REJECT' ? 'danger' : undefined}
              disabled={cmd.busy || (a === 'APPROVE' && !!sodBlocked)}
              aria-describedby={a === 'APPROVE' && sodBlocked ? 'sod-why' : undefined}
              onClick={() => openDecision(a)}
            >
              {t(`task.action.${a}`)}
            </button>
          ))}
          {actions.includes('RETURN') && d.returnTargets.length > 0 && (
            <button type="button" disabled={cmd.busy} onClick={() => setDialog('RETURN')}>
              {t('task.action.RETURN')}
            </button>
          )}
          {actions.includes('DELEGATE') && (
            <button type="button" disabled={cmd.busy} onClick={() => setDialog('DELEGATE')}>
              {t('task.action.DELEGATE')}
            </button>
          )}
          {sodBlocked && (
            <span id="sod-why" className="small muted">
              {t('task.sodDisabled')}
            </span>
          )}
        </div>
      )}

      {dialog && dialog !== 'RETURN' && dialog !== 'DELEGATE' && (
        <DecisionDialog
          task={d}
          action={dialog}
          values={values}
          onClose={() => setDialog(null)}
          onDone={(r) => {
            setDialog(null);
            setResult(r);
            announce(t('task.doneAnnounce', { ref: r.reference ?? d.reference ?? d.id }));
          }}
          onStale={(e) => {
            setDialog(null);
            void handleError(e);
          }}
        />
      )}
      {dialog === 'RETURN' && (
        <ReturnDialog
          task={d}
          onClose={() => setDialog(null)}
          onDone={(r) => {
            setDialog(null);
            setResult(r);
          }}
          onStale={(e) => {
            setDialog(null);
            void handleError(e);
          }}
        />
      )}
      {dialog === 'DELEGATE' && (
        <DelegateDialog
          task={d}
          onClose={() => setDialog(null)}
          onDone={() => {
            setDialog(null);
            announce(t('task.delegated'));
            nav('/tasks');
          }}
        />
      )}
    </>
  );
}

function TaskDiagram({ instanceId }: { instanceId: string }) {
  const { t } = useTranslation();
  const inst = useAsync(() => api.processes.instance(instanceId), [instanceId]);
  const xml = useAsync(() => api.processes.xml(inst.data!.definitionId), [inst.data?.definitionId], !!inst.data);
  const stats = useAsync(() => api.processes.instanceStatistics(instanceId), [instanceId]);
  return (
    <>
      <ProblemAlert error={inst.error ?? xml.error ?? stats.error} />
      {xml.data && <BpmnDiagram title={t('task.tab.diagram')} xml={xml.data.bpmn20Xml} stats={stats.data?.activities} mode="tokens" />}
    </>
  );
}

function DiffTable({ diff }: { diff: FieldChange[] }) {
  const { t } = useTranslation();
  if (!diff.length) return <p>{t('task.noDiff')}</p>;
  return (
    <DataTable
      caption={t('task.diffCaption')}
      rows={diff}
      rowKey={(r) => r.field}
      columns={[
        { key: 'field', header: t('task.field'), render: (r) => r.label ?? r.field },
        { key: 'from', header: t('task.from'), render: (r) => <del>{String(r.from ?? '—')}</del> },
        { key: 'to', header: t('task.to'), render: (r) => <ins>{String(r.to ?? '—')}</ins> },
        { key: 'changedBy', header: t('task.changedBy'), render: (r) => `${r.changedBy ?? '—'} ${r.changedAt ? fmtDate(r.changedAt) : ''}` },
      ]}
    />
  );
}

function DecisionDialog({
  task,
  action,
  values,
  onClose,
  onDone,
  onStale,
}: {
  task: TD;
  action: TaskAction;
  values: FormValues;
  onClose: () => void;
  onDone: (r: CompleteTaskResult) => void;
  onStale: (e: unknown) => void;
}) {
  const { t, i18n } = useTranslation();
  const cmd = useAction();
  const [remarks, setRemarks] = useState('');
  const [reason, setReason] = useState('');
  const [nextSeat, setNextSeat] = useState('');
  const [nextReason, setNextReason] = useState('');
  const [declared, setDeclared] = useState(false);
  const needsSeat = action === 'FORWARD' || action === 'REASSIGN';
  const cands = useAsync(() => api.tasks.candidates(task.id, action), [task.id, action], needsSeat);
  const proposed = cands.data?.find((c) => c.proposed);
  useEffect(() => {
    if (proposed && !nextSeat) setNextSeat(proposed.seatId);
  }, [proposed, nextSeat]);
  const chosen = cands.data?.find((c) => c.seatId === nextSeat);
  const needsReasonCode = action === 'REJECT';
  const reasons = task.rejectReasons ?? [];
  const isApproval = action === 'APPROVE';
  const remarksRequired = action === 'REJECT' || action === 'SEEK_CLARIFICATION' || action === 'REASSIGN';

  const submit = async (key: string) => {
    const body = {
      action,
      variables: values,
      payloadHash: task.payloadHash,
      version: task.version,
      reasonCode: reason || undefined,
      remarks: remarks || undefined,
      nextSeatId: nextSeat || undefined,
      nextSeatReason: chosen?.outOfHierarchy || chosen?.proposed === false ? nextReason || undefined : undefined,
    };
    try {
      if (action === 'RESUBMIT' && task.processInstanceId) {
        // R43: resubmission creates version n+1 through the contract's resubmit command.
        const v = await api.processes.resubmit(task.processInstanceId, { variables: values, reason: remarks || undefined, expectedVersion: task.version }, key);
        onDone({ state: 'RESUBMITTED', newVersion: v.version, reference: task.reference });
        return;
      }
      const r = await api.tasks.complete(task.id, body, key);
      onDone(r);
    } catch (e) {
      if (e instanceof ApiError && e.isStale) onStale(e);
      else throw e;
    }
  };

  const disabled =
    cmd.busy ||
    (remarksRequired && remarks.trim().length < 3) ||
    (needsReasonCode && !reason) ||
    (needsSeat && !nextSeat) ||
    (needsSeat && chosen && (chosen.outOfHierarchy || !chosen.proposed) && nextReason.trim().length < 3) ||
    (isApproval && !declared);

  return (
    <Dialog
      open
      wide
      title={t(`task.action.${action}`)}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button type="button" className={action === 'REJECT' ? 'danger' : 'primary'} disabled={!!disabled} onClick={() => void cmd.run((k) => submit(k))}>
            {cmd.busy ? t('common.working') : t('common.confirm')}
          </button>
        </>
      }
    >
      {isApproval && (
        <Section title={t('task.reviewSummary')}>
          <KeyValues items={(task.materialFields ?? []).map((m) => [m.label, String(m.value ?? '—')])} />
          <p className="small">
            {t('task.boundTo', { version: task.version })} <code className="hash">{task.payloadHash}</code>
          </p>
          <Checkbox label={t('task.declaration')} checked={declared} onChange={setDeclared} />
        </Section>
      )}
      {needsReasonCode && (
        <SelectField
          label={t('task.reasonCode')}
          required
          value={reason}
          onChange={setReason}
          placeholder={t('form.choose')}
          options={reasons.map((r) => ({ value: r.code, label: i18n.language === 'hi' && r.labelHi ? r.labelHi : r.label }))}
        />
      )}
      {needsSeat && (
        <fieldset className="field">
          <legend>{t('task.putUpTo')}</legend>
          {cands.loading && <Loading />}
          <ProblemAlert error={cands.error} onRetry={cands.reload} />
          {cands.data && cands.data.length === 0 && <Notice tone="warn">{t('task.noEligibleSeat')}</Notice>}
          <div className="choices">
            {cands.data?.map((c) => (
              <label key={c.seatId} className="choice">
                <input type="radio" name="nextSeat" value={c.seatId} checked={nextSeat === c.seatId} onChange={() => setNextSeat(c.seatId)} disabled={c.onLeave} />
                <strong>{c.seatName}</strong> {c.holderName && `(${c.holderName})`}
                {c.proposed && <Badge value="PROPOSED" tone="info" />}
                {c.onLeave && <Badge value="ON_LEAVE" tone="warn" />}
                {c.outOfHierarchy && <Badge value="OUT_OF_HIERARCHY" tone="warn" />}
                {c.workload !== undefined && <span className="muted small"> · {t('task.workload', { n: fmtNumber(c.workload) })}</span>}
              </label>
            ))}
          </div>
          {chosen && (chosen.outOfHierarchy || !chosen.proposed) && (
            <TextArea label={t('task.seatReason')} required value={nextReason} onChange={setNextReason} hint={t('task.seatReasonHint')} />
          )}
        </fieldset>
      )}
      <TextArea label={t('task.remarks')} required={remarksRequired} value={remarks} onChange={setRemarks} />
      <ProblemAlert error={cmd.error} />
    </Dialog>
  );
}

function ReturnDialog({ task, onClose, onDone, onStale }: { task: TD; onClose: () => void; onDone: (r: CompleteTaskResult) => void; onStale: (e: unknown) => void }) {
  const { t, i18n } = useTranslation();
  const cmd = useAction();
  const [target, setTarget] = useState(task.returnTargets[task.returnTargets.length - 1]?.seatId ?? '');
  const [codes, setCodes] = useState<string[]>([]);
  const [remarks, setRemarks] = useState('');
  const tgt = task.returnTargets.find((r) => r.seatId === target);
  return (
    <Dialog
      open
      title={t('task.action.RETURN')}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="primary"
            disabled={cmd.busy || !tgt || remarks.trim().length < 3}
            onClick={() =>
              void cmd.run(async (k) => {
                try {
                  const r = await api.tasks.return(
                    task.id,
                    { targetLevel: tgt!.level, targetSeatId: tgt!.seatId, targetActivityId: tgt!.activityId, reasonCodes: codes, remarks, payloadHash: task.payloadHash, version: task.version },
                    k,
                  );
                  onDone(r);
                } catch (e) {
                  if (e instanceof ApiError && e.isStale) onStale(e);
                  else throw e;
                }
              })
            }
          >
            {t('task.returnConfirm')}
          </button>
        </>
      }
    >
      <fieldset className="field">
        <legend>{t('task.returnTo')}</legend>
        {task.returnTargets.map((r) => (
          <label key={r.seatId} className="choice">
            <input type="radio" name="ret" checked={target === r.seatId} onChange={() => setTarget(r.seatId)} />
            {t('task.level', { n: r.level })}: <strong>{r.seatName}</strong> {r.holderName && `(${r.holderName})`}
          </label>
        ))}
      </fieldset>
      {task.returnReasons && task.returnReasons.length > 0 && (
        <fieldset className="field">
          <legend>{t('task.returnReasons')}</legend>
          {task.returnReasons.map((r) => (
            <Checkbox
              key={r.code}
              label={i18n.language === 'hi' && r.labelHi ? r.labelHi : r.label}
              checked={codes.includes(r.code)}
              onChange={(v) => setCodes(v ? [...codes, r.code] : codes.filter((c) => c !== r.code))}
            />
          ))}
        </fieldset>
      )}
      <TextArea label={t('task.remarks')} required value={remarks} onChange={setRemarks} />
      <p className="hint">{t('task.returnHint')}</p>
      <ProblemAlert error={cmd.error} />
    </Dialog>
  );
}

function DelegateDialog({ task, onClose, onDone }: { task: TD; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const cmd = useAction();
  const cands = useAsync(() => api.tasks.candidates(task.id, 'DELEGATE'), [task.id]);
  const [seatId, setSeatId] = useState('');
  const [reason, setReason] = useState('');
  const [until, setUntil] = useState('');
  return (
    <Dialog
      open
      title={t('task.action.DELEGATE')}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="primary"
            disabled={cmd.busy || !seatId || reason.trim().length < 3}
            onClick={() =>
              void cmd
                .run((k) => api.tasks.delegate(task.id, { toSeatId: seatId, reason, until: until ? new Date(until).toISOString() : undefined }, k).then(() => true))
                .then((ok) => ok && onDone())
            }
          >
            {t('common.confirm')}
          </button>
        </>
      }
    >
      <ProblemAlert error={cands.error} />
      <SelectField
        label={t('task.delegateTo')}
        required
        value={seatId}
        onChange={setSeatId}
        placeholder={t('form.choose')}
        options={(cands.data ?? []).map((c) => ({ value: c.seatId, label: `${c.seatName}${c.holderName ? ` (${c.holderName})` : ''}` }))}
      />
      <div className="field">
        <label htmlFor="deleg-until">{t('task.delegateUntil')}</label>
        <input id="deleg-until" type="datetime-local" value={until} onChange={(e) => setUntil(e.target.value)} />
      </div>
      <TextArea label={t('common.reason')} required value={reason} onChange={setReason} />
      <p className="hint">{t('task.delegateHint')}</p>
      <ProblemAlert error={cmd.error} />
    </Dialog>
  );
}

function Attachments({ taskId, canUpload }: { taskId: string; canUpload: boolean }) {
  const { t } = useTranslation();
  const list = useAsync(() => api.tasks.attachments(taskId), [taskId]);
  const up = useAction();
  return (
    <Section title={t('task.tab.attachments')}>
      <ProblemAlert error={list.error ?? up.error} onRetry={list.reload} />
      {canUpload && (
        <div className="field">
          <label htmlFor="att-upload">{t('task.upload')}</label>
          <input
            id="att-upload"
            type="file"
            multiple
            onChange={(e) => {
              const files = Array.from(e.target.files ?? []);
              e.target.value = '';
              void (async () => {
                for (const f of files) await up.run((k) => api.tasks.upload(taskId, f, k));
                announce(t('task.uploaded', { n: files.length }));
                list.reload();
              })();
            }}
          />
          {up.busy && <span aria-live="polite">{t('common.working')}</span>}
        </div>
      )}
      {list.loading && <Loading />}
      {list.data && list.data.length === 0 && <Empty message={t('task.noAttachments')} />}
      {list.data && list.data.length > 0 && (
        <DataTable
          caption={t('task.tab.attachments')}
          captionHidden
          rows={list.data}
          rowKey={(r) => r.id}
          columns={[
            {
              key: 'name',
              header: t('task.fileName'),
              render: (r) => (
                <button type="button" className="link" onClick={() => void api.tasks.downloadAttachment(taskId, r.id, r.name)}>
                  {r.name}
                </button>
              ),
            },
            { key: 'version', header: t('task.version'), render: (r) => `v${r.version}` },
            { key: 'size', header: t('task.size'), render: (r) => `${fmtNumber(r.size / 1024, 1)} KB` },
            { key: 'sha256', header: t('task.checksum'), render: (r) => <code className="hash">{r.sha256.slice(0, 12)}…</code> },
            { key: 'scan', header: t('task.scan'), render: (r) => (r.scanStatus ? <Badge value={r.scanStatus} /> : '—') },
            { key: 'by', header: t('task.uploadedBy'), render: (r) => `${r.uploadedBy}, ${fmtDate(r.uploadedAt)}` },
          ]}
        />
      )}
    </Section>
  );
}

function Comments({ taskId, canComment }: { taskId: string; canComment: boolean }) {
  const { t } = useTranslation();
  const list = useAsync(() => api.tasks.comments(taskId), [taskId]);
  const add = useAction();
  const [text, setText] = useState('');
  return (
    <Section title={t('task.tab.comments')}>
      <ProblemAlert error={list.error ?? add.error} onRetry={list.reload} />
      <ol className="comments">
        {list.data?.map((c) => (
          <li key={c.id}>
            <div className="small muted">
              {c.author}
              {c.seatName && ` · ${c.seatName}`}
              {c.actedFor && ` · ${t('task.actedFor', { name: c.actedFor })}`} · {fmtDate(c.createdAt)}
              {c.version !== undefined && ` · v${c.version}`}
            </div>
            <p>{c.text}</p>
          </li>
        ))}
      </ol>
      {list.data && list.data.length === 0 && <Empty message={t('task.noComments')} />}
      {canComment && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void add.run((k) => api.tasks.addComment(taskId, text, k)).then((r) => {
              if (r) {
                setText('');
                list.reload();
              }
            });
          }}
        >
          <TextArea label={t('task.addComment')} value={text} onChange={setText} required />
          <button type="submit" disabled={add.busy || !text.trim()}>
            {t('task.postComment')}
          </button>
        </form>
      )}
    </Section>
  );
}

function Versions({ taskId, processInstanceId, current }: { taskId: string; processInstanceId?: string; current: number }) {
  const { t } = useTranslation();
  const list = useAsync(() => (processInstanceId ? api.processes.versions(processInstanceId) : Promise.resolve([])), [processInstanceId]);
  const [from, setFrom] = useState<number>();
  const [to, setTo] = useState<number>(current);
  const diff = useAsync(() => api.tasks.diff(taskId, from!, to), [taskId, from, to], from !== undefined && from !== to);
  useEffect(() => {
    if (list.data && list.data.length > 1 && from === undefined) setFrom(list.data[list.data.length - 2].version);
  }, [list.data, from]);
  const opts = (list.data ?? []).map((v) => ({ value: String(v.version), label: `v${v.version} · ${t(`enum.${v.kind}`)} · ${fmtDate(v.createdAt)}` }));
  return (
    <Section title={t('task.tab.versions')}>
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.data && (
        <DataTable
          caption={t('task.versionHistory')}
          rows={list.data}
          rowKey={(r) => String(r.version)}
          columns={[
            { key: 'version', header: t('task.version'), render: (r) => `v${r.version}` },
            { key: 'kind', header: t('task.event'), render: (r) => <Badge value={r.kind} /> },
            {
              key: 'by',
              header: t('task.by'),
              render: (r) => (
                <>
                  {r.createdBy}
                  {r.seatName && ` · ${r.seatName}`}
                  {r.actedFor && <div className="small">{t('task.actedFor', { name: r.actedFor })}</div>}
                </>
              ),
            },
            { key: 'at', header: t('task.when'), render: (r) => fmtDate(r.createdAt) },
            { key: 'reason', header: t('common.reason'), render: (r) => r.reason ?? '—' },
            { key: 'hash', header: t('task.payloadHash'), render: (r) => <code className="hash">{r.payloadHash.slice(0, 12)}…</code> },
          ]}
        />
      )}
      {opts.length > 1 && (
        <div className="filters">
          <SelectField label={t('task.compareFrom')} value={String(from ?? '')} onChange={(v) => setFrom(Number(v))} options={opts} />
          <SelectField label={t('task.compareTo')} value={String(to)} onChange={(v) => setTo(Number(v))} options={opts} />
        </div>
      )}
      {diff.loading && <Loading />}
      <ProblemAlert error={diff.error} />
      {diff.data && <DiffTable diff={diff.data} />}
    </Section>
  );
}

function Lifecycle({ taskId }: { taskId: string }) {
  const { t } = useTranslation();
  const list = useAsync(() => api.tasks.lifecycle(taskId), [taskId]);
  return (
    <Section title={t('task.tab.lifecycle')}>
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.data && (
        <ol className="timeline">
          {list.data.map((e, i) => (
            <li key={i}>
              <time dateTime={e.at}>{fmtDate(e.at)}</time> <Badge value={e.event} tone="info" /> {e.by}
              {e.seatName && ` · ${e.seatName}`}
              {e.actedFor && ` · ${t('task.actedFor', { name: e.actedFor })}`}
            </li>
          ))}
        </ol>
      )}
    </Section>
  );
}
