type DryRun = { ok?: boolean; problems?: { severity: string; code?: string; step?: string; subject?: string; message?: string }[]; plan?: unknown; requestHash?: string };

function DryRunResult({ dry }: { dry: DryRun }) {
  const { t } = useTranslation();
  return (
    <>
      <Notice tone={dry.ok ? 'ok' : 'bad'}>{dry.ok ? t('mod.onboarding.dryOk') : t('mod.onboarding.dryBad')}</Notice>
      <Findings items={(dry.problems ?? []).map((p) => ({ severity: p.severity, code: p.code, path: [p.step, p.subject].filter(Boolean).join(' / '), message: p.message }))} />
      <JsonView value={dry.plan ?? {}} />
    </>
  );
}

/** JSON fallback (kept from before M-035): every part of the request as a JSON editor. */
function NewRun({ onStarted }: { onStarted: () => void }) {
  const { t } = useTranslation();
  const [v, setV] = useState<Record<string, string>>({ tenant: '{\n  "id": "",\n  "name": ""\n}', profile: '{}', packs: '{}', security: '{}', seeds: '{}', compliance: '{}', goLive: '{}' });
  const [dry, setDry] = useState<DryRun>();
  const [err, setErr] = useState<string>();
  const act = useAction();
  const body = (): Row | null => {
    try {
      setErr(undefined);
      return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, JSON.parse(x || '{}')]));
    } catch (e) {
      setErr((e as Error).message);
      return null;
    }
  };
  return (
    <Section title={t('mod.onboarding.title')}>
      <p className="hint">{t('mod.onboarding.hint')}</p>
      {Object.keys(v).map((k) => (
        <TextArea key={k} label={t(`mod.onboarding.part_${k}`)} className="code" rows={k === 'tenant' ? 5 : 3} value={v[k]} onChange={(x) => (setV({ ...v, [k]: x }), setDry(undefined))} />
      ))}
      {err && <Notice tone="bad">{err}</Notice>}
      <div className="toolbar">
        <button type="button" disabled={act.busy} onClick={() => { const b = body(); if (b) void act.run(() => http.post<DryRun>('/onboarding/dry-run', b)).then((r) => r && setDry(r)); }}>{t('mod.onboarding.dryRun')}</button>
        <button type="button" className="primary" disabled={act.busy || !dry?.ok} onClick={() => { const b = body(); if (b) void act.run((k) => http.post<Row>('/onboarding/runs', b, k)).then((r) => { if (r) { announce(t('mod.common.done')); onStarted(); } }); }}>{t('mod.onboarding.start')}</button>
      </div>
      <ProblemAlert error={act.error} />
      {dry && <DryRunResult dry={dry} />}
    </Section>
  );
}

/**
 * M-035: the guided, schema-driven wizard (onboardingWizard.ts mirrors OnboardingRequest.java). Each step is a form;
 * the last step shows the request, dry-runs it (POST /onboarding/dry-run, OnboardingController.java:92) and starts the
 * run (POST /onboarding/runs, :98) only after a clean dry run. A changed value clears the dry run.
 */
