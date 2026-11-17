// SPDX-License-Identifier: Apache-2.0
/**
 * sangam-compliance screens (contract /compliance/**; DPDP Act 2023 and Rules 2025, CERT-In, GOV-4):
 * purposes and consent notices, consent register per data principal (give / withdraw, consent-manager artefacts),
 * rights requests, personal-data breaches and reportable incidents, lawful-authority requests (verify → approve →
 * respond / reject), contacts (DPO, grievance officer, CERT-In PoC), impact assessments and audit evidence,
 * cross-border transfer evaluation, log sealing and integrity verification, and the **compliance evidence report**.
 */
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { download, http } from '../api/client';
import type { FormField } from '../api/types';
import { tenantLocale } from '../i18n';
import { useAction, useAsync } from '../lib/hooks';
import { Checkbox, JsonView, KeyValues, Loading, Notice, ProblemAlert, Section, SelectField, TextField } from '../components/ui';
import { ActionDialog, ModulePage, ModuleScreen, c, f, type Act, type ScreenDef } from './engine';
import { reportPeriod } from './compliancePeriod';
import { PrincipalSearchScreen, type PrincipalSearchDef } from './complianceSearch';

/** A required Yes / No choice with no default, sent as a JSON boolean. */
export function yesNo(key: string, label = `fld.${key}`): FormField {
  return { key, label, type: 'select', json: true, values: [{ value: 'true', label: 'common.yes' }, { value: 'false', label: 'common.no' }], validate: { required: true } };
}

/**
 * Owner decision 362: a form whose attestations are explicit Yes / No choices sends each as a JSON boolean; one left
 * unanswered is refused here, as the server refuses it by name (never read as false).
 */
export function checkChoices(keys: (string | [key: string, label: string])[]): (body: Record<string, unknown>, t: (k: string, o?: Record<string, unknown>) => string) => string | undefined {
  return (body, t) => {
    for (const e of keys) {
      const [k, label] = typeof e === 'string' ? [e, `fld.${e}`] : e;
      if (typeof body[k] !== 'boolean') return t('mod.compliance.choiceRequired', { field: t(label) });
    }
    return undefined;
  };
}

/** The define-purpose form (ComplianceDefinePurposeRequest in sangam-api.yaml). */
export const purposeFields: FormField[] = [
  f.text('code', undefined, true),
  f.json('names', 'fld.names', true),
  f.text('basis', 'fld.lawfulBasis', true, { description: 'mod.compliance.basisHint' }),
  yesNo('standardsAttested'),
  yesNo('active'),
  { ...f.json('dataItems', 'fld.dataItems'), description: 'mod.compliance.dataItemsHint' },
  f.text('retention'),
];

/** Every data item names its code and says, true or false, whether it is sensitive; nothing is assumed. */
export function checkPurpose(body: Record<string, unknown>, t: (k: string, o?: Record<string, unknown>) => string): string | undefined {
  for (const k of ['standardsAttested', 'active']) if (typeof body[k] !== 'boolean') return t('mod.compliance.choiceRequired', { field: t(`fld.${k}`) });
  const items = body.dataItems;
  if (items === undefined) return undefined;
  if (!Array.isArray(items)) return t('mod.compliance.dataItemsInvalid');
  for (const x of items) {
    const it = x as { code?: unknown; sensitive?: unknown } | null;
    if (!it || typeof it !== 'object' || typeof it.code !== 'string' || !it.code || typeof it.sensitive !== 'boolean') return t('mod.compliance.dataItemsInvalid');
  }
  return undefined;
}

export const purposes: ScreenDef = {
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
    // ComplianceDefinePurposeRequest: standardsAttested, active and every data item's sensitive are explicit choices
    // (required Yes / No, no default; the server refuses an omitted one by name), and the version is the server's
    { id: 'define', label: 'act.definePurpose', method: 'PUT', path: '/compliance/purposes/by-code?code={code}', fields: purposeFields, check: checkPurpose, hint: 'mod.compliance.purposeHint' },
    // M-051: the server reads {purposes} only (ComplianceRequests.java:18, ComplianceController.java:106-111); the key is the query parameter key (decision 393)
    { id: 'notice', label: 'act.publishNotice', path: '/compliance/notices/by-key/publish?key={key}', fields: [f.path(f.text('key', 'fld.noticeKey', true)), f.json('purposes', 'privacy.purposeCodes', true)], showResult: true, noJson: true, hint: 'mod.compliance.noticeHint' },
  ],
};

