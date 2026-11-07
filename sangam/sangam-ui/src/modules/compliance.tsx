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
import { useAction } from '../lib/hooks';
import { Checkbox, JsonView, KeyValues, Notice, ProblemAlert, Section, TextField } from '../components/ui';
import { ModulePage, c, f, type ScreenDef } from './engine';

const purposes: ScreenDef = {
  id: 'purposes',
  title: 'mod.compliance.purposes',
  list: '/compliance/purposes',
  unpaged: true,
  columns: [c('code'), c('name'), c('lawfulBasis'), c('retention'), c('active')],
  rowKey: ['code'],
  toolbar: [
    { id: 'define', label: 'act.definePurpose', method: 'PUT', path: '/compliance/purposes/{code}', fields: [f.text('code', undefined, true), f.text('name', undefined, true), f.select('lawfulBasis', ['CONSENT', 'LEGITIMATE_USE', 'LEGAL_OBLIGATION', 'STATE_FUNCTION'], undefined, true), f.text('retention'), f.json('nameI18n', 'fld.hindiLabels')] },
    { id: 'notice', label: 'act.publishNotice', path: '/compliance/notices/{key}/publish', fields: [f.text('key', 'fld.noticeKey', true), f.json('texts', 'fld.noticeTexts', true), f.json('purposeCodes', undefined, true)], showResult: true, hint: 'mod.compliance.noticeHint' },
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
  actions: [{ id: 'withdraw', label: 'act.withdrawConsent', path: '/compliance/consents/{id}/withdraw', fields: [f.json('purposeCodes')], reason: 'body' }],
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
    { id: 'incident', label: 'act.registerIncident', path: '/compliance/incidents', fields: [f.dt('noticedAt', undefined, true), f.text('type', 'fld.incidentType', true), f.json('affectedSystems'), f.area('summary', undefined, true)], showResult: true, hint: 'mod.compliance.incidentHint' },
    { id: 'submitIncident', label: 'act.submitIncidentReport', path: '/compliance/incidents/{incidentId}/submit', fields: [f.text('incidentId', undefined, true), f.text('submissionRef'), f.dt('submittedAt')], showResult: true },
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
    { id: 'receive', label: 'act.receiveLawful', path: '/compliance/lawful-requests', fields: [f.text('authority', undefined, true), f.text('legalProvision', undefined, true), f.text('orderRef', undefined, true), f.json('scope', undefined, true), f.dt('dueAt')], showResult: true },
    { id: 'verify', label: 'act.verifyLawful', path: '/compliance/lawful-requests/{id}/verify', fields: [f.text('verificationRef', undefined, true), f.area('notes')] },
    { id: 'approve', label: 'act.approve', path: '/compliance/lawful-requests/{id}/approve', noJson: true, hint: 'mod.compliance.lawfulApproveHint' },
    { id: 'respond', label: 'act.respond', path: '/compliance/lawful-requests/{id}/respond', fields: [f.json('disclosedRefs', undefined, true), f.text('channel')] },
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
    { id: 'dpia', label: 'act.recordDpia', path: '/compliance/evidence/impact-assessments', fields: [f.text('system', undefined, true), f.text('assessor', undefined, true), f.date('assessedOn', undefined, true), f.json('risks', undefined, true), f.text('documentRef')], showResult: true },
    { id: 'audit', label: 'act.recordAudit', path: '/compliance/evidence/audits', fields: [f.text('auditor', undefined, true), f.date('period', 'fld.auditDate', true), f.text('scope', undefined, true), f.text('reportRef', undefined, true), f.json('findings')], showResult: true },
    { id: 'transfer', label: 'act.evaluateTransfer', path: '/compliance/transfers/evaluate', fields: [f.text('destinationCountry', undefined, true), f.json('categories', undefined, true), f.text('purposeCode', undefined, true)], showResult: true },
    { id: 'seal', label: 'act.sealLogs', path: '/compliance/logs/segments/seal', fields: [f.dt('upTo')], showResult: true },
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
  return <ModulePage title="mod.compliance.title" description="mod.compliance.desc" screens={[purposes, consents, rights, breaches, lawful, contacts, evidence]} extra={[{ id: 'report', label: 'mod.compliance.report', node: <EvidenceReport /> }]} />;
}