export function GuidedRun({ onStarted, initial }: { onStarted: () => void; initial?: Row }) {
  const { t } = useTranslation();
  const [values, setValues] = useState<WizardValues>(() => (initial ? fromRequest(initial) : wizardDefaults()));
  const [step, setStep] = useState(0);
  const [dry, setDry] = useState<DryRun>();
  const act = useAction();
  const built = useMemo(() => toRequest(values), [values]);
  const errors = useMemo(() => Object.fromEntries(Object.entries(built.errors).map(([k, v]) => [k, t(v)])), [built.errors, t]);
  const review = step === STEPS.length;
  const cur = STEPS[step];
  const schema = useMemo(
    () => ({
      components: cur
        ? cur.fields.map((x) => {
            const ff = formField(x);
            return { ...ff, label: t(ff.label ?? ''), description: t(hintKey(x.path), { defaultValue: '' }) || undefined, values: ff.values?.map((o) => ({ ...o, label: t(o.label) })) };
          })
        : [],
    }),
    [cur, t],
  );
  const ok = Object.keys(built.errors).length === 0;
  return (
    <Section title={t('onb.guided')}>
      <p className="hint">{t('onb.guidedHint')}</p>
      <ol className="steps" aria-label={t('onb.steps')}>
        {[...STEPS.map((s) => s.id), 'review'].map((id, i) => (
          <li key={id} aria-current={i === step ? 'step' : undefined}>
            <button type="button" className="link" onClick={() => setStep(i)}>
              {t(`onb.step.${id}`)}
              {i < STEPS.length && stepHasError(STEPS[i], built.errors) ? ` (${t('onb.needsAttention')})` : ''}
            </button>
          </li>
        ))}
      </ol>
      {!review && cur && (
        <>
          <h3>{t(`onb.step.${cur.id}`)}</h3>
          <FormRenderer schema={schema} values={values as FormValues} onChange={(v) => (setValues(v as WizardValues), setDry(undefined))} errors={errors} />
        </>
      )}
      {review && (
        <>
          <h3>{t('onb.step.review')}</h3>
          {!ok && <Notice tone="bad">{t('onb.fixFirst')}</Notice>}
          <JsonView value={built.request} />
          <div className="toolbar">
            <button type="button" disabled={act.busy || !ok} onClick={() => void act.run(() => http.post<DryRun>('/onboarding/dry-run', built.request)).then((r) => r && setDry(r))}>
              {t('mod.onboarding.dryRun')}
            </button>
            <button
              type="button"
              className="primary"
              disabled={act.busy || !ok || !dry?.ok}
              onClick={() => void act.run((k) => http.post<Row>('/onboarding/runs', built.request, k)).then((r) => { if (r) { announce(t('mod.common.done')); onStarted(); } })}
            >
              {t('mod.onboarding.start')}
            </button>
          </div>
          <ProblemAlert error={act.error} />
          {dry && <DryRunResult dry={dry} />}
        </>
      )}
      <div className="toolbar">
        <button type="button" disabled={step === 0} onClick={() => setStep(step - 1)}>{t('onb.back')}</button>
        <button type="button" disabled={review} onClick={() => setStep(step + 1)}>{t('onb.next')}</button>
      </div>
    </Section>
  );
}

type BundleDiff = { path: string; kind: 'ADDED' | 'REMOVED' | 'CHANGED'; left?: unknown; right?: unknown };
type PromotionPlan = { sourceTenant?: string; sourceEnvironment?: string; targetTenant?: string; targetEnvironment?: string; changes?: BundleDiff[]; dryRun?: DryRun; planHash: string };
type Bundle = { format?: number; body?: Row; signature?: { keyId?: string; algorithm?: string; value?: string } };

/** The problem codes of the promotion service, explained (PromotionService.java:68-120, BundleService.java:89-107). */
export function promotionProblem(e: unknown, t: (k: string, o?: Record<string, unknown>) => string): string | null {
  if (!(e instanceof ApiError) || !e.problem.code) return null;
  const known = ['PROMOTION_ENVIRONMENT_NOT_SET', 'PROMOTION_ENVIRONMENT_MISSING', 'PROMOTION_SAME_ENVIRONMENT', 'PROMOTION_TARGET_MISMATCH', 'PROMOTION_PLAN_CHANGED', 'PROMOTION_DRY_RUN_FAILED', 'PROMOTION_NO_CHANGE', 'BUNDLE_INVALID', 'BUNDLE_SIGNATURE_INVALID', 'BUNDLE_UNSIGNED', 'BUNDLE_SIGNER_MISSING'];
  return known.includes(e.problem.code) ? t(`promo.err.${e.problem.code}`) : null;
}

/**
 * M-034: promotion between environments (PromotionController, sangam-server; platform administrators only, :68-72).
 * Export the tenant's signed bundle here (GET /onboarding/promotions/{tenant}/bundle, :116); in the target environment,
 * load it and plan (POST /onboarding/promotions/plan, :124), which verifies the signature (BundleService.verified) and
 * diffs it against the target's current configuration; apply the reviewed plan by its hash (POST
 * /onboarding/promotions, :135), which starts an ordinary onboarding run. A different administrator then approves the
 * run (maker-checker, OnboardingService.java:209-213: SEGREGATION_OF_DUTIES, APPROVAL_INVALIDATED).
 */
