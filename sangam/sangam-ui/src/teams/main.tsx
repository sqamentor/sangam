// SPDX-License-Identifier: Apache-2.0
/**
 * Decision 162 (M-109): entry of the optional Microsoft Teams app (teams.html, a separate page of the same build).
 * The console (index.html, src/main.tsx) never imports anything under src/teams, so the Teams module can be left out
 * (no teams.html deployed, or teams.enabled false in config.json) without changing any other feature.
 */
import { StrictMode, type ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import '../styles.css';
import { loadConfig } from '../config';
import { applyFonts } from '../fonts';
import { initI18n } from '../i18n';
import { AuthProvider, createUserManager } from '../auth/AuthProvider';
import { completePopupLogin, startPopupLogin, teamsPageUrl, teamsView } from './teamsAuth';
import { TeamsPopupStatus, TeamsTab, TeamsUnavailable } from './TeamsApp';

/** How long the tab waits for the Teams host before saying "open this from Teams". */
const HOST_TIMEOUT_MS = 5000;

async function boot() {
  const cfg = await loadConfig();
  await applyFonts(cfg.fonts);
  initI18n(cfg.defaultLanguage);
  const root = createRoot(document.getElementById('root')!);
  const render = (node: ReactElement) => root.render(<StrictMode>{node}</StrictMode>);
  if (!cfg.teams.enabled) {
    render(<TeamsUnavailable reason="disabled" />);
    return;
  }
  const { teamsSdk } = await import('./sdk');
  const base = import.meta.env.BASE_URL;
  const mgr = createUserManager(teamsPageUrl(window.location.origin, base));
  const view = teamsView(window.location.search);

  if (view === 'auth-start') {
    render(<TeamsPopupStatus state="starting" />);
    await teamsSdk.initialize(HOST_TIMEOUT_MS);
    try {
      await startPopupLogin(mgr);
    } catch (e) {
      // E-29(a): the sign-in could not be started (identity provider not reachable, discovery refused). The pop-up
      // says so and the tab that opened it is told, as for a failed end of the sign-in below.
      render(<TeamsPopupStatus state="failed" />);
      teamsSdk.notifyFailure(e instanceof Error ? e.message : 'failed');
    }
    return;
  }
  if (view === 'auth-end') {
    await teamsSdk.initialize(HOST_TIMEOUT_MS);
    try {
      const result = await completePopupLogin(mgr);
      // The pop-up keeps nothing: the tab stores the user; this window closes on notifySuccess.
      await mgr.removeUser();
      render(<TeamsPopupStatus state="done" />);
      teamsSdk.notifySuccess(result);
    } catch (e) {
      render(<TeamsPopupStatus state="failed" />);
      teamsSdk.notifyFailure(e instanceof Error ? e.message : 'failed');
    }
    return;
  }
  if (!(await teamsSdk.initialize(HOST_TIMEOUT_MS))) {
    render(<TeamsUnavailable reason="not-in-teams" />);
    return;
  }
  const theme = await teamsSdk.theme();
  if (theme === 'dark' || theme === 'contrast') document.documentElement.dataset.theme = 'dark';
  render(
    // decision 359: the idle sign-out forgets this tab's tokens; the provider's sign-out page cannot run in a Teams frame
    <AuthProvider providerSignOut={false}>
      <TeamsTab authenticate={teamsSdk.authenticate} userManager={mgr} origin={window.location.origin} base={base} />
    </AuthProvider>,
  );
}

void boot();
