// SPDX-License-Identifier: Apache-2.0
/**
 * M-101: who else has a model file open, over GET /models/by-file/presence/stream?file= (operationId streamModelPresence,
 * package.read; sangam-rest/api/routes/modeller.py). Server-sent events, event name 'presence', data = ModelPresence
 * {fileId, viewers: ModelViewer[]}: one event on connect and one on every join, leave or lease change. The caller is
 * present while the stream is open; closing it leaves. The stream is the M-040 fetch client (api/sse.ts), so it carries
 * the same bearer token and reconnects with backoff.
 *
 * Presence is an optional convenience (owner decision 137): when the stream is refused (400/401/403/404), not
 * implemented (501, final via fatalStatuses), unreachable, or between reconnects, nothing is shown and no error banner
 * appears. A stale list is never shown: it is cleared as soon as the stream stops being live.
 */
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/endpoints';
import { openEventStream } from '../api/sse';
import type { ModelPresence, ModelViewer } from '../api/types';
import { fmtDate } from '../i18n';

/** The event name the server sends. */
export const PRESENCE_EVENT = 'presence';
/** Not implemented is final for presence: retrying would only load a server that has no presence stream. */
export const PRESENCE_FATAL_STATUSES = [501] as const;

/** Reads one 'presence' event's data; anything that is not a ModelPresence is dropped (undefined). */
export function parsePresence(data: string): ModelPresence | undefined {
  let v: unknown;
  try {
    v = JSON.parse(data);
  } catch {
    return undefined;
  }
  if (!v || typeof v !== 'object') return undefined;
  const o = v as { fileId?: unknown; viewers?: unknown };
  if (typeof o.fileId !== 'string' || !Array.isArray(o.viewers)) return undefined;
  const viewers: ModelViewer[] = [];
  for (const x of o.viewers as unknown[]) {
    if (!x || typeof x !== 'object') continue;
    const w = x as Record<string, unknown>;
    if (typeof w.userId !== 'string' || !w.userId || typeof w.editing !== 'boolean' || typeof w.since !== 'string') continue;
    viewers.push({ userId: w.userId, displayName: typeof w.displayName === 'string' && w.displayName ? w.displayName : undefined, editing: w.editing, since: w.since });
  }
  return { fileId: o.fileId, viewers };
}

/** Live presence of one model file; undefined while not live or when the server has no presence stream. */
export function useModelPresence(file: string | undefined): ModelPresence | undefined {
  const [presence, setPresence] = useState<ModelPresence>();
  useEffect(() => {
    setPresence(undefined);
    if (!file) return;
    return openEventStream(
      api.models.presencePath(file),
      {
        onEvent: (e) => {
          if (e.event !== PRESENCE_EVENT) return;
          const p = parsePresence(e.data);
          if (p) setPresence(p);
        },
        onLive: (live) => {
          if (!live) setPresence(undefined);
        },
        onFatal: () => setPresence(undefined),
      },
      { fatalStatuses: PRESENCE_FATAL_STATUSES },
    );
  }, [file]);
  return presence;
}

/** The other people in a presence list (the signed-in user is left out), editors first. */
export function otherViewers(p: ModelPresence | undefined, selfId: string | undefined): ModelViewer[] {
  if (!p) return [];
  return p.viewers.filter((v) => v.userId !== selfId).sort((a, b) => Number(b.editing) - Number(a.editing));
}

/** Presentational: who else has the file open. Renders nothing when nobody else does. */
export function PresenceList({ presence, selfId }: { presence?: ModelPresence; selfId?: string }) {
  const { t } = useTranslation();
  const others = otherViewers(presence, selfId);
  if (others.length === 0) return null;
  return (
    <div className="presence small" role="status" aria-live="polite">
      <span>{t('mod.modeller2.presenceTitle')}</span>{' '}
      <ul className="toolbar inline"aria-label={t('mod.modeller2.presenceTitle')}>
        {others.map((v) => (
          <li key={v.userId}>
            {v.displayName ?? v.userId}
            {v.editing && <> <span className="badge badge-warn">{t('mod.modeller2.presenceEditing')}</span></>}{' '}
            <span className="muted">
              {t('mod.modeller2.presenceSince')} <time dateTime={v.since}>{fmtDate(v.since)}</time>
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** M-101 presence for one model file (the package id, per the contract's {file} parameter). */
export function ModelPresenceBar({ file, selfId }: { file: string; selfId?: string }) {
  const presence = useModelPresence(file);
  return <PresenceList presence={presence} selfId={selfId} />;
}
