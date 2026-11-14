/** One case of a run (PackageToolsPort.TestRun) or of a stored report (BulkModelTests.CaseResult, :30). */
export type TestCaseRow = { name: string; status: string; deterministic: boolean; message?: string; millis?: number };
/** A stored report (BulkModelTests.Report, BulkModelTests.java:33), newest first from GET .../tests/reports (:74-76). */
export type TestReport = { runId: string; packageId?: string; passed: number; failed: number; notJudged: number; cases: { caseId: string; status: string; deterministic: boolean; millis?: number; message?: string }[] };

/**
 * A run's results as rows. A case whose name starts with "judge:" is not deterministic: the server stores it so
 * (ModellerWiring.java:225) and never counts it as passed or failed.
 */
export function runRows(runs: { name: string; passed?: boolean; message?: string; durationMs?: number; deterministic?: boolean; status?: string }[]): TestCaseRow[] {
  return runs.map((r) => ({
    name: r.name,
    status: r.status ?? (r.passed ? 'PASSED' : 'FAILED'),
    deterministic: r.deterministic ?? !r.name.startsWith('judge:'),
    message: r.message,
    millis: r.durationMs,
  }));
}

export function reportRows(r: TestReport): TestCaseRow[] {
  return (r.cases ?? []).map((x) => ({ name: x.caseId, status: x.status, deterministic: x.deterministic, message: x.message, millis: x.millis }));
}

function CaseTables({ rows }: { rows: TestCaseRow[] }) {
  const { t } = useTranslation();
  const det = rows.filter((r) => r.deterministic);
  const judge = rows.filter((r) => !r.deterministic);
  const table = (rs: TestCaseRow[], cap: string) => (
    <DataTable
      caption={cap}
      rows={rs}
      rowKey={(r) => r.name}
      columns={[
        { key: 'name', header: t('common.name') },
        { key: 's', header: t('admin.status'), render: (r) => <Badge value={r.status} /> },
        { key: 'ms', header: t('tests.millis'), className: 'num', render: (r) => (r.millis !== undefined ? fmtNumber(r.millis) : '—') },
        { key: 'm', header: t('ops.message'), render: (r) => r.message ?? '—' },
      ]}
    />
  );
  return (
    <>
      {det.length > 0 && table(det, t('mod.modeller2.deterministic'))}
      {judge.length > 0 && (
        <>
          {table(judge, t('mod.modeller2.judged'))}
          <Notice>{t('mod.modeller2.judgedHint')}</Notice>
        </>
      )}
    </>
  );
}

/**
 * M-038 (NG-32, R80): bulk model tests of a package. Run them (POST /packages/{id}/tests/run, package.author,
 * PackageToolsController.java:49-52), list the stored reports (GET /packages/{id}/tests/reports, package.read,
 * ModellerController.java:368-372) with a report's cases, judge results kept apart and outside the counts, export a
 * sealed bundle of test cases and mocks (POST /packages/{id}/tests/export, package.author, :374-380; format
 * sangam-test-bundle-v1 with sha256, BulkModelTests.java:79-89) and verify one (POST /packages/tests/verify-bundle,
 * package.read, :382-386; a changed bundle is refused with TEST_BUNDLE_INVALID, BulkModelTests.java:92-97).
 */