/**
 * The consent register of one data principal. Owner decision 338: searched with POST /compliance/consents/search and
 * the reference in the body (complianceSearch.tsx), never ?subject= in the URL.
 */
export const consents: PrincipalSearchDef = {
  id: 'consents',
  title: 'mod.compliance.consents',
  description: 'mod.compliance.consentsDesc',
  search: '/compliance/consents/search',
  // the fields of a consent record as the server returns it (ComplianceConsent in sangam-api.yaml; ConsentService.Consent):
  // its id is consentId and its purposes are activePurposes, so the id and purposes columns were always empty
  // (CERT-In / DPDP matrix D-04, compliancePackCodes.test.ts)
  columns: [c('consentId', 'fld.id'), c('noticeKey'), c('noticeVersion'), c('activePurposes', 'fld.purposeCodes'), c('status'), c('givenAt'), c('withdrawnAt')],
  rowKey: ['id', 'consentId'],
  create: {
    id: 'give',
    label: 'act.recordConsent',
    path: '/compliance/consents',
    fields: [f.text('principalRef'), f.text('noticeKey', undefined, true), f.num('noticeVersion'), f.select('language', ['en', 'hi'], 'fld.language', false, 'lang.'), f.json('purposeCodes', undefined, true), yesNo('affirmativeAction', 'fld.affirmativeAction'), f.text('channel'), f.select('declaredCategory', ['ADULT', 'CHILD'], 'fld.declaredCategory', true), f.text('guardianVerificationId')],
    // owner decision 365: the declared category (adult or child) is a required choice with no default; a declared child
    // needs the guardian verification id, which the consent row then carries. Owner decision 369 (h): the affirmative
    // action is an explicit Yes / No, never assumed (the server refuses an omitted one by name)
    check: (body, t) => checkChoices([['affirmativeAction', 'fld.affirmativeAction']])(body, t)
      ?? (body.declaredCategory === 'ADULT' || body.declaredCategory === 'CHILD' ? undefined : t('mod.compliance.choiceRequired', { field: t('fld.declaredCategory') })),
    hint: 'mod.compliance.consentHint',
  },
  // M-051: WithdrawConsentRequest(purposes) (ComplianceRequests.java:27)
  actions: [{ id: 'withdraw', label: 'act.withdrawConsent', path: '/compliance/consents/{id}/withdraw', fields: [f.json('purposes', 'fld.purposeCodes')], reason: 'body' }],
};

/**
 * The tenant's jurisdiction pack as GET /compliance/jurisdiction gives it (ComplianceApiImpl.jurisdiction), as far as
 * the screens read it: the right codes (JurisdictionPack.Right) and the contact roles (JurisdictionPack.ContactRole).
 */
export interface JurisdictionView {
  code?: string;
  rights?: unknown;
  contactRoles?: unknown;
  /** JurisdictionPack.Authority records: code, role, name, catalogue of incident types {code, label}. */
  authorities?: unknown;
}

/** An incident authority of the pack (role INCIDENT) with its incident-type catalogue (IN: CERT-In Annexure I). */
export interface IncidentAuthority {
  code: string;
  name?: string;
  catalogue: { code: string; label: string }[];
}

/** The pack's incident authorities (role INCIDENT), in pack order, with their catalogues; malformed entries are skipped. */
export function incidentAuthorities(pack: JurisdictionView | undefined): IncidentAuthority[] {
  const list = pack?.authorities;
  if (!Array.isArray(list)) return [];
  const out: IncidentAuthority[] = [];
  for (const a of list) {
    if (!a || typeof a !== 'object') continue;
    const r = a as { code?: unknown; role?: unknown; name?: unknown; catalogue?: unknown };
    if (r.role !== 'INCIDENT' || typeof r.code !== 'string' || !r.code) continue;
    const catalogue = Array.isArray(r.catalogue)
      ? r.catalogue
          .map((x) => (x && typeof x === 'object' ? (x as { code?: unknown; label?: unknown }) : {}))
          .filter((x): x is { code: string; label?: unknown } => typeof x.code === 'string' && x.code.length > 0)
          .map((x) => ({ code: x.code, label: typeof x.label === 'string' && x.label ? x.label : x.code }))
      : [];
    out.push({ code: r.code, name: typeof r.name === 'string' ? r.name : undefined, catalogue });
  }
  return out;
}

