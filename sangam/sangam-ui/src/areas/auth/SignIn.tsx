// SPDX-License-Identifier: Apache-2.0
/** S01 / C01 sign-in: OIDC redirect, or the static dev token for the server's dev profile. */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../auth/AuthProvider';
import { failureText } from '../../auth/failure';
import { SignedOutNotice } from '../../auth/SignedOutNotice';
import { config } from '../../config';
import { LanguageSwitch } from '../../app/Layout';
import { Notice, ProblemAlert, TextField } from '../../components/ui';

export function SignIn() {
  const { t } = useTranslation();
  const { login, mode, status, error, reload } = useAuth();
  const [token, setToken] = useState(config().devToken);
  const [busy, setBusy] = useState(false);
  const [startFailed, setStartFailed] = useState(false);
  /**
   * Register E-23: starting the sign-in can fail before the browser leaves the page (the identity provider's discovery
   * document cannot be fetched, times out, or is not what it should be; UserManager.signinRedirect rejects). The
   * rejection used to go nowhere and the button did nothing visible. It is said here in the catalogue's words, with a
   * retry; the cause goes to the console as text (auth/failure.ts). The library's own message is not shown: it is
   * English only and names the address it asked.
   */
  const start = (devToken?: string) => {
    setBusy(true);
    setStartFailed(false);
    void login(devToken)
      .catch((e: unknown) => {
        console.error(`Sign-in could not be started. ${failureText(e)}`);
        setStartFailed(true);
      })
      .finally(() => setBusy(false));
  };
  return (
    <main id="main" className="signin" tabIndex={-1}>
      <div className="signin-card">
        <LanguageSwitch />
        <h1 id="page-title" tabIndex={-1}>
          {t('auth.title')}
        </h1>
        <p>{t('auth.intro')}</p>
        <SignedOutNotice />
        {status === 'error' && <ProblemAlert error={error} onRetry={reload} />}
        {startFailed && (
          <Notice tone="bad">
            <strong>{t('auth.startFailed')}</strong>
            <p className="problem-next">{t('auth.startFailedNext')}</p>
            <button type="button" onClick={() => start(mode === 'dev' ? token : undefined)}>
              {t('common.retry')}
            </button>
          </Notice>
        )}
        {mode === 'oidc' ? (
          <button type="button" className="primary" disabled={busy} onClick={() => start()}>
            {busy ? t('auth.redirecting') : t('auth.signInSso')}
          </button>
        ) : (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              start(token);
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
