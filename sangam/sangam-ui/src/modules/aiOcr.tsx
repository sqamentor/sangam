// SPDX-License-Identifier: Apache-2.0
/**
 * M-103 option A (owner decision 378(3)): OCR into a knowledge base, reviewed before ingest. Every path is a contract
 * operation of sangam-rest/api/sangam-api.yaml: aiKbOcr (POST /ai/knowledge-bases/by-kb/versions/{version}/ocr?kb=,
 * multipart, ai.admin), aiListOcrTranscripts (GET /ai/ocr-transcripts, ai.review), aiGetOcrTranscript
 * (GET /ai/ocr-transcripts/{id}), aiDecideOcrTranscript (POST /ai/ocr-transcripts/{id}/decide) and aiKbSearch, whose
 * hits name the approved transcript a chunk came from (AiKbHit.ocrTranscriptId). The server authorises every call and
 * alone decides who may review (409 SELF_REVIEW_FORBIDDEN): the screen offers the decision to anyone and shows the
 * refusal. Only the image's SHA-256 is stored, so the scan is never shown.
 */
import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { http } from '../api/client';
import { fmtDate, fmtNumber } from '../i18n';
import { useAction, useAsync } from '../lib/hooks';
import { Badge, Empty, Notice, ProblemAlert, Section, SelectField, TextArea, TextField } from '../components/ui';

type State = 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED';
const STATES: State[] = ['PENDING_REVIEW', 'APPROVED', 'REJECTED'];

/** AiOcrTranscriptSummary. */
interface Summary { id: string; kb: string; kbVersion: number; documentId: string; docVersion?: string; mediaType: string; engine?: string; languages: string[]; state: State; chunks?: number; createdBy: string; createdAt: string; reviewedBy?: string; reviewedAt?: string }
/** AiOcrTranscript. */
interface Transcript extends Summary { imageSha256: string; text: string; correctedText?: string | null; reviewNote?: string }
/** AiKbHit. */
interface Hit { documentId?: string; docVersion?: string; seq?: number; text?: string; score?: number; ocrTranscriptId?: string | null }

const seg = (s: string) => encodeURIComponent(s.trim());

function Upload({ onCreated }: { onCreated: (t: Transcript) => void }) {
  const { t } = useTranslation();
  const fileId = useId();
  const [v, setV] = useState({ kb: '', version: '', documentId: '', docVersion: '', languages: '' });
  const [file, setFile] = useState<File>();
  const [created, setCreated] = useState<string>();
  const act = useAction();
  const set = (k: keyof typeof v) => (x: string) => { setCreated(undefined); setV({ ...v, [k]: x }); };
  const ready = !!file && !!v.kb.trim() && !!v.version.trim() && !!v.documentId.trim() && !!v.languages.trim();
  const submit = () => {
    if (!ready) return;
    const form = new FormData();
    form.append('file', file);
    form.append('documentId', v.documentId.trim());
    if (v.docVersion.trim()) form.append('docVersion', v.docVersion.trim());
    form.append('languages', v.languages.trim());
    void act.run((k) => http.upload<Transcript>(`/ai/knowledge-bases/by-kb/versions/${seg(v.version)}/ocr?kb=${seg(v.kb)}`, form, k)).then((r) => {
      if (!r) return;
      setCreated(r.id);
      onCreated(r);
    });
  };
  return (
    <form aria-label={t('mod.ai.ocr.upload')} className="filters" onSubmit={(e) => { e.preventDefault(); submit(); }}>
      <TextField label={t('mod.ai.ocr.kb')} value={v.kb} onChange={set('kb')} required />
      <TextField label={t('mod.ai.ocr.version')} value={v.version} onChange={set('version')} required />
      <div className="field">
        <label htmlFor={fileId}>{t('mod.ai.ocr.file')}</label>
        <input id={fileId} type="file" accept="image/png,image/jpeg,image/tiff" aria-required="true" onChange={(e) => { setCreated(undefined); setFile(e.target.files?.[0]); }} />
      </div>
      <TextField label={t('mod.ai.ocr.documentId')} value={v.documentId} onChange={set('documentId')} required />
      <TextField label={t('mod.ai.ocr.docVersion')} value={v.docVersion} onChange={set('docVersion')} />
      <TextField label={t('mod.ai.ocr.languages')} hint={t('mod.ai.ocr.languagesHint')} value={v.languages} onChange={set('languages')} required />
      <button type="submit" disabled={!ready || act.busy}>{t('mod.ai.ocr.submit')}</button>
      <ProblemAlert error={act.error} />
      {created && <Notice tone="ok">{t('mod.ai.ocr.submitted', { id: created })}</Notice>}
    </form>
  );
}