/** A catalogue entry as a select option: the code and the pack's label (pack data, not UI copy). */
const typeOption = (x: { code: string; label: string }) => ({ value: x.code, label: x.label === x.code ? x.code : `${x.code} - ${x.label}` });

/**
 * Owner decision 344: the incident types an officer may pick, from the pack catalogue: of one authority, or (no
 * authority given) of every incident authority, each code once. The server refuses any other value by name
 * (INCIDENT_TYPE_NOT_IN_CATALOGUE).
 */
export function incidentTypeOptions(pack: JurisdictionView | undefined, authority?: string): { value: string; label: string }[] {
  const seen = new Set<string>();
  const out: { value: string; label: string }[] = [];
  for (const a of incidentAuthorities(pack)) {
    if (authority !== undefined && a.code !== authority) continue;
    for (const x of a.catalogue) {
      if (seen.has(x.code)) continue;
      seen.add(x.code);
      out.push(typeOption(x));
    }
  }
  return out;
}

/** The codes of one pack list, in pack order; an absent or malformed list gives none. */
export function packCodes(list: unknown): string[] {
  if (!Array.isArray(list)) return [];
  return list.map((x) => (x && typeof x === 'object' ? (x as { code?: unknown }).code : undefined)).filter((v): v is string => typeof v === 'string' && v.length > 0);
}

/**
 * A field over codes the jurisdiction pack defines. The pack is the one source: the server looks a code up exactly
 * (JurisdictionPack.right / contactRole) and refuses any other (DataSubjectRightsService.submit, ContactDirectory.set),
 * so the UI never keeps its own list (a fixed list once offered UPDATE where the India pack defines UPDATING). With
 * the pack, a select of its codes, labelled by opt.<code> where the UI has that label, else by the code itself (pack
 * data, not UI copy); without it (not readable, or no jurisdiction yet), a text field for the code, checked by the
 * server like any other.
 */
export function packCodeField(key: string, codes: string[], hasLabel: (i18nKey: string) => boolean, required: boolean): FormField {
  if (codes.length === 0) return f.text(key, undefined, required);
  return { ...f.select(key, [], undefined, required), values: codes.map((v) => ({ value: v, label: hasLabel(`opt.${v}`) ? `opt.${v}` : v })) };
}

/**
 * Rights requests of one data principal. Owner decision 338: searched with POST /compliance/rights-requests/search and
 * the reference in the body, never ?subject= in the URL. The detail carries the published DPO contact (decision 346).
 */
export function rightsScreen(pack: JurisdictionView | undefined, hasLabel: (i18nKey: string) => boolean): PrincipalSearchDef {
  return {
    id: 'rights',
    title: 'mod.compliance.rights',
    search: '/compliance/rights-requests/search',
    // ComplianceRightsRequest (sangam-api.yaml; DataSubjectRightsService.RightsRequest): requestId and receivedAt
    columns: [c('requestId', 'fld.requestId'), c('right'), c('status'), c('receivedAt'), c('dueAt')],
    rowKey: ['id', 'requestId'],
    detail: '/compliance/rights-requests/{id}',
    create: { id: 'submit', label: 'act.submitRightsRequest', path: '/compliance/rights-requests', fields: [f.text('principalRef'), packCodeField('right', packCodes(pack?.rights), hasLabel, true), f.text('identifierType'), f.text('identifierValue', undefined, false, { sensitive: true }), f.text('nomineeRef'), f.text('channel'), f.json('details')] },
  };
}

/**
 * Breaches and incidents. Owner decision 344: the incident type is a pick-list of the pack catalogue (IN: the 20 CERT-In
 * Annexure I types), for the incident (of the chosen authority) and for the incident a breach opens; without a readable
 * pack the code is typed and the server checks it like any other.
 */
