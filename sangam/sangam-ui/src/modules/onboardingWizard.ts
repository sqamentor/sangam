// SPDX-License-Identifier: Apache-2.0
/**
 * M-035: the guided onboarding wizard's schema. The steps and fields mirror the server's request record
 * sangam-onboarding .../api/OnboardingRequest.java, because the contract types its parts only as open objects
 * (sangam-api.yaml OnboardingRequest: only `tenant` required, only `security` typed; M-051 types them additively):
 *   OnboardingRequest(tenant, profile, packs, connectors, security, seeds, compliance, dmnOverrides, goLive) :25-27
 *   TenantSpec(id, name, names, parentId, levelKind, sector, residency, expectedStartsPerSec, hostingProfile, quotas,
 *              preferredCell) :76-78; tenant.id required :30-31
 *   ProfileSpec(jurisdiction, regimePreset, parent, mode INHERIT|IMPORT|COPY, layer, version, effectiveFrom, overrides,
 *               documents, adoptFrom) :96-123
 *   PackSpec(ids, versions, allowUpgrade) :149; ConnectorBindingSpec(packConnector, connectorId, operation, test) :168
 *   SecuritySpec(moduleDefaults, selfService, roles, tenantAdmins, orgLevels) :200-201
 *   SeedSpec(classifications, partyIdentifierTypes, calendars) :243; ComplianceSpec(jurisdictions, settings) :258
 *   GoLiveSpec(evidence, languages) :271
 * The JSON editor stays as the fallback for anything the steps do not cover.
 */
import type { FormField } from '../api/types';

export type WizardValues = Record<string, unknown>;

/** How a value is turned into the request: text as is, a comma list, a number, a boolean, or JSON. */
export type Kind = 'text' | 'list' | 'number' | 'bool' | 'json' | 'date' | 'mode';

export interface WizardField {
  path: string;
  kind: Kind;
  required?: boolean;
  /** JSON fields: the shape expected, for the parse check. */
  shape?: 'object' | 'array';
  defaultValue?: unknown;
}

export interface WizardStep {
  id: string;
  fields: WizardField[];
}

export const PROFILE_MODES = ['INHERIT', 'IMPORT', 'COPY'] as const;

export const STEPS: WizardStep[] = [
  {
    id: 'tenant',
    fields: [
      { path: 'tenant.id', kind: 'text', required: true },
      { path: 'tenant.name', kind: 'text' },
      { path: 'tenant.parentId', kind: 'text' },
      { path: 'tenant.levelKind', kind: 'text' },
      { path: 'tenant.sector', kind: 'text' },
      { path: 'tenant.residency', kind: 'text' },
      { path: 'tenant.hostingProfile', kind: 'text' },
      { path: 'tenant.preferredCell', kind: 'text' },
      { path: 'tenant.expectedStartsPerSec', kind: 'number' },
      { path: 'tenant.names', kind: 'json', shape: 'object' },
      { path: 'tenant.quotas', kind: 'json', shape: 'object' },
    ],
  },
  {
    id: 'profile',
    fields: [
      { path: 'profile.mode', kind: 'mode', defaultValue: 'INHERIT' },
      { path: 'profile.jurisdiction', kind: 'text' },
      { path: 'profile.regimePreset', kind: 'text' },
      { path: 'profile.parent', kind: 'text' },
      { path: 'profile.layer', kind: 'text' },
      { path: 'profile.version', kind: 'text' },
      { path: 'profile.effectiveFrom', kind: 'date' },
      { path: 'profile.overrides', kind: 'json', shape: 'object' },
      { path: 'profile.documents', kind: 'json', shape: 'array' },
      { path: 'profile.adoptFrom', kind: 'json', shape: 'array' },
    ],
  },
  {
    id: 'packs',
    fields: [
      { path: 'packs.ids', kind: 'list' },
      { path: 'packs.versions', kind: 'json', shape: 'object' },
      { path: 'packs.allowUpgrade', kind: 'bool', defaultValue: false },
      { path: 'connectors', kind: 'json', shape: 'array' },
    ],
  },
  {
    id: 'security',
    fields: [
      { path: 'security.moduleDefaults', kind: 'bool', defaultValue: true },
      { path: 'security.selfService', kind: 'bool', defaultValue: true },
      { path: 'security.orgLevels', kind: 'list' },
      { path: 'security.tenantAdmins', kind: 'json', shape: 'array' },
      { path: 'security.roles', kind: 'json', shape: 'array' },
    ],
  },
  {
    id: 'seeds',
    fields: [
      { path: 'seeds.classifications', kind: 'json', shape: 'array' },
      { path: 'seeds.partyIdentifierTypes', kind: 'json', shape: 'array' },
      { path: 'seeds.calendars', kind: 'json', shape: 'array' },
      { path: 'compliance.jurisdictions', kind: 'list' },
      { path: 'compliance.settings', kind: 'json', shape: 'object' },
      { path: 'dmnOverrides', kind: 'json', shape: 'object' },
    ],
  },
  {
    id: 'goLive',
    fields: [
      { path: 'goLive.languages', kind: 'list' },
      { path: 'goLive.evidence', kind: 'json', shape: 'object' },
    ],
  },
];

