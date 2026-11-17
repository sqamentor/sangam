// SPDX-License-Identifier: Apache-2.0
/**
 * One status vocabulary and one colour rule for every screen (UX-SPEC 6.5, decision 397/398 problem 7). A raw server
 * state (ACTIVE, COMPLETED, RETURNED, ...) maps to one plain word and one tone; Badge and StatusChip both read it, so a
 * state has the same colour everywhere. Colour is never the only signal: the chip carries a word and an icon.
 */
export type Tone = 'ok' | 'warn' | 'bad' | 'info' | 'neutral';
export type Status = 'waiting' | 'inProgress' | 'overdue' | 'attention' | 'sentBack' | 'done' | 'cancelled' | 'draft' | 'checked' | 'notChecked';

export const STATUS: Record<Status, { tone: Tone; icon: string }> = {
  waiting: { tone: 'info', icon: '○' },
  inProgress: { tone: 'info', icon: '◐' },
  overdue: { tone: 'warn', icon: '⏰' },
  attention: { tone: 'bad', icon: '!' },
  sentBack: { tone: 'warn', icon: '↩' },
  done: { tone: 'ok', icon: '✓' },
  cancelled: { tone: 'neutral', icon: '✕' },
  draft: { tone: 'neutral', icon: '✎' },
  checked: { tone: 'ok', icon: '✓' },
  notChecked: { tone: 'neutral', icon: '–' },
};

/**
 * Ordered: the first matching rule wins (CANCEL before the failure words, RETURNED before the waiting words). A rule
 * matches a word of the state that starts with one of its stems, so UNPAID is not PAID and INACTIVE is not ACTIVE.
 */
const RULES: [string[], Status][] = [
  [['DRAFT'], 'draft'],
  [['CANCEL', 'WITHDRAW', 'INACTIVE', 'DISABLED'], 'cancelled'],
  [['RETURNED'], 'sentBack'],
  [['BREACH', 'ESCALAT', 'OVERDUE'], 'overdue'],
  [['FAIL', 'INCIDENT', 'REJECT', 'DENY', 'DENIED', 'DOWN', 'INFECTED', 'UNKNOWN', 'ERROR', 'STUCK'], 'attention'],
  [['COMPLETE', 'APPROVED', 'DONE', 'PAID', 'DELIVERED', 'SUCCEEDED', 'DEPLOYED', 'CLOSED'], 'done'],
  [['IN', 'RUNNING', 'ACTIVE', 'CLAIMED', 'ASSIGNED'], 'inProgress'],
  [['PENDING', 'AWAIT', 'WAITING', 'QUEUED', 'CREATED', 'OPEN', 'NEW'], 'waiting'],
];
const words = (raw: string) => raw.toUpperCase().split(/[^A-Z]+/).filter(Boolean);
const hits = (w: string[], stems: string[]) => w.some((x) => stems.some((s) => (s === 'IN' ? x === 'IN' : x.startsWith(s))));

/** The vocabulary word of a raw server state, or null when the state is not a progress state (e.g. UP, VALID). */
export function statusOf(raw: string): Status | null {
  const w = words(raw);
  if (w[0] === 'IN' && w[1] === 'REVIEW') return null;
  if (w.join('_').includes('SENT_BACK')) return 'sentBack';
  for (const [stems, s] of RULES) if (hits(w, stems)) return s;
  return null;
}

/** States outside the vocabulary keep a tone of their own (health, verdicts, review states). */
const OTHER: [string[], Tone][] = [
  [['WARN', 'REVIEW', 'PAUSED', 'DEGRADED', 'SUSPEND', 'PARKED', 'CLARIFICATION'], 'warn'],
  [['OK', 'UP', 'CLEAN', 'ALLOW', 'GRANT', 'VALID', 'PASS', 'HEALTHY'], 'ok'],
];

/** The one colour of a raw state on every screen. */
export function toneOf(raw: string): Tone {
  const s = statusOf(raw);
  if (s) return STATUS[s].tone;
  const w = words(raw);
  for (const [stems, tone] of OTHER) if (hits(w, stems)) return tone;
  return 'neutral';
}
