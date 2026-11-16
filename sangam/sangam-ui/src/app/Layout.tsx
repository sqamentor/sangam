// SPDX-License-Identifier: Apache-2.0
/**
 * Application shell: skip link, banner with language switch and seat context (S02), role-aware navigation, main
 * landmark, GIGW footer and a polite live region. Focus moves to the page heading on every navigation.
 */
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { NavLink, Link, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthProvider';
import { LANGS, setLanguage, fmtDate, type Lang } from '../i18n';
import { announce, registerAnnouncer } from '../lib/hooks';
import { syncFieldRecords, unsentFieldRecords } from '../lib/offline/session';
import { LogoutPrompt } from './LogoutPrompt';
import { navEnd, visibleNav } from './nav';
import { usePlugins } from '../plugins/host';
import { GlobalSearch } from '../modules/platform';
import { config } from '../config';

/** Owner decision Q-06 / 397 Q11: 0.1.0 is English only; the switch shows only when more than one locale is configured. */
export function LanguageSwitch() {
  const { t, i18n } = useTranslation();
  const offered = LANGS.filter((l) => config().locales.includes(l.code));
  if (offered.length < 2) return null;
  return (
    <div className="lang-switch" role="group" aria-label={t('common.language')}>
      {offered.map((l) => (
        <button
          key={l.code}
          type="button"
          lang={l.code}
          aria-pressed={i18n.language === l.code}
          onClick={() => setLanguage(l.code as Lang)}
        >
          {l.label}
        </button>
      ))}
    </div>
  );
}

export function SeatSwitcher() {
  const { t } = useTranslation();
  const { me, seat, setSeat } = useAuth();
  if (!me || me.seats.length === 0) return null;
  return (
    <div className="seat-switch">
      <label htmlFor="seat-select">{t('seat.actingAs')}</label>
      <select id="seat-select" value={seat?.seatId ?? ''} onChange={(e) => setSeat(e.target.value || null)}>
        {me.seats.map((s) => (
          <option key={s.seatId} value={s.seatId} disabled={s.pendingApproval}>
            {s.seatName} — {t(`seat.kind.${s.kind}`)}
            {s.actingForPersonName ? ` (${t('seat.actingFor', { name: s.actingForPersonName })})` : ''}
            {s.validTo ? ` · ${t('seat.until', { date: fmtDate(s.validTo, false) })}` : ''}
            {s.pendingApproval ? ` · ${t('seat.pending')}` : ''}
          </option>
        ))}
      </select>
    </div>
  );
}

export function Layout({ children }: { children: ReactNode }) {
  const { t, i18n } = useTranslation();
  const { me, logout, mode } = useAuth();
  const loc = useLocation();
  const [msg, setMsg] = useState('');
  const [navOpen, setNavOpen] = useState(false);
  const first = useRef(true);
  // M-001: signing out with unsent field records asks first (non-blocking); the records stay encrypted and bound
  const [unsent, setUnsent] = useState<number | null>(null);
  const [syncing, setSyncing] = useState(false);
  const signOut = async () => {
    const n = me ? await unsentFieldRecords(me) : 0;
    if (n > 0) setUnsent(n);
    else void logout();
  };
  const syncBeforeLogout = async () => {
    if (!me) return;
    setSyncing(true);
    try {
      const left = await syncFieldRecords(me);
      setUnsent(left > 0 ? left : null);
      if (left === 0) announce(t('field.synced'));
    } catch {
      /* offline or refused: the prompt stays with the same count */
    } finally {
      setSyncing(false);
    }
  };

  useEffect(() => registerAnnouncer((m) => setMsg(m)), []);
  const goto = useNavigate();
  // Tasklist-style shortcuts (Help > Keyboard shortcuts), each only for a caller whose menu offers its page.
  const shortcuts = useShortcuts();
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (!e.altKey || !e.shiftKey) return;
      const to = shortcuts.find((k) => k.key === e.key.toUpperCase())?.to;
      if (to) {
        e.preventDefault();
        goto(to);
      }
    };
    window.addEventListener('keydown', on);
    return () => window.removeEventListener('keydown', on);
  }, [goto, shortcuts]);

  useEffect(() => {
    setNavOpen(false);
    if (first.current) {
      first.current = false;
      return;
    }
    // Move focus to the new page's heading so screen-reader and keyboard users land on the content.
    window.setTimeout(() => (document.getElementById('page-title') as HTMLElement | null)?.focus(), 50);
  }, [loc.pathname]);

  // M-028 and decision 397: the seven job groups as this caller may use them (nav.ts visibleNav).
  const areas = visibleNav(me);
  const appPages = usePlugins('app.page');
  const adminPages = usePlugins('admin.page');
  const pluginPages = [...appPages, ...adminPages];

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        {t('common.skipToContent')}
      </a>
      <header className="banner" role="banner">
        <button type="button" className="nav-toggle" aria-expanded={navOpen} aria-controls="primary-nav" onClick={() => setNavOpen((v) => !v)}>
          {t('common.menu')}
        </button>
        <Link to="/" className="brand">
          <span aria-hidden="true" className="logo" />
          {t('app.name')}
        </Link>
        <div className="banner-right">
          <LanguageSwitch />
          {me && <Link to="/notifications" className="bell">{t('menu.find.notifications.label')}</Link>}
          <SeatSwitcher />
          {me && (
            <div className="user">
              <Link to="/account">
                {me.displayName}
                {me.tenantName ? ` · ${me.tenantName}` : ''}
              </Link>
              {mode === 'dev' && <span className="badge badge-warn">{t('auth.devMode')}</span>}
              <button type="button" onClick={() => void signOut()}>
                {t('auth.signOut')}
              </button>
            </div>
          )}
        </div>
      </header>
      {unsent !== null && <LogoutPrompt count={unsent} busy={syncing} onSync={() => void syncBeforeLogout()} onLogout={() => void logout()} />}
      <div className="body">
        <nav id="primary-nav" className={`sidenav${navOpen ? ' open' : ''}`} aria-label={t('nav.primary')}>
          <ul className="nav-home">
            <li>
              <NavLink to="/" end>
                {t('menu.home')}
              </NavLink>
            </li>
          </ul>
          {areas.map((a) => (
            <div key={a.id} className={`nav-area nav-area-${a.id}`}>
              <h2 className="nav-heading">{t(a.label)}</h2>
              {a.sections.map((s, n) => (
                <div key={s.label ?? n} className="nav-section">
                  {s.label && <h3 className="nav-subheading">{t(s.label)}</h3>}
                  <ul>
                    {s.items.map((i) => (
                      <li key={i.to}>
                        <NavLink to={i.to} end={navEnd(i.to)} title={t(i.description)}>
                          {t(i.label)}
                        </NavLink>
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          ))}
          {pluginPages.length > 0 && (
            <div className="nav-area">
              <h2 className="nav-heading">{t('plugin.nav')}</h2>
              <ul>
                {pluginPages.map((p) => (
                  <li key={p.pluginId + p.contribution.id}>
                    <NavLink to={`/x/${p.pluginId}/${p.contribution.id}`}>{i18n.language === 'hi' ? p.contribution.label.hi : p.contribution.label.en}</NavLink>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </nav>
        <main id="main" tabIndex={-1}>
          {children}
        </main>
      </div>
      <GlobalSearch />
      <footer className="footer" role="contentinfo">
        <ul>
          <li>
            <Link to="/help/accessibility">{t('footer.accessibility')}</Link>
          </li>
          <li>
            <Link to="/help/about">{t('footer.about')}</Link>
          </li>
          <li>{t('footer.licence')}</li>
          <li>
            <Link to="/help/shortcuts">{t('menu.help.shortcuts.label')}</Link>
          </li>
        </ul>
      </footer>
      <div className="sr-only" aria-live="polite" aria-atomic="true">
        {msg}
      </div>
    </div>
  );
}

/** Keyboard shortcuts offered to this caller: each only when the caller's menu holds its page (UX-SPEC 6.7). */
export function useShortcuts(): { key: string; to: string; label: string }[] {
  const { me } = useAuth();
  return useMemo(() => {
    const open = new Set(visibleNav(me).flatMap((a) => a.sections.flatMap((s) => s.items.map((i) => i.to))));
    return [
    { key: 'T', to: '/tasks', label: 'menu.work.tasks.label' },
    { key: 'F', to: '/tasks/filters', label: 'menu.work.taskLists.label' },
    { key: 'S', to: '/start', label: 'menu.work.start.label' },
    ].filter((k) => open.has(k.to));
  }, [me]);
}