export function breachesScreen(pack: JurisdictionView | undefined): ScreenDef {
  const authorities = incidentAuthorities(pack);
  const anyType = incidentTypeOptions(pack);
  const breachType: FormField = anyType.length ? { ...f.select('incidentType', []), values: anyType } : f.text('incidentType');
  const authority: FormField = authorities.length
    ? { ...f.select('authority', [], undefined, true), values: authorities.map((a) => ({ value: a.code, label: a.name ? `${a.code} - ${a.name}` : a.code })) }
    : f.text('authority', undefined, true);
  const type: FormField = authorities.length ? { ...f.select('type', [], 'fld.incidentType', true), values: [] } : f.text('type', 'fld.incidentType', true);
  return {
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
    { id: 'breach', label: 'act.registerBreach', path: '/compliance/breaches', fields: [f.dt('awareAt', undefined, true), breachType, f.json('facts', 'fld.facts', true), f.json('subjects'), f.json('roots')], showResult: true, hint: 'mod.compliance.breachHint' },
    // M-051: IncidentRequest(authority, type, occurredAt, noticedAt, systems, description) (ComplianceRequests.java:34); required
    // authority, type, occurredAt (sangam-api.yaml ComplianceIncidentRequest)
    // decision 344: the type options are the catalogue of the chosen authority
    {
      id: 'incident',
      label: 'act.registerIncident',
      path: '/compliance/incidents',
      fields: [authority, type, f.dt('occurredAt', 'fld.occurredAt', true), f.dt('noticedAt', undefined, true), f.json('systems', 'fld.affectedSystems'), f.area('description', 'fld.summary', true)],
      dynamicValues: authorities.length ? { type: (v) => incidentTypeOptions(pack, typeof v.authority === 'string' ? v.authority : '') } : undefined,
      showResult: true,
      hint: 'mod.compliance.incidentHint',
    },
    // M-051: SubmitIncidentRequest(actionsTaken) (ComplianceRequests.java:38); the incident id is the path only
    { id: 'submitIncident', label: 'act.submitIncidentReport', path: '/compliance/incidents/{incidentId}/submit', fields: [f.path(f.text('incidentId', undefined, true)), f.area('actionsTaken', 'fld.actionsTaken')], showResult: true },
  ],
  };
}

export const lawful: ScreenDef = {
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
    { id: 'verify', label: 'act.verifyLawful', path: '/compliance/lawful-requests/{id}/verify', fields: [yesNo('rankAdequate')], check: checkChoices(['rankAdequate']) },
    { id: 'approve', label: 'act.approve', path: '/compliance/lawful-requests/{id}/approve', noJson: true, hint: 'mod.compliance.lawfulApproveHint' },
    // M-051: RespondLawfulRequest(responseRef, principalRefs, dataCategories) (ComplianceRequests.java:48); responseRef required
    { id: 'respond', label: 'act.respond', path: '/compliance/lawful-requests/{id}/respond', fields: [f.text('responseRef', 'fld.responseRef', true), f.json('principalRefs', 'fld.disclosedRefs'), f.json('dataCategories', 'fld.categories')] },
    { id: 'reject', label: 'act.reject', path: '/compliance/lawful-requests/{id}/reject', reason: 'body', danger: true },
  ],
};

/** The published contacts list (the form to set one is per role: contactAct). */
export const contactsList: ScreenDef = {
  id: 'contacts',
  title: 'mod.compliance.contacts',
  list: '/compliance/contacts',
  unpaged: true,
  columns: [c('role'), c('name'), c('designation'), c('email'), c('phone'), c('mobile')],
  rowKey: ['role'],
};

/** A contact role of the pack as GET /compliance/jurisdiction gives it (JurisdictionPack.ContactRole). */
export interface ContactRoleView {
  code: string;
  mustBeResident?: boolean;
  requiredFields?: string[];
}

/** The pack's contact roles, in pack order; malformed entries are skipped. */
export function contactRoles(pack: JurisdictionView | undefined): ContactRoleView[] {
  const list = pack?.contactRoles;
  if (!Array.isArray(list)) return [];
  const out: ContactRoleView[] = [];
  for (const x of list) {
    if (!x || typeof x !== 'object') continue;
    const r = x as { code?: unknown; mustBeResident?: unknown; requiredFields?: unknown };
    if (typeof r.code !== 'string' || !r.code) continue;
    const req = Array.isArray(r.requiredFields) ? r.requiredFields.filter((v): v is string => typeof v === 'string' && v.length > 0) : [];
    out.push({ code: r.code, mustBeResident: r.mustBeResident === true, requiredFields: req });
  }
  return out;
}

/** The role picker of the contact form: the pack's contact roles, labelled by opt.<code> where the UI has it. */
export function contactRoleOptions(pack: JurisdictionView | undefined, hasLabel: (i18nKey: string) => boolean): { value: string; label: string }[] {
  return contactRoles(pack).map((r) => ({ value: r.code, label: hasLabel(`opt.${r.code}`) ? `opt.${r.code}` : r.code }));
}

/** The contact fields the server stores (ContactDirectory.Contact), in form order, besides role and resident. */
const CONTACT_FIELDS = ['name', 'designation', 'organisation', 'email', 'phone', 'mobile', 'address'];

