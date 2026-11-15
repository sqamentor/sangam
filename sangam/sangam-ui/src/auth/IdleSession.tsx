// SPDX-License-Identifier: Apache-2.0
/**
 * Decision 349: mounted while a user is signed in. Shows the idle warning with a countdown and "Stay signed in", and
 * ends the session when the configured period passes without input (idle.ts). The user then signs in again.
 */
import { useTranslation } from 'react-i18next';
import { config } from '../config';
import { useAuth } from './AuthProvider';
import { useIdleTimeout } from './idle';

export function IdleSession() {
  const { t } = useTranslation();
  const { endSession } = useAuth();
  const { phase, remainingMs, stay } = useIdleTimeout({
    idleMs: config().session.idleMinutes * 60_000,
    onIdle: () => void endSession('idle'),
  });
  if (phase !== 'warning') return null;
  return (
    <div className="idle-warning alert alert-warn" role="alertdialog" aria-modal="false" aria-labelledby="idle-warning-title" aria-describedby="idle-warning-text">
      <h2 id="idle-warning-title" className="small">
        {t('auth.idleWarningTitle')}
      </h2>
      <p id="idle-warning-text">{t('auth.idleWarning', { seconds: Math.ceil(remainingMs / 1000) })}</p>
      {/* autoFocus: the warning is the one thing to act on now (keyboard users reach the button at once) */}
      <button type="button" className="primary" autoFocus onClick={stay}>
        {t('auth.idleStay')}
      </button>
    </div>
  );
}
