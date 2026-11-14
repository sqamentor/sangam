
/** RenderedNotice (ConsentNoticeService.java:45-46; language: JurisdictionPack.Language, JurisdictionPack.java:39). */
export interface RenderedNotice {
  noticeKey: string;
  version: number;
  language?: { code?: string; name?: string; nativeName?: string; script?: string; rtl?: boolean };
  purposeCodes?: string[];
  text?: string;
  html?: string;
  contentHash?: string;
  fallback?: boolean;
}

/**
 * A rendered notice in its own language and direction (lang / dir from the server's language), as plain text: the
 * server also sends `html`, but the page never injects markup it did not build (CSP script-src 'self', X.4.7).
 */
export function NoticeView({ notice }: { notice: RenderedNotice }) {
  const { t } = useTranslation();
  const lang = notice.language?.code;
  return (
    <>
      <KeyValues
        items={[
          [t('privacy.version'), String(notice.version)],
          [t('privacy.language'), notice.language?.nativeName ?? notice.language?.name ?? lang ?? '—'],
          [t('privacy.purposes'), (notice.purposeCodes ?? []).join(', ') || '—'],
          [t('privacy.contentHash'), <code key="h" className="hash">{notice.contentHash ?? '—'}</code>],
        ]}
      />
      {notice.fallback && <Notice tone="warn">{t('privacy.fallback')}</Notice>}
      <div className="notice-text" lang={lang} dir={notice.language?.rtl ? 'rtl' : 'auto'} style={{ whiteSpace: 'pre-wrap' }}>
        {notice.text ?? ''}
      </div>
    </>
  );
}

/**
 * M-033 (admin): the privacy policy as the public sees it (GET /compliance/privacy-policy ?lang, public,
 * ComplianceController.java:114-120; SecurityConfig.java:80-81), a published consent notice by key and version
 * (GET /compliance/notices/{key} ?version&lang, :98-104), and publishing a new notice version from purposes
 * (POST /compliance/notices/{key}/publish {purposes}, compliance.admin, :106-111; ComplianceRequests.java:18). The policy
 * itself has no edit endpoint: it is rendered from the active purposes, the activity register and the published
 * contacts (ConsentNoticeService.java:198), so it is edited through those.
 */
export function PrivacyAdmin() {
  const { t, i18n } = useTranslation();
  const [lang, setLang] = useState(i18n.language);
  const [key, setKey] = useState('');
  const [version, setVersion] = useState('');
  const [q, setQ] = useState<{ lang: string } | null>(null);
  const [nq, setNq] = useState<{ key: string; version?: string; lang: string } | null>(null);
  const policy = useAsync((signal) => http.get<RenderedNotice>('/compliance/privacy-policy', { lang: q?.lang || undefined }, signal), [JSON.stringify(q)], !!q);
  const notice = useAsync((signal) => http.get<RenderedNotice>(`/compliance/notices/${encodeURIComponent(nq?.key ?? '')}`, { version: nq?.version || undefined, lang: nq?.lang || undefined }, signal), [JSON.stringify(nq)], !!nq);
  return (
    <>
      <Section title={t('privacy.policy')}>
        <p className="hint">{t('privacy.policyHint')}</p>
        <form className="filters" onSubmit={(e) => (e.preventDefault(), setQ({ lang }))}>
          <TextField label={t('privacy.languageCode')} value={lang} onChange={setLang} />
          <button type="submit">{t('privacy.preview')}</button>
        </form>
        <ProblemAlert error={policy.error} onRetry={policy.reload} />
        {policy.data && <NoticeView notice={policy.data} />}
      </Section>
      <Section title={t('privacy.notice')}>
        <form className="filters" onSubmit={(e) => (e.preventDefault(), setNq({ key: key.trim(), version: version.trim(), lang }))}>
          <TextField label={t('fld.noticeKey')} required value={key} onChange={setKey} />
          <TextField label={t('privacy.version')} type="number" min={1} value={version} onChange={setVersion} hint={t('privacy.versionHint')} />
          <button type="submit" disabled={!key.trim()}>{t('common.open')}</button>
        </form>
        <ProblemAlert error={notice.error} onRetry={notice.reload} />
        {notice.data && <NoticeView notice={notice.data} />}
      </Section>
    </>
  );
}
