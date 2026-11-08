// SPDX-License-Identifier: Apache-2.0
/** S01 / C01 sign-in: OIDC redirect, or the static dev token for the server's dev profile. */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../auth/AuthProvider';
import { config } from '../../config';
import { LanguageSwitch } from '../../app/Layout';
import { ProblemAlert, TextField } from '../../components/ui';

export function SignIn() {
  const { t } = useTranslation();
  const { login, mode, status, error, reload } = useAuth();
  const [token, setToken] = useState(config().devToken);
  const [busy, setBusy] = useState(false);
  return (
    <main id="main" className="signin" tabIndex={-1}>
      <div className="signin-card">
        <LanguageSwitch />
        <h1 id="page-title" tabIndex={-1}>
          {t('auth.title')}
        </h1>
        <p>{t('auth.intro')}</p>
        {status === 'error' && <ProblemAlert error={error} onRetry={reload} />}
        {mode === 'oidc' ? (
          <button
            type="button"
            className="primary"
            disabled={busy}
            onClick={() => {
              setBusy(true);
              void login().finally(() => setBusy(false));
            }}
          >
            {busy ? t('auth.redirecting') : t('auth.signInSso')}
          </button>
        ) : (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              setBusy(true);
              void login(token).finally(() => setBusy(false));
            }}
          >
            <p className="alert alert-warn">{t('auth.devNotice')}</p>
            <TextField label={t('auth.devToken')} value={token} onChange={setToken} autoComplete="off" required />
            <button type="submit" className="primary" disabled={busy}>
              {t('auth.signIn')}
            </button>
          </form>
        )}
        <p className="muted small">{t('auth.assisted')}</p>
      </div>
    </main>
  );
}

export function AuthCallback() {
  const { t } = useTranslation();
  const { completeCallback } = useAuth();
  const nav = useNavigate();
  const [err, setErr] = useState<Error>();
  useEffect(() => {
    completeCallback()
      .then((to) => nav(to, { replace: true }))
      .catch((e: Error) => setErr(e));
  }, [completeCallback, nav]);
  return (
    <main id="main" className="signin">
      <p aria-live="polite">{t('auth.completing')}</p>
      <ProblemAlert error={err} />
    </main>
  );
}
