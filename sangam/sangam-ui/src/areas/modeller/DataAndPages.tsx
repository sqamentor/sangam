// SPDX-License-Identifier: Apache-2.0
/**
 * R78 / EC-06 / PF-38 data objects and data dictionary with an ERD (SVG + table alternative), and
 * R77 / EC-03 / PF-35 low-code pages: a versioned page model (heading, text, list, detail, KPI, action blocks) bound to
 * named queries and data objects; rendered at /pages/:key with server-side authorisation of every query.
 */
import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { api, type DataObjectDef, type DataObjectField, type PageBlock, type PageModel } from '../../api/endpoints';
import { announce, useAction, useAsync, usePaged } from '../../lib/hooks';
import { Badge, Checkbox, DataTable, Empty, Loading, Notice, PageHeader, Pagination, ProblemAlert, Section, SelectField, TextField } from '../../components/ui';
import { Stat } from '../../components/Charts';

const FIELD_TYPES: DataObjectField['type'][] = ['string', 'number', 'boolean', 'date', 'reference', 'json'];

function Erd({ objects }: { objects: DataObjectDef[] }) {
  const { t } = useTranslation();
  const W = 200;
  const rowH = 18;
  const pos = objects.map((o, i) => ({ o, x: 20 + (i % 4) * (W + 40), y: 20 + Math.floor(i / 4) * 260 }));
  const height = 40 + Math.ceil(objects.length / 4) * 260;
  const byKey = new Map(pos.map((p) => [p.o.key, p]));
  return (
    <figure className="erd">
      <figcaption>{t('data.erd')}</figcaption>
      <svg viewBox={`0 0 ${4 * (W + 40) + 20} ${height}`} role="img" aria-label={t('data.erdLabel', { n: objects.length })}>
        {pos.flatMap((p) =>
          p.o.fields
            .filter((f) => f.type === 'reference' && f.reference && byKey.has(f.reference))
            .map((f) => {
              const tgt = byKey.get(f.reference!)!;
              return <line key={`${p.o.key}.${f.name}`} x1={p.x + W} y1={p.y + 30} x2={tgt.x} y2={tgt.y + 30} className="erd-link" markerEnd="url(#arrow)" />;
            }),
        )}
        <defs>
          <marker id="arrow" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
            <path d="M 0 0 L 10 5 L 0 10 z" className="erd-arrow" />
          </marker>
        </defs>
        {pos.map((p) => (
          <g key={p.o.key} transform={`translate(${p.x},${p.y})`}>
            <rect width={W} height={28 + p.o.fields.length * rowH} className="erd-box" rx={4} />
            <text x={8} y={19} className="erd-title">
              {p.o.name} ({p.o.backing})
            </text>
            {p.o.fields.map((f, i) => (
              <text key={f.name} x={8} y={44 + i * rowH} className="erd-field">
                {f.name}: {f.type}
                {f.reference ? ` → ${f.reference}` : ''}
                {f.required ? ' *' : ''}
              </text>
            ))}
          </g>
        ))}
      </svg>
    </figure>
  );
}

