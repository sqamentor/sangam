// SPDX-License-Identifier: Apache-2.0
/**
 * M-021 / C3-13: the test-mode clock, a tab of Engine administration (/admin/engine, platform administrators, the
 * contract's group sangam-admin). It reads GET /test-mode/clock and moves the engine and business clocks together with
 * POST /test-mode/clock: advance by a positive ISO-8601 duration, set to an instant, or reset to the wall clock, with an
 * optional reason the server records in TEST_CLOCK_CHANGED. Both routes answer 404 unless the server runs in test mode
 * (sangam.test-mode.enabled); the panel then says so and offers nothing. The server validates every change (400).
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/endpoints';
import { ApiError } from '../api/client';
import type { TestClockChange } from '../api/types';
import { announce, useAction, useAsync } from '../lib/hooks';
import { fmtDate, fmtNumber } from '../i18n';
import { KeyValues, Loading, Notice, ProblemAlert, Section, SelectField, TextField } from '../components/ui';

type Mode = 'advance' | 'set' | 'reset';

/** An ISO-8601 duration with at least one component (P3D, PT2H, P1DT30M); positivity is the server's check. */
const DURATION = /^P(?=\d|T\d)(\d+Y)?(\d+M)?(\d+W)?(\d+D)?(T(?=\d)(\d+H)?(\d+M)?(\d+(\.\d+)?S)?)?$/;

/**
 * The request body for a choice, or undefined while the input is incomplete. A datetime-local value is the reader's
 * local time; it is sent as the instant it names (UTC ISO-8601).
 */
export function clockChange(mode: Mode, advanceBy: string, setAt: string, reason: string): TestClockChange | undefined {
  const r = reason.trim() ? { reason: reason.trim() } : {};
  if (mode === 'reset') return { reset: true, ...r };
  if (mode === 'advance') {
    const d = advanceBy.trim().toUpperCase();
    return DURATION.test(d) ? { advanceBy: d, ...r } : undefined;
  }
  const at = setAt ? new Date(setAt) : undefined;
  return at && !Number.isNaN(at.getTime()) ? { set: at.toISOString(), ...r } : undefined;
}

export function TestClockPanel() {
  const { t } = useTranslation();
  const clock = useAsync(() => api.testMode.clock(), []);
  const act = useAction();
  const [mode, setMode] = useState<Mode>('advance');
  const [advanceBy, setAdvanceBy] = useState('');
  const [setAt, setSetAt] = useState('');
  const [reason, setReason] = useState('');
  const off = clock.error instanceof ApiError && clock.error.status === 404;
  const body = clockChange(mode, advanceBy, setAt, reason);

  const submit = async () => {
    if (!body) return;
    const r = await act.run((k) => api.testMode.change(body, k));
    if (r) {
      clock.setData(r);
      setAdvanceBy('');
      setSetAt('');
      setReason('');
      announce(t('mod.tclock.changed', { now: fmtDate(r.now) }));
    }
  };

  return (
    <Section title={t('mod.tclock.title')}>
      <p className="hint">{t('mod.tclock.desc')}</p>
      {off ? (
        <Notice>{t('mod.tclock.off')}</Notice>
      ) : clock.loading && !clock.data ? (
        <Loading slow={clock.slow} onRetry={clock.reload} />
      ) : (
        <>
          <ProblemAlert error={clock.error} onRetry={clock.reload} />
          {clock.data && (
            <KeyValues
              items={[
                [t('mod.tclock.now'), fmtDate(clock.data.now)],
                [t('mod.tclock.offset'), t('mod.tclock.offsetMs', { n: fmtNumber(clock.data.offsetMs) })],
              ]}
            />
          )}
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void submit();
            }}
          >
            <SelectField
              label={t('mod.tclock.action')}
              value={mode}
              onChange={(v) => setMode(v as Mode)}
              options={[
                { value: 'advance', label: t('mod.tclock.modeAdvance') },
                { value: 'set', label: t('mod.tclock.modeSet') },
                { value: 'reset', label: t('mod.tclock.modeReset') },
              ]}
            />
            {mode === 'advance' && (
              <TextField
                label={t('mod.tclock.advanceBy')}
                hint={t('mod.tclock.advanceByHint')}
                error={advanceBy.trim() && !body ? t('mod.tclock.advanceByInvalid') : undefined}
                value={advanceBy}
                onChange={setAdvanceBy}
                required
                autoComplete="off"
              />
            )}
            {mode === 'set' && <TextField label={t('mod.tclock.setAt')} type="datetime-local" step={1} value={setAt} onChange={setSetAt} required />}
            <TextField label={t('mod.tclock.reason')} value={reason} onChange={setReason} />
            <button type="submit" className="primary" disabled={!body || act.busy}>
              {t('mod.tclock.apply')}
            </button>
            <ProblemAlert error={act.error} />
          </form>
        </>
      )}
    </Section>
  );
}
