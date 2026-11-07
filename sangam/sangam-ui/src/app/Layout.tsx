// SPDX-License-Identifier: Apache-2.0
/**
 * Application shell: skip link, banner with language switch and seat context (S02), role-aware navigation, main
 * landmark, GIGW footer and a polite live region. Focus moves to the page heading on every navigation.
 */
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { NavLink, Link, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthProvider';
import { LANGS, setLanguage, fmtDate, type Lang } from '../i18n';
import { registerAnnouncer } from '../lib/hooks';
import { NAV } from './nav';
import { usePlugins } from '../plugins/host';
import { GlobalSearch } from '../modules/platform';
import { config } from '../config';

export function LanguageSwitch() {
  const { t, i18n } = useTranslation();
  return (
    <div className="lang-switch" role="group" aria-label={t('common.language')}>
      {LANGS.filter((l) => config().locales.includes(l.code)).map((l) => (
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
  const { me, hasRole, logout, mode } = useAuth();
  const loc = useLocation();
  const [msg, setMsg] = useState('');
  const [navOpen, setNavOpen] = useState(false);
  const first = useRef(true);

  useEffect(() => registerAnnouncer((m) => setMsg(m)), []);
  const goto = useNavigate();
  // Tasklist-style shortcuts: Alt+Shift+T inbox, F filters, S start a process.
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (!e.altKey || !e.shiftKey) return;
      const to = { T: '/tasks', F: '/tasks/filters', S: '/start' }[e.key.toUpperCase()];
      if (to) {
        e.preventDefault();
        goto(to);
      }
    };
    window.addEventListener('keydown', on);
    return () => window.removeEventListener('keydown', on);
  }, [goto]);

  useEffect(() => {
    setNavOpen(false);
    if (first.current) {
      first.current = false;
      return;
    }
    // Move focus to the new page's heading so screen-reader and keyboard users land on the content.
    window.setTimeout(() => (document.getElementById('page-title') as HTMLElement | null)?.focus(), 50);
  }, [loc.pathname]);

  const areas = NAV.filter((a) => hasRole(...a.roles));
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
          <SeatSwitcher />
          {me && (
            <div className="user">
              <Link to="/account">
                {me.displayName}
                {me.tenantName ? ` · ${me.tenantName}` : ''}
              </Link>
              {mode === 'dev' && <span className="badge badge-warn">{t('auth.devMode')}</span>}
              <button type="button" onClick={() => void logout()}>
                {t('auth.signOut')}
              </button>
            </div>
          )}
        </div>
      </header>
      <div className="body">
        <nav id="primary-nav" className={`sidenav${navOpen ? ' open' : ''}`} aria-label={t('nav.primary')}>
          {areas.map((a) => (
            <div key={a.id} className="nav-area">
              <h2 className="nav-heading">{t(a.label)}</h2>
              <ul>
                {a.items.filter((i) => !i.only || hasRole(...i.only)).map((i) => (
                  <li key={i.to}>
                    <NavLink to={i.to} end={i.to === '/ops' || i.to === '/analytics' || i.to === '/audit' || i.to === '/portal' || i.to === '/modeller'}>
                      {t(i.label)}
                    </NavLink>
                  </li>
                ))}
              </ul>
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
          <li>{t('footer.shortcuts')}</li>
        </ul>
      </footer>
      <div className="sr-only" aria-live="polite" aria-atomic="true">
        {msg}
      </div>
    </div>
  );
}
