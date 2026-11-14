import { readFileSync as r, writeFileSync as w } from 'node:fs';
const ui = 'D:/GITHUB  Project/Custom WorkFlow/sangam/sangam-ui/';
function ed(p, pairs) {
  let c = r(ui + p, 'utf8').replace(/\r\n/g, '\n');
  for (const [a, b] of pairs) {
    if (typeof a === 'string' ? !c.includes(a) : !a.test(c)) console.log('NOMATCH', p, String(a).slice(0, 70));
    c = c.replace(a, typeof b === 'string' ? () => b : b);
  }
  w(ui + p, c);
}
const E = '${E}';
ed('src/modules/platform.tsx', [
  ["import { Badge, DataTable, Dialog, Empty, JsonView, Notice, PageHeader, ProblemAlert, Section, SelectField, TextArea, TextField }", "import { Badge, DataTable, Dialog, Empty, JsonView, Notice, ProblemAlert, Section, TextArea, TextField }"],
  ["\nexport { SelectField, PageHeader };\n", "\n"],
]);
// pickers
ed('src/api/types.ts', [["  json?: boolean;", "  json?: boolean;\n  /** Suggest values from this GET path (rows' id and name), as a searchable list. */\n  lookup?: string;"]]);
ed('src/components/FormRenderer.tsx', [
  ["      case 'textfield':\n      default:\n        return wrap(\n          <input", "      case 'textfield':\n      default:\n        return wrap(\n          <>\n          {f.lookup && <LookupList id={`${id}-list`} path={f.lookup} />}\n          <input\n            list={f.lookup ? `${id}-list` : undefined}"],
  ["            onChange={(e) => set(f.key!, e.target.value)}\n          />,\n        );\n    }\n  };", "            onChange={(e) => set(f.key!, e.target.value)}\n          />\n          </>,\n        );\n    }\n  };"],
  ["export type FormValues = Record<string, unknown>;", "export type FormValues = Record<string, unknown>;\n\n/** Native datalist: the user can search the known ids or still type one. */\nfunction LookupList({ id, path }: { id: string; path: string }) {\n  const list = useAsync(() => http.get<{ id: string; name?: string }[]>(path, { maxResults: 200 }), [path]);\n  return (\n    <datalist id={id}>\n      {(Array.isArray(list.data) ? list.data : []).map((x) => (\n        <option key={x.id} value={x.id}>\n          {x.name ?? x.id}\n        </option>\n      ))}\n    </datalist>\n  );\n}"],
  ["import type { FieldChange,", "import { http } from '../api/client';\nimport { useAsync } from '../lib/hooks';\nimport type { FieldChange,"],
]);
let op = r(ui + 'src/modules/operaton.tsx', 'utf8');
op = op.replaceAll("[f.text('groupId', undefined, true)]", "[f.text('groupId', undefined, true, { lookup: `${E}/group` })]").replaceAll("[f.text('tenantId', undefined, true)]", "[f.text('tenantId', undefined, true, { lookup: `${E}/tenant` })]");
w(ui + 'src/modules/operaton.tsx', op);
// dashboard click-through
ed('src/modules/operaton.tsx', [
  [/<KeyValues items=\{\[\[t\('mod\.op\.openTasks'\), d\.data\.open\][\s\S]*?\]\} \/>\n/, `<div className="toolbar" role="group" aria-label={t('mod.op.taskDashboard')}>
            <button type="button" onClick={() => setDrill({})}>{t('mod.op.openTasks')}: {d.data.open}</button>
            <button type="button" onClick={() => setDrill({ assigned: true })}>{t('mod.op.assigned')}: {d.data.assigned}</button>
            <button type="button" onClick={() => setDrill({ unassigned: true })}>{t('mod.op.unassigned')}: {d.data.unassigned}</button>
          </div>
`],
  ["{ key: 'groupName', header: t('nav.groups'), render: (r: Row) => String(r.groupName ?? '—') }", "{ key: 'groupName', header: t('nav.groups'), render: (r: Row) => (r.groupName ? <button type=\"button\" className=\"link\" onClick={() => setDrill({ candidateGroup: String(r.groupName) })}>{String(r.groupName)}</button> : '—') }"],
  ['      <p className="small muted">{fmtDate(new Date().toISOString())}</p>', `      {drill && (
        <>
          <ProblemAlert error={tasks.error} />
          <DataTable caption={t('mod.op.filterTasks')} rows={tasks.data ?? []} rowKey={(r: Row) => String(r.id)} columns={[{ key: 'name', header: t('common.name'), render: (r: Row) => <Link to={\`/tasks/\${encodeURIComponent(String(r.id))}\`}>{String(r.name ?? r.id)}</Link> }, { key: 'assignee', header: t('fld.assignee'), render: (r: Row) => cell(r.assignee) }, { key: 'created', header: t('fld.created'), render: (r: Row) => cell(r.created) }]} />
        </>
      )}
      <p className="small muted">{fmtDate(new Date().toISOString())}</p>`],
  ["  const count = (q: Row) =>", "  const [drill, setDrill] = useState<Row | null>(null);\n  const tasks = useAsync(() => http.get<Row[]>(`/engine-rest/task`, { ...drill, maxResults: 200 } as never), [JSON.stringify(drill)], !!drill);\n  const count = (q: Row) =>"],
]);
// bulk retry
ed('src/modules/operaton2.tsx', [["/** Bulk override:", `/** Operaton's bulk job retry dialog: pick failed jobs of an instance and choose what happens to the due date. */
export function BulkRetry() {
  const { t } = useTranslation();
  const [pi, setPi] = useState('');
  const [active, setActive] = useState('');
  const jobs = useAsync(() => http.get<Row[]>(\`${E}/job\`, { processInstanceId: active, withException: true, maxResults: 200 }), [active], !!active);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [retries, setRetries] = useState('1');
  const [due, setDue] = useState<'keep' | 'now' | 'custom'>('keep');
  const [when, setWhen] = useState('');
  const [res, setRes] = useState<{ ok: number; failed: string[] }>();
  const act = useAction();
  const dueValue = () => (due === 'now' ? new Date() : new Date(when)).toISOString().replace('Z', '+0000');
  const run = () =>
    void act
      .run(async () => {
        const ids = [...picked];
        const r = await Promise.allSettled(
          ids.map(async (id) => {
            const p = \`${E}/job/\${encodeURIComponent(id)}\`;
            if (due !== 'keep') await http.put(\`\${p}/duedate\`, { duedate: dueValue() });
            await http.put(\`\${p}/retries\`, { retries: Number(retries) });
          }),
        );
        return { ok: r.filter((x) => x.status === 'fulfilled').length, failed: ids.filter((_, i) => r[i].status === 'rejected') };
      })
      .then((x) => {
        if (x) {
          setRes(x);
          jobs.reload();
        }
      });
  return (
    <Section title={t('mod.op3.bulkRetry')}>
      <form className="filters" onSubmit={(e) => (e.preventDefault(), setActive(pi.trim()), setPicked(new Set()))}>
        <TextField label={t('fld.processInstanceId')} value={pi} onChange={setPi} required />
        <button type="submit">{t('common.open')}</button>
      </form>
      <ProblemAlert error={jobs.error ?? act.error} />
      {jobs.data && (
        <DataTable caption={t('mod.op2.failedJobs')} rows={jobs.data} rowKey={(r) => String(r.id)} selectable selected={picked} onSelect={setPicked} columns={[{ key: 'id', header: t('ops.job'), render: (r: Row) => String(r.id) }, { key: 'due', header: t('fld.dueDate'), render: (r: Row) => String(r.dueDate ?? '—') }, { key: 'msg', header: t('ops.message'), render: (r: Row) => String(r.exceptionMessage ?? '—') }]} />
      )}
      <TextField label={t('ops.retries')} type="number" min={1} value={retries} onChange={setRetries} />
      <fieldset className="field">
        <legend>{t('fld.dueDate')}</legend>
        {(['keep', 'now', 'custom'] as const).map((d) => (
          <label key={d} className="choice"><input type="radio" name="retry-due" checked={due === d} onChange={() => setDue(d)} /> {t(\`mod.op3.due_\${d}\`)}</label>
        ))}
      </fieldset>
      {due === 'custom' && <TextField label={t('fld.dueDate')} type="datetime-local" value={when} onChange={setWhen} />}
      <button type="button" className="primary" disabled={picked.size === 0 || act.busy || (due === 'custom' && !when)} onClick={run}>{t('common.confirm')}</button>
      {res && <Notice tone={res.failed.length ? 'warn' : 'ok'}>{t('mod.op2.bulkResult', { ok: res.ok, failed: res.failed.join(', ') || '—' })}</Notice>}
    </Section>
  );
}

/** Bulk override:`],
  ["import { JsonView, Notice,", "import { DataTable, JsonView, Notice,"],
]);
ed('src/modules/operaton.tsx', [
  ["import { BinaryVariable, BulkPriority,", "import { BinaryVariable, BulkPriority, BulkRetry,"],
  ["{ id: 'bulk', label: 'mod.op2.bulkPriority', node: <BulkPriority /> },", "{ id: 'bulk', label: 'mod.op2.bulkPriority', node: <BulkPriority /> }, { id: 'bulkRetry', label: 'mod.op3.bulkRetry', node: <BulkRetry /> },"],
]);
// cause links
ed('src/areas/ops/IncidentsRecon.tsx', [
  ["          [t('ops.owner'), i.owner],\n        ]}", "          [t('ops.owner'), i.owner],\n          [t('ops.causeIncident'), cause(i, 'causeIncidentId')],\n          [t('ops.rootCauseIncident'), cause(i, 'rootCauseIncidentId')],\n        ]}"],
  ["export function IncidentDetail() {", "function cause(i: object, key: string) {\n  const id = (i as Record<string, unknown>)[key];\n  return id ? <Link key={key} to={`/ops/incidents/${encodeURIComponent(String(id))}`}>{String(id)}</Link> : undefined;\n}\n\nexport function IncidentDetail() {"],
]);
// wiring
ed('src/app/App.tsx', [
  ["const mOp2 = () =>", "const mPl = () => import('../modules/platform');\nconst AiPage = named(mPl, 'AiPage');\nconst ChannelsPage = named(mPl, 'ChannelsPage');\nconst ModellerToolsPage = named(mPl, 'ModellerToolsPage');\nconst OpsToolsPage = named(mPl, 'OpsToolsPage');\nconst mOp2 = () =>"],
  ['          <Route path="/welcome" element={<Welcome />} />', '          <Route path="/welcome" element={<Welcome />} />\n          <Route path="/m/ai" element={<AiPage />} />\n          <Route path="/m/channels" element={<ChannelsPage />} />\n          <Route path="/modeller/tools" element={g(MODEL, <ModellerToolsPage />)} />\n          <Route path="/ops/tools" element={g(OPS, <OpsToolsPage />)} />'],
]);
ed('src/app/nav.ts', [
  ["      { to: '/m/case-files', label: 'mod.cases.title' },", "      { to: '/m/ai', label: 'mod.ai.title' },\n      { to: '/m/channels', label: 'mod.channels.title' },\n      { to: '/m/case-files', label: 'mod.cases.title' },"],
  ["      { to: '/modeller/compatibility', label: 'nav.compatibility' },", "      { to: '/modeller/compatibility', label: 'nav.compatibility' },\n      { to: '/modeller/tools', label: 'mod.modeller2.title' },"],
  ["      { to: '/ops/audit-log', label: 'nav.operatorAudit' },", "      { to: '/ops/audit-log', label: 'nav.operatorAudit' },\n      { to: '/ops/tools', label: 'mod.ops2.title' },"],
]);
ed('src/app/Layout.tsx', [
  ["import { usePlugins } from '../plugins/host';", "import { usePlugins } from '../plugins/host';\nimport { GlobalSearch } from '../modules/platform';"],
  ['      <footer className="footer"', '      <GlobalSearch />\n      <footer className="footer"'],
]);
ed('src/areas/tasklist/StartAndCases.tsx', [
  ["          { id: 'people', label: t('case.participants') },", "          { id: 'people', label: t('case.participants') },\n          { id: 'conv', label: t('mod.channels.conversations') },"],
  ['      <TabPanel id="people" active={tab}>', '      <TabPanel id="conv" active={tab}>\n        <ConversationPanel caseId={id} />\n      </TabPanel>\n      <TabPanel id="people" active={tab}>'],
  ["import { AddVariables } from '../../modules/operaton3';", "import { AddVariables } from '../../modules/operaton3';\nimport { ConversationPanel } from '../../modules/platform';"],
]);
ed('src/areas/modeller/Modeller.tsx', [
  ["import { ScanReport,", "import { BulkTestReport, FeelPlayground, LeaseBanner, TokenWalk } from '../../modules/platform';\nimport { ScanReport,"],
  ["          { id: 'play', label: t('modeller.play') },", "          { id: 'play', label: t('modeller.play') },\n          { id: 'walk', label: t('mod.modeller2.tokenWalk') },\n          { id: 'feel', label: t('mod.ai.feel') },"],
  ['      <TabPanel id="play" active={tab}>', '      <TabPanel id="walk" active={tab}>\n        {art?.kind === \'BPMN\' ? <TokenWalk key={art.id} file={art.name} bpmnXml={art.content} /> : <Empty message={t(\'modeller.noArtifacts\')} />}\n      </TabPanel>\n      <TabPanel id="feel" active={tab}>\n        <FeelPlayground />\n      </TabPanel>\n      <TabPanel id="play" active={tab}>'],
  ['        <TestsPanel packageId={id} />', '        <BulkTestReport packageId={id} />'],
  ['              editable ? (\n                <ArtifactEditor', '              editable ? (\n                <>\n                <LeaseBanner key={art.id} packageId={id} file={art.name} />\n                <ArtifactEditor'],
  ['                  onChange={() => setDirty(true)}\n                />\n              ) : (', '                  onChange={() => setDirty(true)}\n                />\n                </>\n              ) : ('],
  [/function TestsPanel\(\{ packageId \}[\s\S]*?\n\}\n\n\/\*\* PF-23/, '/** PF-23'],
]);
// i18n
const K = { 'ops.causeIncident': ['Cause incident', 'कारण घटना'], 'ops.rootCauseIncident': ['Root cause incident', 'मूल कारण घटना'], 'mod.op3.bulkRetry': ['Bulk job retry', 'सामूहिक जॉब पुनः प्रयास'], 'mod.op3.due_keep': ['Keep the due date', 'नियत तिथि रखें'], 'mod.op3.due_now': ['Due now', 'अभी नियत'], 'mod.op3.due_custom': ['Choose a date', 'तिथि चुनें'],
'mod.ai.title': ['AI', 'AI'], 'mod.ai.desc': ['Agent runs, approvals, knowledge bases, drafts and classification review.', 'एजेंट रन, अनुमोदन, ज्ञान आधार, मसौदे और वर्गीकरण समीक्षा।'], 'mod.ai.runs': ['Agent runs', 'एजेंट रन'], 'mod.ai.runsDesc': ['Each run with its step timeline.', 'प्रत्येक रन अपने चरणों की समयरेखा सहित।'], 'mod.ai.steps': ['Timeline', 'समयरेखा'], 'mod.ai.approvals': ['Approval inbox', 'अनुमोदन इनबॉक्स'], 'mod.ai.approvalsDesc': ['Agent actions waiting for a person.', 'व्यक्ति की प्रतीक्षा में एजेंट कार्रवाइयाँ।'], 'mod.ai.kb': ['Knowledge bases', 'ज्ञान आधार'], 'mod.ai.kbHint': ['Publishing creates a new version.', 'प्रकाशित करने पर नया संस्करण बनता है।'], 'mod.ai.drafts': ['Draft review', 'मसौदा समीक्षा'], 'mod.ai.draftsDesc': ['AI output goes to package drafts only. Check the changes before approving.', 'AI का परिणाम केवल पैकेज मसौदों में जाता है। अनुमोदन से पहले परिवर्तन जाँचें।'], 'mod.ai.generate': ['Generate draft', 'मसौदा बनाएँ'], 'mod.ai.generateHint': ['Nothing is deployed from here.', 'यहाँ से कुछ भी परिनियोजित नहीं होता।'], 'mod.ai.classifications': ['Classification review', 'वर्गीकरण समीक्षा'], 'mod.ai.feel': ['FEEL playground', 'FEEL अभ्यास क्षेत्र'], 'mod.ai.expression': ['Expression', 'व्यंजक'], 'mod.ai.goal': ['Describe what you need', 'बताएँ कि क्या चाहिए'], 'mod.ai.goalHint': ['The result is a proposal to check.', 'परिणाम जाँचने योग्य प्रस्ताव है।'], 'mod.ai.propose': ['Propose an expression', 'व्यंजक प्रस्तावित करें'],
'mod.channels.title': ['Channels', 'चैनल'], 'mod.channels.desc': ['Conversations, opt-ins and inbound channel status.', 'वार्तालाप, ऑप्ट-इन और आवक चैनल की स्थिति।'], 'mod.channels.conversations': ['Conversations', 'वार्तालाप'], 'mod.channels.optIns': ['Opt-ins', 'ऑप्ट-इन'], 'mod.channels.inbound': ['Inbound status', 'आवक स्थिति'], 'mod.channels.inboundDesc': ['Last poll, poison items and cursor per channel.', 'प्रति चैनल अंतिम पोल, दूषित मद और कर्सर।'],
'mod.modeller2.title': ['Modelling tools', 'मॉडलिंग उपकरण'], 'mod.modeller2.landscape': ['Process landscape', 'प्रक्रिया परिदृश्य'], 'mod.modeller2.tokenWalk': ['Token walk', 'टोकन भ्रमण'], 'mod.modeller2.gateway': ['Choose a branch', 'शाखा चुनें'], 'mod.modeller2.leased': ['{{who}} is editing this model.', '{{who}} यह मॉडल संपादित कर रहे हैं।'], 'mod.modeller2.takeover': ['Take over', 'नियंत्रण लें'], 'mod.modeller2.templates': ['Templates', 'टेम्पलेट'], 'mod.modeller2.templatesDesc': ['Process, element and form component templates.', 'प्रक्रिया, तत्व और फ़ॉर्म घटक टेम्पलेट।'], 'mod.modeller2.saveTemplate': ['Save as template', 'टेम्पलेट के रूप में सहेजें'], 'mod.modeller2.actions': ['Actions', 'कार्रवाइयाँ'], 'mod.modeller2.mocks': ['Service mocks', 'सेवा मॉक'], 'mod.modeller2.mocksDesc': ['For non-production profiles only.', 'केवल गैर-उत्पादन प्रोफ़ाइल के लिए।'], 'mod.modeller2.mocksGlobal': ['Global switch', 'वैश्विक स्विच'], 'mod.modeller2.catalogue': ['Catalogue', 'सूची'], 'mod.modeller2.bulkTests': ['Test report', 'परीक्षण रिपोर्ट'], 'mod.modeller2.deterministic': ['Deterministic tests', 'निर्धारक परीक्षण'], 'mod.modeller2.judged': ['Judge results', 'निर्णायक परिणाम'], 'mod.modeller2.judgedHint': ['Non-deterministic. Listed apart from the tests above.', 'अनिर्धारक। ऊपर के परीक्षणों से अलग सूचीबद्ध।'],
'mod.ops2.title': ['Platform tools', 'प्लेटफ़ॉर्म उपकरण'], 'mod.ops2.usage': ['Usage', 'उपयोग'], 'mod.ops2.usageDesc': ['Daily usage for capacity planning.', 'क्षमता नियोजन हेतु दैनिक उपयोग।'], 'mod.ops2.outliers': ['Outliers', 'असामान्य मान'], 'mod.ops2.outliersDesc': ['Correlation, not cause.', 'सहसंबंध, कारण नहीं।'], 'mod.ops2.variables': ['Scoped variables', 'दायरे वाले चर'], 'mod.ops2.variablesDesc': ['Not for secrets.', 'गुप्त मानों के लिए नहीं।'], 'mod.ops2.secrets': ['Secrets', 'गुप्त मान'], 'mod.ops2.secretsDesc': ['Values can be set but never displayed.', 'मान निर्धारित किए जा सकते हैं, पर कभी दिखाए नहीं जाते।'], 'mod.ops2.sequences': ['Sequences', 'अनुक्रम'], 'mod.ops2.sequenceHint': ['Advancing a sequence can leave gaps in numbering.', 'अनुक्रम आगे बढ़ाने से क्रमांकन में अंतराल आ सकता है।'], 'mod.ops2.index': ['Index maintenance', 'इंडेक्स रखरखाव'], 'mod.ops2.rebuild': ['Start a job', 'कार्य आरंभ करें'], 'mod.ops2.migration': ['Migration reconciliation', 'माइग्रेशन मिलान'], 'mod.ops2.migrationDesc': ['Checkpoints, failed and mismatched rows.', 'चेकपॉइंट, विफल और बेमेल पंक्तियाँ।'], 'mod.ops2.retention': ['Retention plan', 'प्रतिधारण योजना'], 'mod.ops2.retentionHint': ['Preview what would be removed, then execute that plan.', 'देखें कि क्या हटेगा, फिर वही योजना निष्पादित करें।'], 'mod.ops2.preview': ['Preview plan', 'योजना का पूर्वावलोकन'], 'mod.ops2.execute': ['Execute plan', 'योजना निष्पादित करें'], 'mod.ops2.search': ['Search (Ctrl+K)', 'खोज (Ctrl+K)'],
'fld.prompt': ['Prompt', 'निर्देश'], 'fld.secretValue': ['Secret value', 'गुप्त मान'], 'fld.created': ['Created', 'बनाया गया'] };
const auto = new Set(); const src = r(ui + 'src/modules/platform.tsx', 'utf8');
for (const m of src.matchAll(/\bf\.(?:text|num|money|date|dt|area|json|bool)\('([\w.]+)'(?:\)|, undefined)/g)) auto.add(m[1]);
for (const m of src.matchAll(/\bc\('(\w+)'\)/g)) auto.add(m[1]);
for (const m of src.matchAll(/\bf\.select\('(\w+)', \[([^\]]*)\]/g)) { auto.add(m[1]); }
const opts = {}; for (const m of src.matchAll(/\bf\.select\('\w+', \[([^\]]*)\]/g)) for (const v of m[1].matchAll(/'([^']+)'/g)) opts[v[1]] = 1;
const HI = { agentKey:'एजेंट कुंजी',status:'स्थिति',businessKey:'व्यावसायिक कुंजी',runId:'रन आईडी',startedAt:'आरंभ समय',seq:'क्रम',kind:'प्रकार',tool:'उपकरण',tokens:'टोकन',at:'समय',approvalId:'अनुमोदन आईडी',summary:'सारांश',requestedAt:'अनुरोध समय',key:'कुंजी',version:'संस्करण',chunks:'खंड',updatedAt:'अद्यतन समय',metadata:'मेटाडेटा',packageId:'पैकेज आईडी',draftId:'मसौदा आईडी',createdAt:'निर्माण समय',id:'आईडी',subjectRef:'विषय संदर्भ',label:'लेबल',confidence:'विश्वास',decision:'निर्णय',channel:'चैनल',externalId:'बाहरी आईडी',principalRef:'प्रिंसिपल संदर्भ',lastPollAt:'अंतिम पोल',cursor:'कर्सर',poisonCount:'दूषित मद',name:'नाम',scope:'दायरा',permission:'अनुमति',active:'सक्रिय',processKey:'प्रक्रिया कुंजी',form:'फ़ॉर्म',input:'इनपुट',service:'सेवा',operation:'संचालन',enabled:'सक्षम',delayMs:'विलंब (ms)',owner:'स्वामी',from:'से',to:'तक',tenant:'टेनेंट',day:'दिन',tenantId:'टेनेंट आईडी',metric:'मेट्रिक',processDefinitionKey:'प्रक्रिया परिभाषा कुंजी',subject:'विषय',baseline:'आधार रेखा',correlatedWith:'सहसंबद्ध',updatedBy:'अद्यतनकर्ता',current:'वर्तमान',table:'तालिका',progress:'प्रगति',alias:'उपनाम',checkpoint:'चेकपॉइंट',source:'स्रोत',migrated:'माइग्रेट',failed:'विफल',mismatched:'बेमेल' };
const OPTH = { BPMN:'BPMN',DMN:'DMN',FORM:'फ़ॉर्म',ACCEPT:'स्वीकार करें',RETURN:'वापस करें',PROCESS:'प्रक्रिया',ELEMENT:'तत्व',FORM_COMPONENT:'फ़ॉर्म घटक',GLOBAL:'वैश्विक',TENANT:'टेनेंट',PACKAGE:'पैकेज',REBUILD:'पुनर्निर्माण',DRIFT_CHECK:'विचलन जाँच' };
const human = (s) => s.replace(/([A-Z])/g, ' $1').replace(/^./, (x) => x.toUpperCase()).replace(/\bId\b/, 'id');
for (const k of auto) if (!K['fld.' + k]) K['fld.' + k] = [human(k), HI[k] ?? '@'];
for (const k of Object.keys(opts)) if (!K['opt.' + k]) K['opt.' + k] = [human(k.toLowerCase().replace(/_/g, ' ')), OPTH[k] ?? '@'];
for (const [i, l] of ['en', 'hi'].entries()) {
  const p = ui + 'src/i18n/locales/' + l + '.json';
  const j = JSON.parse(r(p, 'utf8'));
  for (const [k, v] of Object.entries(K)) { const ps = k.split('.'); let c = j; for (const x of ps.slice(0, -1)) c = c[x] ??= {}; if (!(ps.at(-1) in c)) { if (v[i] === '@') console.log('NOHI', k); c[ps.at(-1)] = v[i]; } }
  w(p, JSON.stringify(j, null, 2) + '\n');
}
const dp = ui + 'scripts/i18n-dynamic.json'; const d = JSON.parse(r(dp, 'utf8')); d['mod.op3.due_'] = ['keep', 'now', 'custom']; w(dp, JSON.stringify(d, null, 2) + '\n');
// docs
ed('OPERATON-UI-PARITY.md', [
  [/\| Membership pickers \|[^\n]*\n/, ''], [/\| Task dashboard \|[^\n]*\n/, ''], [/\| Bulk job retry \|[^\n]*\n/, ''], [/\| Incidents \|[^\n]*\n/, ''],
  [/\| Multi-key sort \|[^\n]*\n/, '| Multi-key sort | Server keys: created, due, priority, name. Amount and reference are not sortable: `TaskQuery` has no business-key order, and amount is a process variable whose type differs by process |\n| Membership fields | Searchable list of ids; a typed id is still accepted |\n'],
  [/\| Plugins \|[^\n]*\n/, ''],
]);
ed('README.md', [['## Integration notes', `## New module routes (to reconcile)

\`src/modules/platform.tsx\` has screens for sangam-ai, sangam-channels, sangam-modeller and sangam-ops. Their routes are **not in the contract yet**, so apart from the READMEs' \`/landscape\`, \`/models/token-walk\`, \`/models/{file}/lease\`, \`/templates\`, \`/actions\`, \`/catalogue\` and \`/packages/{id}/tests/run\`, these paths and all field names are assumptions for WS-REST to confirm:

- **AI:** \`/ai/agent-runs\`, \`/ai/agent-approvals\`, \`/ai/knowledge-bases\`, \`/ai/drafts\`, \`/ai/classifications\`, \`/ai/expressions/{evaluate,propose}\`.
- **Channels:** \`/conversations\` (messages, attachments), \`/channels/opt-ins\`, \`/channels/inbound-status\`.
- **Modeller:** \`/service-mocks\`, \`/actions/{key}/run\`.
- **Ops:** \`/search\`, \`/ops/usage\`, \`/ops/outliers\`, \`/ops/variables\`, \`/ops/secrets\`, \`/ops/sequences\`, \`/ops/index-jobs\`, \`/ops/retention/{plan,execute}\`, \`/ops/migration-status\`.

Not built: bpmn-js custom palette and form-js composite components (templates are managed as data but not injected into the editors), the action menu inside task and case screens, Teams tab, test report export.

## Integration notes`]]);
