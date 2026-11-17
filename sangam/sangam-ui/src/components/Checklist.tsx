// SPDX-License-Identifier: Apache-2.0
/**
 * First-run checklists (UX-SPEC 6.4, decision 397 Q8): one short list per persona the caller holds, on the start page.
 * Each step links to its page; the caller ticks it off. Progress and "hide" are kept in this browser per user (Q8:
 * browser storage for 0.1.0); when storage is blocked the list still works for the visit and simply starts again.
 * A step whose page the caller's menu does not offer is left out, so the list never leads to a refusal.
 */
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import type { Me, Role } from '../api/types';
import { visibleNav } from '../app/nav';

export interface Step {
  id: string;
  to: string;
}
export interface Persona {
  id: string;
  roles: Role[];
  /** Endpoint permissions that also make the persona (a supervisor holds team.read, decision 401). */
  perms?: string[];
  steps: Step[];
}

const s = (id: string, to: string): Step => ({ id, to });

export const PERSONAS: Persona[] = [
  { id: 'citizen', roles: ['CITIZEN'], steps: [s('find', '/portal'), s('apply', '/portal'), s('track', '/portal/applications')] },
  {
    id: 'taskUser',
    roles: ['TASK_USER'],
    steps: [s('post', '/account'), s('open', '/tasks'), s('pickUp', '/tasks'), s('complete', '/tasks'), s('away', '/my/delegations')],
  },
  { id: 'supervisor', roles: [], perms: ['team.read'], steps: [s('team', '/team'), s('list', '/tasks/filters'), s('reassign', '/team')] },
  {
    id: 'designer',
    roles: ['MODELLER'],
    steps: [s('project', '/modeller'), s('flow', '/modeller'), s('form', '/modeller'), s('test', '/design/test'), s('review', '/modeller/review')],
  },
  {
    id: 'admin',
    roles: ['ADMIN', 'PLATFORM_ADMIN'],
    steps: [
      s('offices', '/admin/org'),
      s('posts', '/admin/seats'),
      s('people', '/admin/users'),
      s('postings', '/admin/assignments'),
      s('packs', '/admin/packs'),
      s('timeLimits', '/admin/sla'),
      s('templates', '/admin/templates'),
      s('test', '/start'),
    ],
  },
  {
    id: 'operator',
    roles: ['OPERATOR', 'PLATFORM_OPERATOR'],
    steps: [s('health', '/ops'), s('stuck', '/ops/incidents'), s('retry', '/ops/incidents'), s('delivery', '/ops/outbox'), s('versions', '/ops/deployments')],
  },
  { id: 'auditor', roles: ['AUDITOR'], steps: [s('audit', '/audit'), s('timeline', '/audit'), s('tamper', '/audit'), s('export', '/audit/exports')] },
];

/** Pages every signed-in caller may open without a menu entry. */
const ALWAYS = new Set(['/account']);

/** The checklists for this caller, each with only the steps whose page the caller can open. */
export function checklistsFor(me: Pick<Me, 'roles' | 'permissions'> | null | undefined): Persona[] {
  if (!me) return [];
  const open = new Set([...ALWAYS, ...visibleNav(me).flatMap((a) => a.sections.flatMap((x) => x.items.map((i) => i.to)))]);
  const held = me.permissions ?? [];
  return PERSONAS.filter((p) => p.roles.some((r) => me.roles.includes(r)) || (p.perms ?? []).some((x) => held.includes(x)))
    .map((p) => ({ ...p, steps: p.steps.filter((st) => open.has(st.to)) }))
    .filter((p) => p.steps.length > 0);
}

interface Saved {
  done: string[];
  hidden: boolean;
}
const key = (user: string, persona: string) => `sangam.firstRun.${user}.${persona}`;

export function loadProgress(user: string, persona: string): Saved {
  try {
    const raw = window.localStorage.getItem(key(user, persona));
    const v = raw ? (JSON.parse(raw) as Partial<Saved>) : {};
    return { done: Array.isArray(v.done) ? v.done.filter((x): x is string => typeof x === 'string') : [], hidden: v.hidden === true };
  } catch {
    return { done: [], hidden: false };
  }
}
function saveProgress(user: string, persona: string, v: Saved): void {
  try {
    window.localStorage.setItem(key(user, persona), JSON.stringify(v));
  } catch {
    /* storage blocked: the list works for this visit only */
  }
}

export function Checklist({ user, persona }: { user: string; persona: Persona }) {
  const { t } = useTranslation();
  const [state, setState] = useState<Saved>(() => loadProgress(user, persona.id));
  const update = (v: Saved) => {
    setState(v);
    saveProgress(user, persona.id, v);
  };
  if (state.hidden) return null;
  const done = persona.steps.filter((st) => state.done.includes(st.id)).length;
  const head = `checklist-${persona.id}`;
  return (
    <section className="card checklist" aria-labelledby={head}>
      <div className="card-head">
        <h2 id={head}>{t(`checklist.${persona.id}.title`)}</h2>
        <button type="button" className="link" onClick={() => update({ ...state, hidden: true })}>
          {t('checklist.hide')}
        </button>
      </div>
      <p className="muted">{t('checklist.progress', { done, total: persona.steps.length })}</p>
      <ol>
        {persona.steps.map((st) => {
          const id = `${head}-${st.id}`;
          const checked = state.done.includes(st.id);
          return (
            <li key={st.id}>
              <input
                type="checkbox"
                id={id}
                checked={checked}
                onChange={() => update({ ...state, done: checked ? state.done.filter((x) => x !== st.id) : [...state.done, st.id] })}
              />{' '}
              <label htmlFor={id}>{t(`checklist.${persona.id}.${st.id}`)}</label>{' '}
              <Link to={st.to} aria-label={t('checklist.goTo', { step: t(`checklist.${persona.id}.${st.id}`) })}>
                {t('checklist.go')}
              </Link>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