function Detail({ id, onDecided }: { id: string; onDecided: () => void }) {
  const { t } = useTranslation();
  const d = useAsync((signal) => http.get<Transcript>(`/ai/ocr-transcripts/${encodeURIComponent(id)}`, undefined, signal), [id]);
  const [corrected, setCorrected] = useState('');
  const [note, setNote] = useState('');
  const [decided, setDecided] = useState(false);
  const act = useAction();
  const x = d.data;
  // A new transcript resets the inputs during render, not in an effect: with React 19 the passive effect of the load
  // can still be pending when the reviewer clicks, and would then wipe what the click just set (UP-L6b).
  const [shown, setShown] = useState(x);
  if (shown !== x) {
    setShown(x);
    setCorrected(x?.correctedText ?? '');
    setNote('');
  }
  useEffect(() => setDecided(false), [id]);
  const decide = (approve: boolean) => {
    if (!x) return;
    const body: { approve: boolean; correctedText?: string; note?: string } = { approve };
    // blank keeps the engine text (AiOcrDecide.correctedText); an unchanged copy of it is not a correction either
    if (approve && corrected.trim() && corrected !== x.text) body.correctedText = corrected;
    if (note.trim()) body.note = note.trim();
    void act.run((k) => http.post<Transcript>(`/ai/ocr-transcripts/${encodeURIComponent(x.id)}/decide`, body, k)).then((r) => {
      if (!r) return;
      setDecided(true);
      d.setData(r);
      onDecided();
    });
  };
  const pending = x?.state === 'PENDING_REVIEW';
  const row = (k: string, v: unknown) => (v === undefined || v === null || v === '' ? null : <><dt>{t(`mod.ai.ocr.${k}`)}</dt><dd>{String(v)}</dd></>);
  return (
    <Section title={t('mod.ai.ocr.detail', { id })}>
      <ProblemAlert error={d.error} onRetry={d.reload} />
      {x && (
        <>
          <dl className="kv">
            <dt>{t('mod.ai.ocr.state')}</dt><dd><Badge value={x.state} /></dd>
            {row('kb', `${x.kb} / ${x.kbVersion}`)}
            {row('documentId', x.documentId)}
            {row('docVersion', x.docVersion)}
            {row('mediaType', x.mediaType)}
            {row('engine', x.engine)}
            {row('languages', x.languages.join(', '))}
            <dt>{t('mod.ai.ocr.imageHash')}</dt><dd><code>{x.imageSha256}</code></dd>
            {row('createdBy', x.createdBy)}
            {row('createdAt', fmtDate(x.createdAt))}
            {row('reviewedBy', x.reviewedBy)}
            {row('reviewedAt', x.reviewedAt ? fmtDate(x.reviewedAt) : undefined)}
            {row('reviewNote', x.reviewNote)}
            {row('chunks', x.chunks)}
          </dl>
          <p className="hint">{t('mod.ai.ocr.noImage')}</p>
          <TextArea label={t('mod.ai.ocr.engineText')} hint={t('mod.ai.ocr.engineTextHint')} className="code" value={x.text} readOnly onChange={() => undefined} />
          <TextArea label={t('mod.ai.ocr.corrected')} hint={pending ? t('mod.ai.ocr.correctedHint') : undefined} className="code" value={corrected} readOnly={!pending} onChange={setCorrected} />
          {pending && (
            <>
              <button type="button" onClick={() => setCorrected(x.text)}>{t('mod.ai.ocr.copyEngine')}</button>
              <TextField label={t('mod.ai.ocr.note')} value={note} onChange={setNote} />
              <div className="toolbar">
                <button type="button" disabled={act.busy} onClick={() => decide(true)}>{t('act.approve')}</button>
                <button type="button" className="danger" disabled={act.busy} onClick={() => decide(false)}>{t('act.reject')}</button>
              </div>
            </>
          )}
          <ProblemAlert error={act.error} />
          {decided && <Notice tone="ok">{t('mod.ai.ocr.decided')}</Notice>}
        </>
      )}
    </Section>
  );
}