/** i18n key of a field's label and of its hint. */
export const labelKey = (path: string) => `onb.f.${path}`;
export const hintKey = (path: string) => `onb.h.${path}`;

export function defaults(): WizardValues {
  const v: WizardValues = {};
  for (const s of STEPS) for (const f of s.fields) if (f.defaultValue !== undefined) v[f.path] = f.defaultValue;
  return v;
}

/** The form-js field for one wizard field (labels are i18n keys, translated by the caller). */
export function formField(f: WizardField): FormField {
  const base = { key: f.path, label: labelKey(f.path), description: hintKey(f.path), validate: f.required ? { required: true } : undefined };
  switch (f.kind) {
    case 'bool':
      return { ...base, type: 'checkbox' };
    case 'number':
      return { ...base, type: 'number' };
    case 'date':
      return { ...base, type: 'datetime', subtype: 'date' };
    case 'mode':
      return { ...base, type: 'select', values: PROFILE_MODES.map((m) => ({ value: m, label: `onb.mode.${m}` })) };
    case 'json':
      return { ...base, type: 'textarea', json: true };
    default:
      return { ...base, type: 'textfield' };
  }
}

function setPath(o: Record<string, unknown>, path: string, v: unknown) {
  const parts = path.split('.');
  let cur = o;
  for (const p of parts.slice(0, -1)) {
    if (!cur[p] || typeof cur[p] !== 'object') cur[p] = {};
    cur = cur[p] as Record<string, unknown>;
  }
  cur[parts[parts.length - 1]] = v;
}

export interface Built {
  request: Record<string, unknown>;
  /** path → problem (i18n key) for values that cannot be turned into the request. */
  errors: Record<string, string>;
}

/** Wizard values → OnboardingRequest body. Empty values are left out, so the server's own defaults apply. */
export function toRequest(values: WizardValues): Built {
  const request: Record<string, unknown> = {};
  const errors: Record<string, string> = {};
  for (const s of STEPS) {
    for (const f of s.fields) {
      const raw = values[f.path];
      if (raw === undefined || raw === null || raw === '') {
        if (f.required) errors[f.path] = 'form.required';
        continue;
      }
      let v: unknown = raw;
      if (f.kind === 'list') {
        const items = String(raw)
          .split(/[,\n]/)
          .map((x) => x.trim())
          .filter(Boolean);
        if (!items.length) continue;
        v = items;
      } else if (f.kind === 'number') {
        const n = typeof raw === 'number' ? raw : Number(raw);
        if (!Number.isFinite(n)) {
          errors[f.path] = 'onb.err.number';
          continue;
        }
        v = n;
      } else if (f.kind === 'bool') {
        v = raw === true || raw === 'true';
      } else if (f.kind === 'json') {
        try {
          v = typeof raw === 'string' ? JSON.parse(raw) : raw;
        } catch {
          errors[f.path] = 'onb.err.json';
          continue;
        }
        const ok = f.shape === 'array' ? Array.isArray(v) : !!v && typeof v === 'object' && !Array.isArray(v);
        if (!ok) {
          errors[f.path] = f.shape === 'array' ? 'onb.err.array' : 'onb.err.object';
          continue;
        }
      } else if (f.kind === 'text') {
        v = String(raw).trim();
        if (v === '') {
          if (f.required) errors[f.path] = 'form.required';
          continue;
        }
      }
      setPath(request, f.path, v);
    }
  }
  return { request, errors };
}

/** Request → wizard values (to continue from the JSON editor or an existing run's request). */
export function fromRequest(request: Record<string, unknown>): WizardValues {
  const v = defaults();
  const get = (path: string): unknown => path.split('.').reduce<unknown>((o, k) => (o && typeof o === 'object' ? (o as Record<string, unknown>)[k] : undefined), request);
  for (const s of STEPS) {
    for (const f of s.fields) {
      const x = get(f.path);
      if (x === undefined || x === null) continue;
      if (f.kind === 'list') v[f.path] = Array.isArray(x) ? x.join(', ') : String(x);
      else if (f.kind === 'json') v[f.path] = JSON.stringify(x, null, 2);
      else v[f.path] = x;
    }
  }
  return v;
}

/** Whether a step has an error, for the step list. */
export function stepHasError(step: WizardStep, errors: Record<string, string>): boolean {
  return step.fields.some((f) => errors[f.path]);
}
