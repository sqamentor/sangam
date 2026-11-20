// SPDX-License-Identifier: Apache-2.0
/**
 * Seat context the server no longer accepts (S02, K7; register E-24).
 *
 * The chosen seat travels on every call (AuthProvider). The server accepts it only while the person holds the seat,
 * and answers 403 SEAT_NOT_HELD otherwise: a posting or a charge that ended during the session, or a seat kept in
 * sessionStorage from an earlier day. The client reports that answer here (client.ts, setSeatNotHeldHandler). This
 * component then reads GET /me again (it lists the seats held now and never names a seat itself, client.ts), drops
 * the refused seat, so that AuthProvider takes the first seat /me offers now, and tells the user in plain words which
 * seat was dropped. Nobody is signed out; the user repeats the refused step under the seat now shown.
 */
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { setSeatNotHeldHandler } from '../api/client';
import { Notice } from '../components/ui';
import { useAuth } from './AuthProvider';

export function SeatContextGuard({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const { me, error, setSeat, reload } = useAuth();
  /** Name of the seat that was dropped, until the user closes the message. */
  const [lost, setLost] = useState<string | null>(null);
  // the handler is registered once and must see the latest auth state
  const live = useRef({ me, setSeat, reload, busy: false, meAtLoss: me });
  live.current.me = me;
  live.current.setSeat = setSeat;
  live.current.reload = reload;

  useEffect(
    () =>
      setSeatNotHeldHandler((named) => {
        const cur = live.current;
        // calls already in flight under the same seat are refused too: one recovery for all of them
        if (cur.busy) return;
        cur.busy = true;
        cur.meAtLoss = cur.me;
        // the seat the user had chosen: the acted-for seat when one was sent, else the seat
        const id = named.actingFor ?? named.seat ?? '';
        setLost(cur.me?.seats.find((s) => s.seatId === id)?.seatName ?? id);
        cur.reload();
      }),
    [],
  );

  // GET /me was read again: drop the refused seat now, so the default is taken from the seats held now and not from
  // the list that still had the refused one. If the read failed, nothing is dropped and the next refusal tries again.
  useEffect(() => {
    const cur = live.current;
    if (!cur.busy) return;
    if (me !== cur.meAtLoss) {
      cur.busy = false;
      cur.setSeat(null);
    } else if (error) {
      cur.busy = false;
    }
  }, [me, error]);

  return (
    <>
      {lost !== null && (
        <Notice tone="warn">
          {t('seat.notHeld', { seat: lost })}{' '}
          <button type="button" className="link" onClick={() => setLost(null)}>
            {t('common.close')}
          </button>
        </Notice>
      )}
      {children}
    </>
  );
}
