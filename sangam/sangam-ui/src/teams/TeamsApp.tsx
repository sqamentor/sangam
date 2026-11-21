// SPDX-License-Identifier: Apache-2.0
/**
 * Decision 162 (M-109): the Sangam personal tab inside Microsoft Teams. It shows the user's work (task inbox and task
 * detail, the same screens as the console) after the pop-up OIDC sign-in of teamsAuth.ts. The server stays the
 * security boundary: every call carries the user's own bearer token, exactly as in the console.
 *
 * Sign-out here only forgets the tokens in this tab (the IdP's sign-out page cannot run inside a Teams frame).
 */
import { lazy, Suspense, useState, type ComponentType } from 'react';
import { MemoryRouter, Navigate, Route, Routes } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthProvider';
import { IdleSession } from '../auth/IdleSession';
import { SeatContextGuard } from '../auth/SeatContextGuard';
import { SignedOutNotice } from '../auth/SignedOutNotice';
import { Loading, Notice, PageHeader, ProblemAlert } from '../components/ui';
import { signInFromTab, TeamsSignInError } from './teamsAuth';
import type { UserManager } from 'oidc-client-ts';

function named<T>(loader: () => Promise<T>, name: keyof T) {
  return lazy(async () => ({ default: (await loader())[name] as unknown as ComponentType }));
}
const TaskInbox = named(() => import('../areas/tasklist/TaskInbox'), 'TaskInbox');
const TaskDetail = named(() => import('../areas/tasklist/TaskDetail'), 'TaskDetail');

export interface TeamsTabProps {
  authenticate: (url: string) => Promise<string>;
  /** The OIDC client whose store AuthProvider reads (createUserManager with the Teams page as redirect). */
  userManager: Pick<UserManager, 'storeUser' | 'removeUser'>;
  origin: string;
  base: string;
}

export function TeamsSignIn({ authenticate, userManager, origin, base }: TeamsTabProps) {
  const { t } = useTranslation();
  const auth = useAuth();
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<TeamsSignInError>();
  const signIn = async () => {
    setBusy(true);
    setFailure(undefined);
    try {
      if (auth.mode === 'dev') await auth.login();
      else {
        const user = await signInFromTab(authenticate, userManager, origin, base);
        // E-29(b): the session is loaded for the new token; when GET /me answers 401 to it, the sign-in below says
        // that the server did not accept the sign-in (SignedOutNotice), as the console's sign-in screen does
        await auth.signedIn(user.access_token);
      }
    } catch (e) {
      setFailure(e instanceof TeamsSignInError ? e : new TeamsSignInError('failed', String(e)));
    } finally {
      setBusy(false);
    }
  };
  return (
    <main className="teams-signin">
      <PageHeader title={t('teams.title')} description={t('teams.signInDesc')} />
      <SignedOutNotice />
      {failure && <Notice tone={failure.reason === 'cancelled' ? 'info' : 'bad'}>{t(failure.reason === 'cancelled' ? 'teams.cancelled' : failure.reason === 'invalid' ? 'teams.invalid' : 'teams.failed')}</Notice>}
      <button type="button" className="primary" disabled={busy} onClick={() => void signIn()}>
        {t('teams.signIn')}
      </button>
    </main>
  );
}

export function TeamsTab(props: TeamsTabProps) {
  const { t } = useTranslation();
  const auth = useAuth();
  if (auth.status === 'loading') return <Loading />;
  if (auth.status !== 'authenticated') {
    return (
      <>
        {auth.status === 'error' && <ProblemAlert error={auth.error} onRetry={auth.reload} />}
        <TeamsSignIn {...props} />
      </>
    );
  }
  return (
    // E-32(d): a seat the server no longer accepts (403 SEAT_NOT_HELD) is dropped with a plain message, as in the console
    <SeatContextGuard>
    <MemoryRouter initialEntries={['/tasks']}>
      {/* decision 359: the same idle sign-out as the console (warning, then the session ends; IdleSession.tsx) */}
      <IdleSession />
      <div className="teams-bar toolbar">
        <span className="muted small">{auth.me?.displayName ?? auth.me?.userId}</span>
        <button
          type="button"
          className="link"
          onClick={() => void props.userManager.removeUser().then(() => auth.reload())}
        >
          {t('teams.signOut')}
        </button>
      </div>
      <main id="main">
        <Suspense fallback={<Loading />}>
          <Routes>
            <Route path="/tasks" element={<TaskInbox />} />
            <Route path="/tasks/:id" element={<TaskDetail />} />
            <Route path="*" element={<Navigate to="/tasks" replace />} />
          </Routes>
        </Suspense>
      </main>
    </MemoryRouter>
    </SeatContextGuard>
  );
}

/** Shown when the page is opened outside Teams, or when the Teams app is switched off in config.json. */
export function TeamsUnavailable({ reason }: { reason: 'disabled' | 'not-in-teams' }) {
  const { t } = useTranslation();
  return (
    <main>
      <PageHeader title={t('teams.title')} />
      <Notice tone="info">{t(reason === 'disabled' ? 'teams.disabled' : 'teams.notInTeams')}</Notice>
    </main>
  );
}

/** The pop-up's own screen while it signs in or hands the result back. */
export function TeamsPopupStatus({ state }: { state: 'starting' | 'done' | 'failed' }) {
  const { t } = useTranslation();
  return (
    <main>
      <p role="status">{t(state === 'starting' ? 'teams.popupStarting' : state === 'done' ? 'teams.popupDone' : 'teams.failed')}</p>
    </main>
  );
}
