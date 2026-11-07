// SPDX-License-Identifier: Apache-2.0
/**
 * Runtime UI plugin host. The server verifies signatures and enablement (plugin loader, lib-ext/plugins) and lists the
 * verified bundles at GET /plugins/ui (authenticated). Entry files are public verified assets: the shell imports them
 * directly from the same origin, so the strict script-src 'self' holds, and relative imports inside a plugin work.
 * Plugin code must not contain secrets, because the assets are readable without a token.
 * The module either exports mount(element, ctx), placed at the manifest's contributes[].slot, or a default
 * { contributions: [...] }.
 * A plugin failure is contained: it is logged and the rest of the app renders.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { buildUrl, http } from '../api/client';
import { Notice, PageHeader } from '../components/ui';

export const POINTS = ['app.page', 'app.api', 'dashboard.widget', 'process-definition.tab', 'process-instance.tab', 'process-instance.action', 'task.tab', 'decision.tab', 'admin.page', 'diagram.overlay'] as const;
export type Point = (typeof POINTS)[number];

export interface Contribution {
  point: Point;
  id: string;
  label: { en: string; hi: string };
  /** Mount into the element; may return a cleanup. Not used by diagram.overlay / app.api. */
  render?: (el: HTMLElement, ctx: PluginContext) => void | (() => void);
  /** diagram.overlay: badges for a diagram. */
  badges?: (ctx: PluginContext) => Promise<{ activityId: string; text: string; href?: string }[]>;
  /** process-instance.action: run on click. */
  run?: (ctx: PluginContext) => Promise<void> | void;
}

export interface PluginContext {
  language: string;
  /** Same client as the app: bearer token, seat headers, Idempotency-Key. The server authorises every call. */
  api: typeof http;
  processDefinitionId?: string;
  processInstanceId?: string;
  taskId?: string;
  decisionDefinitionId?: string;
}

interface Manifest {
  id: string;
  version: string;
  name?: string;
  entry: string;
  contributes?: { slot: string; title?: string; titleHi?: string }[];
}

interface Loaded {
  pluginId: string;
  contribution: Contribution;
}

let loading: Promise<Loaded[]> | null = null;

const SLOT_ALIAS: Record<string, Point> = { 'admin.panel': 'admin.page' };

export async function importPlugin(m: Manifest): Promise<Contribution[]> {
  const url = new URL(buildUrl(`/plugins/ui/${encodeURIComponent(m.id)}/assets/${m.entry.split('/').map(encodeURIComponent).join('/')}`), window.location.origin);
  if (url.origin !== window.location.origin) throw new Error(`plugin ${m.id}: not same-origin`);
  const mod = (await import(/* @vite-ignore */ url.href)) as { default?: { contributions?: Contribution[] }; mount?: Contribution['render'] };
  const declared = (m.contributes ?? []).flatMap((x, i): Contribution[] => {
    const point = (SLOT_ALIAS[x.slot] ?? x.slot) as Point;
    if (!mod.mount || !POINTS.includes(point)) return [];
    const en = x.title ?? m.name ?? m.id;
    return [{ point, id: `slot${i}`, label: { en, hi: x.titleHi ?? en }, render: mod.mount }];
  });
  return [...declared, ...(mod.default?.contributions ?? []).filter((x) => POINTS.includes(x.point))];
}

export function loadPlugins(): Promise<Loaded[]> {
  loading ??= http
    .get<Manifest[]>('/plugins/ui')
    .catch(() => [] as Manifest[])
    .then(async (list) => {
      const out: Loaded[] = [];
      for (const m of list ?? []) {
        try {
          for (const contribution of await importPlugin(m)) out.push({ pluginId: m.id, contribution });
        } catch (e) {
          console.error(e);
        }
      }
      return out;
    });
  return loading;
}

export function usePlugins(point: Point): Loaded[] {
  const [all, setAll] = useState<Loaded[]>([]);
  useEffect(() => {
    let live = true;
    void loadPlugins().then((x) => live && setAll(x));
    return () => {
      live = false;
    };
  }, []);
  return useMemo(() => all.filter((x) => x.contribution.point === point), [all, point]);
}

export function PluginMount({ item, ctx }: { item: Loaded; ctx: Omit<PluginContext, 'api' | 'language'> }) {
  const ref = useRef<HTMLDivElement>(null);
  const { i18n, t } = useTranslation();
  const [failed, setFailed] = useState(false);
  const key = JSON.stringify(ctx);
  useEffect(() => {
    if (!ref.current || !item.contribution.render) return;
    const el = ref.current;
    try {
      const clean = item.contribution.render(el, { ...ctx, language: i18n.language, api: http });
      return () => {
        if (typeof clean === 'function') clean();
        el.replaceChildren();
      };
    } catch (e) {
      console.error(e);
      setFailed(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item, key, i18n.language]);
  return failed ? <Notice tone="bad">{t('plugin.failed', { id: item.pluginId })}</Notice> : <div ref={ref} className="plugin-mount" />;
}

/** Renders every plugin contribution of a point as labelled sections. */
export function PluginSlot({ point, ctx = {} }: { point: Point; ctx?: Omit<PluginContext, 'api' | 'language'> }) {
  const { i18n } = useTranslation();
  const items = usePlugins(point);
  const label = (c: Contribution) => (i18n.language === 'hi' ? c.label.hi : c.label.en);
  return (
    <>
      {items.map((x) => (
        <section key={`${x.pluginId}/${x.contribution.id}`} className="card">
          <h2>{label(x.contribution)}</h2>
          <PluginMount item={x} ctx={ctx} />
        </section>
      ))}
    </>
  );
}

export function PluginPage() {
  const { pluginId, id } = useParams();
  const { i18n, t } = useTranslation();
  const pages = usePlugins('app.page');
  const admin = usePlugins('admin.page');
  const item = [...pages, ...admin].find((x) => x.pluginId === pluginId && x.contribution.id === id);
  if (!item) return <PageHeader title={t('error.notAvailable')} />;
  return (
    <>
      <PageHeader title={i18n.language === 'hi' ? item.contribution.label.hi : item.contribution.label.en} />
      <PluginMount item={item} ctx={{}} />
    </>
  );
}
