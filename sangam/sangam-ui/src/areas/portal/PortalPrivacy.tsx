// SPDX-License-Identifier: Apache-2.0
/**
 * The citizen's own data-protection pages (owner decisions 340 and 347):
 * - My consents: every consent the caller gave, with its notice and version, and one Withdraw button per active purpose
 *   plus one for the whole consent. Withdrawing is as easy as giving: one click, the existing owner-withdraw endpoint
 *   (POST /compliance/consents/{id}/withdraw, ComplianceController.java:158-168), no reason asked.
 * - My notices: the notices sent to the caller's portal account (a personal-data breach notice with the items the
 *   jurisdiction pack requires, IN: DPDP Rules r.7(1)(a)-(e)), each item under the pack's label and citation.
 * Purpose names come from the current notice of the caller's services; an unknown code is shown as the code.
 */
import { useTranslation } from 'react-i18next';
import { api } from '../../api/endpoints';
import type { MyConsent, PortalNotification } from '../../api/types';
import { announce, useAction, useAsync } from '../../lib/hooks';
import { fmtDate } from '../../i18n';
import { Badge, Empty, KeyValues, Loading, Notice, PageHeader, ProblemAlert, Section } from '../../components/ui';

/** Purpose code to name, from the published notices of the caller's services (best effort: a refusal leaves the code). */
async function purposeNames(lang: string): Promise<Record<string, string>> {
  const services = await api.portal.services();
  const names: Record<string, string> = {};
  const noticed = services.filter((s) => s.noticeKey);
  const notices = await Promise.all(noticed.map((s) => api.portal.notice(s.key, lang).catch(() => undefined)));
  for (const n of notices) for (const p of n?.purposes ?? []) names[p.code] = p.name;
  return names;
}

export function PortalConsents() {
  const { t, i18n } = useTranslation();
  const list = useAsync(() => api.portal.myConsents(), []);
  const names = useAsync(() => purposeNames(i18n.language), [i18n.language]);
  const act = useAction();
  const name = (code: string) => names.data?.[code] ?? code;
  const withdraw = (c: MyConsent, purposes: string[]) =>
    void act.run((k) => api.portal.withdrawConsent(c.consentId, purposes, k)).then((r) => {
      if (r) {
        announce(t('portal.consentWithdrawn'));
        list.reload();
      }
    });
  return (
    <>
      <PageHeader title={t('portal.myConsents')} description={t('portal.myConsentsDesc')} />
      <ProblemAlert error={list.error ?? act.error} onRetry={list.reload} />
      {list.loading && !list.data && <Loading />}
      {list.data?.length === 0 && <Empty message={t('portal.noConsents')} />}
      {list.data?.map((c) => (
        <Section key={c.consentId} title={t('portal.consentOn', { notice: c.noticeKey ?? '—', version: c.noticeVersion })}>
          <KeyValues
            items={[
              [t('admin.status'), <Badge key="s" value={c.status} />],
              [t('portal.consentGivenAt'), fmtDate(c.givenAt)],
              [t('portal.consentWithdrawnAt'), c.withdrawnAt ? fmtDate(c.withdrawnAt) : undefined],
            ]}
          />
          {c.activePurposes.length === 0 ? (
            <Notice>{t('portal.consentNothingActive')}</Notice>
          ) : (
            <>
              <ul className="plain">
                {c.activePurposes.map((p) => (
                  <li key={p}>
                    {name(p)}{' '}
                    <button type="button" disabled={act.busy} onClick={() => withdraw(c, [p])}>
                      {t('portal.withdrawPurpose', { purpose: name(p) })}
                    </button>
                  </li>
                ))}
              </ul>
              {c.activePurposes.length > 1 && (
                <button type="button" className="danger" disabled={act.busy} onClick={() => withdraw(c, [])}>
                  {t('portal.withdrawAll')}
                </button>
              )}
            </>
          )}
        </Section>
      ))}
    </>
  );
}

export function PortalNotices() {
  const { t } = useTranslation();
  const list = useAsync(() => api.portal.notifications(), []);
  return (
    <>
      <PageHeader title={t('portal.myNotices')} description={t('portal.myNoticesDesc')} />
      <ProblemAlert error={list.error} onRetry={list.reload} />
      {list.loading && !list.data && <Loading />}
      {list.data?.length === 0 && <Empty message={t('portal.noNotices')} />}
      {list.data?.map((n: PortalNotification) => (
        <Section key={n.id} title={n.subject ?? t('portal.breachNotice')}>
          <p className="small muted">
            {t('portal.breachNotice')} · {fmtDate(n.at)}
          </p>
          <dl className="notice-items">
            {n.items.map((i) => (
              <div key={i.key}>
                <dt>
                  {i.label}
                  {i.citation ? <span className="small muted"> ({i.citation})</span> : null}
                </dt>
                <dd style={{ whiteSpace: 'pre-wrap' }}>{i.text}</dd>
              </div>
            ))}
          </dl>
        </Section>
      ))}
    </>
  );
}