export function Promotions({ onApplied }: { onApplied: () => void }) {
  const { t } = useTranslation();
  const [source, setSource] = useState('');
  const [text, setText] = useState('');
  const [target, setTarget] = useState('');
  const [plan, setPlan] = useState<PromotionPlan>();
  const [run, setRun] = useState<Row>();
  const [parseErr, setParseErr] = useState<string>();
  const exp = useAction();
  const act = useAction();
  const bundle = useMemo((): Bundle | null => {
    if (!text.trim()) return null;
    try {
      return JSON.parse(text) as Bundle;
    } catch {
      return null;
    }
  }, [text]);
  const explained = promotionProblem(act.error ?? exp.error, t);
  return (
    <>
      <Section title={t('promo.export')}>
        <p className="hint">{t('promo.exportHint')}</p>
        <form className="filters" onSubmit={(e) => (e.preventDefault(), void exp.run(() => download(`/onboarding/promotions/${encodeURIComponent(source.trim())}/bundle`, undefined, `${source.trim()}-bundle.json`)))}>
          <TextField label={t('fld.tenant')} required value={source} onChange={setSource} />
          <button type="submit" disabled={!source.trim() || exp.busy}>{t('promo.download')}</button>
        </form>
      </Section>
      <Section title={t('promo.plan')}>
        <p className="hint">{t('promo.planHint')}</p>
        <div className="field">
          <label htmlFor="promo-file">{t('promo.bundleFile')}</label>
          <input id="promo-file" type="file" accept=".json,application/json" onChange={(e) => void e.target.files?.[0]?.text().then((x) => (setText(x), setPlan(undefined), setRun(undefined)))} />
        </div>
        <TextArea label={t('promo.bundle')} className="code" rows={6} value={text} onChange={(x) => (setText(x), setPlan(undefined), setRun(undefined), setParseErr(undefined))} />
        <TextField label={t('promo.target')} required value={target} onChange={(x) => (setTarget(x), setPlan(undefined))} />
        {bundle && (
          <KeyValues
            items={[
              [t('promo.sourceTenant'), String(bundle.body?.sourceTenant ?? '—')],
              [t('promo.sourceEnv'), String(bundle.body?.environment ?? '—')],
              [t('promo.signature'), bundle.signature ? t('promo.signedBy', { key: bundle.signature.keyId ?? '?', alg: bundle.signature.algorithm ?? '?' }) : t('promo.unsigned')],
            ]}
          />
        )}
        {parseErr && <Notice tone="bad">{parseErr}</Notice>}
        <button
          type="button"
          disabled={act.busy || !text.trim() || !target.trim()}
          onClick={() => {
            if (!bundle) return setParseErr(t('mod.common.invalidJson'));
            void act.run(() => http.post<PromotionPlan>('/onboarding/promotions/plan', { bundle, targetTenant: target.trim() })).then((p) => p && setPlan(p));
          }}
        >
          {t('promo.makePlan')}
        </button>
        {explained ? <Notice tone="bad">{explained}</Notice> : <ProblemAlert error={act.error ?? exp.error} />}
      </Section>
      {plan && bundle && (
        <Section title={t('promo.review')}>
          <Notice tone="ok">{bundle.signature ? t('promo.verified', { key: bundle.signature.keyId ?? '?' }) : t('promo.unsignedAccepted')}</Notice>
          <KeyValues
            items={[
              [t('promo.from'), `${plan.sourceTenant ?? '—'} @ ${plan.sourceEnvironment ?? '—'}`],
              [t('promo.to'), `${plan.targetTenant ?? '—'} @ ${plan.targetEnvironment ?? '—'}`],
              [t('promo.planHash'), <code key="h" className="hash">{plan.planHash}</code>],
            ]}
          />
          {(plan.changes ?? []).length === 0 ? (
            <Notice>{t('promo.noChanges')}</Notice>
          ) : (
            <DataTable
              caption={t('promo.changes')}
              rows={(plan.changes ?? []).map((x, i) => ({ ...x, i }))}
              rowKey={(r) => String(r.i)}
              columns={[
                { key: 'kind', header: t('promo.kind'), render: (r) => <Badge value={r.kind} tone={r.kind === 'REMOVED' ? 'bad' : r.kind === 'ADDED' ? 'ok' : 'warn'} /> },
                { key: 'path', header: t('fld.path'), render: (r) => <code>{r.path}</code> },
                { key: 'left', header: t('promo.current'), render: (r) => cell(r.left) },
                { key: 'right', header: t('promo.incoming'), render: (r) => cell(r.right) },
              ]}
            />
          )}
          {plan.dryRun && <DryRunResult dry={plan.dryRun} />}
          <button
            type="button"
            className="primary"
            disabled={act.busy || !plan.dryRun?.ok || (plan.changes ?? []).length === 0 || !!run}
            onClick={() => void act.run((k) => http.post<Row>('/onboarding/promotions', { bundle, targetTenant: target.trim(), planHash: plan.planHash }, k)).then((r) => { if (r) { setRun(r); announce(t('mod.common.done')); onApplied(); } })}
          >
            {t('promo.apply')}
          </button>
          {run && <Notice tone="ok">{t('promo.applied', { id: String(run.id ?? ''), status: String(run.status ?? '') })}</Notice>}
        </Section>
      )}
    </>
  );
}

