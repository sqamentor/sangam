// SPDX-License-Identifier: Apache-2.0
/**
 * UX-SPEC 6.1 (decision 397): Group > Entry > Item, built from the menu (nav.ts). The last item is the current page.
 * Rendered inside a router only: PageHeader leaves it out for embedded components and isolated tests.
 */
import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { navLocate } from '../app/nav';

export function Breadcrumbs({ current }: { current?: string }) {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const hit = navLocate(pathname);
  if (!hit || hit.area.id === 'help') return null;
  const atEntry = pathname === hit.item.to;
  const entry = t(hit.item.label);
  return (
    <nav aria-label={t('frame.breadcrumb')} className="breadcrumbs">
      <ol>
        <li>{t(hit.area.label)}</li>
        <li>{atEntry ? <span aria-current="page">{entry}</span> : <Link to={hit.item.to}>{entry}</Link>}</li>
        {!atEntry && current && current !== entry && (
          <li>
            <span aria-current="page">{current}</span>
          </li>
        )}
      </ol>
    </nav>
  );
}

/**
 * The page's one-line "what this page is": the page's own description, else the menu entry's description when the
 * address is the entry itself. Rendered inside a router only (PageHeader checks).
 */
export function PageDescription({ given }: { given?: string }) {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const hit = navLocate(pathname);
  const text = given ?? (hit && hit.item.to === pathname ? t(hit.item.description) : undefined);
  return text ? <p className="muted">{text}</p> : null;
}
