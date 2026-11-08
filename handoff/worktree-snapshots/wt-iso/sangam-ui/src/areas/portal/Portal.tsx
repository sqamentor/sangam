// SPDX-License-Identifier: Apache-2.0
/**
 * Citizen / applicant portal (C01–C06, UP-22, R76): service catalogue, application form with DPDP notice and consent,
 * status in plain words with SLA date, clarification replies with documents, withdraw, outcome download, appeal,
 * fee/payment status (pay disabled while UNKNOWN_OUTCOME), grievances and data-rights requests.
 * Officer names are never shown (seat designation only). Forms use the Sangam renderer (no watermark question, PK-11).
 */
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import type { FormSchema } from '../../api/types';
import { announce, useAction, useAsync, usePaged } from '../../lib/hooks';
import { fmtDate, fmtMoney } from '../../i18n';
import { FormRenderer, validateForm, type FormValues } from '../../components/FormRenderer';
import { Badge, Checkbox, DataTable, Empty, KeyValues, Loading, Notice, PageHeader, Pagination, ProblemAlert, ReasonDialog, Section, SelectField, TextArea, TextField } from '../../components/ui';

export function PortalHome() {
  const { t, i18n } = useTranslation();
  const services = useAsync(() => api.portal.services(), []);
  const hi = i18n.language === 'hi';
  return (
    <>
      <PageHeader title={t('portal.title')} description={t('portal.description')} />
      <Notice>{t('portal.assisted')}</Notice>
      <ProblemAlert error={services.error} onRetry={services.reload} />
      {services.loading && <Loading />}
      {services.data?.length === 0 && <Empty message={t('portal.noServices')} />}
      <ul className="cards">
        {services.data?.map((s) => (
          <li key={s.key} className="card">
            <h2>{hi && s.nameHi ? s.nameHi : s.name}</h2>
            <p>{hi && s.descriptionHi ? s.descriptionHi : s.description}</p>
            <KeyValues
              items={[
                [t('portal.sla'), s.slaDays !== undefined ? t('portal.slaDays', { n: s.slaDays }) : undefined],
                [t('portal.fee'), s.fee !== undefined ? fmtMoney(s.fee) : t('portal.noFee')],
              ]}
            />
            {s.documentsRequired && s.documentsRequired.length > 0 && (
              <details>
                <summary>{t('portal.documentsRequired')}</summary>
                <ul>
                  {s.documentsRequired.map((d) => (
                    <li key={d}>{d}</li>
                  ))}
                </ul>
              </details>
            )}
            <Link className="button primary" to={`/portal/apply/${encodeURIComponent(s.key)}`}>
              {t('portal.apply')}
            </Link>
          </li>
        ))}
      </ul>
    </>
  );
}

export function PortalApply() {
  const { serviceKey = '' } = useParams();
  const { t, i18n } = useTranslation();
  const nav = useNavigate();
  const services = useAsync(() => api.portal.services(), []);
  const svc = services.data?.find((s) => s.key === serviceKey);
  const form = useAsync(() => (svc ? api.forms.get(svc.formKey) : Promise.resolve<FormSchema>({ components: [] })), [svc?.formKey], !!svc);
  const [values, setValues] = useState<FormValues>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [consent, setConsent] = useState(false);
  const act = useAction();
  if (services.loading || (svc && form.loading)) return <Loading />;
  if (!svc) return <ProblemAlert error={services.error ?? new Error(t('error.notAvailable'))} />;
  const submit = (final: boolean) => {
    if (final && form.data) {
      const errs = validateForm(form.data, values, t);
      setErrors(errs);
      if (Object.keys(errs).length) return;
    }
    void act.run((k) => api.portal.apply(serviceKey, values, final, k)).then((a) => {
      if (a) {
        announce(final ? t('portal.submitted', { ref: a.reference }) : t('portal.draftSaved'));
        nav(`/portal/applications/${encodeURIComponent(a.id)}`);
      }
    });
  };
  return (
    <>
      <PageHeader title={i18n.language === 'hi' && svc.nameHi ? svc.nameHi : svc.name} />
      <Section title={t('portal.dpdpTitle')}>
        <p>{t('portal.dpdpNotice')}</p>
        <Checkbox label={t('portal.consent')} checked={consent} onChange={setConsent} />
      </Section>
      <ProblemAlert error={form.error ?? act.error} />
      {form.data && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            submit(true);
          }}
          noValidate
        >
          <FormRenderer schema={form.data} values={values} onChange={setValues} errors={errors} />
          {svc.fee !== undefined && svc.fee > 0 && <Notice>{t('portal.feeNotice', { fee: fmtMoney(svc.fee) })}</Notice>}
          <div className="toolbar">
            <button type="button" disabled={act.busy} onClick={() => submit(false)}>
              {t('portal.saveDraft')}
            </button>
            <button type="submit" className="primary" disabled={!consent || act.busy}>
              {t('portal.submit')}
            </button>
          </div>
        </form>
      )}
    </>
  );
}

