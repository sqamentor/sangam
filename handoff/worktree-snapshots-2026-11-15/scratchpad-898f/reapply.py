import os, sys
os.chdir(sys.argv[1])
def ed(p, pairs):
    s = open(p, encoding='utf-8').read()
    for a, b in pairs:
        assert s.count(a) == 1, (p, a[:70])
        s = s.replace(a, b)
    open(p, 'w', encoding='utf-8', newline='').write(s)

ed('engine.tsx', [
("export function buildRequest(fields: FormField[], values: FormValues, extraJson: string): { body: Row; query: Query; error?: string } {\n  const body: Row = {};\n  const query: Query = {};\n  for (const f of flat(fields)) {\n    if (!f.key) continue;",
"""/**
 * Operaton REST reads dates as yyyy-MM-dd'T'HH:mm:ss.SSSZ (JacksonConfigurator, DateConverter): an RFC 822 offset such
 * as +0000. ISO 8601 "Z" and a bare datetime-local value are both rejected with 400.
 */
export function engineDate(v: string | Date): string {
  return new Date(v).toISOString().replace('Z', '+0000');
}

export const isEnginePath = (p: string): boolean => p === '/engine-rest' || p.startsWith('/engine-rest/') || p.startsWith('/engine-rest?');

const isoDate = (v: string): string => new Date(v).toISOString();

/** Filter values to list query; date-time filters of an engine list are sent in the engine date format. */
export function filterQuery(fields: FormField[], values: FormValues, enginePath: boolean): Query {
  const dt = new Set(flat(fields).filter((x) => x.type === 'datetime' && x.subtype === 'datetime').map((x) => x.key));
  return Object.fromEntries(Object.entries(values).map(([k, v]) => [k, enginePath && dt.has(k) && typeof v === 'string' && v ? engineDate(v) : (v as string)]));
}

export function buildRequest(fields: FormField[], values: FormValues, extraJson: string, dateOut: (v: string) => string = isoDate): { body: Row; query: Query; error?: string } {
  const body: Row = {};
  const query: Query = {};
  for (const f of flat(fields)) {
    if (!f.key || f.inPath) continue;"""),
("typeof v === 'string') v = new Date(v).toISOString();", "typeof v === 'string') v = dateOut(v);"),
("    const req = buildRequest(fields, values, json);", "    const req = buildRequest(fields, values, json, isEnginePath(act.path) ? engineDate : undefined);"),
("  const q: Query = { ...def.listQuery, ...Object.fromEntries(Object.entries(filters).filter(([k]) => !def.list.includes(`{${k}}`)).map(([k, v]) => [k, v as string])) };",
 "  const q: Query = { ...def.listQuery, ...filterQuery(def.filters ?? [], Object.fromEntries(Object.entries(filters).filter(([k]) => !def.list.includes(`{${k}}`))), isEnginePath(def.list)) };"),
("  q: (field: FormField): FormField => ({ ...field, inQuery: true }),",
 "  q: (field: FormField): FormField => ({ ...field, inQuery: true }),\n  /** Fills a {placeholder} of the action path only (e.g. a new variable's name); not sent in body or query. */\n  path: (field: FormField): FormField => ({ ...field, inPath: true }),"),
])
ed('operaton.tsx', [
("sendNulls: ['historyTimeToLive'] },\n    { id: 'suspend', label: 'act.setSuspension', method: 'PUT', path: `${E}/process-definition/{id}/suspended`",
 "sendNulls: ['historyTimeToLive'] },\n    // Operaton's TTL dialog follow-up: re-date the removal time of this definition's historic instances (async batch).\n    { id: 'removal', label: 'act.setRemovalTime', path: `${E}/history/process-instance/set-removal-time`, fields: [f.text('historicProcessInstanceQuery.processDefinitionId', 'fld.processDefinitionId', true), f.bool('calculatedRemovalTime'), f.dt('absoluteRemovalTime'), f.bool('clearedRemovalTime'), f.bool('hierarchical')], noJson: true, hint: 'mod.op4.removalHint', showResult: true, defaults: (r) => ({ 'historicProcessInstanceQuery.processDefinitionId': r?.id, calculatedRemovalTime: true, hierarchical: true }) },\n    { id: 'suspend', label: 'act.setSuspension', method: 'PUT', path: `${E}/process-definition/{id}/suspended`"),
("    { id: 'edit', label: 'act.update', method: 'PUT', path: `${E}/authorization/{id}`, fields: [f.text('resourceId', undefined, true), f.text('userId'), f.text('groupId'), f.json('permissions', undefined, true)], noJson: true, defaults: (r) => ({ resourceId: r?.resourceId,",
 "    // The engine converts permission names against the body's resourceType (AuthorizationDto.update): without it the PUT fails.\n    { id: 'edit', label: 'act.update', method: 'PUT', path: `${E}/authorization/{id}`, fields: [{ key: 'resourceType', label: 'fld.resourceType', type: 'select', values: resOpts, validate: { required: true } }, f.text('resourceId', undefined, true), f.text('userId'), f.text('groupId'), f.json('permissions', undefined, true)], noJson: true, hint: 'mod.op4.permissionsHint', defaults: (r) => ({ resourceType: r?.resourceType === undefined || r?.resourceType === null ? undefined : String(r.resourceType), resourceId: r?.resourceId,"),
])
ed('operaton2.tsx', [
("import { ModulePage, c, f, type ScreenDef } from './engine';", "import { ModulePage, c, engineDate, f, type ScreenDef } from './engine';"),
("export const variablesEngine: ScreenDef = {", "/** Engine value types a user can type (Bytes and File go through the binary upload). */\nconst VAR_TYPES = ['String', 'Integer', 'Long', 'Double', 'Short', 'Boolean', 'Date', 'Null', 'Json', 'Xml', 'Object'];\n\nexport const variablesEngine: ScreenDef = {"),
("  rowKey: ['id'],\n  actions: [\n    { id: 'set', label: 'act.update', method: 'PUT', path: `${E}/process-instance/{processInstanceId}/variables/{name}`, fields: [f.select('type', ['String', 'Integer', 'Long', 'Double', 'Short', 'Boolean', 'Date', 'Null', 'Json', 'Xml', 'Object'], undefined, true), f.json('value', 'fld.value', true), f.json('valueInfo')], noJson: true },",
 "  rowKey: ['id'],\n  // Operaton's \"add variable\" dialog: name, type, value on the instance chosen in the filter.\n  toolbar: [{ id: 'add', label: 'ops.addVariable', method: 'PUT', path: `${E}/process-instance/{processInstanceIdIn}/variables/{name}`, fields: [f.path(f.text('name', undefined, true)), f.select('type', VAR_TYPES, undefined, true), f.json('value', 'fld.value', true), f.json('valueInfo')], noJson: true, hint: 'mod.op4.varValueHint', defaults: () => ({ type: 'String' }) }],\n  actions: [\n    { id: 'set', label: 'act.update', method: 'PUT', path: `${E}/process-instance/{processInstanceId}/variables/{name}`, fields: [f.select('type', VAR_TYPES, undefined, true), f.json('value', 'fld.value', true), f.json('valueInfo')], noJson: true, hint: 'mod.op4.varValueHint', defaults: (r) => ({ type: r?.type === undefined ? undefined : String(r.type), value: r?.value === undefined ? undefined : JSON.stringify(r.value), valueInfo: r?.valueInfo && Object.keys(r.valueInfo as Row).length ? JSON.stringify(r.valueInfo) : undefined }) },"),
("  const dueValue = () => (due === 'now' ? new Date() : new Date(when)).toISOString().replace('Z', '+0000');", "  const dueValue = () => engineDate(due === 'now' ? new Date() : when);"),
])
ed('operaton3.tsx', [
("import { cell, type Row } from './engine';", "import { cell, engineDate, type Row } from './engine';"),
("  const [everyone, setEveryone] = useState(false);\n  const act = useAction();",
 "  // \"Visible to everyone\" is the filter's GLOBAL (type 0) READ authorization: show it as it is, create it once, revoke it on untick.\n  const global = useAsync(() => http.get<Row[]>(`${E}/authorization`, { type: 0, resourceType: 5, resourceId: filter.id }), [filter.id], !!filter.id);\n  const [everyoneEdit, setEveryone] = useState<boolean | null>(null);\n  const everyone = everyoneEdit ?? (global.data?.length ?? 0) > 0;\n  const act = useAction();"),
("        if (everyone) await http.post(`${E}/authorization/create`, { type: 0, resourceType: 5, resourceId: id, userId: '*', permissions: ['READ'] });",
 "        const existing = global.data ?? [];\n        if (everyone && existing.length === 0) await http.post(`${E}/authorization/create`, { type: 0, resourceType: 5, resourceId: id, userId: '*', permissions: ['READ'] });\n        if (!everyone) for (const a of existing) await http.del(`${E}/authorization/${encodeURIComponent(String(a.id))}`);"),
("      <Checkbox label={t('mod.op3.everyone')} checked={everyone} onChange={setEveryone} />\n      <ProblemAlert error={act.error} />",
 "      <Checkbox label={t('mod.op3.everyone')} checked={everyone} onChange={setEveryone} />\n      <ProblemAlert error={act.error ?? global.error} />"),
("  const iso = (v: string) => (v ? new Date(v).toISOString().replace('Z', '+0000') : undefined);", "  const iso = (v: string) => (v ? engineDate(v) : undefined);"),
])
print('ok')
