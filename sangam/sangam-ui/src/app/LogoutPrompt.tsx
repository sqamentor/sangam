// SPDX-License-Identifier: Apache-2.0
/**
 * M-001 (owner decision 2026-11-12): signing out with unsent field records shows a non-blocking prompt, "N records not
 * yet sent", with Sync now and Log out anyway. Either way the records stay encrypted on the device and bound to this
 * user; they are sent only when the same user signs in again (or now, by Sync now).
 */
import { useTranslation } from 'react-i18next';

export function LogoutPrompt({ count, busy, onSync, onLogout }: { count: number; busy?: boolean; onSync: () => void; onLogout: () => void }) {
  const { t } = useTranslation();
  return (
    <div className="alert alert-warn" role="region" aria-live="polite" aria-label={t('field.logoutPromptLabel')}>
      <p>{t('field.logoutUnsent', { n: count })}</p>
      <p className="hint">{t('field.logoutKept')}</p>
      <div className="toolbar">
        <button type="button" className="primary" disabled={busy} onClick={onSync}>
          {t('field.syncNow')}
        </button>
        <button type="button" disabled={busy} onClick={onLogout}>
          {t('field.logoutAnyway')}
        </button>
      </div>
    </div>
  );
}