/** The OCR review screen: submit an image, the review queue and one transcript's review. */
export function OcrReview() {
  const { t } = useTranslation();
  const [state, setState] = useState<string>('PENDING_REVIEW');
  const [kb, setKb] = useState('');
  const [applied, setApplied] = useState<{ state: string; kb: string }>({ state: 'PENDING_REVIEW', kb: '' });
  const [sel, setSel] = useState<string>();
  const q = useAsync(
    (signal) => {
      const query: Record<string, string | number> = {};
      if (applied.state) query.state = applied.state;
      if (applied.kb) query.kb = applied.kb;
      query.limit = 100;
      return http.get<Summary[]>('/ai/ocr-transcripts', query, signal);
    },
    [applied],
  );
  const rows = q.data ?? [];
  return (
    <>
      <Section title={t('mod.ai.ocr.upload')}>
        <p className="hint">{t('mod.ai.ocr.uploadHint')}</p>
        <Upload onCreated={(x) => { setSel(x.id); q.reload(); }} />
      </Section>
      <Section title={t('mod.ai.ocr.queue')}>
        <form className="filters" onSubmit={(e) => { e.preventDefault(); setApplied({ state, kb: kb.trim() }); }}>
          <SelectField label={t('mod.ai.ocr.state')} value={state} onChange={setState} options={[{ value: '', label: t('mod.ai.ocr.anyState') }, ...STATES.map((s) => ({ value: s, label: t(`mod.ai.ocr.states.${s}`) }))]} />
          <TextField label={t('mod.ai.ocr.kb')} value={kb} onChange={setKb} />
          <button type="submit">{t('common.search')}</button>
        </form>
        <ProblemAlert error={q.error} onRetry={q.reload} />
        {q.data && rows.length === 0 && <Empty message={t('common.emptyNone')} />}
        {rows.length > 0 && (
          <table>
            <thead>
              <tr>
                {['id', 'kb', 'documentId', 'docVersion', 'languages', 'state', 'createdBy', 'createdAt', 'open'].map((k) => <th key={k} scope="col">{t(k === 'open' ? 'common.open' : `mod.ai.ocr.${k}`)}</th>)}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>{r.id}</td>
                  <td>{`${r.kb} / ${r.kbVersion}`}</td>
                  <td>{r.documentId}</td>
                  <td>{r.docVersion ?? ''}</td>
                  <td>{r.languages.join(', ')}</td>
                  <td><Badge value={r.state} /></td>
                  <td>{r.createdBy}</td>
                  <td>{fmtDate(r.createdAt)}</td>
                  <td><button type="button" className="link" aria-label={t('mod.ai.ocr.open', { id: r.id })} onClick={() => setSel(r.id)}>{t('common.open')}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>
      {sel && <Detail key={sel} id={sel} onDecided={q.reload} />}
    </>
  );
}

/** Knowledge-base search (aiKbSearch); a hit ingested from an approved OCR transcript names it. */
export function KbSearch() {
  const { t } = useTranslation();
  const [kb, setKb] = useState('');
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState<Hit[]>();
  const act = useAction();
  const search = () => void act.run(() => http.post<Hit[]>(`/ai/knowledge-bases/by-kb/search?kb=${seg(kb)}`, { query })).then((r) => r && setHits(r));
  return (
    <Section title={t('mod.ai.ocr.search')}>
      <form className="filters" onSubmit={(e) => { e.preventDefault(); if (kb.trim() && query.trim()) search(); }}>
        <TextField label={t('mod.ai.ocr.kb')} value={kb} onChange={setKb} required />
        <TextField label={t('mod.ai.ocr.query')} value={query} onChange={setQuery} required />
        <button type="submit" disabled={!kb.trim() || !query.trim() || act.busy}>{t('common.search')}</button>
      </form>
      <ProblemAlert error={act.error} />
      {hits && hits.length === 0 && <Empty message={t('common.emptyNone')} />}
      {hits && hits.length > 0 && (
        <table>
          <thead>
            <tr>
              {['documentId', 'docVersion', 'seq', 'score', 'text', 'transcript'].map((k) => <th key={k} scope="col">{t(`mod.ai.ocr.${k}`)}</th>)}
            </tr>
          </thead>
          <tbody>
            {hits.map((h, i) => (
              <tr key={`${h.documentId}-${h.seq}-${i}`}>
                <td>{h.documentId}</td>
                <td>{h.docVersion}</td>
                <td>{h.seq}</td>
                <td>{h.score === undefined ? '' : fmtNumber(h.score, 3)}</td>
                <td>{h.text}</td>
                <td>{h.ocrTranscriptId ? <code>{h.ocrTranscriptId}</code> : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Section>
  );
}
