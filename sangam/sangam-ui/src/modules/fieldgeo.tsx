// SPDX-License-Identifier: Apache-2.0
/**
 * sangam-field and sangam-geo screens.
 * Field (contract /field/**): inspection templates (versioned checklists), assignments with review, schedules and
 * verifiable random selection, devices (offline queue policy, hash chain), sync conflicts, evidence integrity, and a
 * **dispatch board** (jobs by status, units, ranked candidates, offer / assign / arrive / close).
 * Geo (contract /geo/**): layers and boundary import, features with lineage, resolve / nearest, fences, and a
 * **geofence viewer** that draws boundaries and fences as SVG from the server's geometry. No external map tiles are
 * loaded (air-gapped, R56); clicking the map resolves the point and checks it against the fences.
 */
import { useMemo, useState, type MouseEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { http } from '../api/client';
import { useAction, useAsync, useInterval } from '../lib/hooks';
import { fmtDate } from '../i18n';
import { Badge, Empty, JsonView, Notice, ProblemAlert, Section, TextField } from '../components/ui';
import { ActionDialog, ModulePage, c, f, type Act, type Row, type ScreenDef } from './engine';
import { checkTemplateSections } from './fieldTemplateCheck';

// ------------------------------------------------------------------ field

const templates: ScreenDef = {
  id: 'templates',
  title: 'mod.field.templates',
  description: 'mod.field.templatesDesc',
  list: '/field/templates',
  unpaged: true,
  columns: [c('key'), c('version'), c('name'), c('state')],
  rowKey: ['key'],
  detail: '/field/templates/{key}',
  // M-051: the body is a FieldTemplate(key, version, title {lang: text}, offlineCapable, sections, scoring, geofence, evidence, attributes)
  // (FieldTemplate.java:29; FieldRestService.java:176-178)
  create: { id: 'publish', label: 'act.publishTemplate', path: '/field/templates', fields: [f.text('key', undefined, true), f.json('title', 'fld.title', true), f.bool('offlineCapable', 'fld.offlineCapable'), f.json('sections', 'fld.checklistSections', true)], hint: 'mod.field.templateHint', check: (body, t) => checkTemplateSections(body.sections, t) },
  actions: [{ id: 'retire', label: 'act.retire', path: '/field/templates/{key}/{version}/retire', reason: 'body', danger: true }],
};

const assignments: ScreenDef = {
  id: 'assignments',
  title: 'mod.field.assignments',
  list: '/field/assignments',
  unpaged: true,
  filters: [f.text('assignee'), f.text('status'), f.text('targetRef')],
  columns: [c('id'), c('templateKey'), c('targetRef'), c('assignee'), c('status'), c('dueAt')],
  rowKey: ['id', 'assignmentId'],
  detail: '/field/assignments/{id}',
  sections: [{ id: 'evidence', label: 'mod.field.evidence', path: '/field/assignments/{id}/evidence' }],
  // M-051: NewAssignment(templateKey, templateVersion, targetRef, targetLabel, address, lat, lon, assignee, seatId, dueAt, windowStart,
  // windowEnd, taskId, businessKey, attributes) (AssignmentService.java:31; FieldRestService.java:212-214)
  create: { id: 'create', label: 'act.create', path: '/field/assignments', fields: [f.text('templateKey', undefined, true), f.text('targetRef', undefined, true), f.text('assignee', undefined, true), f.dt('dueAt'), f.text('taskId'), f.text('businessKey'), f.text('address'), f.num('lat'), f.num('lon')] },
  toolbar: [
    // M-051: the start point is {lat, lon} (FieldRestService.java:234-241)
    { id: 'route', label: 'act.myRoute', path: '/field/assignments/route', fields: [f.num('lat', 'fld.fromLat'), f.num('lon', 'fld.fromLon')], showResult: true },
    { id: 'sweep', label: 'act.sweepOverdue', path: '/field/assignments/sweep-overdue', showResult: true, noJson: true },
  ],
  actions: [
    // M-051: transition {status: Assignment.Status, version?, reason} (FieldRestService.java:223-225; Assignment.java:23);
    // reassign {assignee, seatId?, version?} (:217-219); review {accept, reason} (:229-231)
    { id: 'transition', label: 'act.transition', path: '/field/assignments/{id}/transition', fields: [f.select('status', ['PLANNED', 'ASSIGNED', 'IN_PROGRESS', 'SUBMITTED', 'ACCEPTED', 'REJECTED', 'CANCELLED', 'OVERDUE'], 'fld.transition', true), f.num('version')], reason: 'body' },
    { id: 'reassign', label: 'act.reassign', path: '/field/assignments/{id}/reassign', fields: [f.text('assignee', undefined, true), f.text('seatId'), f.num('version')], reason: 'body' },
    { id: 'review', label: 'act.review', path: '/field/assignments/{id}/review', fields: [f.bool('accept', 'fld.acceptVisit'), f.area('reason')] },
  ],
};

const schedules: ScreenDef = {
  id: 'schedules',
  title: 'mod.field.schedules',
  list: '/field/schedules',
  unpaged: true,
  columns: [c('id'), c('templateKey'), c('frequency'), c('selectionRule'), c('nextRunAt')],
  rowKey: ['id', 'scheduleId'],
  // M-051: ScheduleService.Schedule(id, templateKey, cadenceDays, dueDays, targets, assigneePool, rotationExcludeLast, sampleSize,
  // riskDecisionKey, rotationDecisionKey, active, ...) (ScheduleService.java:35; FieldRestService.java:264-266); Target(ref, label,
  // address, lat, lon, weight, attributes)
  create: { id: 'define', label: 'act.defineSchedule', path: '/field/schedules', fields: [f.text('id'), f.text('templateKey', undefined, true), f.num('cadenceDays', 'fld.cadenceDays', true), f.num('dueDays'), f.json('targets', 'fld.targets', true), f.json('assigneePool', 'fld.assigneePool'), f.num('sampleSize', 'fld.sampleSize'), f.num('rotationExcludeLast', 'fld.rotationExcludeLast'), f.text('riskDecisionKey', 'fld.riskDecisionKey'), f.text('rotationDecisionKey', 'fld.rotation'), f.bool('active')], defaults: () => ({ active: true }) },
  toolbar: [
    { id: 'runDue', label: 'act.runDue', path: '/field/schedules/run-due', showResult: true, noJson: true },
    // M-051: {population: [{ref, weight, attributes}], seed (required), sampleSize, riskDecisionKey} (FieldRestService.java:279-281;
    // RandomSelector.Candidate, RandomSelector.java:35)
    { id: 'select', label: 'act.randomSelection', path: '/field/selections', fields: [f.json('population', 'fld.population', true), f.num('sampleSize', 'fld.count', true), f.text('seed', 'fld.seedCommitment', true), f.text('riskDecisionKey', 'fld.riskDecisionKey')], showResult: true, hint: 'mod.field.selectionHint' },
    { id: 'verify', label: 'act.verifySelection', method: 'GET', path: '/field/selections/{selectionId}/verify', fields: [f.text('selectionId', undefined, true)], showResult: true, noJson: true },
  ],
  actions: [{ id: 'run', label: 'act.runNow', path: '/field/schedules/{id}/run', showResult: true, noJson: true }],
};

const devices: ScreenDef = {
  id: 'devices',
  title: 'mod.field.devices',
  list: '/field/devices',
  unpaged: true,
  columns: [c('id'), c('owner'), c('model'), c('status'), c('lastSyncAt'), c('queued')],
  rowKey: ['id', 'deviceId'],
  sections: [{ id: 'chain', label: 'mod.field.chain', path: '/field/devices/{id}/chain' }],
  // M-051: DeviceRegistry.Registration(deviceId, unitId, platform, appVersion, publicKey, keyAlg) (DeviceRegistry.java:55)
  create: { id: 'register', label: 'act.registerDevice', path: '/field/devices', fields: [f.text('deviceId'), f.text('unitId'), f.text('platform', 'fld.model'), f.text('appVersion', 'fld.appVersion'), f.text('publicKey', undefined, true), f.text('keyAlg', 'fld.keyAlg')] },
  actions: [
    { id: 'status', label: 'act.setStatus', path: '/field/devices/{id}/status', fields: [f.select('status', ['ACTIVE', 'LOST', 'REVOKED'], undefined, true)], reason: 'body', hint: 'mod.field.revokeHint' },
    // M-051: QueuePolicy(maxItems, maxAgeHours, wipeOnRevoke, requireSignature, maxOfflineHours) (QueuePolicy.java:13; FieldRestService.java:310-312)
    { id: 'policy', label: 'act.queuePolicy', path: '/field/devices/{id}/policy', fields: [f.num('maxItems', 'fld.maxPackets'), f.num('maxAgeHours'), f.num('maxOfflineHours', 'fld.maxOfflineHours'), f.bool('requireSignature', 'fld.requireSignature'), f.bool('wipeOnRevoke', 'fld.wipeOnRevoke')] },
  ],
};

const conflicts: ScreenDef = {
  id: 'conflicts',
  title: 'mod.field.conflicts',
  list: '/field/conflicts',
  unpaged: true,
  filters: [f.select('status', ['OPEN', 'RESOLVED'])],
  columns: [c('id'), c('deviceId'), c('entityRef'), c('kind'), c('status'), c('raisedAt')],
  rowKey: ['id', 'conflictId'],
  detail: '/field/conflicts/{id}',
  actions: [
    // M-051: {resolution: ACCEPT_CLIENT | KEEP_SERVER | DISCARD, note} (FieldRestService.java:338-340; ConflictResolver.java:38)
    { id: 'resolve', label: 'act.resolve', path: '/field/conflicts/{id}/resolve', fields: [f.select('resolution', ['ACCEPT_CLIENT', 'KEEP_SERVER', 'DISCARD'], 'fld.choice', true), f.area('note')] },
    // M-039: re-check an evidence item's hash chain (field.review, FieldWorkController.java:281-284) → {evidenceId, intact}
    { id: 'reverify', label: 'act.reverify', path: '/field/evidence/{evidenceId}/reverify', fields: [f.path(f.text('evidenceId', 'fld.evidenceId', true))], noJson: true, showResult: true },
  ],
};

const units: ScreenDef = {
  id: 'units',
  title: 'mod.field.units',
  list: '/field/units',
  unpaged: true,
  filters: [f.text('status')],
  columns: [c('id'), c('name'), c('kind'), c('status'), c('lastPingAt')],
  rowKey: ['id', 'unitId'],
  sections: [
    { id: 'offers', label: 'mod.field.offers', path: '/field/units/{id}/offers' },
    { id: 'trips', label: 'mod.field.trips', path: '/field/units/{id}/trips' },
  ],
  // M-051: DispatchService.UnitInput(id, kind, label, skills, homeLat, homeLon, orgUnitRef, attributes) (DispatchService.java:53)
  create: { id: 'save', label: 'act.saveUnit', path: '/field/units', fields: [f.text('id', 'fld.unitId', true), f.text('label', 'fld.name', true), f.text('kind'), f.json('skills'), f.num('homeLat', 'fld.homeLat'), f.num('homeLon', 'fld.homeLon'), f.text('orgUnitRef', 'fld.orgUnit')] },
  actions: [
    { id: 'status', label: 'act.setStatus', path: '/field/units/{id}/status', fields: [f.select('status', ['AVAILABLE', 'BUSY', 'OFF_DUTY', 'OUT_OF_SERVICE'], undefined, true)] },
    // M-039 (dispatch.operate, FieldWorkController.java:40-43): ETA to a job (GET ?jobId, :325-328) and a position ping
    // {lat, lon, at?, accuracyM?, speedMps?, headingDeg?, source?} (:313-316; keys read at FieldRestService.java:404-409)
    { id: 'eta', label: 'act.eta', method: 'GET', path: '/field/units/{id}/eta', fields: [f.text('jobId', 'fld.jobId', true)], noJson: true, showResult: true },
    { id: 'ping', label: 'act.ping', path: '/field/units/{id}/pings', fields: [f.num('lat', undefined, true), f.num('lon', undefined, true), f.dt('at'), f.num('accuracyM', 'fld.accuracyM'), f.num('speedMps', 'fld.speedMps'), f.num('headingDeg', 'fld.headingDeg'), f.text('source')], noJson: true },
  ],
};

const JOB_COLUMNS = ['NEW', 'OFFERED', 'ASSIGNED', 'EN_ROUTE', 'ARRIVED', 'CLOSED'];

/** Dispatch board: jobs by status, available units, ranked candidates; refreshed every 15 s. */
export function DispatchBoard() {
  const { t } = useTranslation();
  const jobs = useAsync(() => http.get<Row[] | { items: Row[] }>('/field/jobs', { limit: 200 }), []);
  const unitsList = useAsync(() => http.get<Row[] | { items: Row[] }>('/field/units'), []);
  useInterval(() => {
    jobs.reload();
    unitsList.reload();
  }, 15000);
  const [sel, setSel] = useState<Row | null>(null);
  const cands = useAsync(() => http.get<unknown>(`/field/jobs/${encodeURIComponent(String(sel?.id ?? sel?.jobId))}/candidates`), [sel?.id ?? sel?.jobId], !!sel);
  const [act, setAct] = useState<Act | null>(null);
  const list = (x: unknown): Row[] => (Array.isArray(x) ? (x as Row[]) : ((x as { items?: Row[] } | undefined)?.items ?? []));
  const jobRows = list(jobs.data);
  const unitRows = list(unitsList.data);
  const byStatus = useMemo(() => {
    const m = new Map<string, Row[]>(JOB_COLUMNS.map((s) => [s, []]));
    for (const j of jobRows) {
      const s = String(j.status ?? 'NEW').toUpperCase();
      (m.get(s) ?? m.set(s, []).get(s)!).push(j);
    }
    return m;
  }, [jobRows]);
  const jobAction = (action: string, withUnit: boolean): Act => ({
    id: action,
    label: `act.job.${action}`,
    path: `/field/jobs/{jobId}/${action}`,
    fields: withUnit ? [f.text('unitId', undefined, true)] : [f.area('remarks')],
    reason: action === 'cancel' ? 'body' : undefined,
    danger: action === 'cancel',
  });
  return (
    <Section
      title={t('mod.field.dispatch')}
      actions={
        <button
          type="button"
          className="primary"
          onClick={() =>
            setAct({ id: 'new', label: 'act.createJob', path: '/field/jobs', fields: [f.text('kind', undefined, true), f.text('priority'), f.text('lat', undefined, true), f.text('lon', undefined, true), f.text('address'), f.json('requiredSkills'), f.text('businessKey'), f.num('slaTargetSeconds', 'fld.slaTargetSeconds'), f.bool('autoDispatch', 'fld.autoDispatch'), f.json('attributes')] }) // M-051: JobInput keys (FieldRestService.java:427-433)
          }
        >
          {t('act.createJob')}
        </button>
      }
    >
      <ProblemAlert error={jobs.error ?? unitsList.error} />
      <div className="board" role="list" aria-label={t('mod.field.dispatch')}>
        {[...byStatus].map(([status, items]) => (
          <section key={status} className="board-col" role="listitem" aria-labelledby={`col-${status}`}>
            <h3 id={`col-${status}`}>
              {t(`opt.${status}`, { defaultValue: status })} <span className="count">{items.length}</span>
            </h3>
            <ul>
              {items.map((j) => (
                <li key={String(j.id ?? j.jobId)}>
                  <button type="button" className={`board-card${sel && (sel.id ?? sel.jobId) === (j.id ?? j.jobId) ? ' selected' : ''}`} aria-pressed={!!sel && (sel.id ?? sel.jobId) === (j.id ?? j.jobId)} onClick={() => setSel(j)}>
                    <strong>{String(j.kind ?? j.id)}</strong> {j.priority ? <Badge value={String(j.priority)} /> : null}
                    <span className="small muted">{String(j.address ?? '')}</span>
                    <span className="small">{fmtDate(String(j.createdAt ?? ''))}</span>
                    {j.unitId ? <span className="small">{t('fld.unitId')}: {String(j.unitId)}</span> : null}
                  </button>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
      <div className="grid-2">
        <Section title={t('mod.field.units')}>
          {unitRows.length === 0 && <Empty message={t('common.emptyNone')} />}
          <ul>
            {unitRows.map((u) => (
              <li key={String(u.id ?? u.unitId)}>
                {String(u.name ?? u.id)} <Badge value={String(u.status ?? '')} /> <span className="small muted">{fmtDate(String(u.lastPingAt ?? ''))}</span>
              </li>
            ))}
          </ul>
        </Section>
        {sel && (
          <Section title={`${t('mod.field.job')} ${String(sel.id ?? sel.jobId)}`}>
            <div className="toolbar">
              {(['offer', 'assign'] as const).map((a) => (
                <button key={a} type="button" onClick={() => setAct(jobAction(a, true))}>
                  {t(`act.job.${a}`)}
                </button>
              ))}
              {(['arrive', 'close', 'cancel'] as const).map((a) => (
                <button key={a} type="button" className={a === 'cancel' ? 'danger' : undefined} onClick={() => setAct(jobAction(a, false))}>
                  {t(`act.job.${a}`)}
                </button>
              ))}
            </div>
            <h3>{t('mod.field.candidates')}</h3>
            <ProblemAlert error={cands.error} />
            {cands.data !== undefined && <JsonView value={cands.data} />}
          </Section>
        )}
      </div>
      {act && (
        <ActionDialog
          act={act}
          context={sel ? { jobId: String(sel.id ?? sel.jobId) } : undefined}
          onClose={() => setAct(null)}
          onDone={() => {
            setAct(null);
            jobs.reload();
            unitsList.reload();
          }}
        />
      )}
    </Section>
  );
}

export function FieldPage() {
  return <ModulePage title="mod.field.title" description="mod.field.desc" screens={[templates, assignments, schedules, devices, conflicts, units]} extra={[{ id: 'dispatch', label: 'mod.field.dispatch', node: <DispatchBoard /> }]} />;
}

// ------------------------------------------------------------------ geo

const layers: ScreenDef = {
  id: 'layers',
  title: 'mod.geo.layers',
  list: '/geo/layers',
  unpaged: true,
  columns: [c('layer'), c('name'), c('levels'), c('srid'), c('featureCount')],
  rowKey: ['layer', 'key'],
  detail: '/geo/layers/{layer}',
  // M-051: a layer is {code, names {lang: text}, levels [{code, names}], attributes, ...} (BoundaryDataLoader.layer, BoundaryDataLoader.java:104)
  create: { id: 'define', label: 'act.defineLayer', path: '/geo/layers', fields: [f.text('code', 'fld.layer', true), f.json('names', 'fld.names', true), f.json('levels', 'fld.levels', true)] },
  // M-051: a boundary package {features (GeoJSON), codeProperty, levelProperty, parentProperty, orgUnitProperty, nameProperties, layer?}
  // (BoundaryDataLoader.read, BoundaryDataLoader.java:57-66)
  actions: [{ id: 'import', label: 'act.importBoundaries', path: '/geo/layers/{layer}/import', fields: [f.json('features', 'fld.geojson', true), f.text('codeProperty', 'fld.codeProperty'), f.text('levelProperty', 'fld.levelProperty'), f.text('parentProperty', 'fld.parentProperty'), f.json('nameProperties', 'fld.nameProperties')], noJson: true, hint: 'mod.geo.importHint' }],
};

const features: ScreenDef = {
  id: 'features',
  title: 'mod.geo.features',
  list: '/geo/layers/{layer}/features',
  filters: [f.text('layer', undefined, true), f.text('level'), f.text('parentId'), f.bool('history')],
  requireFilters: ['layer'],
  columns: [c('id'), c('name'), c('level'), c('code'), c('parentId'), c('validFrom')],
  rowKey: ['id', 'featureId'],
  detail: '/geo/features/{id}',
  sections: [{ id: 'lineage', label: 'mod.geo.lineage', path: '/geo/features/{id}/lineage' }],
  // M-051: {code, level, parentCode, names, properties, geometry, orgUnitRef} (GeoRestService.java:127-135)
  create: { id: 'put', label: 'act.putFeature', path: '/geo/layers/{layer}/features', fields: [f.text('code', undefined, true), f.json('names', 'fld.names', true), f.text('level', undefined, true), f.text('parentCode', 'fld.parentCode'), f.json('geometry', 'fld.geometry', true)] },
  toolbar: [
    { id: 'resolve', label: 'act.resolvePoint', method: 'GET', path: '/geo/resolve', fields: [f.text('layer', undefined, true), f.text('level'), f.text('lat', undefined, true), f.text('lon', undefined, true)], showResult: true, noJson: true },
    { id: 'nearest', label: 'act.nearest', method: 'GET', path: '/geo/nearest', fields: [f.text('layer', undefined, true), f.text('objectType'), f.text('lat', undefined, true), f.text('lon', undefined, true), f.num('n'), f.num('maxKm')], showResult: true, noJson: true },
    // M-051: {layer, geometry} (GeoRestService.java:204-209)
    { id: 'overlaps', label: 'act.overlaps', path: '/geo/overlaps', fields: [f.text('layer', undefined, true), f.json('geometry', 'fld.geometry', true)], showResult: true },
  ],
  actions: [{ id: 'retire', label: 'act.retire', method: 'DELETE', path: '/geo/features/{id}', reason: 'query', danger: true }],
};

type Coord = [number, number];
type Geom = { type: string; coordinates: unknown };

function rings(g?: Geom | null): Coord[][] {
  if (!g) return [];
  if (g.type === 'Polygon') return g.coordinates as Coord[][];
  if (g.type === 'MultiPolygon') return (g.coordinates as Coord[][][]).flat();
  if (g.type === 'LineString') return [g.coordinates as Coord[]];
  return [];
}

function geomOf(x: Row | undefined): Geom | null {
  if (!x) return null;
  const g = (x.geometry ?? x.geom ?? (x.feature as Row | undefined)?.geometry) as Geom | string | undefined;
  if (typeof g === 'string') {
    try {
      return JSON.parse(g) as Geom;
    } catch {
      return null;
    }
  }
  return g ?? null;
}

/** SVG boundary and fence viewer; equirectangular projection fitted to the data. No tiles, no external requests. */
export function GeofenceViewer() {
  const { t } = useTranslation();
  const [layer, setLayer] = useState('');
  const [level, setLevel] = useState('');
  const [fenceIds, setFenceIds] = useState('');
  const [q, setQ] = useState<{ layer: string; level: string; fences: string[] } | null>(null);
  const feats = useAsync(async () => {
    const page = await http.page<Row>(`/geo/layers/${encodeURIComponent(q!.layer)}/features`, { level: q!.level || undefined, size: 100 });
    return Promise.all(page.items.map(async (x) => (geomOf(x) ? x : { ...x, ...(await http.get<Row>(`/geo/features/${encodeURIComponent(String(x.id))}`, { geometry: true }).catch(() => ({}))) })));
  }, [JSON.stringify(q)], !!q && !!q.layer);
  const fences = useAsync(() => Promise.all((q?.fences ?? []).map((id) => http.get<Row>(`/geo/fences/${encodeURIComponent(id)}`))), [JSON.stringify(q?.fences)], !!q && q.fences.length > 0);
  const [point, setPoint] = useState<{ lat: number; lon: number } | null>(null);
  const [checks, setChecks] = useState<unknown>();
  const act = useAction();
  const shapes = [...(feats.data ?? []).map((x) => ({ kind: 'feature' as const, row: x, rings: rings(geomOf(x)) })), ...(fences.data ?? []).map((x) => ({ kind: 'fence' as const, row: x, rings: rings(geomOf(x)) }))];
  const all = shapes.flatMap((s) => s.rings.flat());
  const W = 800;
  const H = 520;
  const [minX, maxX] = all.length ? [Math.min(...all.map((p) => p[0])), Math.max(...all.map((p) => p[0]))] : [68, 97];
  const [minY, maxY] = all.length ? [Math.min(...all.map((p) => p[1])), Math.max(...all.map((p) => p[1]))] : [6, 37];
  const scale = Math.min(W / Math.max(1e-6, maxX - minX), H / Math.max(1e-6, maxY - minY)) * 0.95;
  const px = (lon: number) => (lon - minX) * scale + (W - (maxX - minX) * scale) / 2;
  const py = (lat: number) => H - ((lat - minY) * scale + (H - (maxY - minY) * scale) / 2);
  const toPath = (r: Coord[][]) => r.map((ring) => ring.map((p, i) => `${i ? 'L' : 'M'}${px(p[0]).toFixed(1)},${py(p[1]).toFixed(1)}`).join(' ') + 'Z').join(' ');
  const onClick = (e: MouseEvent<SVGSVGElement>) => {
    const box = e.currentTarget.getBoundingClientRect();
    const sx = ((e.clientX - box.left) / box.width) * W;
    const sy = ((e.clientY - box.top) / box.height) * H;
    const lon = (sx - (W - (maxX - minX) * scale) / 2) / scale + minX;
    const lat = (H - sy - (H - (maxY - minY) * scale) / 2) / scale + minY;
    const p = { lat: Number(lat.toFixed(6)), lon: Number(lon.toFixed(6)) };
    setPoint(p);
    void act
      .run(async () => {
        const resolved = q?.layer ? await http.get<unknown>('/geo/resolve', { layer: q.layer, level: q.level || undefined, lat: p.lat, lon: p.lon }).catch((er: Error) => ({ error: er.message })) : undefined;
        const fenceResults = await Promise.all((q?.fences ?? []).map(async (id) => ({ fence: id, result: await http.post<unknown>(`/geo/fences/${encodeURIComponent(id)}/check`, p).catch((er: Error) => ({ error: er.message })) })));
        return { point: p, resolved, fences: fenceResults };
      })
      .then((r) => r && setChecks(r));
  };
  return (
    <Section title={t('mod.geo.viewer')}>
      <Notice>{t('mod.geo.noTiles')}</Notice>
      <form
        className="filters"
        onSubmit={(e) => {
          e.preventDefault();
          setQ({ layer: layer.trim(), level: level.trim(), fences: fenceIds.split(',').map((s) => s.trim()).filter(Boolean) });
        }}
      >
        <TextField label={t('fld.layer')} value={layer} onChange={setLayer} />
        <TextField label={t('fld.level')} value={level} onChange={setLevel} />
        <TextField label={t('fld.fenceIds')} hint={t('events.commaList')} value={fenceIds} onChange={setFenceIds} />
        <button type="submit">{t('common.open')}</button>
      </form>
      <ProblemAlert error={feats.error ?? fences.error ?? act.error} />
      {q && (
        <figure className="geo">
          <figcaption className="sr-only">{t('mod.geo.viewerCaption', { n: shapes.length })}</figcaption>
          <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={t('mod.geo.viewerCaption', { n: shapes.length })} onClick={onClick}>
            {shapes.map((s, i) => (
              <path key={i} d={toPath(s.rings)} className={s.kind === 'fence' ? 'geo-fence' : 'geo-feature'}>
                <title>{String(s.row.name ?? s.row.code ?? s.row.id)}</title>
              </path>
            ))}
            {point && <circle cx={px(point.lon)} cy={py(point.lat)} r={5} className="geo-point" />}
          </svg>
          <p className="hint">{t('mod.geo.clickHint')}</p>
        </figure>
      )}
      {shapes.length > 0 && (
        <details>
          <summary>{t('diagram.tableAlternative')}</summary>
          <ul>
            {shapes.map((s, i) => (
              <li key={i}>
                <Badge value={s.kind === 'fence' ? 'FENCE' : 'BOUNDARY'} tone="info" /> {String(s.row.name ?? s.row.code ?? s.row.id)} — {t('mod.geo.rings', { n: s.rings.length })}
              </li>
            ))}
          </ul>
        </details>
      )}
      {checks !== undefined && <JsonView value={checks} />}
    </Section>
  );
}

const fencesScreen: ScreenDef = {
  id: 'fences',
  title: 'mod.geo.fences',
  description: 'mod.geo.fencesDesc',
  list: '/geo/fences/{id}',
  unpaged: true,
  filters: [f.text('id', 'fld.fenceId', true)],
  requireFilters: ['id'],
  columns: [c('key', 'fld.field'), c('value', 'fld.value')],
  rowKey: ['key'],
  // M-051: {id (required), kind CIRCLE | POLYGON | FEATURE, geometry, center, radiusM, featureRef, toleranceM, maxAccuracyM, attributes}
  // (GeoRestService.java:216-224; Geofence.java:24)
  create: { id: 'define', label: 'act.defineFence', path: '/geo/fences', fields: [f.text('id', 'fld.fenceId', true), f.select('kind', ['POLYGON', 'CIRCLE', 'FEATURE'], undefined, true), f.json('geometry', 'fld.geometry'), f.json('center', 'fld.center'), f.num('radiusM', 'fld.radiusM'), f.text('featureRef', 'fld.featureId'), f.num('toleranceM', 'fld.bufferMeters'), f.json('attributes')], showResult: true },
  toolbar: [
    { id: 'check', label: 'act.checkPoint', path: '/geo/fences/{id}/check', fields: [f.text('lat', undefined, true), f.text('lon', undefined, true)], showResult: true },
    { id: 'deactivate', label: 'act.deactivate', method: 'DELETE', path: '/geo/fences/{id}', reason: 'query', danger: true },
  ],
};

const external: ScreenDef = {
  id: 'external',
  title: 'mod.geo.external',
  description: 'mod.geo.externalDesc',
  list: '/geo/external-layers',
  unpaged: true,
  columns: [c('ref'), c('name'), c('kind'), c('url')],
  rowKey: ['ref', 'id'],
  // M-051: ExternalLayer(ref, protocol WMS | WFS, connectorId, baseUrl, remoteName, serviceVersion, srs, outputFormat, importLayer,
  // attributes, active) (GeoRestService.java:305-310; ExternalLayer.java:19-23: ref, protocol and remoteName required)
  create: { id: 'register', label: 'act.registerExternal', path: '/geo/external-layers', fields: [f.text('ref', undefined, true), f.select('protocol', ['WMS', 'WFS'], 'fld.kind', true), f.text('remoteName', 'fld.name', true), f.text('baseUrl', 'fld.url', true, { description: 'admin.egressHint' }), f.text('importLayer', 'fld.layer'), f.json('attributes')] },
  actions: [
    { id: 'query', label: 'act.query', path: '/geo/external-layers/{ref}/query', fields: [f.json('filter')], showResult: true },
    { id: 'import', label: 'act.import', path: '/geo/external-layers/{ref}/import', fields: [f.text('layer', undefined, true), f.text('level')], showResult: true },
  ],
};

export function GeoPage() {
  return <ModulePage title="mod.geo.title" description="mod.geo.desc" screens={[layers, features, fencesScreen, external]} extra={[{ id: 'viewer', label: 'mod.geo.viewer', node: <GeofenceViewer /> }]} />;
}