export function OnboardingWizard() {
  const [n, setN] = useState(0);
  return (
    <ModulePage
      key={n}
      title="mod.onboarding.title"
      screens={[runs]}
      extra={[
        { id: 'guided', label: 'onb.guided', node: <GuidedRun onStarted={() => setN(n + 1)} /> },
        { id: 'new', label: 'onb.jsonEditor', node: <NewRun onStarted={() => setN(n + 1)} /> },
        { id: 'promote', label: 'promo.title', node: <Promotions onApplied={() => setN(n + 1)} /> },
      ]}
    />
  );
}

// ------------------------------------------------------------------ packs (M-030)

/** InstalledPack (sangam-api.yaml; PackDeployer.Installed, PackDeployer.java:305-307). */
export type InstalledPack = { packId: string; version: string; tenantId: string; deploymentId: string; deployedAt?: string };

export const PACK_CODES = ['PACK_NOT_INSTALLED', 'PACK_HAS_DEPENDANTS', 'PACK_HAS_LIVE_INSTANCES', 'PACK_NO_PREVIOUS_VERSION', 'PACK_VERSION_NOT_SEMVER', 'PACK_OPERATION_IN_PROGRESS', 'PACK_REJECTED'] as const;

/**
 * A pack lifecycle refusal, explained (PackLifecycleController.java:82-97; PackDeployer.java:369-515,
 * PackOperationTxLock.java:65): the reason in words plus the server's `blockers` (409) or `violations` (422).
 */
export function packProblem(e: unknown, t: (k: string, o?: Record<string, unknown>) => string): { message: string; items: string[] } | null {
  if (!(e instanceof ApiError)) return null;
  const code = e.problem.code ?? '';
  if (!(PACK_CODES as readonly string[]).includes(code)) return null;
  const items = [...((e.problem.blockers as string[] | undefined) ?? []), ...((e.problem.violations as string[] | undefined) ?? [])].map(String);
  return { message: t(`packs.reason.${code}`), items };
}

/** Versions per pack, oldest first as the server lists them; rollback needs two distinct versions (SL-54). */
export function groupPacks(list: InstalledPack[]): { packId: string; versions: InstalledPack[]; distinct: number }[] {
  const by = new Map<string, InstalledPack[]>();
  for (const p of list) by.set(p.packId, [...(by.get(p.packId) ?? []), p]);
  return [...by.entries()].map(([packId, versions]) => ({ packId, versions, distinct: new Set(versions.map((v) => v.version)).size }));
}

/**
 * M-030: installed packs of a tenant, uninstall and roll back (PackLifecycleController, sangam-server). The server
 * requires tenant.admin AND the platform group sangam-admin (:51-61), so the route is for PLATFORM_ADMIN only.
 * Both commands need the tenant and a reason, which the audit records (PACK_UNINSTALLED / PACK_ROLLED_BACK, :117, :134).
 */