export function DataObjects() {
  const { t } = useTranslation();
  const list = useAsync(() => api.dataObjects.list(), []);
  const [edit, setEdit] = useState<DataObjectDef | null>(null);
  const [browse, setBrowse] = useState<string>('');
  const records = usePaged((p) => api.dataObjects.records(browse, p), [browse]);
  const act = useAction();
  const upd = (i: number, p: Partial<DataObjectField>) => edit && setEdit({ ...edit, fields: edit.fields.map((f, j) => (j === i ? { ...f, ...p } : f)) });
  return (
    <>
      <PageHeader
        title={t('data.title')}
        description={t('data.description')}
        actions={
          <button type="button" className="primary" onClick={() => setEdit({ key: '', name: '', backing: 'DB', fields: [{ name: 'id', type: 'string', required: true }] })}>
            {t('common.new')}
          </button>
        }
      />
      <ProblemAlert error={list.error ?? act.error} onRetry={list.reload} />
      {list.loading && <Loading />}
      {list.data && list.data.length === 0 && <Empty message={t('data.empty')} />}
      {list.data && list.data.length > 0 && (
        <>
          <Erd objects={list.data} />
          <DataTable
            caption={t('data.dictionary')}
            rows={list.data}
            rowKey={(r) => r.key}
            columns={[
              { key: 'key', header: t('ops.key') },
              { key: 'name', header: t('common.name') },
              { key: 'backing', header: t('data.backing') },
              { key: 'version', header: t('ops.version'), render: (r) => String(r.version ?? 1) },
              { key: 'fields', header: t('data.fields'), render: (r) => r.fields.map((f) => `${f.name}:${f.type}${f.sensitive ? ' (S)' : ''}`).join(', ') },
              {
                key: 'a',
                header: t('common.actions'),
                render: (r) => (
                  <span className="toolbar inline">
                    <button type="button" className="link" onClick={() => setEdit(r)}>
                      {t('common.edit')}
                    </button>
                    <button type="button" className="link" onClick={() => setBrowse(r.key)}>
                      {t('data.browse')}
                    </button>
                  </span>
                ),
              },
            ]}
          />
        </>
      )}
      {edit && (
        <Section title={edit.id ? edit.name : t('data.newObject')}>
          <TextField label={t('ops.key')} required value={edit.key} onChange={(v) => setEdit({ ...edit, key: v })} />
          <TextField label={t('common.name')} required value={edit.name} onChange={(v) => setEdit({ ...edit, name: v })} />
          <SelectField label={t('data.backing')} value={edit.backing} onChange={(v) => setEdit({ ...edit, backing: v as 'DB' | 'REST' })} options={[{ value: 'DB', label: t('data.backingDb') }, { value: 'REST', label: t('data.backingRest') }]} />
          {edit.backing === 'REST' && <TextField label={t('data.endpointRef')} hint={t('data.endpointRefHint')} value={edit.endpointRef ?? ''} onChange={(v) => setEdit({ ...edit, endpointRef: v })} />}
          <table>
            <caption>{t('data.fields')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('common.name')}</th>
                <th scope="col">{t('ops.varType')}</th>
                <th scope="col">{t('data.reference')}</th>
                <th scope="col">{t('common.required')}</th>
                <th scope="col">{t('data.sensitive')}</th>
                <th scope="col">
                  <span className="sr-only">{t('common.actions')}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {edit.fields.map((f, i) => (
                <tr key={i}>
                  <td>
                    <input aria-label={t('common.name')} value={f.name} onChange={(e) => upd(i, { name: e.target.value })} />
                  </td>
                  <td>
                    <select aria-label={t('ops.varType')} value={f.type} onChange={(e) => upd(i, { type: e.target.value as DataObjectField['type'] })}>
                      {FIELD_TYPES.map((ft) => (
                        <option key={ft} value={ft}>
                          {ft}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td>
                    <input aria-label={t('data.reference')} value={f.reference ?? ''} disabled={f.type !== 'reference'} onChange={(e) => upd(i, { reference: e.target.value })} />
                  </td>
                  <td>
                    <input type="checkbox" aria-label={t('common.required')} checked={!!f.required} onChange={(e) => upd(i, { required: e.target.checked })} />
                  </td>
                  <td>
                    <input type="checkbox" aria-label={t('data.sensitive')} checked={!!f.sensitive} onChange={(e) => upd(i, { sensitive: e.target.checked })} />
                  </td>
                  <td>
                    <button type="button" className="link danger" onClick={() => setEdit({ ...edit, fields: edit.fields.filter((_, j) => j !== i) })}>
                      {t('common.remove')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <button type="button" onClick={() => setEdit({ ...edit, fields: [...edit.fields, { name: '', type: 'string' }] })}>
            {t('data.addField')}
          </button>
          <div className="toolbar">
            <button type="button" onClick={() => setEdit(null)}>
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="primary"
              disabled={!edit.key || !edit.name}
              onClick={() =>
                void act.run(() => api.dataObjects.save(edit)).then((r) => {
                  if (r) {
                    setEdit(null);
                    announce(t('common.saved'));
                    list.reload();
                  }
                })
              }
            >
              {t('common.save')}
            </button>
          </div>
          <Notice>{t('data.versioningHint')}</Notice>
        </Section>
      )}
      {browse && (
        <Section title={t('data.records', { key: browse })}>
          <ProblemAlert error={records.error} />
          {records.data && records.data.items.length > 0 && (
            <DataTable
              caption={browse}
              captionHidden
              rows={records.data.items}
              rowKey={(r) => String(r.id ?? JSON.stringify(r))}
              columns={Object.keys(records.data.items[0]).map((k) => ({ key: k, header: k, render: (r: Record<string, unknown>) => String(r[k] ?? '—') }))}
            />
          )}
          <Pagination data={records.data} page={records.page} size={records.size} setPage={records.setPage} setSize={records.setSize} />
        </Section>
      )}
    </>
  );
}

const BLOCK_KINDS: PageBlock['kind'][] = ['heading', 'text', 'list', 'detail', 'kpi', 'action'];

export function PageBuilder() {
  const { t } = useTranslation();
  const pages = useAsync(() => api.pages.list(), []);
  const [model, setModel] = useState<PageModel | null>(null);
  const act = useAction();
  const upd = (i: number, p: Partial<PageBlock>) => model && setModel({ ...model, blocks: model.blocks.map((b, j) => (j === i ? { ...b, ...p } : b)) });
  const move = (i: number, d: -1 | 1) => {
    if (!model) return;
    const j = i + d;
    if (j < 0 || j >= model.blocks.length) return;
    const b = [...model.blocks];
    [b[i], b[j]] = [b[j], b[i]];
    setModel({ ...model, blocks: b });
  };
  return (
    <>
      <PageHeader
        title={t('pages.title')}
        description={t('pages.description')}
        actions={
          <button type="button" className="primary" onClick={() => setModel({ key: '', title: '', blocks: [{ id: 'b1', kind: 'heading', title: '' }] })}>
            {t('common.new')}
          </button>
        }
      />
      <ProblemAlert error={pages.error ?? act.error} />
      {pages.data && (
        <DataTable
          caption={t('pages.title')}
          captionHidden
          rows={pages.data}
          rowKey={(r) => r.key}
          columns={[
            { key: 'title', header: t('common.name'), render: (r) => <Link to={`/pages/${encodeURIComponent(r.key)}`}>{r.title}</Link> },
            { key: 'key', header: t('ops.key') },
            { key: 'version', header: t('ops.version') },
            { key: 'state', header: t('admin.status'), render: (r) => <Badge value={r.state} /> },
            {
              key: 'a',
              header: t('common.actions'),
              render: (r) => (
                <button type="button" className="link" onClick={() => void api.pages.get(r.key).then(setModel)}>
                  {t('common.edit')}
                </button>
              ),
            },
          ]}
        />
      )}
      {model && (
        <Section title={model.title || t('pages.newPage')}>
          <TextField label={t('ops.key')} required value={model.key} onChange={(v) => setModel({ ...model, key: v })} />
          <TextField label={t('pages.pageTitle')} required value={model.title} onChange={(v) => setModel({ ...model, title: v })} />
          <ol className="blocks">
            {model.blocks.map((b, i) => (
              <li key={b.id} className="card">
                <div className="toolbar">
                  <SelectField label={t('pages.blockKind')} value={b.kind} onChange={(v) => upd(i, { kind: v as PageBlock['kind'] })} options={BLOCK_KINDS.map((k) => ({ value: k, label: t(`pages.kind.${k}`) }))} />
                  <button type="button" onClick={() => move(i, -1)} aria-label={t('pages.moveUp')}>
                    ↑
                  </button>
                  <button type="button" onClick={() => move(i, 1)} aria-label={t('pages.moveDown')}>
                    ↓
                  </button>
                  <button type="button" className="link danger" onClick={() => setModel({ ...model, blocks: model.blocks.filter((_, j) => j !== i) })}>
                    {t('common.remove')}
                  </button>
                </div>
                <TextField label={t('pages.blockTitle')} value={b.title ?? ''} onChange={(v) => upd(i, { title: v })} />
                {b.kind === 'text' && <TextField label={t('pages.text')} value={b.text ?? ''} onChange={(v) => upd(i, { text: v })} />}
                {(b.kind === 'list' || b.kind === 'kpi' || b.kind === 'detail') && (
                  <>
                    <TextField label={t('pages.query')} hint={t('pages.queryHint')} value={b.query ?? ''} onChange={(v) => upd(i, { query: v })} />
                    <TextField label={t('pages.dataObject')} value={b.dataObject ?? ''} onChange={(v) => upd(i, { dataObject: v })} />
                  </>
                )}
                {b.kind === 'list' && (
                  <TextField label={t('pages.columns')} hint={t('events.commaList')} value={(b.columns ?? []).join(', ')} onChange={(v) => upd(i, { columns: v.split(',').map((s) => s.trim()).filter(Boolean) })} />
                )}
                {b.kind === 'action' && <TextField label={t('modeller.processKey')} value={b.processKey ?? ''} onChange={(v) => upd(i, { processKey: v })} />}
              </li>
            ))}
          </ol>
          <button type="button" onClick={() => setModel({ ...model, blocks: [...model.blocks, { id: `b${Date.now()}`, kind: 'list' }] })}>
            {t('pages.addBlock')}
          </button>
          <div className="toolbar">
            <button type="button" onClick={() => setModel(null)}>
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="primary"
              disabled={!model.key || !model.title}
              onClick={() =>
                void act.run(() => api.pages.save(model)).then((r) => {
                  if (r) {
                    setModel(null);
                    pages.reload();
                    announce(t('common.saved'));
                  }
                })
              }
            >
              {t('common.save')}
            </button>
          </div>
          <Notice>{t('pages.serverAuthz')}</Notice>
        </Section>
      )}
    </>
  );
}

function ListBlock({ block }: { block: PageBlock }) {
  const { t } = useTranslation();
  const q = usePaged((p) => api.pages.query(block.query!, p), [block.query]);
  const cols = useMemo(() => block.columns ?? (q.data?.items[0] ? Object.keys(q.data.items[0]) : []), [block.columns, q.data]);
  return (
    <>
      <ProblemAlert error={q.error} />
      {q.data && q.data.items.length === 0 && <Empty message={t('common.emptyNone')} />}
      {q.data && q.data.items.length > 0 && (
        <DataTable caption={block.title ?? block.query!} rows={q.data.items} rowKey={(r) => String(r.id ?? JSON.stringify(r))} columns={cols.map((c) => ({ key: c, header: c, render: (r: Record<string, unknown>) => String(r[c] ?? '—') }))} />
      )}
      <Pagination data={q.data} page={q.page} size={q.size} setPage={q.setPage} setSize={q.setSize} />
    </>
  );
}

function KpiBlock({ block }: { block: PageBlock }) {
  const q = useAsync(() => api.pages.query(block.query!, { size: 1 }), [block.query]);
  const v = q.data?.items[0];
  const val = v ? Object.values(v)[0] : undefined;
  return <Stat label={block.title ?? block.query!} value={val === undefined ? '—' : String(val)} />;
}

export function PageRuntime() {
  const { key = '' } = useParams();
  const { t } = useTranslation();
  const page = useAsync(() => api.pages.get(key), [key]);
  const [confirmStart, setConfirmStart] = useState(false);
  const act = useAction();
  if (page.loading && !page.data) return <Loading />;
  if (!page.data) return <ProblemAlert error={page.error} onRetry={page.reload} />;
  return (
    <>
      <PageHeader title={page.data.title} />
      {page.data.blocks.map((b) => {
        switch (b.kind) {
          case 'heading':
            return <h2 key={b.id}>{b.title}</h2>;
          case 'text':
            return (
              <p key={b.id}>
                {b.title && <strong>{b.title} </strong>}
                {b.text}
              </p>
            );
          case 'list':
          case 'detail':
            return b.query ? (
              <Section key={b.id} title={b.title ?? b.query}>
                <ListBlock block={b} />
              </Section>
            ) : null;
          case 'kpi':
            return b.query ? <KpiBlock key={b.id} block={b} /> : null;
          case 'action':
            return b.processKey ? (
              <div key={b.id} className="toolbar">
                <Checkbox label={t('pages.confirmStart')} checked={confirmStart} onChange={setConfirmStart} />
                <button
                  type="button"
                  className="primary"
                  disabled={!confirmStart || act.busy}
                  onClick={() => void act.run((k) => api.processes.start({ key: b.processKey! }, k)).then((r) => r && announce(t('start.started')))}
                >
                  {b.title ?? t('start.begin')}
                </button>
              </div>
            ) : null;
          default:
            return null;
        }
      })}
      <ProblemAlert error={act.error} />
    </>
  );
}
