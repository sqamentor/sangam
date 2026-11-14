
/**
 * M-033 (public): the tenant's privacy policy for anyone, signed in or not. A DPDP notice is public by nature, and the
 * server serves it without authentication (GET /compliance/privacy-policy, security: [] in sangam-api.yaml;
 * SecurityConfig.java:80-81). The tenant is the signed-in caller's, else the :tenant path segment or ?tenant=
 * (ComplianceController.java:84-95, publicTenant). The language follows the UI; the server falls back and says so.
 */
export function PublicPrivacy() {
  const { t, i18n } = useTranslation();
  const { tenant: pathTenant } = useParams();
  const queryTenant = new URLSearchParams(window.location.search).get('tenant') ?? undefined;
  const tenant = pathTenant ?? queryTenant;
  const policy = useAsync((signal) => http.get<RenderedNotice>('/compliance/privacy-policy', { lang: i18n.language, tenant }, signal), [i18n.language, tenant]);
  return (
    <main className="public-page">
      <PageHeader title={t('privacy.publicTitle')} description={t('privacy.publicDesc')} />
      <ProblemAlert error={policy.error} onRetry={policy.reload} />
      {policy.loading && <Loading />}
      {policy.data && <NoticeView notice={policy.data} />}
    </main>
  );
}
