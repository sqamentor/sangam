// SPDX-License-Identifier: Apache-2.0
/**
 * Why the last session ended, or why the last sign-in did not hold, said on the sign-in screen (console and Teams tab)
 * until the next sign-in: decision 349 (idle), register E-23 (the session could not be renewed; the server did not
 * accept the token of a completed sign-in). The reason comes from AuthProvider (SignedOutReason).
 */
import { useTranslation } from 'react-i18next';
import { config } from '../config';
import { useAuth } from './AuthProvider';

export function SignedOutNotice() {
  const { t } = useTranslation();
  const { signedOutReason } = useAuth();
  if (signedOutReason === 'idle') {
    return (
      <p className="alert alert-info" role="status">
        {t('auth.idleSignedOut', { minutes: config().session.idleMinutes })}
      </p>
    );
  }
  if (signedOutReason === 'expired') {
    return (
      <p className="alert alert-info" role="status">
        {t('auth.sessionExpired')}
      </p>
    );
  }
  if (signedOutReason === 'refused') {
    return (
      <p className="alert alert-bad" role="alert">
        {t('auth.signInRefused')}
      </p>
    );
  }
  return null;
}
