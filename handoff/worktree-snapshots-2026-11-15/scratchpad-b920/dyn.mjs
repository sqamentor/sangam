import { readFileSync, writeFileSync } from 'node:fs';
const p = process.argv[2];
const raw = readFileSync(p, 'utf8');
const crlf = raw.includes('\r\n');
const d = JSON.parse(raw);
const paths = 'tenant.id tenant.name tenant.parentId tenant.levelKind tenant.sector tenant.residency tenant.hostingProfile tenant.preferredCell tenant.expectedStartsPerSec tenant.names tenant.quotas profile.mode profile.jurisdiction profile.regimePreset profile.parent profile.layer profile.version profile.effectiveFrom profile.overrides profile.documents profile.adoptFrom packs.ids packs.versions packs.allowUpgrade connectors security.moduleDefaults security.selfService security.orgLevels security.tenantAdmins security.roles seeds.classifications seeds.partyIdentifierTypes seeds.calendars compliance.jurisdictions compliance.settings dmnOverrides goLive.languages goLive.evidence'.split(' ');
const add = {
  'bulk.state.': ['OPEN', 'PROCESSING', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'ABORTED'],
  'bulk.rowState.': ['PENDING', 'RUNNING', 'DONE', 'SKIPPED', 'FAILED', 'CANCELLED'],
  'bulk.fmt.': ['csv', 'json', 'ndjson'],
  'onb.step.': ['tenant', 'profile', 'packs', 'security', 'seeds', 'goLive', 'review'],
  'onb.mode.': ['INHERIT', 'IMPORT', 'COPY'],
  'onb.err.': ['number', 'json', 'object', 'array'],
  'onb.f.': paths,
  'packs.reason.': ['PACK_NOT_INSTALLED', 'PACK_HAS_DEPENDANTS', 'PACK_HAS_LIVE_INSTANCES', 'PACK_NO_PREVIOUS_VERSION', 'PACK_VERSION_NOT_SEMVER', 'PACK_OPERATION_IN_PROGRESS', 'PACK_REJECTED'],
  'promo.err.': ['PROMOTION_ENVIRONMENT_NOT_SET', 'PROMOTION_ENVIRONMENT_MISSING', 'PROMOTION_SAME_ENVIRONMENT', 'PROMOTION_TARGET_MISMATCH', 'PROMOTION_PLAN_CHANGED', 'PROMOTION_DRY_RUN_FAILED', 'PROMOTION_NO_CHANGE', 'BUNDLE_INVALID', 'BUNDLE_SIGNATURE_INVALID', 'BUNDLE_UNSIGNED', 'BUNDLE_SIGNER_MISSING'],
  'tpl.err.': ['required', 'pattern', 'choices'],
  'mod.links.scope.': ['bpmn', 'cmmn', 'task'],
  'packs.': ['confirmRollback', 'confirmUninstall'],
};
for (const [k, v] of Object.entries(add)) {
  if (d[k]) throw new Error(`prefix exists: ${k}`);
  d[k] = v;
}
let out = JSON.stringify(d, null, 2) + '\n';
if (crlf) out = out.replace(/\n/g, '\r\n');
writeFileSync(p, out);
console.log('ok', crlf);
