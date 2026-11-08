// SPDX-License-Identifier: Apache-2.0
/**
 * Dependency-free SVG charts (R65). Each chart has a text title, an aria description and a data-table alternative,
 * and series are told apart by pattern/shape as well as colour (WCAG 1.4.1).
 */
import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { fmtNumber } from '../i18n';

export interface Series {
  name: string;
  values: number[];
  className: string;
}

export function LineChart({ title, labels, series, height = 220 }: { title: string; labels: string[]; series: Series[]; height?: number }) {
  const { t } = useTranslation();
  const id = useId();
  const W = 640;
  const H = height;
  const pad = { l: 48, r: 12, t: 12, b: 28 };
  const max = Math.max(1, ...series.flatMap((s) => s.values));
  const x = (i: number) => pad.l + (labels.length <= 1 ? 0 : (i * (W - pad.l - pad.r)) / (labels.length - 1));
  const y = (v: number) => H - pad.b - (v / max) * (H - pad.t - pad.b);
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => Math.round(max * f));
  const step = Math.max(1, Math.ceil(labels.length / 8));
  return (
    <figure className="chart">
      <figcaption id={id}>{title}</figcaption>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-labelledby={id} preserveAspectRatio="none">
        {ticks.map((v) => (
          <g key={v}>
            <line x1={pad.l} x2={W - pad.r} y1={y(v)} y2={y(v)} className="grid" />
            <text x={pad.l - 6} y={y(v) + 4} textAnchor="end" className="axis">
              {fmtNumber(v)}
            </text>
          </g>
        ))}
        {labels.map((l, i) =>
          i % step === 0 ? (
            <text key={i} x={x(i)} y={H - 8} textAnchor="middle" className="axis">
              {l}
            </text>
          ) : null,
        )}
        {series.map((s, si) => (
          <g key={s.name} className={s.className}>
            <polyline fill="none" strokeWidth={2} strokeDasharray={si === 1 ? '6 3' : si === 2 ? '2 3' : undefined} points={s.values.map((v, i) => `${x(i)},${y(v)}`).join(' ')} />
            {s.values.map((v, i) => (
              <circle key={i} cx={x(i)} cy={y(v)} r={2.5}>
                <title>{`${s.name} ${labels[i]}: ${fmtNumber(v)}`}</title>
              </circle>
            ))}
          </g>
        ))}
      </svg>
      <ul className="legend">
        {series.map((s) => (
          <li key={s.name} className={s.className}>
            <span className="swatch" aria-hidden="true" /> {s.name}
          </li>
        ))}
      </ul>
      <details>
        <summary>{t('chart.showTable')}</summary>
        <table>
          <caption className="sr-only">{title}</caption>
          <thead>
            <tr>
              <th scope="col">{t('chart.period')}</th>
              {series.map((s) => (
                <th key={s.name} scope="col">
                  {s.name}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {labels.map((l, i) => (
              <tr key={i}>
                <th scope="row">{l}</th>
                {series.map((s) => (
                  <td key={s.name}>{fmtNumber(s.values[i])}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  );
}

export function BarChart({ title, rows, unit, format = fmtNumber }: { title: string; rows: { label: string; value: number; tone?: 'ok' | 'warn' | 'bad' }[]; unit?: string; format?: (n: number) => string }) {
  const { t } = useTranslation();
  const id = useId();
  const max = Math.max(1, ...rows.map((r) => r.value));
  return (
    <figure className="chart bars">
      <figcaption id={id}>{title}</figcaption>
      <ul aria-labelledby={id} className="bar-list">
        {rows.map((r) => (
          <li key={r.label}>
            <span className="bar-label">{r.label}</span>
            <span className="bar-track" aria-hidden="true">
              <span className={`bar-fill tone-${r.tone ?? 'info'}`} style={{ width: `${(r.value / max) * 100}%` }} />
            </span>
            <span className="bar-value">
              {format(r.value)}
              {unit ? ` ${unit}` : ''}
            </span>
          </li>
        ))}
      </ul>
      {rows.length === 0 && <p className="muted">{t('chart.noData')}</p>}
    </figure>
  );
}

export function StackedBar({ parts, label }: { parts: { value: number; className: string; name: string }[]; label: string }) {
  const total = Math.max(1, parts.reduce((a, p) => a + p.value, 0));
  return (
    <div className="stacked" role="img" aria-label={`${label}: ${parts.map((p) => `${p.name} ${fmtNumber(p.value)}`).join(', ')}`}>
      {parts.map((p) => (
        <span key={p.name} className={p.className} style={{ width: `${(p.value / total) * 100}%` }} title={`${p.name}: ${fmtNumber(p.value)}`} />
      ))}
    </div>
  );
}

export function Stat({ label, value, sub, tone }: { label: string; value: string; sub?: string; tone?: 'ok' | 'warn' | 'bad' }) {
  return (
    <div className={`stat${tone ? ` tone-${tone}` : ''}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {sub && <div className="stat-sub">{sub}</div>}
    </div>
  );
}