export function BulkTestReport({ packageId }: { packageId: string }) {
  const { t } = useTranslation();
  const pid = encodeURIComponent(packageId);
  const [run, setRun] = useState<TestCaseRow[]>();
  const [open, setOpen] = useState<string | null>(null);
  const [cases, setCases] = useState('[]');
  const [mocks, setMocks] = useState('[]');
  const [bundleText, setBundleText] = useState('');
  const [verified, setVerified] = useState<Row>();
  const [jsonErr, setJsonErr] = useState(false);
  const act = useAction();
  const reports = useAsync((signal) => http.get<TestReport[]>(`/packages/${pid}/tests/reports`, undefined, signal), [pid]);
  const shown = (reports.data ?? []).find((r) => r.runId === open);
  const exportBundle = () => {
    let body: { cases: unknown; mocks: unknown };
    try {
      body = { cases: JSON.parse(cases), mocks: JSON.parse(mocks) };
      if (!Array.isArray(body.cases) || !Array.isArray(body.mocks)) throw new Error('arrays');
    } catch {
      setJsonErr(true);
      return;
    }
    setJsonErr(false);
    void act.run(() => http.post<{ bundle: string }>(`/packages/${pid}/tests/export`, body)).then((r) => {
      if (!r) return;
      setBundleText(r.bundle);
      const url = URL.createObjectURL(new Blob([r.bundle], { type: 'application/json' }));
      const a = document.createElement('a');
      a.href = url;
      a.download = `${packageId}-tests.json`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
  };
  return (
    <Section title={t('mod.modeller2.bulkTests')}>
      <button
        type="button"
        className="primary"
        disabled={act.busy}
        onClick={() => void act.run(() => http.post<Parameters<typeof runRows>[0]>(`/packages/${pid}/tests/run`)).then((r) => { if (r) { setRun(runRows(r)); reports.reload(); } })}
      >
        {t('modeller.runTests')}
      </button>
      <ProblemAlert error={act.error} />
      {run && <CaseTables rows={run} />}

      <h3>{t('tests.reports')}</h3>
      <ProblemAlert error={reports.error} onRetry={reports.reload} />
      {reports.data && reports.data.length === 0 && <Empty message={t('tests.noReports')} />}
      {reports.data && reports.data.length > 0 && (
        <DataTable
          caption={t('tests.reports')}
          captionHidden
          rows={reports.data}
          rowKey={(r) => r.runId}
          columns={[
            { key: 'runId', header: t('fld.runId'), render: (r) => <button type="button" className="link" aria-pressed={open === r.runId} onClick={() => setOpen(open === r.runId ? null : r.runId)}>{r.runId}</button> },
            { key: 'passed', header: t('tests.passed'), className: 'num', render: (r) => fmtNumber(r.passed) },
            { key: 'failed', header: t('tests.failed'), className: 'num', render: (r) => fmtNumber(r.failed) },
            { key: 'notJudged', header: t('tests.notJudged'), className: 'num', render: (r) => fmtNumber(r.notJudged) },
          ]}
        />
      )}
      {shown && (
        <>
          <h4>{t('tests.report', { id: shown.runId })}</h4>
          <CaseTables rows={reportRows(shown)} />
          <button type="button" onClick={() => setCases(JSON.stringify(shown.cases.map((x) => ({ id: x.caseId, processKey: packageId, definition: {} })), null, 2))}>
            {t('tests.casesFromReport')}
          </button>
        </>
      )}

      <h3>{t('tests.export')}</h3>
      <p className="hint">{t('tests.exportHint')}</p>
      <TextArea label={t('tests.cases')} className="code" rows={4} value={cases} onChange={setCases} />
      <TextArea label={t('tests.mocks')} className="code" rows={3} value={mocks} onChange={setMocks} />
      {jsonErr && <Notice tone="bad">{t('mod.common.invalidJson')}</Notice>}
      <button type="button" disabled={act.busy} onClick={exportBundle}>
        {t('tests.exportButton')}
      </button>

      <h3>{t('tests.verify')}</h3>
      <div className="field">
        <label htmlFor="bundle-file">{t('tests.bundleFile')}</label>
        <input id="bundle-file" type="file" accept=".json,application/json" onChange={(e) => void e.target.files?.[0]?.text().then((x) => (setBundleText(x), setVerified(undefined)))} />
      </div>
      <TextArea label={t('tests.bundle')} className="code" rows={3} value={bundleText} onChange={(x) => (setBundleText(x), setVerified(undefined))} />
      <button type="button" disabled={act.busy || !bundleText.trim()} onClick={() => void act.run(() => http.post<Row>('/packages/tests/verify-bundle', { bundle: bundleText })).then((r) => r && setVerified(r))}>
        {t('tests.verifyButton')}
      </button>
      {verified && (
        <>
          <Notice tone="ok">{t('tests.verified', { pkg: String(verified.packageId ?? ''), rev: String(verified.packageRevision ?? '') })}</Notice>
          <JsonView value={verified} />
        </>
      )}
    </Section>
  );
}
