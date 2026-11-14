import { readFileSync as r, writeFileSync as w } from 'node:fs';
const ui = 'D:/GITHUB  Project/Custom WorkFlow/sangam/sangam-ui/';
function ed(p, pairs) {
  let c = r(ui + p, 'utf8').replace(/\r\n/g, '\n');
  for (const [a, b] of pairs) {
    if (typeof a === 'string' ? !c.includes(a) : !a.test(c)) console.log('NOMATCH', p, String(a).slice(0, 70));
    c = c.replace(a, () => b);
  }
  w(ui + p, c);
}
const channels = `/** Conversation against the live /channels routes. The server has no list route, so a conversation is reached by message search, by id, or by creating it. */
export function ConversationPanel({ caseId }: { caseId?: string }) {
  const { t } = useTranslation();
  const [q, setQ] = useState('');
  const [term, setTerm] = useState('');
  const [idText, setIdText] = useState('');
  const [title, setTitle] = useState('');
  const [sel, setSel] = useState<string>();
  const [text, setText] = useState('');
  const base = '/channels/conversations';
  const hits = useAsync(() => http.get<Row[]>(\`\${base}/search\`, { text: term, limit: 50 }), [term], !!term);
  const msgs = useAsync(() => http.get<Row[]>(\`\${base}/\${encodeURIComponent(sel!)}/messages\`, { limit: 200 }), [sel], !!sel);
  useInterval(() => { if (sel) msgs.reload(); }, 10000);
  const act = useAction();
  const path = () => \`\${base}/\${encodeURIComponent(sel!)}\`;
  return (
    <Section title={t('mod.channels.conversations')}>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setTerm(q.trim()); }}>
        <TextField label={t('common.search')} value={q} onChange={setQ} />
        <button type="submit">{t('common.search')}</button>
      </form>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setSel(idText.trim() || undefined); }}>
        <TextField label={t('fld.conversationId')} value={idText} onChange={setIdText} />
        <button type="submit">{t('common.open')}</button>
      </form>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); void act.run((k) => http.post<Row>(base, { title, caseRef: caseId, participants: [] }, k)).then((x) => { if (x) { setSel(String(x.id)); setTitle(''); } }); }}>
        <TextField label={t('fld.title')} value={title} onChange={setTitle} required />
        <button type="submit" disabled={!title.trim() || act.busy}>{t('act.create')}</button>
      </form>
      {caseId && <p className="hint">{t('mod.channels.caseHint')}</p>}
      <ProblemAlert error={hits.error ?? msgs.error ?? act.error} />
      {term && hits.data?.length === 0 && <Empty message={t('common.emptyNone')} />}
      <ul>{hits.data?.map((m) => <li key={String(m.id)}><button type="button" className="link" onClick={() => setSel(String(m.conversationId))}>{String(m.body ?? m.id)}</button></li>)}</ul>
      {sel && (
        <div>
          <ol className="comments" aria-live="polite">
            {msgs.data?.map((m) => (
              <li key={String(m.id)}>
                <div className="small muted">{String(m.sender ?? '')} · {cell(m.at)}</div>
                <p>{String(m.body ?? '')}</p>
                {((m.attachments ?? []) as Row[]).map((a) => <div key={String(a.key)} className="small">{String(a.name)}</div>)}
              </li>
            ))}
          </ol>
          <TextArea label={t('task.addComment')} value={text} onChange={setText} />
          <div className="toolbar">
            <button type="button" disabled={!text.trim() || act.busy} onClick={() => void act.run((k) => http.post(\`\${path()}/messages\`, { body: text, attachments: [], clientKey: k }, k).then(() => true)).then((ok) => { if (ok) { setText(''); msgs.reload(); } })}>{t('common.send')}</button>
            <label className="button">{t('task.upload')}<input type="file" className="sr-only" onChange={(e) => { const file = e.target.files?.[0]; if (file) void act.run((k) => request(\`POST\`, \`\${path()}/attachments\`, { form: file, query: { name: file.name }, idempotencyKey: k, headers: { 'Content-Type': 'application/octet-stream', 'X-Content-Type': file.type || 'application/octet-stream' } })); }} /></label>
          </div>
          <p className="hint">{t('mod.channels.attachHint')}</p>
        </div>
      )}
    </Section>
  );
}

function OptIns() {
  const { t } = useTranslation();
  const [v, setV] = useState({ channel: '', externalId: '', principalId: '', evidence: '' });
  const [done, setDone] = useState(false);
  const act = useAction();
  const fld = (k: keyof typeof v) => <TextField label={t(\`fld.\${k}\`)} value={v[k]} required onChange={(x) => { setDone(false); setV({ ...v, [k]: x }); }} />;
  const send = (p: string) => void act.run((key) => http.post(p, v, key).then(() => true)).then((ok) => setDone(!!ok));
  return (
    <Section title={t('mod.channels.optIns')}>
      <p className="hint">{t('mod.channels.optInHint')}</p>
      {fld('channel')}{fld('externalId')}{fld('principalId')}{fld('evidence')}
      <div className="toolbar">
        <button type="button" disabled={act.busy || !v.channel || !v.externalId || !v.principalId} onClick={() => send('/channels/opt-ins')}>{t('act.register')}</button>
        <button type="button" className="danger" disabled={act.busy || !v.channel || !v.externalId} onClick={() => send('/channels/opt-ins/withdraw')}>{t('act.revoke')}</button>
      </div>
      <ProblemAlert error={act.error} />
      {done && <Notice tone="ok">{t('common.saved')}</Notice>}
    </Section>
  );
}
const inbound: ScreenDef = {
  id: 'inbound', title: 'mod.channels.inbound', description: 'mod.channels.inboundDesc', list: '/channels/status', unpaged: true,
  columns: [c('key'), c('channel'), c('state'), c('detail')], rowKey: ['key'],
};
export function ChannelsPage() {
  return <ModulePage title="mod.channels.title" description="mod.channels.desc" screens={[inbound]} extra={[{ id: 'conv', label: 'mod.channels.conversations', node: <ConversationPanel /> }, { id: 'opt', label: 'mod.channels.optIns', node: <OptIns /> }]} />;
}

`;
ed('src/modules/platform.tsx', [
  [/\/\*\* Conversation with messages[\s\S]*?(?=\/\/ -+ modeller)/, channels],
  ["import { http } from '../api/client';", "import { http, request } from '../api/client';"],
  ["except\n * POST", "except\n * POST"],
]);
ed('src/api/client.ts', [[/form\?: FormData;/, 'form?: FormData | Blob;']]);
ed('README.md', [["- **Channels:** `/conversations` (messages, attachments), `/channels/opt-ins`, `/channels/inbound-status`.\n", "- **Channels:** wired to the live `ChannelsController` (`/channels/conversations`, `/channels/opt-ins`, `/channels/status`). The server needs `GET /channels/conversations?caseRef=` before the case panel can list a case's conversations, and an attachment reference on send; opt-ins have no list route.\n"]]);
const K = { 'fld.conversationId': ['Conversation id', 'वार्तालाप आईडी'], 'fld.state': ['State', 'अवस्था'], 'fld.detail': ['Detail', 'विवरण'], 'fld.evidence': ['Evidence', 'साक्ष्य'], 'fld.principalId': ['Principal id', 'प्रिंसिपल आईडी'], 'fld.title': ['Title', 'शीर्षक'], 'common.saved': ['Saved', 'सहेजा गया'],
 'mod.channels.caseHint': ['A conversation created here is linked to this case. Existing ones are found by search or id.', 'यहाँ बना वार्तालाप इस मामले से जुड़ता है। मौजूदा वार्तालाप खोज या आईडी से मिलते हैं।'],
 'mod.channels.attachHint': ['An uploaded file is stored but not yet shown in the conversation.', 'अपलोड की गई फ़ाइल सहेजी जाती है, पर अभी वार्तालाप में नहीं दिखती।'],
 'mod.channels.optInHint': ['Matrix and Slack have no adapter. Existing opt-ins cannot be listed yet.', 'Matrix और Slack के लिए एडैप्टर नहीं है। मौजूदा ऑप्ट-इन अभी सूचीबद्ध नहीं हो सकते।'] };
for (const [i, l] of ['en', 'hi'].entries()) {
  const p = ui + 'src/i18n/locales/' + l + '.json'; const j = JSON.parse(r(p, 'utf8'));
  for (const [k, v] of Object.entries(K)) { const ps = k.split('.'); let c = j; for (const x of ps.slice(0, -1)) c = c[x] ??= {}; if (!(ps.at(-1) in c)) c[ps.at(-1)] = v[i]; }
  w(p, JSON.stringify(j, null, 2) + '\n');
}
