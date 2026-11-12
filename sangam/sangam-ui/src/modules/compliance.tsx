// SPDX-License-Identifier: Apache-2.0
/**
 * sangam-compliance screens (contract /compliance/**; DPDP Act 2023 and Rules 2025, CERT-In, GOV-4):
 * purposes and consent notices, consent register per data principal (give / withdraw, consent-manager artefacts),
 * rights requests, personal-data breaches and reportable incidents, lawful-authority requests (verify → approve →
 * respond / reject), contacts (DPO, grievance officer, CERT-In PoC), impact assessments and audit evidence,
 * cross-border transfer evaluation, log sealing and integrity verification, and the **compliance evidence report**.
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { download, http } from '../api/client';
import { useAction, useAsync } from '../lib/hooks';
import { Checkbox, JsonView, KeyValues, Notice, ProblemAlert, Section, TextField } from '../components/ui';
import { ModulePage, c, f, type ScreenDef } from './engine';

const purposes: ScreenDef = {
  id: 'purposes',
  title: 'mod.compliance.purposes',
  list: '/compliance/purposes',
  unpaged: true,
  // PurposeDefinition fields (PurposeDefinition.java:27): the basis is basis, not lawfulBasis
  columns: [c('code'), c('names', 'fld.names'), c('basis', 'fld.lawfulBasis'), c('retention'), c('active')],
  rowKey: ['code'],
  toolbar: [
    // M-051: PurposeDefinition(code, names {lang: text}, descriptions, dataItems, basis, ...) (PurposeDefinition.java:27); basis is a legal
    // basis code of the tenant's jurisdiction pack (PurposeBindingGuard.java:64-65); owner 2026-11-12 #49: no fixed list in the UI
    { id: 'define', label: 'act.definePurpose', method: 'PUT', path: '/compliance/purposes/{code}', fields: [f.text('code', undefined, true), f.json('names', 'fld.names', true), f.text('basis', 'fld.lawfulBasis', true, { description: 'mod.compliance.basisHint' }), f.text('retention')] },
    // M-051: the server reads {purposes} only (ComplianceRequests.java:18, ComplianceController.java:106-111); the key is the path
    { id: 'notice', label: 'act.publishNotice', path: '/compliance/notices/{key}/publish', fields: [f.path(f.text('key', 'fld.noticeKey', true)), f.json('purposes', 'privacy.purposeCodes', true)], showResult: true, noJson: true, hint: 'mod.compliance.noticeHint' },
  ],
};

const consents: ScreenDef = {
  id: 'consents',
  title: 'mod.compliance.consents',
  description: 'mod.compliance.consentsDesc',
  list: '/compliance/consents',
  unpaged: true,
  filters: [f.text('subject', 'fld.principalRef', true)],
  requireFilters: ['subject'],
  columns: [c('id'), c('noticeKey'), c('noticeVersion'), c('purposeCodes'), c('status'), c('givenAt'), c('withdrawnAt')],
  rowKey: ['id', 'consentId'],
  create: {
    id: 'give',
    label: 'act.recordConsent',
    path: '/compliance/consents',
    fields: [f.text('principalRef'), f.text('noticeKey', undefined, true), f.num('noticeVersion'), f.select('language', ['en', 'hi'], 'fld.language', false, 'lang.'), f.json('purposeCodes', undefined, true), f.bool('affirmativeAction', 'fld.affirmativeAction'), f.text('channel'), f.text('guardianVerificationId')],
    hint: 'mod.compliance.consentHint',
  },
  // M-051: WithdrawConsentRequest(purposes) (ComplianceRequests.java:27)
  actions: [{ id: 'withdraw', label: 'act.withdrawConsent', path: '/compliance/consents/{id}/withdraw', fields: [f.json('purposes', 'fld.purposeCodes')], reason: 'body' }],
};

const rights: ScreenDef = {
  id: 'rights',
  title: 'mod.compliance.rights',
  list: '/compliance/rights-requests',
  unpaged: true,
  filters: [f.text('subject', 'fld.principalRef', true)],
  requireFilters: ['subject'],
  columns: [c('id'), c('right'), c('status'), c('submittedAt'), c('dueAt')],
  rowKey: ['id', 'requestId'],
  detail: '/compliance/rights-requests/{id}',
  create: { id: 'submit', label: 'act.submitRightsRequest', path: '/compliance/rights-requests', fields: [f.text('principalRef'), f.select('right', ['ACCESS', 'CORRECTION', 'COMPLETION', 'UPDATE', 'ERASURE', 'GRIEVANCE', 'NOMINATION'], undefined, true), f.text('identifierType'), f.text('identifierValue', undefined, false, { sensitive: true }), f.text('nomineeRef'), f.text('channel'), f.json('details')] },
};

const breaches: ScreenDef = {
  id: 'breaches',
  title: 'mod.compliance.breaches',
  description: 'mod.compliance.breachesDesc',
  list: '/compliance/breaches/{id}',
  unpaged: true,
  filters: [f.text('id', 'fld.breachId', true)],
  requireFilters: ['id'],
  emptyHint: 'mod.compliance.lookupById',
  columns: [c('key', 'fld.field'), c('value', 'fld.value')],
  rowKey: ['key'],
  toolbar: [
    { id: 'breach', label: 'act.registerBreach', path: '/compliance/breaches', fields: [f.dt('awareAt', undefined, true), f.text('incidentType'), f.json('facts', 'fld.facts', true), f.json('subjects'), f.json('roots')], showResult: true, hint: 'mod.compliance.breachHint' },
    // M-051: IncidentRequest(authority, type, occurredAt, noticedAt, systems, description) (ComplianceRequests.java:34); required
    // authority, type, occurredAt (sangam-api.yaml ComplianceIncidentRequest)
    { id: 'incident', label: 'act.registerIncident', path: '/compliance/incidents', fields: [f.text('authority', undefined, true), f.text('type', 'fld.incidentType', true), f.dt('occurredAt', 'fld.occurredAt', true), f.dt('noticedAt', undefined, true), f.json('systems', 'fld.affectedSystems'), f.area('description', 'fld.summary', true)], showResult: true, hint: 'mod.compliance.incidentHint' },
    // M-051: SubmitIncidentRequest(actionsTaken) (ComplianceRequests.java:38); the incident id is the path only
    { id: 'submitIncident', label: 'act.submitIncidentReport', path: '/compliance/incidents/{incidentId}/submit', fields: [f.path(f.text('incidentId', undefined, true)), f.area('actionsTaken', 'fld.actionsTaken')], showResult: true },
  ],
};

const lawful: ScreenDef = {
  id: 'lawful',
  title: 'mod.compliance.lawful',
  description: 'mod.compliance.lawfulDesc',
  list: '/compliance/lawful-requests/{id}',
  unpaged: true,
  filters: [f.text('id', 'fld.requestId', true)],
  requireFilters: ['id'],
  emptyHint: 'mod.compliance.lookupById',
  columns: [c('key', 'fld.field'), c('value', 'fld.value')],
  rowKey: ['key'],
  toolbar: [
    // M-051: LawfulRequestBody(authority, officerName, officerDesignation, basis, reference, scope: text, dueAt) (ComplianceRequests.java:41);
    // authority and reference required (LawfulRequestService.java:51-52), basis a code of the jurisdiction pack (:53)
    { id: 'receive', label: 'act.receiveLawful', path: '/compliance/lawful-requests', fields: [f.text('authority', undefined, true), f.text('officerName', 'fld.officerName'), f.text('officerDesignation', 'fld.officerDesignation'), f.text('basis', 'fld.legalProvision', true), f.text('reference', 'fld.orderRef', true), f.area('scope', undefined, true), f.dt('dueAt')], showResult: true },
    // M-051: VerifyLawfulRequest(rankAdequate) (ComplianceRequests.java:45)
    { id: 'verify', label: 'act.verifyLawful', path: '/compliance/lawful-requests/{id}/verify', fields: [f.bool('rankAdequate', 'fld.rankAdequate')] },
    { id: 'approve', label: 'act.approve', path: '/compliance/lawful-requests/{id}/approve', noJson: true, hint: 'mod.compliance.lawfulApproveHint' },
    // M-051: RespondLawfulRequest(responseRef, principalRefs, dataCategories) (ComplianceRequests.java:48); responseRef required
    { id: 'respond', label: 'act.respond', path: '/compliance/lawful-requests/{id}/respond', fields: [f.text('responseRef', 'fld.responseRef', true), f.json('principalRefs', 'fld.disclosedRefs'), f.json('dataCategories', 'fld.categories')] },
    { id: 'reject', label: 'act.reject', path: '/compliance/lawful-requests/{id}/reject', reason: 'body', danger: true },
  ],
};

const contacts: ScreenDef = {
  id: 'contacts',
  title: 'mod.compliance.contacts',
  list: '/compliance/contacts',
  unpaged: true,
  columns: [c('role'), c('name'), c('email'), c('phone')],
  rowKey: ['role'],
  toolbar: [{ id: 'set', label: 'act.setContact', path: '/compliance/contacts/{role}', fields: [f.select('role', ['DPO', 'GRIEVANCE_OFFICER', 'CERT_IN_POC', 'CISO'], undefined, true), f.text('name', undefined, true), f.text('email', undefined, true), f.text('phone')] }],
};

const evidence: ScreenDef = {
  id: 'evidence',
  title: 'mod.compliance.evidence',
  list: '/compliance/jurisdiction',
  unpaged: true,
  columns: [c('key', 'fld.field'), c('value', 'fld.value')],
  rowKey: ['key'],
  description: 'mod.compliance.evidenceDesc',
  toolbar: [
    // M-051: ImpactAssessmentRequest(activityId, scope, risks, measures, residualRisk, assessor, independent, assessedAt, authorityReportRef)
    // (ComplianceRequests.java:59); scope and assessor required (AccountabilityService.java:85-86)
    { id: 'dpia', label: 'act.recordDpia', path: '/compliance/evidence/impact-assessments', fields: [f.text('scope', 'fld.system', true), f.text('assessor', undefined, true), f.dt('assessedAt', 'fld.assessedOn'), f.json('risks', undefined, true), f.json('measures'), f.text('authorityReportRef', 'fld.documentRef')], showResult: true },
    // M-051: AuditEvidenceRequest(kind, periodFrom, periodTo, completedAt, auditor, accreditationRef, independent, findings, reportHash,
    // authorityReportRef) (ComplianceRequests.java:54); kind is an evidence kind of the jurisdiction pack, auditor and reportHash are
    // required, and a kind may require independence or an accreditation reference (AccountabilityService.java:106-118)
    { id: 'audit', label: 'act.recordAudit', path: '/compliance/evidence/audits', fields: [f.text('kind', 'fld.evidenceKind', true), f.text('auditor', undefined, true), f.dt('periodFrom', 'fld.from'), f.dt('periodTo', 'fld.to'), f.text('reportHash', 'fld.reportHash', true), f.bool('independent', 'fld.independent'), f.text('accreditationRef', 'fld.accreditationRef'), f.json('findings')], showResult: true },
    // M-051: TransferRequest(destinationCountry, recipient, purposeCode, dataCategories, ...) (CrossBorderTransferGuard.java:37)
    { id: 'transfer', label: 'act.evaluateTransfer', path: '/compliance/transfers/evaluate', fields: [f.text('destinationCountry', undefined, true), f.json('dataCategories', 'fld.categories', true), f.text('purposeCode', undefined, true)], showResult: true },
    // M-051: SealLogsRequest(from, to) (ComplianceRequests.java:64); from required (ComplianceController.java:348-352), to defaults to now
    { id: 'seal', label: 'act.sealLogs', path: '/compliance/logs/segments/seal', fields: [f.dt('from', undefined, true), f.dt('to', 'fld.upTo')], showResult: true },
    { id: 'verifyLogs', label: 'act.verifyLogs', method: 'GET', path: '/compliance/logs/verify', showResult: true, noJson: true },
  ],
};

/** The compliance evidence report (DPDP / CERT-In), with hash; downloadable as JSON or HTML. */
export function EvidenceReport() {
  const { t } = useTranslation();
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [verifyChains, setVerify] = useState(true);
  const [report, setReport] = useState<{ reportId?: string; hash?: string; content?: unknown; html?: string }>();
  const act = useAction();
  const q = { from: from || undefined, to: to || undefined, verifyChains };
  return (
    <Section title={t('mod.compliance.report')}>
      <p className="hint">{t('mod.compliance.reportHint')}</p>
      <div className="filters">
        <TextField label={t('common.from')} type="date" value={from} onChange={setFrom} />
        <TextField label={t('common.to')} type="date" value={to} onChange={setTo} />
        <Checkbox label={t('mod.compliance.verifyChains')} checked={verifyChains} onChange={setVerify} />
        <button type="button" className="primary" disabled={act.busy} onClick={() => void act.run(() => http.get<typeof report>('/compliance/reports', { ...q, format: 'json' })).then((r) => r && setReport(r))}>
          {t('mod.compliance.generate')}
        </button>
        <button type="button" onClick={() => void act.run(() => download('/compliance/reports', { ...q, format: 'html' }, 'compliance-report.html').then(() => true))}>
          {t('mod.compliance.downloadHtml')}
        </button>
      </div>
      <ProblemAlert error={act.error} />
      {report && (
        <>
          <KeyValues items={[[t('mod.compliance.reportId'), report.reportId], [t('audit.hash'), <code key="h" className="hash">{report.hash}</code>]]} />
          <Notice>{t('mod.compliance.reportHashHint')}</Notice>
          <JsonView value={report.content ?? report} />
        </>
      )}
    </Section>
  );
}

