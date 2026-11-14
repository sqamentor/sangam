import { readFileSync as r, writeFileSync as w } from 'node:fs';
const ui = 'D:/GITHUB  Project/Custom WorkFlow/sangam/sangam-ui/';
const rest = 'D:/GITHUB  Project/Custom WorkFlow/sangam/sangam-rest/';
function ed(p, pairs) {
  let c = r(p, 'utf8').replace(/\r\n/g, '\n');
  for (const [a, b] of pairs) {
    if (typeof a === 'string' ? !c.includes(a) : !a.test(c)) console.log('NOMATCH', p.split('/').pop(), String(a).slice(0, 70));
    c = c.replace(a, typeof b === 'string' ? () => b : b);
  }
  w(p, c);
}
const E = '${E}';
// ---- plugin host: same-origin import, no blob
ed(ui + 'src/plugins/host.tsx', [
  [/ \* verified bundles at GET \/plugins\/ui\.[\s\S]*?A plugin failure/, ` * verified bundles at GET /plugins/ui (authenticated). Entry files are public verified assets: the shell imports them
 * directly from the same origin, so the strict script-src 'self' holds, and relative imports inside a plugin work.
 * Plugin code must not contain secrets, because the assets are readable without a token.
 * The module either exports mount(element, ctx), placed at the manifest's contributes[].slot, or a default
 * { contributions: [...] }.
 * A plugin failure`],
  [/  const path = `\/plugins\/ui[\s\S]*?URL\.revokeObjectURL\(blobUrl\);\n  \}/, `  const url = new URL(buildUrl(\`/plugins/ui/\${encodeURIComponent(m.id)}/assets/\${m.entry.split('/').map(encodeURIComponent).join('/')}\`), window.location.origin);
  if (url.origin !== window.location.origin) throw new Error(\`plugin \${m.id}: not same-origin\`);
  const mod = (await import(/* @vite-ignore */ url.href)) as { default?: { contributions?: Contribution[] }; mount?: Contribution['render'] };`],
  ["import { http, raw } from '../api/client';", "import { buildUrl, http } from '../api/client';"],
]);
ed(ui + 'index.html', [["script-src 'self' blob:;", "script-src 'self';"]]);
ed(ui + 'examples/ui-plugin-hello/README.md', [
  [/\*\*One file only\.\*\*.*/, '**No secrets in plugin code.** Entry files and assets are served without a token (the manifest list stays authenticated), so anyone who can reach the server can read them. Call the API from `ctx.api` at run time instead.\n\nThe shell imports the entry with a same-origin dynamic import under the strict `script-src \'self\'` policy, so relative imports within the bundle work.'],
]);
ed(ui + 'README.md', [
  [/- \*\*UI plugins:\*\*[^\n]*\n- \*\*CSP conflict:\*\*[^\n]*/, "- **UI plugins:** `src/plugins/host.tsx` reads the authenticated list at `/plugins/ui` and imports entries from `/plugins/ui/{id}/assets/{entry}` by same-origin dynamic import, under strict `script-src 'self'`. Needs anonymous GET on that asset route (WS-CONFORMANCE). Plugin code must not contain secrets; see `examples/ui-plugin-hello/README.md`."],
]);
// ---- multi-key sort: UI
ed(ui + 'src/api/endpoints.ts', [
  [/function sortParams\(sort\?: string\): Query \{[\s\S]*?\n\}/, `function sortParams(sort?: string): Query {
  if (!sort) return {};
  const keys = sort.split(',').filter(Boolean);
  return { sortBy: keys.map((k) => k.replace(/^-/, '')).join(','), sortOrder: keys.map((k) => (k.startsWith('-') ? 'desc' : 'asc')).join(',') };
}

/** Inbox columns → the contract's task sort keys. */
const TASK_SORT: Record<string, string> = { received: 'created', created: 'created', slaDue: 'due', due: 'due', priority: 'priority', subject: 'name', name: 'name' };
function taskSort(sort?: string): string | undefined {
  const out = (sort ?? '').split(',').filter(Boolean).flatMap((k) => {
    const m = TASK_SORT[k.replace(/^-/, '')];
    return m ? [(k.startsWith('-') ? '-' : '') + m] : [];
  });
  return out.join(',') || undefined;
}`],
  ['...sortParams(q.sort),\n      };\n      return A.mapPage(await http.page<Record<string, unknown>>(\'/tasks\'', "...sortParams(taskSort(q.sort)),\n      };\n      return A.mapPage(await http.page<Record<string, unknown>>('/tasks'"],
  [/ \/\/ \[P\]( \([^)]*\))?/g, (m, g) => (g ? ` //${g}` : '')],
  [/ \/\/ \[C\]( \(\+decisionKey filter \[P\]\))?/g, ''],
  [/ \/\/ \[C\][^\n]*/g, ''],
  [/\/\*\* \[C\] /g, '/** '],
  [/\[C\] /g, ''],
  [/ \* - Calls marked \[C\][\s\S]*?\* - Calls marked \[E\][^\n]*\n/, ' * All paths were checked against the contract. [E] marks the unchanged Operaton REST API at /engine-rest.\n'],
  [/\/\*\* Delegation-of-powers DMN tables \(A04\)\. \[P\] — /, '/** Delegation-of-powers DMN tables (A04): '],
  [/ — \[P\]$/gm, ''],
  [/ — all \[P\]$/gm, ''],
  [/ \[P\] GET \(contract has POST only\)/g, ''],
  [/the registries are \[P\]\. /, ''],
  [/Contract resources: tenants, seats; /, ''],
]);
ed(ui + 'src/areas/tasklist/TaskInbox.tsx', [
  [/\/\*\* The contract sorts by one key;[\s\S]*?\n\}\n\nexport function TaskInbox/, 'export function TaskInbox'],
  ['slaState: sla || undefined, sort }), [section, q, sla, sort, seat?.seatId]', "slaState: sla || undefined, sort: sort2 ? `${sort},${sort2}` : sort }), [section, q, sla, sort, sort2, seat?.seatId]"],
  ["          hint={t('task.thenSortHint')}\n", ''],
  ["['received', 'slaDue', 'amount', 'reference'].flatMap", "['received', 'slaDue'].flatMap"],
  ['rows={secondary(list.data.items, sort, sort2)}', 'rows={list.data.items}'],
  [/key: 'reference',\n        header: t\('task.reference'\),\n        sortable: true,/, "key: 'reference',\n        header: t('task.reference'),"],
  ["header: t('task.amount'), sortable: true,", "header: t('task.amount'),"],
]);
// ---- server: comma-separated sort
ed(rest + 'src/main/java/org/sangam/rest/adapter/operaton/OperatonTaskAdapter.java', [
  [/private static void sort\(TaskQuery q, String by, String order\) \{\n        String b = by == null \? "created" : by;\n        switch \(b\) \{/, `/** sortBy and sortOrder may each be a comma-separated list, applied in order; a missing order means desc. */
    private static void sort(TaskQuery q, String by, String order) {
        String[] keys = (by == null ? "created" : by).split(",");
        String[] orders = (order == null ? "" : order).split(",");
        for (int i = 0; i < keys.length; i++) {
            sortOne(q, keys[i].trim(), i < orders.length ? orders[i].trim() : "");
        }
    }

    private static void sortOne(TaskQuery q, String b, String order) {
        switch (b) {`],
]);
ed(rest + 'api/gen_openapi.py', [
  ['q("sortBy", enum=["created", "due", "priority", "name"]), q("sortOrder", enum=["asc", "desc"])', 'q("sortBy", desc="created, due, priority or name; a comma-separated list sorts by several keys"), q("sortOrder", desc="asc or desc per key, comma-separated")'],
]);
// yaml: only the listTasks operation
{
  const p = rest + 'api/sangam-api.yaml';
  const raw = r(p, 'utf8');
  const nl = raw.includes('\r\n') ? '\r\n' : '\n';
  const c = raw.replace(/\r\n/g, '\n');
  const i = c.indexOf('operationId: listTasks');
  const j = c.indexOf('\n  /tasks/', i);
  let seg = c.slice(i, j);
  const before = seg;
  seg = seg.replace(/( +- name: sortBy\n +in: query\n +schema:\n +type: string\n) +enum:\n(?: +- \w+\n)+/, '$1');
  seg = seg.replace(/( +- name: sortOrder\n +in: query\n +schema:\n +type: string\n) +enum:\n(?: +- \w+\n)+/, '$1');
  seg = seg.replace(/( +)- name: sortBy\n( +)in: query\n/, '$1- name: sortBy\n$2in: query\n$2description: created, due, priority or name; a comma-separated list sorts by several keys\n');
  seg = seg.replace(/( +)- name: sortOrder\n( +)in: query\n/, '$1- name: sortOrder\n$2in: query\n$2description: asc or desc per key, comma-separated\n');
  if (seg === before) console.log('NOMATCH yaml');
  w(p, (c.slice(0, i) + seg + c.slice(j)).replace(/\n/g, nl));
}
// ---- operaton screens
ed(ui + 'src/modules/operaton.tsx', [
  ["fields: [f.num('historyTimeToLive', undefined, true)], noJson: true },", "fields: [f.num('historyTimeToLive')], noJson: true, hint: 'mod.op2.ttlClear', sendNulls: ['historyTimeToLive'] },"],
  [`    { id: 'priority', label: 'act.overridePriority', method: 'PUT', path: \`${E}/job-definition/{id}/jobPriority\``, `    { id: 'retries', label: 'ops.setRetries', method: 'PUT', path: \`${E}/job-definition/{id}/retries\`, fields: [f.num('retries', undefined, true)], noJson: true },\n    { id: 'priority', label: 'act.overridePriority', method: 'PUT', path: \`${E}/job-definition/{id}/jobPriority\``],
  ["sections: [{ id: 'res', label: 'ops.resources', path: `" + E + "/deployment/{id}/resources` }],", "sections: [{ id: 'res', label: 'ops.resources', path: `" + E + "/deployment/{id}/resources` }, { id: 'count', label: 'mod.op3.instanceCount', path: `" + E + "/process-instance/count?deploymentId={id}` }],"],
  ["f.text('assignee'), f.text('owner'), f.num('priority')], noJson: true },\n};", "f.text('assignee'), f.text('owner'), f.num('priority'), f.text('tenantId')], noJson: true },\n  toolbar: [{ id: 'batchMod', label: 'act.batchModification', path: `" + E + "/modification/executeAsync`, fields: [f.text('processDefinitionId', undefined, true), f.json('processInstanceIds', undefined, true), f.json('instructions', undefined, true), f.bool('skipCustomListeners'), f.bool('skipIoMappings')], noJson: true, showResult: true }],\n};"],
  ["screens={[filters, filterGrants]}", "screens={[filters, filterGrants]} extra={[{ id: 'board', label: 'mod.op3.board', node: <FilterBoard /> }, { id: 'search', label: 'mod.op3.search', node: <TaskSearch /> }, { id: 'url', label: 'mod.op3.attachUrl', node: <AttachmentByUrl /> }]}"],
  ["import { PluginSlot } from '../plugins/host';", "import { PluginSlot } from '../plugins/host';\nimport { AttachmentByUrl, FilterBoard, TaskSearch } from './operaton3';"],
  ["      <Section title={t('mod.op.memberships')}>", "      <AccountEdit id={me?.userId ?? ''} profile={profile.data} onSaved={profile.reload} onTenants={tenants.reload} />\n      <Section title={t('mod.op.memberships')}>"],
  ['export function Account() {', `function AccountEdit({ id, profile, onSaved, onTenants }: { id: string; profile?: Row; onSaved: () => void; onTenants: () => void }) {
  const { t } = useTranslation();
  const [p, setP] = useState<Row | null>(null);
  const [tenant, setTenant] = useState('');
  const act = useAction();
  const cur = p ?? profile ?? {};
  const set = (k: string) => (v: string) => setP({ ...cur, [k]: v });
  const member = (add: boolean) => void act.run((k) => (add ? http.put(\`${E}/tenant/\${encodeURIComponent(tenant)}/user-members/\${encodeURIComponent(id)}\`, undefined, k) : http.del(\`${E}/tenant/\${encodeURIComponent(tenant)}/user-members/\${encodeURIComponent(id)}\`, undefined, k)).then(() => true)).then((ok) => ok && onTenants());
  return (
    <Section title={t('mod.op3.editProfile')}>
      <TextField label={t('fld.firstName')} value={String(cur.firstName ?? '')} onChange={set('firstName')} />
      <TextField label={t('fld.lastName')} value={String(cur.lastName ?? '')} onChange={set('lastName')} />
      <TextField label={t('fld.email')} type="email" value={String(cur.email ?? '')} onChange={set('email')} />
      <button type="button" disabled={!p || act.busy} onClick={() => void act.run((k) => http.put(\`${E}/user/\${encodeURIComponent(id)}/profile\`, { ...cur, id }, k).then(() => true)).then((ok) => { if (ok) { setP(null); announce(t('common.saved')); onSaved(); } })}>{t('common.save')}</button>
      <TextField label={t('fld.tenantId')} value={tenant} onChange={setTenant} />
      <div className="toolbar">
        <button type="button" disabled={!tenant} onClick={() => member(true)}>{t('act.addToTenant')}</button>
        <button type="button" disabled={!tenant} onClick={() => member(false)}>{t('act.removeFromTenant')}</button>
      </div>
      <ProblemAlert error={act.error} />
    </Section>
  );
}

export function Account() {`],
]);
ed(ui + 'src/modules/operaton2.tsx', [
  ["columns: [c('name'), c('type'), c('value', 'fld.value'), c('activityInstanceId'), c('executionId')],", "columns: [c('name'), c('type'), c('value', 'fld.value'), c('valueInfo'), c('activityInstanceId'), c('executionId')],"],
  ["f.text('variableName'), f.text('activityInstanceIdIn'", "f.text('variableNameLike', 'fld.variableName'), f.text('activityInstanceIdIn'"],
  ["filters: [f.text('type'), f.bool('suspended')],\n  columns: [c('id'), c('type'), c('totalJobs')", "filters: [f.text('batchId'), f.text('type'), f.bool('suspended')],\n  columns: [c('id'), c('type'), c('totalJobs')"],
]);
// ---- instance page
ed(ui + 'src/areas/ops/InstanceDetail.tsx', [
  ['function ActivityTree({ node }: { node: ActivityInstance }) {\n  const { t } = useTranslation();\n  return (', "function ActivityTree({ node, q = '' }: { node: ActivityInstance; q?: string }) {\n  const { t } = useTranslation();\n  const hit = (n: ActivityInstance): boolean => `${n.activityName ?? ''} ${n.activityId}`.toLowerCase().includes(q.toLowerCase()) || (n.children ?? []).some(hit);\n  if (q && !hit(node)) return null;\n  return ("],
  ['node.children.map((c) => <ActivityTree key={c.id} node={c} />)', 'node.children.map((c) => <ActivityTree key={c.id} node={c} q={q} />)'],
  ['{tree.data && <ActivityTree node={tree.data} />}', "<TextField label={t('common.search')} value={treeQ} onChange={setTreeQ} />\n        {tree.data && <ActivityTree node={tree.data} q={treeQ} />}"],
  ["  const [tab, setTab] = useState('tree');", "  const [tab, setTab] = useState('tree');\n  const [treeQ, setTreeQ] = useState('');"],
  ['            {piActions.map((p) => (', "            <Link to={`/ops/incidents?processInstanceId=${encodeURIComponent(id)}`}>{t('nav.incidents')}</Link>\n            {piActions.map((p) => ("],
  ["  const [mode, setMode] = useState<'BUSINESS' | 'TECHNICAL'>('BUSINESS');", "  const [mode, setMode] = useState<'BUSINESS' | 'TECHNICAL'>('BUSINESS');\n  const { hasRole } = useAuth();\n  const [skipL, setSkipL] = useState(false);\n  const [skipIo, setSkipIo] = useState(false);"],
  ['void act.run((k) => api.processes.cancel(instanceId, mode, reason, k).then(() => true))', "void act.run((k) => (mode === 'TECHNICAL' && (skipL || skipIo) ? http.del(`/engine-rest/process-instance/${encodeURIComponent(instanceId)}`, { skipCustomListeners: skipL, skipIoMappings: skipIo, deleteReason: reason }, k) : api.processes.cancel(instanceId, mode, reason, k)).then(() => true))"],
  ["{t(`ops.cancelMode${m}`)}\n          </label>\n        ))}\n      </fieldset>", "{t(`ops.cancelMode${m}`)}\n          </label>\n        ))}\n      </fieldset>\n      {mode === 'TECHNICAL' && hasRole('PLATFORM_OPERATOR') && (\n        <>\n          <Checkbox label={t('fld.skipCustomListeners')} checked={skipL} onChange={setSkipL} />\n          <Checkbox label={t('fld.skipIoMappings')} checked={skipIo} onChange={setSkipIo} />\n        </>\n      )}"],
]);
ed(ui + 'src/areas/ops/IncidentsRecon.tsx', [
  ["  const list = usePaged((p) => api.ops.incidents({ ...p, type: type || undefined, q, processDefinitionKey: defKey }), [type, q, defKey]);", "  const piId = sp.get('processInstanceId') ?? undefined;\n  const list = usePaged((p) => api.ops.incidents({ ...p, type: type || undefined, q, processDefinitionKey: defKey, processInstanceId: piId }), [type, q, defKey, piId]);"],
]);
// ---- task detail, start
ed(ui + 'src/areas/tasklist/TaskDetail.tsx', [
  ["import { PluginSlot } from '../../plugins/host';", "import { PluginSlot } from '../../plugins/host';\nimport { AddVariables, TaskDates } from '../../modules/operaton3';"],
  ['      <PluginSlot point="task.tab" ctx={{ taskId: id }} />', '      {hasRole(\'PLATFORM_OPERATOR\') && <TaskDates taskId={id} />}\n      <PluginSlot point="task.tab" ctx={{ taskId: id }} />'],
  ['  const { me, seat } = useAuth();', '  const { me, seat, hasRole } = useAuth();'],
  ['readOnly={!canAct} changed={d.changedSinceLastSeen} />', 'readOnly={!canAct} changed={d.changedSinceLastSeen} />\n          {canAct && !d.form && <AddVariables values={values} onChange={setValues} />}'],
]);
ed(ui + 'src/areas/tasklist/StartAndCases.tsx', [
  ["import { Badge, DataTable,", "import { AddVariables } from '../../modules/operaton3';\nimport { Badge, DataTable,"],
  ["  const [chosen, setChosen] = useState<{ key: string; name: string; kind: 'PROCESS' | 'CASE'; formKey?: string } | null>(null);", "  const [chosen, setChosen] = useState<{ key: string; name: string; kind: 'PROCESS' | 'CASE'; formKey?: string } | null>(null);\n  const [search, setSearch] = useState('');"],
  ['      {!chosen && (\n        <ul className="cards">\n          {cat.data?.map((c) => (', "      {!chosen && <TextField label={t('common.search')} value={search} onChange={setSearch} />}\n      {!chosen && (\n        <ul className=\"cards\">\n          {cat.data?.filter((c) => c.name.toLowerCase().includes(search.toLowerCase())).map((c) => ("],
  ['errors={errors} />}\n      <div className="toolbar">\n        <button type="button" onClick={onCancel}>', 'errors={errors} />}\n      <AddVariables values={values} onChange={setValues} />\n      <div className="toolbar">\n        <button type="button" onClick={onCancel}>'],
]);
// ---- i18n
const K = { 'mod.op3.criteria': ['Criteria', 'मापदंड'], 'mod.op3.criterion': ['Criterion', 'मापदंड'], 'mod.op3.addCriterion': ['Add criterion', 'मापदंड जोड़ें'], 'mod.op3.autoRefresh': ['Refresh automatically', 'स्वतः ताज़ा करें'], 'mod.op3.cardVariables': ['Variables shown on tasks', 'कार्यों पर दिखने वाले चर'], 'mod.op3.cardVariablesHint': ['name:label pairs separated by commas.', 'अल्पविराम से अलग नाम:लेबल जोड़े।'], 'mod.op3.everyone': ['Visible to all users', 'सभी उपयोगकर्ताओं को दृश्य'], 'mod.op3.board': ['Filter board', 'फ़िल्टर बोर्ड'], 'mod.op3.search': ['Task search', 'कार्य खोज'], 'mod.op3.sortBy': ['Sort by', 'इससे क्रमित करें'], 'mod.op3.order': ['Order', 'क्रम'], 'mod.op3.sortVariable': ['Sort by variable', 'चर से क्रमित करें'], 'mod.op3.sortVariableHint': ['If set, this replaces the sort field.', 'भरने पर यह क्रम फ़ील्ड का स्थान लेता है।'], 'mod.op3.dates': ['Due and follow-up dates', 'नियत और अनुवर्ती तिथियाँ'], 'mod.op3.attachUrl': ['Attachment by URL', 'URL से संलग्नक'], 'mod.op3.addVariable': ['Add variables', 'चर जोड़ें'], 'mod.op3.vt_string': ['Text', 'पाठ'], 'mod.op3.vt_number': ['Number', 'संख्या'], 'mod.op3.vt_boolean': ['True / false', 'सत्य / असत्य'], 'mod.op3.instanceCount': ['Running instances', 'चालू उदाहरण'], 'mod.op3.editProfile': ['Edit profile and tenants', 'प्रोफ़ाइल और टेनेंट संपादित करें'], 'act.batchModification': ['Batch modification', 'बैच संशोधन'], 'fld.batchId': ['Batch id', 'बैच आईडी'], 'fld.valueInfo': ['Value info', 'मान विवरण'], 'fld.variableNameLike': ['Variable name', 'चर का नाम'], 'fld.skipCustomListeners': ['Skip custom listeners', 'कस्टम लिसनर छोड़ें'], 'fld.skipIoMappings': ['Skip I/O mappings', 'I/O मैपिंग छोड़ें'], 'fld.processInstanceIds': ['Instance ids (JSON list)', 'उदाहरण आईडी (JSON सूची)'], 'fld.instructions': ['Instructions (JSON)', 'निर्देश (JSON)'], 'fld.description': ['Description', 'विवरण'], 'fld.type': ['Type', 'प्रकार'], 'fld.followUp': ['Follow-up', 'अनुवर्ती'], 'fld.due': ['Due', 'नियत'], 'fld.tenantId': ['Tenant id', 'टेनेंट आईडी'] };
for (const [i, l] of ['en', 'hi'].entries()) {
  const p = ui + 'src/i18n/locales/' + l + '.json';
  const j = JSON.parse(r(p, 'utf8'));
  for (const [k, v] of Object.entries(K)) { const ps = k.split('.'); let c = j; for (const x of ps.slice(0, -1)) c = c[x] ??= {}; if (!(ps.at(-1) in c)) c[ps.at(-1)] = v[i]; }
  w(p, JSON.stringify(j, null, 2) + '\n');
}
const dp = ui + 'scripts/i18n-dynamic.json';
const d = JSON.parse(r(dp, 'utf8')); d['mod.op3.vt_'] = ['string', 'number', 'boolean']; w(dp, JSON.stringify(d, null, 2) + '\n');
// ---- parity doc
{
  const p = ui + 'OPERATON-UI-PARITY.md';
  let c = r(p, 'utf8').replace(/\r\n/g, '\n');
  const start = c.indexOf('## Cockpit');
  const end = c.indexOf('## Second pass');
  const tail = c.indexOf('## Action level');
  const audit = c.slice(c.indexOf('\n', c.indexOf('**Closed after the audit:**'))).trim();
  const top = `# Operaton UI parity table

Every page, plugin and action found in \`reference/operaton/webapps/frontend/ui\` (Cockpit, Tasklist, Admin, Welcome) and \`reference/operaton/webapps-neo/frontend/src\`, mapped to a Sangam screen. **Status is about code: nothing here has been run in a browser or against a server.**

No Operaton UI code was copied; screens are written from the REST contract. Every \`/engine-rest\` screen is limited to platform roles in the UI (owner decision); server enforcement is WS-REST's.

## Pages and plugins

| Operaton area | Sangam screen | Route |
|---|---|---|
| Cockpit dashboard, metrics | OpsDashboard; metrics chart | \`/ops\`, \`/admin/engine\` |
| Processes, thumbnails, definition page, version selector, instance search | ProcessDefinitions, DefinitionDetail | \`/ops/processes\` |
| Token, history, duration, job-definition, call-activity overlays | BpmnDiagram | same |
| Definition TTL, suspension, delete, job definitions, called definitions | Engine views | \`/ops/engine\` |
| Instance page: tree with filter, variables, incidents, called instances, cancel, suspend, modify, restart | InstanceDetail | \`/ops/instances/:id\` |
| Jobs, external tasks, user tasks, identity links, variables | Jobs; Engine views | \`/ops/jobs\`, \`/ops/engine\` |
| Decisions, DRD page, matched rules | Decisions, DrdPage | \`/ops/decisions\`, \`/ops/drd/:id\` |
| Deployments, preview, download, redeploy, delete with counts | Deployments; Engine views | \`/ops/deployments\`, \`/ops/engine\` |
| Batches, migration, batch modification | Batches, Migration; Engine views | \`/ops/batches\`, \`/ops/migration\` |
| Task dashboard, reports, cleanup, operation log | TaskDashboard, Reports, Cleanup, OperatorAudit | see nav |
| Tasklist: list, multi-key sort, claim, delegate, complete, forms, comments, diagram | TaskInbox, TaskDetail | \`/tasks\` |
| Filters: editor, criteria, variables, sharing, board; task search; sort by variable | TaskFiltersPage | \`/tasks/filters\` |
| Start process, standalone task | StartCatalog; Engine views | \`/start\`, \`/ops/engine\` |
| Admin: users, groups, tenants, authorizations, system, metrics, diagnostics | EngineAdminPage | \`/admin/engine\` |
| Welcome, account | Welcome, Account | \`/welcome\`, \`/account\` |
| Setup | Removed by owner decision: install-time bootstrap | — |
| Neo plugin host, points | \`src/plugins/host.tsx\`, \`/admin/plugins\` | \`/x/:pluginId/:id\` |

## Known limits

| Item | Limit |
|---|---|
| Embedded forms | Parsed into Sangam forms; \`cam-script\` logic does not run |
| External forms | Link only, no iframe |
| Membership pickers | Typed ids, not search pickers (Operaton has pick lists) |
| Multi-key sort | Server keys: created, due, priority, name only |
| Matched rules | Depends on the rule id in \`/history/decisions\` and dmn-js \`data-row-id\`, both unverified |
| Plugins | Need anonymous asset GET from WS-CONFORMANCE |
| Locales de, es, fr, nl | Out of scope for now |

## Action-level audit (historic)

Taken before the later passes, so its Partial, Missing and In progress marks are out of date; **"Pages and plugins" and "Known limits" above are current.** Its rows are kept as the action inventory.

`;
  c = top + audit + '\n';
  void start; void end; void tail;
  w(p, c);
}