export function PackLifecycle() {
  const { t } = useTranslation();
  const [draft, setDraft] = useState('');
  const [tenant, setTenant] = useState('');
  const [op, setOp] = useState<{ kind: 'uninstall' | 'rollback'; packId: string } | null>(null);
  const [result, setResult] = useState<unknown>();
  const list = useAsync((signal) => http.get<InstalledPack[]>('/packs/installed', { tenant }, signal), [tenant], !!tenant);
  const act = useAction();
  const groups = groupPacks(list.data ?? []);
  const explained = packProblem(act.error, t);
  return (
    <>
      <PageHeader title={t('packs.title')} description={t('packs.desc')} />
      <form className="filters" onSubmit={(e) => (e.preventDefault(), setTenant(draft.trim()), setResult(undefined))}>
        <TextField label={t('fld.tenant')} required value={draft} onChange={setDraft} />
        <button type="submit" disabled={!draft.trim()}>{t('common.open')}</button>
      </form>
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {tenant && list.loading && <Loading />}
      {tenant && list.data && groups.length === 0 && <Empty message={t('packs.none')} />}
      {groups.length > 0 && (
        <DataTable
          caption={t('packs.installed')}
          rows={groups}
          rowKey={(g) => g.packId}
          columns={[
            { key: 'packId', header: t('packs.pack'), render: (g) => <code>{g.packId}</code> },
            { key: 'versions', header: t('packs.versions'), render: (g) => g.versions.map((v) => v.version).join(' → ') },
            { key: 'deployedAt', header: t('packs.deployedAt'), render: (g) => fmtDate(g.versions[g.versions.length - 1]?.deployedAt) },
            {
              key: 'a',
              header: t('common.actions'),
              render: (g) => (
                <span className="toolbar inline">
                  <button type="button" className="link" disabled={g.distinct < 2} title={g.distinct < 2 ? t('packs.reason.PACK_NO_PREVIOUS_VERSION') : undefined} onClick={() => (act.reset(), setOp({ kind: 'rollback', packId: g.packId }))}>
                    {t('packs.rollback')}
                  </button>
                  <button type="button" className="link danger" onClick={() => (act.reset(), setOp({ kind: 'uninstall', packId: g.packId }))}>
                    {t('packs.uninstall')}
                  </button>
                </span>
              ),
            },
          ]}
        />
      )}
      {result !== undefined && (
        <Section title={t('mod.common.done')}>
          <JsonView value={result} />
        </Section>
      )}
      <ReasonDialog
        open={!!op}
        title={op ? t(op.kind === 'uninstall' ? 'packs.confirmUninstall' : 'packs.confirmRollback', { id: op.packId, tenant }) : ''}
        danger={op?.kind === 'uninstall'}
        busy={act.busy}
        error={explained ? undefined : act.error}
        onClose={() => setOp(null)}
        onConfirm={(reason) =>
          op &&
          void act.run((k) => http.post<unknown>(`/packs/${encodeURIComponent(op.packId)}/${op.kind}`, { tenant, reason }, k)).then((r) => {
            if (r !== undefined) {
              setResult(r);
              setOp(null);
              list.reload();
              announce(t('mod.common.done'));
            }
          })
        }
      >
        <p>{op?.kind === 'uninstall' ? t('packs.uninstallHint') : t('packs.rollbackHint')}</p>
        {explained && (
          <Notice tone="bad">
            {explained.message}
            {explained.items.length > 0 && <ul>{explained.items.map((x) => <li key={x}><code>{x}</code></li>)}</ul>}
          </Notice>
        )}
      </ReasonDialog>
    </>
  );
}

// ------------------------------------------------------------------ custom fields (M-031)

/**
 * Tenant custom fields (TenantCustomFieldController, sangam-rest .../web): list (form.read, :165-175), the versions of
 * one field (form.read, :177-186), define (tenant.admin, :188-206) and retire (tenant.admin, :208-225). The object
 * type is `form:<formKey>`: no other type is registered yet (TenantCustomFieldsWiring.java:21-34). The key is
 * `x_<name>` (TenantCustomFieldRegistry.java:43) and the schema is the profile at :44-48 (string, integer, boolean,
 * array; decimals as {type: string, format: decimal}).
 */
const customFields: ScreenDef = {
  id: 'customFields',
  title: 'cf.title',
  description: 'cf.desc',
  list: '/custom-fields',
  filters: [f.text('objectType', 'cf.objectType'), f.date('on', 'cf.on')],
  columns: [c('key', 'cf.key'), c('objectType', 'cf.objectType'), c('version'), c('label', 'cf.label'), c('required', 'cf.required'), c('readOnly', 'cf.readOnly'), c('effectiveFrom'), c('effectiveTo', 'cf.effectiveTo')],
  rowKey: ['key'],
  sections: [{ id: 'versions', label: 'cf.versions', path: '/custom-fields/{objectType}/{key}' }],
  create: {
    id: 'define',
    label: 'cf.define',
    path: '/custom-fields',
    noJson: true,
    hint: 'cf.defineHint',
    fields: [
      f.text('objectType', 'cf.objectType', true, { description: 'cf.objectTypeHint' }),
      f.text('key', 'cf.key', true, { description: 'cf.keyHint', validate: { required: true, pattern: 'x_[a-z][a-z0-9_]{0,62}' } }),
      f.text('label', 'cf.label'),
      f.bool('required', 'cf.required'),
      f.bool('readOnly', 'cf.readOnly'),
      f.json('schema', 'cf.schema', true),
      f.date('effectiveFrom', undefined, true),
      f.date('effectiveTo', 'cf.effectiveTo'),
    ],
    defaults: () => ({ schema: '{"type": "string", "maxLength": 100}', effectiveFrom: todayIso() }),
  },
  actions: [
    {
      id: 'retire',
      label: 'act.retire',
      path: '/custom-fields/{objectType}/{key}/retire',
      fields: [f.date('effectiveTo', 'cf.retireOn', true)],
      reason: 'body',
      danger: true,
      noJson: true,
      hint: 'cf.retireHint',
      visible: (r) => !r.effectiveTo,
    },
  ],
};

export function CustomFieldsPage() {
  return <ModuleScreen def={customFields} />;
}