/**
 * Owner decision 345: the form that sets the contact of one pack role shows the fields the pack requires for that role
 * (IN CERT_IN_POC: designation, mobile, address; CERT-In Directions Annexure II) as required fields of the form, not
 * behind "more fields"; name and email are always required (ContactDirectory.set), and a role that must be resident
 * asks for the residency confirmation. The server checks the same rules.
 */
export function contactAct(role: ContactRoleView): Act {
  const required = new Set<string>(['name', 'email', ...(role.requiredFields ?? [])]);
  const shown = CONTACT_FIELDS.filter((k) => required.has(k) || k === 'phone');
  const extra = (role.requiredFields ?? []).filter((k) => !CONTACT_FIELDS.includes(k));
  const fields: FormField[] = [...shown.map((k) => (k === 'address' ? f.area(k, undefined, true) : f.text(k, undefined, required.has(k)))), ...extra.map((k) => f.text(k, undefined, true))];
  if (role.mustBeResident) fields.push(f.bool('resident', 'fld.resident'));
  return {
    id: 'set',
    label: 'act.setContact',
    path: `/compliance/contacts/by-role?role=${encodeURIComponent(role.code)}`,
    fields,
    hint: role.mustBeResident ? 'mod.compliance.residentHint' : undefined,
    check: role.mustBeResident ? (body, t) => (body.resident === true ? undefined : t('mod.compliance.residentRequired')) : undefined,
  };
}

/** The contacts tab: the list, and a role picker whose form shows that role's required fields (decision 345). */
export function ContactsTab({ pack, hasLabel }: { pack: JurisdictionView | undefined; hasLabel: (i18nKey: string) => boolean }) {
  const { t } = useTranslation();
  const roles = contactRoles(pack);
  const [role, setRole] = useState('');
  const [open, setOpen] = useState(false);
  const [n, setN] = useState(0);
  const chosen = roles.find((r) => r.code === role);
  return (
    <>
      <ModuleScreen key={n} def={contactsList} embedded />
      <Section title={t('act.setContact')}>
        {roles.length === 0 ? (
          <Notice tone="warn">{t('mod.compliance.noPackRoles')}</Notice>
        ) : (
          <div className="filters">
            <SelectField label={t('fld.role')} required value={role} onChange={setRole} placeholder={t('form.choose')} options={contactRoleOptions(pack, hasLabel).map((o) => ({ value: o.value, label: o.label.startsWith('opt.') ? t(o.label) : o.label }))} />
            <button type="button" className="primary" disabled={!chosen} onClick={() => setOpen(true)}>
              {t('act.setContact')}
            </button>
          </div>
        )}
      </Section>
      {open && chosen && (
        <ActionDialog
          act={contactAct(chosen)}
          onClose={() => setOpen(false)}
          onDone={() => {
            setOpen(false);
            setN((x) => x + 1);
          }}
        />
      )}
    </>
  );
}