export function CompliancePage() {
  return <ModulePage title="mod.compliance.title" description="mod.compliance.desc" screens={[purposes, consents, rights, breaches, lawful, contacts, evidence]} extra={[{ id: 'report', label: 'mod.compliance.report', node: <EvidenceReport /> }, { id: 'privacy', label: 'privacy.title', node: <PrivacyAdmin /> }]} />;
}

/** RenderedNotice (ConsentNoticeService.java:45-46; language: JurisdictionPack.Language, JurisdictionPack.java:39). */
export interface RenderedNotice {
  noticeKey: string;
  version: number;
  language?: { code?: string; name?: string; nativeName?: string; script?: string; rtl?: boolean };
  purposeCodes?: string[];
  text?: string;
  html?: string;
  contentHash?: string;
  fallback?: boolean;
}

/**
 * A rendered notice in its own language and direction (lang / dir from the server's language), as plain text: the
 * server also sends `html`, but the page never injects markup it did not build (CSP script-src 'self', X.4.7).
 */
export function NoticeView({ notice }: { notice: RenderedNotice }) {
  const { t } = useTranslation();
  const lang = notice.language?.code;
  return (
    <>
      <KeyValues
        items={[
          [t('privacy.version'), String(notice.version)],
          [t('privacy.language'), notice.language?.nativeName ?? notice.language?.name ?? lang ?? '—'],
          [t('privacy.purposes'), (notice.purposeCodes ?? []).join(', ') || '—'],
          [t('privacy.contentHash'), <code key="h" className="hash">{notice.contentHash ?? '—'}</code>],
        ]}
      />
      {notice.fallback && <Notice tone="warn">{t('privacy.fallback')}</Notice>}
      <div className="notice-text" lang={lang} dir={notice.language?.rtl ? 'rtl' : 'auto'} style={{ whiteSpace: 'pre-wrap' }}>
        {notice.text ?? ''}
      </div>
    </>
  );
}

