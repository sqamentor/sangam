// SPDX-License-Identifier: Apache-2.0
/**
 * Ctrl+K global search, mounted by Layout on every signed-in page; results come from the server, so they are already
 * authorised. It lives on its own, apart from modules/platform.tsx: Layout is in the entry's static import graph, and
 * importing it from platform.tsx brought that module's static imports (the BPMN diagram engine among them) into every
 * page, the public privacy policy included. The engine's min-dom runs an innerHTML feature test when it loads, a
 * Trusted Types violation on pages that show no diagram (decision 349; e2e xss-rendering W-03). Checked by
 * src/app/entryImports.test.ts.
 */
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { http } from '../api/client';
import { useAsync } from '../lib/hooks';
import { Badge, Dialog, Empty, ProblemAlert, TextField } from '../components/ui';

type Row = Record<string, unknown>;

export function GlobalSearch() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const [term, setTerm] = useState('');
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setOpen(true);
      }
    };
    window.addEventListener('keydown', on);
    return () => window.removeEventListener('keydown', on);
  }, []);
  const r = useAsync(() => http.page<Row>('/search', { q: term, size: 30 }), [term], open && !!term);
  const target = useMemo(() => (x: Row) => {
    const id = encodeURIComponent(String(x.id ?? x.resourceId ?? ''));
    const type = String(x.type ?? x.resourceType ?? '').toUpperCase();
    return type.includes('TASK') ? `/tasks/${id}` : type.includes('PROCESS') ? `/ops/instances/${id}` : type.includes('CASE') ? `/cases/${id}` : null;
  }, []);
  return (
    <Dialog open={open} wide title={t('mod.ops2.search')} onClose={() => setOpen(false)}>
      <form onSubmit={(e) => { e.preventDefault(); setTerm(q); }}>
        <TextField label={t('common.search')} autoFocus value={q} onChange={setQ} />
      </form>
      <ProblemAlert error={r.error} />
      {term && r.data?.items.length === 0 && <Empty message={t('common.emptyNone')} />}
      <ul>
        {r.data?.items.map((x, i) => {
          const to = target(x);
          const label = String(x.title ?? x.name ?? x.id);
          return <li key={i}><Badge value={String(x.type ?? x.resourceType ?? '')} tone="info" /> {to ? <button type="button" className="link" onClick={() => { setOpen(false); nav(to); }}>{label}</button> : label}</li>;
        })}
      </ul>
    </Dialog>
  );
}
