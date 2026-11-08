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
  create: { id: 'publish', label: 'act.publishTemplate', path: '/field/templates', fields: [f.text('key', undefined, true), f.text('name', undefined, true), f.json('sections', 'fld.checklistSections', true), f.json('i18n', 'fld.hindiLabels')], hint: 'mod.field.templateHint' },
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
  create: { id: 'create', label: 'act.create', path: '/field/assignments', fields: [f.text('templateKey', undefined, true), f.text('targetRef', undefined, true), f.text('assignee', undefined, true), f.dt('dueAt'), f.text('taskId'), f.text('businessKey'), f.json('location')] },
  toolbar: [
    { id: 'route', label: 'act.myRoute', path: '/field/assignments/route', fields: [f.text('fromLat'), f.text('fromLon')], showResult: true },
    { id: 'sweep', label: 'act.sweepOverdue', path: '/field/assignments/sweep-overdue', showResult: true, noJson: true },
  ],
  actions: [
    { id: 'transition', label: 'act.transition', path: '/field/assignments/{id}/transition', fields: [f.select('action', ['accept', 'start', 'suspend', 'resume', 'cancel'], 'fld.transition', true)], reason: 'body' },
    { id: 'reassign', label: 'act.reassign', path: '/field/assignments/{id}/reassign', fields: [f.text('assignee', undefined, true)], reason: 'body' },
    { id: 'review', label: 'act.review', path: '/field/assignments/{id}/review', fields: [f.select('decision', ['APPROVE', 'RETURN'], undefined, true), f.area('remarks')] },
  ],
};

const schedules: ScreenDef = {
  id: 'schedules',
  title: 'mod.field.schedules',
  list: '/field/schedules',
  unpaged: true,
  columns: [c('id'), c('templateKey'), c('frequency'), c('selectionRule'), c('nextRunAt')],
  rowKey: ['id', 'scheduleId'],
  create: { id: 'define', label: 'act.defineSchedule', path: '/field/schedules', fields: [f.text('templateKey', undefined, true), f.select('frequency', ['DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY'], undefined, true), f.json('selectionRule', 'fld.selectionRule', true), f.json('rotation')] },
  toolbar: [
    { id: 'runDue', label: 'act.runDue', path: '/field/schedules/run-due', showResult: true, noJson: true },
    { id: 'select', label: 'act.randomSelection', path: '/field/selections', fields: [f.text('population', 'fld.population', true), f.num('count', undefined, true), f.text('seedCommitment')], showResult: true, hint: 'mod.field.selectionHint' },
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
  create: { id: 'register', label: 'act.registerDevice', path: '/field/devices', fields: [f.text('model'), f.text('publicKey', undefined, true)] },
  actions: [
    { id: 'status', label: 'act.setStatus', path: '/field/devices/{id}/status', fields: [f.select('status', ['ACTIVE', 'LOST', 'REVOKED'], undefined, true)], reason: 'body', hint: 'mod.field.revokeHint' },
    { id: 'policy', label: 'act.queuePolicy', path: '/field/devices/{id}/policy', fields: [f.num('maxPackets'), f.num('maxAgeHours'), f.bool('requireLocation')] },
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
  actions: [{ id: 'resolve', label: 'act.resolve', path: '/field/conflicts/{id}/resolve', fields: [f.select('choice', ['KEEP_SERVER', 'KEEP_DEVICE', 'MERGE'], undefined, true), f.json('merged')], reason: 'body' }],
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
  create: { id: 'save', label: 'act.saveUnit', path: '/field/units', fields: [f.text('id', 'fld.unitId', true), f.text('name', undefined, true), f.text('kind'), f.json('skills'), f.text('baseLocation')] },
  actions: [{ id: 'status', label: 'act.setStatus', path: '/field/units/{id}/status', fields: [f.select('status', ['AVAILABLE', 'BUSY', 'OFF_DUTY', 'OUT_OF_SERVICE'], undefined, true)] }],
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
            setAct({ id: 'new', label: 'act.createJob', path: '/field/jobs', fields: [f.text('kind', undefined, true), f.text('priority'), f.text('lat', undefined, true), f.text('lon', undefined, true), f.text('address'), f.area('description'), f.json('requiredSkills')] })
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
  create: { id: 'define', label: 'act.defineLayer', path: '/geo/layers', fields: [f.text('layer', undefined, true), f.text('name', undefined, true), f.json('levels', 'fld.levels', true)] },
  actions: [{ id: 'import', label: 'act.importBoundaries', path: '/geo/layers/{layer}/import', fields: [f.json('featureCollection', 'fld.geojson', true), f.text('level'), f.date('effectiveFrom')], noJson: true, hint: 'mod.geo.importHint' }],
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
  create: { id: 'put', label: 'act.putFeature', path: '/geo/layers/{layer}/features', fields: [f.text('code', undefined, true), f.text('name', undefined, true), f.text('level', undefined, true), f.text('parentId'), f.json('geometry', 'fld.geometry', true)] },
  toolbar: [
    { id: 'resolve', label: 'act.resolvePoint', method: 'GET', path: '/geo/resolve', fields: [f.text('layer', undefined, true), f.text('level'), f.text('lat', undefined, true), f.text('lon', undefined, true)], showResult: true, noJson: true },
    { id: 'nearest', label: 'act.nearest', method: 'GET', path: '/geo/nearest', fields: [f.text('layer', undefined, true), f.text('objectType'), f.text('lat', undefined, true), f.text('lon', undefined, true), f.num('n'), f.num('maxKm')], showResult: true, noJson: true },
    { id: 'overlaps', label: 'act.overlaps', path: '/geo/overlaps', fields: [f.text('layer', undefined, true), f.text('level')], showResult: true },
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
  create: { id: 'define', label: 'act.defineFence', path: '/geo/fences', fields: [f.text('name', undefined, true), f.json('geometry', 'fld.geometry'), f.text('featureId'), f.num('bufferMeters'), f.text('objectType')], showResult: true },
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
  create: { id: 'register', label: 'act.registerExternal', path: '/geo/external-layers', fields: [f.text('ref', undefined, true), f.text('name', undefined, true), f.select('kind', ['WFS', 'ARCGIS_REST', 'GEOJSON_URL'], undefined, true), f.text('url', undefined, true, { description: 'admin.egressHint' })] },
  actions: [
    { id: 'query', label: 'act.query', path: '/geo/external-layers/{ref}/query', fields: [f.json('filter')], showResult: true },
    { id: 'import', label: 'act.import', path: '/geo/external-layers/{ref}/import', fields: [f.text('layer', undefined, true), f.text('level')], showResult: true },
  ],
};

export function GeoPage() {
  return <ModulePage title="mod.geo.title" description="mod.geo.desc" screens={[layers, features, fencesScreen, external]} extra={[{ id: 'viewer', label: 'mod.geo.viewer', node: <GeofenceViewer /> }]} />;
}
