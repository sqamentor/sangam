// @vitest-environment jsdom
// SPDX-License-Identifier: Apache-2.0
/**
 * Round 6 item 3, M-103 option A (owner decision 378(3)): the OCR review screen (modules/aiOcr.tsx) against
 * POST /ai/knowledge-bases/{kb}/versions/{version}/ocr (aiKbOcr, multipart), GET /ai/ocr-transcripts (aiListOcrTranscripts),
 * GET /ai/ocr-transcripts/{id} (aiGetOcrTranscript) and POST /ai/ocr-transcripts/{id}/decide (aiDecideOcrTranscript),
 * and the knowledge-base search hits (aiKbSearch, AiKbHit.ocrTranscriptId). The API client is mocked; each test
 * asserts what is rendered, the exact request and how a refusal is named. Every call is checked against the contract.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn(), page: vi.fn(), upload: vi.fn() }));
vi.mock('../api/client', async (orig) => {
  const real = await orig<typeof import('../api/client')>();
  return { ...real, http: { ...real.http, get: api.get, post: api.post, put: api.put, del: api.del, page: api.page, upload: api.upload } };
});

import apiYaml from '../../../sangam-rest/api/sangam-api.yaml?raw';
import { initI18n } from '../i18n';
import { problemTitleKey } from '../components/ui';
import { EMPTY_PAGE, KEY, L, T, contractOperations, outsideContract, problem, recordCalls, show, type } from '../test/screens';
import { KbSearch, OcrReview } from './aiOcr';
import { AiPage } from './platform';

let routes: Record<string, unknown> = {};
const seen = new Set<string>();

beforeAll(() => initI18n('en'));
beforeEach(() => {
  routes = {};
  for (const f of Object.values(api)) f.mockReset();
  api.get.mockImplementation(async (path: string) => {
    const v = routes[path];
    if (v instanceof Error) throw v;
    return v ?? [];
  });
  api.post.mockResolvedValue({});
});
afterEach(() => {
  recordCalls(seen, api);
  cleanup();
});
afterAll(() => expect(outsideContract(seen, contractOperations(apiYaml))).toEqual([]));

const SUMMARY = { id: 'ocr-1', kb: 'rti', kbVersion: 3, documentId: 'letter-7', docVersion: 'a', mediaType: 'image/png', engine: 'tesseract', languages: ['eng', 'hin'], state: 'PENDING_REVIEW', createdBy: 'clerk', createdAt: '2026-11-15T10:00:00Z' };
const TRANSCRIPT = { ...SUMMARY, imageSha256: 'ab12cd34', text: 'Engine read this' };

const upload = () => screen.getByRole('form', { name: T('mod.ai.ocr.upload') });
const fillUpload = (docVersion = '') => {
  const f = upload();
  type(f, 'mod.ai.ocr.kb', 'rti');
  type(f, 'mod.ai.ocr.version', '3');
  fireEvent.change(within(f).getByLabelText(L('mod.ai.ocr.file')), { target: { files: [new File([new Uint8Array([1, 2, 3])], 'scan.png', { type: 'image/png' })] } });
  type(f, 'mod.ai.ocr.documentId', 'letter-7');
  if (docVersion) type(f, 'mod.ai.ocr.docVersion', docVersion);
  type(f, 'mod.ai.ocr.languages', 'eng,hin');
  return f;
};
const openDetail = async () => {
  routes['/ai/ocr-transcripts'] = [SUMMARY];
  routes['/ai/ocr-transcripts/ocr-1'] = TRANSCRIPT;
  show(<OcrReview />);
  fireEvent.click(await screen.findByRole('button', { name: T('mod.ai.ocr.open', { id: 'ocr-1' }) }));
  return screen.findByRole('region', { name: T('mod.ai.ocr.detail', { id: 'ocr-1' }) });
};
const lastPost = () => api.post.mock.calls[api.post.mock.calls.length - 1];

describe('OCR upload', () => {
  it('sends the image, document id, document version and languages as multipart to the version, then opens the transcript', async () => {
    api.upload.mockResolvedValue(TRANSCRIPT);
    routes['/ai/ocr-transcripts/ocr-1'] = TRANSCRIPT;
    show(<OcrReview />);
    const f = fillUpload('a');
    fireEvent.click(within(f).getByRole('button', { name: T('mod.ai.ocr.submit') }));
    await waitFor(() => expect(api.upload).toHaveBeenCalledTimes(1));
    const [path, form, key] = api.upload.mock.calls[0] as [string, FormData, string];
    expect(path).toBe('/ai/knowledge-bases/rti/versions/3/ocr');
    expect(key).toEqual(KEY);
    expect((form.get('file') as File).name).toBe('scan.png');
    expect([...form.keys()].sort()).toEqual(['docVersion', 'documentId', 'file', 'languages']);
    expect(form.get('documentId')).toBe('letter-7');
    expect(form.get('docVersion')).toBe('a');
    expect(form.get('languages')).toBe('eng,hin');
    expect(await screen.findByText(T('mod.ai.ocr.submitted', { id: 'ocr-1' }))).toBeTruthy();
    expect(await screen.findByRole('region', { name: T('mod.ai.ocr.detail', { id: 'ocr-1' }) })).toBeTruthy();
  });

  it('leaves out an empty document version and cannot be sent without the required fields', async () => {
    api.upload.mockResolvedValue(TRANSCRIPT);
    routes['/ai/ocr-transcripts/ocr-1'] = TRANSCRIPT;
    show(<OcrReview />);
    const send = within(upload()).getByRole('button', { name: T('mod.ai.ocr.submit') }) as HTMLButtonElement;
    expect(send.disabled).toBe(true);
    fillUpload();
    expect(send.disabled).toBe(false);
    type(upload(), 'mod.ai.ocr.languages', ' ');
    expect(send.disabled).toBe(true);
    type(upload(), 'mod.ai.ocr.languages', 'eng');
    fireEvent.click(send);
    await waitFor(() => expect(api.upload).toHaveBeenCalledTimes(1));
    expect((api.upload.mock.calls[0][1] as FormData).has('docVersion')).toBe(false);
  });

  it.each([
    [400, 'VALIDATION_FAILED', 'error.validation'],
    [409, 'KB_VERSION_NOT_BUILDING', 'error.code.KB_VERSION_NOT_BUILDING'],
    [422, 'OCR_REJECTED', 'error.code.OCR_REJECTED'],
    [503, 'OCR_UNAVAILABLE', 'error.code.OCR_UNAVAILABLE'],
  ])('a %i %s refusal is shown by name', async (status, code, title) => {
    api.upload.mockRejectedValue(problem(status, code, `detail of ${code}`));
    show(<OcrReview />);
    fireEvent.click(within(fillUpload()).getByRole('button', { name: T('mod.ai.ocr.submit') }));
    const alert = await within(upload()).findByRole('alert');
    expect(within(alert).getByText(T(title))).toBeTruthy();
    expect(within(alert).getByText(`detail of ${code}`)).toBeTruthy();
  });
});

describe('OCR review queue', () => {
  it('lists pending transcripts by default and filters by state and knowledge base', async () => {
    routes['/ai/ocr-transcripts'] = [SUMMARY, { ...SUMMARY, id: 'ocr-0', documentId: 'letter-6', createdAt: '2026-11-14T10:00:00Z' }];
    show(<OcrReview />);
    const q = await screen.findByRole('region', { name: T('mod.ai.ocr.queue') });
    await within(q).findByText('letter-7');
    expect(api.get).toHaveBeenCalledWith('/ai/ocr-transcripts', { state: 'PENDING_REVIEW', limit: 100 }, expect.anything());
    const rows = within(q).getAllByRole('row').slice(1).map((r) => within(r).getAllByRole('cell')[0].textContent);
    expect(rows).toEqual(['ocr-1', 'ocr-0']);
    fireEvent.change(within(q).getByLabelText(L('mod.ai.ocr.state')), { target: { value: 'APPROVED' } });
    type(q, 'mod.ai.ocr.kb', 'rti');
    fireEvent.click(within(q).getByRole('button', { name: T('common.search') }));
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/ai/ocr-transcripts', { state: 'APPROVED', kb: 'rti', limit: 100 }, expect.anything()));
    fireEvent.change(within(q).getByLabelText(L('mod.ai.ocr.state')), { target: { value: '' } });
    fireEvent.click(within(q).getByRole('button', { name: T('common.search') }));
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/ai/ocr-transcripts', { kb: 'rti', limit: 100 }, expect.anything()));
  });

  it('a refused queue read is shown by name', async () => {
    routes['/ai/ocr-transcripts'] = problem(403, 'FORBIDDEN', 'needs ai.review');
    show(<OcrReview />);
    expect(await screen.findByText('needs ai.review')).toBeTruthy();
  });
});

describe('OCR transcript review', () => {
  it('shows the engine text and the image hash, never the scan', async () => {
    const d = await openDetail();
    expect(api.get).toHaveBeenCalledWith('/ai/ocr-transcripts/ocr-1', undefined, expect.anything());
    expect((within(d).getByLabelText(L('mod.ai.ocr.engineText')) as HTMLTextAreaElement).value).toBe('Engine read this');
    expect((within(d).getByLabelText(L('mod.ai.ocr.engineText')) as HTMLTextAreaElement).readOnly).toBe(true);
    expect(within(d).getByText('ab12cd34')).toBeTruthy();
    expect(within(d).getByText(T('mod.ai.ocr.noImage'))).toBeTruthy();
    expect(d.querySelector('img')).toBeNull();
  });

  it('approving with a blank correction approves the engine text as read', async () => {
    const d = await openDetail();
    api.post.mockResolvedValue({ ...TRANSCRIPT, state: 'APPROVED', chunks: 2 });
    fireEvent.click(within(d).getByRole('button', { name: T('act.approve') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
    expect(lastPost()).toEqual(['/ai/ocr-transcripts/ocr-1/decide', { approve: true }, KEY]);
    expect(await screen.findByText(T('mod.ai.ocr.decided'))).toBeTruthy();
  });

  it('an unchanged copy of the engine text is not a correction', async () => {
    const d = await openDetail();
    api.post.mockResolvedValue({ ...TRANSCRIPT, state: 'APPROVED', chunks: 1 });
    fireEvent.click(within(d).getByRole('button', { name: T('mod.ai.ocr.copyEngine') }));
    expect((within(d).getByLabelText(L('mod.ai.ocr.corrected')) as HTMLTextAreaElement).value).toBe('Engine read this');
    fireEvent.click(within(d).getByRole('button', { name: T('act.approve') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
    expect(lastPost()).toEqual(['/ai/ocr-transcripts/ocr-1/decide', { approve: true }, KEY]);
  });

  it('approving sends the corrected text and the note', async () => {
    const d = await openDetail();
    api.post.mockResolvedValue({ ...TRANSCRIPT, state: 'APPROVED', correctedText: 'Engine read this, fixed', chunks: 1 });
    type(d, 'mod.ai.ocr.corrected', 'Engine read this, fixed');
    type(d, 'mod.ai.ocr.note', 'two words fixed');
    fireEvent.click(within(d).getByRole('button', { name: T('act.approve') }));
    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
    expect(lastPost()).toEqual(['/ai/ocr-transcripts/ocr-1/decide', { approve: true, correctedText: 'Engine read this, fixed', note: 'two words fixed' }, KEY]);
    expect(await within(d).findByText(T('mod.ai.ocr.decided'))).toBeTruthy();
    expect(within(d).queryByRole('button', { name: T('act.approve') })).toBeNull();
  });

  it('rejecting sends approve false with the note and never the corrected text', async () => {
    const d = await openDetail();
    api.post.mockResolvedValue({ ...TRANSCRIPT, state: 'REJECTED', reviewNote: 'wrong page' });
    type(d, 'mod.ai.ocr.corrected', 'something');
    type(d, 'mod.ai.ocr.note', 'wrong page');
    fireEvent.click(within(d).getByRole('button', { name: T('act.reject') }));
    await waitFor(() => expect(lastPost()).toEqual(['/ai/ocr-transcripts/ocr-1/decide', { approve: false, note: 'wrong page' }, KEY]));
  });

  it.each([
    ['SELF_REVIEW_FORBIDDEN', 409],
    ['OCR_TRANSCRIPT_NOT_PENDING', 409],
    ['KB_VERSION_NOT_BUILDING', 409],
    ['OCR_TRANSCRIPT_EMPTY', 422],
  ])('the server refusal %s is shown by name; the buttons are offered to the submitter too', async (code, status) => {
    const d = await openDetail();
    api.post.mockRejectedValue(problem(status, code, `detail of ${code}`));
    fireEvent.click(within(d).getByRole('button', { name: T('act.approve') }));
    const alert = await within(d).findByRole('alert');
    expect(within(alert).getByText(T(`error.code.${code}`))).toBeTruthy();
    expect(within(alert).getByText(`detail of ${code}`)).toBeTruthy();
  });

  it('a decided transcript shows the decision and offers no decision', async () => {
    routes['/ai/ocr-transcripts'] = [{ ...SUMMARY, state: 'APPROVED' }];
    routes['/ai/ocr-transcripts/ocr-1'] = { ...TRANSCRIPT, state: 'APPROVED', correctedText: 'Fixed text', chunks: 2, reviewedBy: 'officer', reviewedAt: '2026-11-15T11:00:00Z', reviewNote: 'ok' };
    show(<OcrReview />);
    fireEvent.click(await screen.findByRole('button', { name: T('mod.ai.ocr.open', { id: 'ocr-1' }) }));
    const d = await screen.findByRole('region', { name: T('mod.ai.ocr.detail', { id: 'ocr-1' }) });
    expect(within(d).getByText('officer')).toBeTruthy();
    expect(within(d).getByText('ok')).toBeTruthy();
    expect((within(d).getByLabelText(L('mod.ai.ocr.corrected')) as HTMLTextAreaElement).value).toBe('Fixed text');
    expect(within(d).queryByRole('button', { name: T('act.approve') })).toBeNull();
    expect(within(d).queryByRole('button', { name: T('act.reject') })).toBeNull();
  });
});

describe('knowledge-base search', () => {
  it('shows the OCR transcript a hit was ingested from', async () => {
    api.post.mockResolvedValue([
      { documentId: 'letter-7', docVersion: 'a', seq: 0, text: 'from the scan', score: 0.91, ocrTranscriptId: 'ocr-1' },
      { documentId: 'policy', docVersion: '1', seq: 4, text: 'typed text', score: 0.5 },
    ]);
    show(<KbSearch />);
    const s = screen.getByRole('region', { name: T('mod.ai.ocr.search') });
    type(s, 'mod.ai.ocr.kb', 'rti');
    type(s, 'mod.ai.ocr.query', 'appeal');
    fireEvent.click(within(s).getByRole('button', { name: T('common.search') }));
    await waitFor(() => expect(lastPost()).toEqual(['/ai/knowledge-bases/rti/search', { query: 'appeal' }]));
    const scanned = (await within(s).findByText('from the scan')).closest('tr')!;
    expect(within(scanned).getByText('ocr-1')).toBeTruthy();
    const typed = within(s).getByText('typed text').closest('tr')!;
    expect(within(typed).queryByText('ocr-1')).toBeNull();
    expect(within(s).getByRole('columnheader', { name: T('mod.ai.ocr.transcript') })).toBeTruthy();
  });
});

describe('AI page', () => {
  it('offers the OCR review and the knowledge-base search as tabs', async () => {
    api.page.mockResolvedValue(EMPTY_PAGE);
    routes['/ai/ocr-transcripts'] = [SUMMARY];
    show(<AiPage />);
    fireEvent.click(screen.getByRole('tab', { name: T('mod.ai.ocr.title') }));
    expect(await screen.findByRole('region', { name: T('mod.ai.ocr.queue') })).toBeTruthy();
    fireEvent.click(screen.getByRole('tab', { name: T('mod.ai.ocr.search') }));
    expect(await screen.findByRole('region', { name: T('mod.ai.ocr.search') })).toBeTruthy();
  });
});
describe('refusal titles', () => {
  it('names every OCR refusal by its code', () => {
    for (const [s, c] of [[409, 'KB_VERSION_NOT_BUILDING'], [422, 'OCR_REJECTED'], [503, 'OCR_UNAVAILABLE'], [409, 'OCR_TRANSCRIPT_NOT_PENDING'], [409, 'SELF_REVIEW_FORBIDDEN'], [422, 'OCR_TRANSCRIPT_EMPTY']] as const) {
      expect(problemTitleKey(problem(s, c))).toBe(`error.code.${c}`);
      expect(T(`error.code.${c}`)).not.toBe('');
    }
  });
});