export function PortalApplications() {
  const { t } = useTranslation();
  const list = usePaged((p) => api.portal.mine(p), []);
  return (
    <>
      <PageHeader title={t('portal.myApplications')} />
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.loading && !list.data && <Loading />}
      {list.data?.items.length === 0 && <Empty message={t('portal.noApplications')} action={<Link to="/portal">{t('nav.services')}</Link>} />}
      {list.data && list.data.items.length > 0 && (
        <DataTable
          caption={t('portal.myApplications')}
          captionHidden
          rows={list.data.items}
          rowKey={(r) => r.id}
          columns={[
            { key: 'reference', header: t('task.reference'), render: (r) => <Link to={`/portal/applications/${encodeURIComponent(r.id)}`}>{r.reference}</Link> },
            { key: 'service', header: t('portal.service'), render: (r) => r.serviceName },
            { key: 'status', header: t('admin.status'), render: (r) => <Badge value={r.status} /> },
            { key: 'sla', header: t('portal.expectedBy'), render: (r) => fmtDate(r.slaDate, false) },
            { key: 'submitted', header: t('portal.submittedOn'), render: (r) => fmtDate(r.submittedAt) },
          ]}
        />
      )}
      <Pagination data={list.data} page={list.page} size={list.size} setPage={list.setPage} setSize={list.setSize} />
    </>
  );
}

export function PortalApplication() {
  const { id = '' } = useParams();
  const { t, i18n } = useTranslation();
  const app = useAsync(() => api.portal.get(id), [id]);
  const [reply, setReply] = useState<{ cid: string; text: string; files: File[] } | null>(null);
  const [withdraw, setWithdraw] = useState(false);
  const [appeal, setAppeal] = useState(false);
  const [grounds, setGrounds] = useState('');
  const act = useAction();
  if (app.loading && !app.data) return <Loading />;
  if (!app.data) return <ProblemAlert error={app.error} onRetry={app.reload} />;
  const a = app.data;
  const stage = (i18n.language === 'hi' ? a.stageLabelHi : a.stageLabel) ?? a.stage;
  const open = a.clarifications.filter((c) => !c.reply);
  const appealOpen = a.status === 'REJECTED' && a.appealDeadline && new Date(a.appealDeadline) > new Date();
  return (
    <>
      <PageHeader
        title={`${a.serviceName} · ${a.reference}`}
        actions={
          <>
            {(a.status === 'SUBMITTED' || a.status === 'IN_PROGRESS' || a.status === 'CLARIFICATION_SOUGHT') && (
              <button type="button" onClick={() => setWithdraw(true)}>
                {t('portal.withdraw')}
              </button>
            )}
            {a.outcomeDocumentId && (
              <button type="button" className="primary" onClick={() => void act.run(() => api.portal.outcome(id).then(() => true))}>
                {t('portal.downloadOutcome')}
              </button>
            )}
            {appealOpen && (
              <button type="button" onClick={() => setAppeal(true)}>
                {t('portal.fileAppeal')}
              </button>
            )}
          </>
        }
      />
      <ProblemAlert error={act.error} />
      <Section title={t('portal.statusTitle')}>
        <p className="lead">{stage}</p>
        <KeyValues
          items={[
            [t('admin.status'), <Badge key="s" value={a.status} />],
            [t('portal.expectedBy'), fmtDate(a.slaDate, false)],
            [t('portal.submittedOn'), fmtDate(a.submittedAt)],
            [t('portal.payment'), a.paymentStatus ? <Badge key="p" value={a.paymentStatus} /> : undefined],
            [t('portal.appealBy'), a.appealDeadline ? fmtDate(a.appealDeadline, false) : undefined],
          ]}
        />
        {a.paymentStatus === 'UNKNOWN_OUTCOME' && <Notice tone="warn">{t('portal.paymentUnknown')}</Notice>}
      </Section>
      {open.length > 0 && <Notice tone="warn">{t('portal.clarificationNeeded', { n: open.length })}</Notice>}
      <Section title={t('portal.clarifications')}>
        {a.clarifications.length === 0 && <Empty message={t('portal.noClarifications')} />}
        <ol className="comments">
          {a.clarifications.map((c) => (
            <li key={c.id}>
              <div className="small muted">
                {c.askedBySeat ?? t('portal.office')} · {fmtDate(c.askedAt)}
                {c.dueAt && ` · ${t('portal.replyBy', { date: fmtDate(c.dueAt, false) })}`}
              </div>
              <p>{c.question}</p>
              {c.reply ? (
                <blockquote>
                  {c.reply} <span className="small muted">({fmtDate(c.repliedAt)})</span>
                </blockquote>
              ) : (
                <button type="button" className="primary" onClick={() => setReply({ cid: c.id, text: '', files: [] })}>
                  {t('portal.reply')}
                </button>
              )}
            </li>
          ))}
        </ol>
        {reply && (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void act.run((k) => api.portal.reply(id, reply.cid, reply.text, reply.files, k).then(() => true)).then((ok) => {
                if (ok) {
                  setReply(null);
                  announce(t('portal.replySent'));
                  app.reload();
                }
              });
            }}
          >
            <TextArea label={t('portal.yourReply')} required value={reply.text} onChange={(v) => setReply({ ...reply, text: v })} />
            <div className="field">
              <label htmlFor="reply-files">{t('portal.attachDocs')}</label>
              <input id="reply-files" type="file" multiple onChange={(e) => setReply({ ...reply, files: Array.from(e.target.files ?? []) })} />
            </div>
            <div className="toolbar">
              <button type="button" onClick={() => setReply(null)}>
                {t('common.cancel')}
              </button>
              <button type="submit" className="primary" disabled={!reply.text.trim() || act.busy}>
                {t('common.send')}
              </button>
            </div>
          </form>
        )}
      </Section>
      {a.messages && a.messages.length > 0 && (
        <Section title={t('portal.messages')}>
          <ul>
            {a.messages.map((m, i) => (
              <li key={i}>
                <span className="small muted">{fmtDate(m.at)}</span> {m.text}
              </li>
            ))}
          </ul>
        </Section>
      )}
      <ReasonDialog
        open={withdraw}
        danger
        title={t('portal.withdraw')}
        busy={act.busy}
        error={act.error}
        onClose={() => setWithdraw(false)}
        onConfirm={(reason) =>
          void act.run((k) => api.portal.withdraw(id, reason, k).then(() => true)).then((ok) => {
            if (ok) {
              setWithdraw(false);
              app.reload();
            }
          })
        }
      >
        <Notice tone="warn">{t('portal.withdrawHint')}</Notice>
      </ReasonDialog>
      <ReasonDialog
        open={appeal}
        title={t('portal.fileAppeal')}
        busy={act.busy}
        error={act.error}
        onClose={() => setAppeal(false)}
        onConfirm={() =>
          void act.run((k) => api.portal.appeal(id, grounds, k).then(() => true)).then((ok) => {
            if (ok) {
              setAppeal(false);
              announce(t('portal.appealFiled'));
              app.reload();
            }
          })
        }
      >
        <TextArea label={t('portal.grounds')} required value={grounds} onChange={setGrounds} />
      </ReasonDialog>
    </>
  );
}

