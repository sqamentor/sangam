
// ------------------------------------------------------------------ M-032 module security defaults

/** ModuleDefaultsResult (sangam-api.yaml; SecurityDefaultsController.java:62-66). */
export interface ModuleDefaultsResult {
  tenantId: string;
  roleTemplates: string[];
  selfService: boolean;
}

/**
 * M-032: installs the module role templates of the caller's tenant (POST /admin/security/module-defaults
 * {selfService}, SecurityDefaultsController.java:46-67; tenant.admin, :48). Installing again is safe: the command is
 * idempotent (x-sangam-idempotent) and the templates are the server's. Without sangam-security the server answers 501
 * CAPABILITY_NOT_INSTALLED (:50-52).
 */
export function ModuleSecurityDefaults() {
  const { t } = useTranslation();
  const [selfService, setSelfService] = useState(true);
  const [result, setResult] = useState<ModuleDefaultsResult>();
  const act = useAction();
  return (
    <>
      <PageHeader title={t('secdef.title')} description={t('secdef.desc')} />
      <Section title={t('secdef.install')}>
        <Checkbox label={t('secdef.selfService')} hint={t('secdef.selfServiceHint')} checked={selfService} onChange={setSelfService} />
        <button
          type="button"
          className="primary"
          disabled={act.busy}
          onClick={() => void act.run((k) => http.post<ModuleDefaultsResult>('/admin/security/module-defaults', { selfService }, k)).then((r) => { if (r) { setResult(r); announce(t('mod.common.done')); } })}
        >
          {act.busy ? t('common.working') : t('secdef.installButton')}
        </button>
        <ProblemAlert error={act.error} />
      </Section>
      {result && (
        <Section title={t('secdef.installed')}>
          <KeyValues items={[[t('fld.tenant'), result.tenantId], [t('secdef.selfService'), result.selfService ? t('common.yes') : t('common.no')]]} />
          {result.roleTemplates.length ? (
            <ul>{result.roleTemplates.map((r) => <li key={r}><code>{r}</code></li>)}</ul>
          ) : (
            <Empty message={t('secdef.noTemplates')} />
          )}
        </Section>
      )}
    </>
  );
}