/**
 * M-033 (admin): the privacy policy as the public sees it (GET /compliance/privacy-policy ?lang, public,
 * ComplianceController.java:114-120; SecurityConfig.java:80-81), a published consent notice by key and version
 * (GET /compliance/notices/{key} ?version&lang, :98-104), and publishing a new notice version from purposes
 * (POST /compliance/notices/{key}/publish {purposes}, compliance.admin, :106-111; ComplianceRequests.java:18). The policy
 * itself has no edit endpoint: it is rendered from the active purposes, the activity register and the published
 * contacts (ConsentNoticeService.java:198), so it is edited through those.
 */
export function PrivacyAdmin() {
  const { t, i18n } = useTranslation();
  const [lang, setLang] = useState(i18n.language);
  const [key, setKey] = useState('');
  const [version, setVersion] = useState('');
  const [q, setQ] = useState<{ lang: string } | null>(null);
  const [nq, setNq] = useState<{ key: string; version?: string; lang: string } | null>(null);
  const policy = useAsync((signal) => http.get<RenderedNotice>('/compliance/privacy-policy', { lang: q?.lang || undefined }, signal), [JSON.stringify(q)], !!q);
  const notice = useAsync((signal) => http.get<RenderedNotice>(`/compliance/notices/${encodeURIComponent(nq?.key ?? '')}`, { version: nq?.version || undefined, lang: nq?.lang || undefined }, signal), [JSON.stringify(nq)], !!nq);
  return (
    <>
      <Section title={t('privacy.policy')}>
        <p className="hint">{t('privacy.policyHint')}</p>
        <form className="filters" onSubmit={(e) => (e.preventDefault(), setQ({ lang }))}>
          <TextField label={t('privacy.languageCode')} value={lang} onChange={setLang} />
          <button type="submit">{t('privacy.preview')}</button>
        </form>
        <ProblemAlert error={policy.error} onRetry={policy.reload} />
        {policy.data && <NoticeView notice={policy.data} />}
      </Section>
      <Section title={t('privacy.notice')}>
        <form className="filters" onSubmit={(e) => (e.preventDefault(), setNq({ key: key.trim(), version: version.trim(), lang }))}>
          <TextField label={t('fld.noticeKey')} required value={key} onChange={setKey} />
          <TextField label={t('privacy.version')} type="number" min={1} value={version} onChange={setVersion} hint={t('privacy.versionHint')} />
          <button type="submit" disabled={!key.trim()}>{t('common.open')}</button>
        </form>
        <ProblemAlert error={notice.error} onRetry={notice.reload} />
        {notice.data && <NoticeView notice={notice.data} />}
      </Section>
    </>
  );
}