export function PortalGrievances() {
  const { t } = useTranslation();
  const list = useAsync(() => api.portal.grievances(), []);
  const [form, setForm] = useState({ subject: '', text: '', kind: 'GRIEVANCE' as 'GRIEVANCE' | 'DATA_RIGHTS' });
  const [ref, setRef] = useState<string>();
  const act = useAction();
  return (
    <>
      <PageHeader title={t('portal.grievancesTitle')} description={t('portal.grievancesDesc')} />
      <ProblemAlert error={list.error ?? act.error} onRetry={list.reload} />
      {ref && <Notice tone="ok">{t('portal.grievanceFiled', { ref })}</Notice>}
      {list.data && list.data.length > 0 && (
        <DataTable
          caption={t('portal.grievancesTitle')}
          captionHidden
          rows={list.data}
          rowKey={(r) => r.id}
          columns={[
            { key: 'reference', header: t('task.reference') },
            { key: 'subject', header: t('task.subject') },
            { key: 'status', header: t('admin.status'), render: (r) => <Badge value={r.status} /> },
            { key: 'filed', header: t('portal.submittedOn'), render: (r) => fmtDate(r.filedAt) },
            { key: 'due', header: t('portal.expectedBy'), render: (r) => fmtDate(r.dueAt, false) },
          ]}
        />
      )}
      <Section title={t('portal.newGrievance')}>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void act.run((k) => api.portal.fileGrievance(form, k)).then((r) => {
              if (r) {
                setRef(r.reference);
                setForm({ subject: '', text: '', kind: 'GRIEVANCE' });
                list.reload();
              }
            });
          }}
        >
          <SelectField label={t('portal.grievanceKind')} value={form.kind} onChange={(v) => setForm({ ...form, kind: v as typeof form.kind })} options={[{ value: 'GRIEVANCE', label: t('portal.kindGrievance') }, { value: 'DATA_RIGHTS', label: t('portal.kindDataRights') }]} />
          <TextField label={t('task.subject')} required value={form.subject} onChange={(v) => setForm({ ...form, subject: v })} />
          <TextArea label={t('portal.details')} required value={form.text} onChange={(v) => setForm({ ...form, text: v })} />
          {form.kind === 'DATA_RIGHTS' && <Notice>{t('portal.dataRightsHint')}</Notice>}
          <button type="submit" className="primary" disabled={!form.subject || !form.text || act.busy}>
            {t('common.submit')}
          </button>
        </form>
      </Section>
    </>
  );
}