export const evidence: ScreenDef = {
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
    { id: 'dpia', label: 'act.recordDpia', path: '/compliance/evidence/impact-assessments', fields: [f.text('scope', 'fld.system', true), f.text('assessor', undefined, true), f.dt('assessedAt', 'fld.assessedOn'), f.json('risks', undefined, true), f.json('measures'), yesNo('independent', 'fld.independentAssessor'), f.text('authorityReportRef', 'fld.documentRef')], check: checkChoices([['independent', 'fld.independentAssessor']]), showResult: true },
    // M-051: AuditEvidenceRequest(kind, periodFrom, periodTo, completedAt, auditor, accreditationRef, independent, findings, reportHash,
    // authorityReportRef) (ComplianceRequests.java:54); kind is an evidence kind of the jurisdiction pack, auditor and reportHash are
    // required, and a kind may require independence or an accreditation reference (AccountabilityService.java:106-118)
    { id: 'audit', label: 'act.recordAudit', path: '/compliance/evidence/audits', fields: [f.text('kind', 'fld.evidenceKind', true), f.text('auditor', undefined, true), f.dt('periodFrom', 'fld.from'), f.dt('periodTo', 'fld.to'), f.text('reportHash', 'fld.reportHash', true), yesNo('independent'), f.text('accreditationRef', 'fld.accreditationRef'), f.json('findings')], check: checkChoices(['independent']), showResult: true },
    // M-051: TransferRequest(destinationCountry, recipient, purposeCode, dataCategories, ...) (CrossBorderTransferGuard.java:37);
    // owner decision 362: recipientSameProtectionAttested and recipientConditionMet are required Yes / No choices (never
    // defaulted), as independent and rankAdequate are on the evidence and lawful-request forms
    { id: 'transfer', label: 'act.evaluateTransfer', path: '/compliance/transfers/evaluate', fields: [f.text('destinationCountry', undefined, true), f.json('dataCategories', 'fld.categories', true), f.text('purposeCode', undefined, true), yesNo('recipientSameProtectionAttested'), yesNo('recipientConditionMet')], check: checkChoices(['recipientSameProtectionAttested', 'recipientConditionMet']), showResult: true },
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
  // owner decision 342: the chosen days become instants in the tenant's zone, [start of the first day, start of the
  // day after the last); without the tenant's zone nothing is sent (never the reader's zone)
  const zone = tenantLocale().zone;
  const period = reportPeriod(from, to, zone);
  const q = 'error' in period ? undefined : { from: period.from, to: period.to, verifyChains };
  return (
    <Section title={t('mod.compliance.report')}>
      <p className="hint">{t('mod.compliance.reportHint')}</p>
      {zone ? <p className="hint">{t('mod.compliance.reportZone', { zone })}</p> : <Notice tone="warn">{t('mod.compliance.reportNoZone')}</Notice>}
      <div className="filters">
        <TextField label={t('mod.compliance.firstDay')} type="date" required value={from} onChange={setFrom} />
        <TextField label={t('mod.compliance.lastDay')} type="date" required value={to} onChange={setTo} />
        <Checkbox label={t('mod.compliance.verifyChains')} checked={verifyChains} onChange={setVerify} />
        <button type="button" className="primary" disabled={act.busy || !q} onClick={() => void (q && act.run(() => http.get<typeof report>('/compliance/reports', { ...q, format: 'json' })).then((r) => r && setReport(r)))}>
          {t('mod.compliance.generate')}
        </button>
        <button type="button" disabled={act.busy || !q} onClick={() => void (q && act.run(() => download('/compliance/reports', { ...q, format: 'html' }, 'compliance-report.html').then(() => true)))}>
          {t('mod.compliance.downloadHtml')}
        </button>
      </div>
      {'error' in period && period.error === 'order' && <Notice tone="bad">{t('mod.compliance.reportPeriodOrder')}</Notice>}
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
  const { i18n } = useTranslation();
  // the pack's codes for the rights and contact-role fields (packCodeField); the evidence tab shows the same answer
  const pack = useAsync((signal) => http.get<JurisdictionView>('/compliance/jurisdiction', undefined, signal), []);
  // every tab in order; the consent and rights registers are searched by POST (decision 338) and the contact form is
  // per role (decision 345), so those tabs are their own screens
  const tabs = useMemo(() => {
    const hasLabel = (k: string) => i18n.exists(k);
    const screen = (def: ScreenDef) => ({ id: def.id, label: def.title, node: <ModuleScreen def={def} embedded /> });
    const search = (def: PrincipalSearchDef) => ({ id: def.id, label: def.title, node: <PrincipalSearchScreen def={def} /> });
    return [
      screen(purposes),
      search(consents),
      search(rightsScreen(pack.data, hasLabel)),
      screen(breachesScreen(pack.data)),
      screen(lawful),
      { id: 'contacts', label: 'mod.compliance.contacts', node: <ContactsTab pack={pack.data} hasLabel={hasLabel} /> },
      screen(evidence),
      { id: 'report', label: 'mod.compliance.report', node: <EvidenceReport /> },
      { id: 'privacy', label: 'privacy.title', node: <PrivacyAdmin /> },
    ];
  }, [pack.data, i18n]);
  if (pack.loading && !pack.data && !pack.error) return <Loading />;
  return <ModulePage title="mod.compliance.title" description="mod.compliance.desc" screens={[]} extra={tabs} />;
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
 * (GET /compliance/notices/by-key ?key&version&lang, :98-104), and publishing a new notice version from purposes
 * (POST /compliance/notices/by-key/publish?key= {purposes}, compliance.admin, :106-111; ComplianceRequests.java:18). The policy
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
  const notice = useAsync((signal) => http.get<RenderedNotice>('/compliance/notices/by-key', { key: nq?.key ?? '', version: nq?.version || undefined, lang: nq?.lang || undefined }, signal), [JSON.stringify(nq)], !!nq);
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
