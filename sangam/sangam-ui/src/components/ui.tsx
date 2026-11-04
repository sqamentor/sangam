// SPDX-License-Identifier: Apache-2.0
/**
 * Shared accessible building blocks (GIGW / WCAG 2.1 AA): labelled fields, real <table>s with captions, native
 * <dialog> modals, roving-tabindex tabs, aria-busy loading states and plain-language problem messages (X.4.7.2).
 */
import {
  useEffect,
  useId,
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
  type ReactNode,
  type SelectHTMLAttributes,
  type InputHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, type Page } from '../api/client';
import type { ConsequencePreview } from '../api/types';
import { fmtNumber } from '../i18n';

// ------------------------------------------------------------------ layout bits

export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <header className="page-header">
      <div>
        <h1 tabIndex={-1} id="page-title">
          {title}
        </h1>
        {description && <p className="muted">{description}</p>}
      </div>
      {actions && <div className="toolbar">{actions}</div>}
    </header>
  );
}

export function Section({ title, children, actions, id }: { title: string; children: ReactNode; actions?: ReactNode; id?: string }) {
  const hid = useId();
  return (
    <section className="card" aria-labelledby={id ?? hid}>
      <div className="card-head">
        <h2 id={id ?? hid}>{title}</h2>
        {actions && <div className="toolbar">{actions}</div>}
      </div>
      {children}
    </section>
  );
}

export function KeyValues({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="kv">
      {items.map(([k, v]) => (
        <div key={k}>
          <dt>{k}</dt>
          <dd>{v ?? '—'}</dd>
        </div>
      ))}
    </dl>
  );
}

export function Badge({ value, tone }: { value: string; tone?: 'ok' | 'warn' | 'bad' | 'info' | 'neutral' }) {
  const { t } = useTranslation();
  const auto =
    tone ??
    (/BREACH|FAIL|INCIDENT|REJECT|DENY|DOWN|INFECTED|UNKNOWN|ERROR|CANCEL/.test(value)
      ? 'bad'
      : /WARN|PENDING|REVIEW|PAUSED|AWAIT|DEGRADED|SUSPEND|PARKED|CLARIFICATION|RETURNED/.test(value)
        ? 'warn'
        : /OK|ACTIVE|COMPLETED|APPROVED|UP|CLEAN|DELIVERED|ALLOW|SUCCEEDED|DEPLOYED|PAID|VALID|PASS/.test(value)
          ? 'ok'
          : 'neutral');
  return <span className={`badge badge-${auto}`}>{t(`enum.${value}`, { defaultValue: value })}</span>;
}

// ------------------------------------------------------------------ states

export function Loading({ slow, onRetry }: { slow?: boolean; onRetry?: () => void }) {
  const { t } = useTranslation();
  return (
    <div className="loading" aria-busy="true" aria-live="polite">
      <div className="skeleton" />
      <div className="skeleton short" />
      {slow && (
        <p>
          {t('common.stillLoading')}{' '}
          {onRetry && (
            <button type="button" className="link" onClick={onRetry}>
              {t('common.retry')}
            </button>
          )}
        </p>
      )}
    </div>
  );
}

export function Empty({ message, action }: { message: string; action?: ReactNode }) {
  return (
    <div className="empty" role="status">
      <p>{message}</p>
      {action}
    </div>
  );
}

export function ProblemAlert({ error, onRetry }: { error?: Error | null; onRetry?: () => void }) {
  const { t } = useTranslation();
  if (!error) return null;
  let title = t('error.generic');
  let detail: string | undefined;
  let ref: string | undefined;
  let fieldErrors: { field?: string; message: string }[] = [];
  if (error instanceof ApiError) {
    const p = error.problem;
    ref = p.traceId;
    fieldErrors = p.errors ?? [];
    if (error.isNotAvailable) title = t('error.notAvailable');
    else if (p.status === 0) title = t('error.network');
    else if (p.code === 'stale-lease' || p.code === 'FORM_VERSION_STALE') title = t('error.stale');
    else if (p.code === 'APPROVAL_INVALIDATED') title = t('error.approvalInvalidated');
    else if (p.code === 'CONFLICT_RETRY_EXHAUSTED') title = t('error.retryExhausted');
    else if (p.status === 409) title = t('error.conflict');
    else if (p.status === 503) title = t('error.backpressure');
    else if (p.status === 422 || p.status === 400) title = t('error.validation');
    // Never show stack traces or SQL to users (R28): only title/detail from problem+json.
    detail = p.detail ?? (p.title !== title ? p.title : undefined);
  } else {
    detail = error.message;
  }
  return (
    <div className="alert alert-bad" role="alert">
      <strong>{title}</strong>
      {detail && <p>{detail}</p>}
      {fieldErrors.length > 0 && (
        <ul>
          {fieldErrors.map((f, i) => (
            <li key={i}>
              {f.field ? <a href={`#field-${f.field}`}>{f.field}</a> : null} {f.message}
            </li>
          ))}
        </ul>
      )}
      {ref && (
        <p className="muted small">
          {t('error.reference')}: <code>{ref}</code>
        </p>
      )}
      {onRetry && (
        <button type="button" onClick={onRetry}>
          {t('common.retry')}
        </button>
      )}
    </div>
  );
}

export function Notice({ tone = 'info', children }: { tone?: 'info' | 'warn' | 'ok' | 'bad'; children: ReactNode }) {
  return (
    <div className={`alert alert-${tone}`} role={tone === 'bad' ? 'alert' : 'status'}>
      {children}
    </div>
  );
}

// ------------------------------------------------------------------ fields

interface FieldWrapProps {
  label: string;
  hint?: string;
  error?: string;
  required?: boolean;
  id?: string;
}

function FieldWrap({ label, hint, error, required, id, children }: FieldWrapProps & { children: (id: string, describedBy?: string) => ReactNode }) {
  const gen = useId();
  const fid = id ?? gen;
  const { t } = useTranslation();
  const describedBy = [hint ? `${fid}-hint` : '', error ? `${fid}-err` : ''].filter(Boolean).join(' ') || undefined;
  return (
    <div className={`field${error ? ' has-error' : ''}`}>
      <label htmlFor={fid}>
        {label}
        {required && (
          <span className="req" aria-hidden="true">
            {' '}
            *
          </span>
        )}
        {required && <span className="sr-only"> ({t('common.required')})</span>}
      </label>
      {hint && (
        <p className="hint" id={`${fid}-hint`}>
          {hint}
        </p>
      )}
      {children(fid, describedBy)}
      {error && (
        <p className="field-error" id={`${fid}-err`}>
          {error}
        </p>
      )}
    </div>
  );
}

export function TextField(
  props: FieldWrapProps & Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'value'> & { value: string; onChange: (v: string) => void },
) {
  const { label, hint, error, required, id, value, onChange, ...rest } = props;
  return (
    <FieldWrap label={label} hint={hint} error={error} required={required} id={id}>
      {(fid, d) => (
        <input
          id={fid}
          aria-describedby={d}
          aria-invalid={!!error}
          required={required}
          value={value}
          onChange={(e: ChangeEvent<HTMLInputElement>) => onChange(e.target.value)}
          {...rest}
        />
      )}
    </FieldWrap>
  );
}

export function TextArea(
  props: FieldWrapProps & Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'onChange' | 'value'> & { value: string; onChange: (v: string) => void },
) {
  const { label, hint, error, required, id, value, onChange, ...rest } = props;
  return (
    <FieldWrap label={label} hint={hint} error={error} required={required} id={id}>
      {(fid, d) => (
        <textarea
          id={fid}
          aria-describedby={d}
          aria-invalid={!!error}
          required={required}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          rows={4}
          {...rest}
        />
      )}
    </FieldWrap>
  );
}

export function SelectField(
  props: FieldWrapProps &
    Omit<SelectHTMLAttributes<HTMLSelectElement>, 'onChange' | 'value'> & {
      value: string;
      onChange: (v: string) => void;
      options: { value: string; label: string }[];
      placeholder?: string;
    },
) {
  const { label, hint, error, required, id, value, onChange, options, placeholder, ...rest } = props;
  return (
    <FieldWrap label={label} hint={hint} error={error} required={required} id={id}>
      {(fid, d) => (
        <select id={fid} aria-describedby={d} aria-invalid={!!error} required={required} value={value} onChange={(e) => onChange(e.target.value)} {...rest}>
          {placeholder !== undefined && <option value="">{placeholder}</option>}
          {options.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      )}
    </FieldWrap>
  );
}

export function Checkbox({ label, checked, onChange, hint }: { label: string; checked: boolean; onChange: (v: boolean) => void; hint?: string }) {
  const id = useId();
  return (
    <div className="check">
      <input id={id} type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} aria-describedby={hint ? `${id}-h` : undefined} />
      <label htmlFor={id}>{label}</label>
      {hint && (
        <span className="hint" id={`${id}-h`}>
          {hint}
        </span>
      )}
    </div>
  );
}

export function SearchBox({ value, onChange, label }: { value: string; onChange: (v: string) => void; label: string }) {
  const id = useId();
  const [v, setV] = useState(value);
  useEffect(() => setV(value), [value]);
  return (
    <form
      role="search"
      className="search"
      onSubmit={(e) => {
        e.preventDefault();
        onChange(v);
      }}
    >
      <label htmlFor={id} className="sr-only">
        {label}
      </label>
      <input id={id} type="search" value={v} placeholder={label} onChange={(e) => setV(e.target.value)} />
      <button type="submit">{label}</button>
    </form>
  );
}

// ------------------------------------------------------------------ table + pagination

export interface Column<T> {
  key: string;
  header: string;
  render?: (row: T) => ReactNode;
  className?: string;
  sortable?: boolean;
}

export function DataTable<T>({
  caption,
  columns,
  rows,
  rowKey,
  selectable,
  selected,
  onSelect,
  onSort,
  sort,
  captionHidden,
}: {
  caption: string;
  columns: Column<T>[];
  rows: T[];
  rowKey: (r: T) => string;
  selectable?: boolean;
  selected?: Set<string>;
  onSelect?: (s: Set<string>) => void;
  onSort?: (key: string) => void;
  sort?: string;
  captionHidden?: boolean;
}) {
  const { t } = useTranslation();
  const allIds = rows.map(rowKey);
  const allSelected = selectable && allIds.length > 0 && allIds.every((id) => selected?.has(id));
  const toggle = (id: string) => {
    const n = new Set(selected);
    if (n.has(id)) n.delete(id);
    else n.add(id);
    onSelect?.(n);
  };
  return (
    <div className="table-wrap" role="region" aria-label={caption} tabIndex={0}>
      <table>
        <caption className={captionHidden ? 'sr-only' : undefined}>{caption}</caption>
        <thead>
          <tr>
            {selectable && (
              <th scope="col">
                <input
                  type="checkbox"
                  aria-label={t('common.selectAll')}
                  checked={!!allSelected}
                  onChange={() => onSelect?.(allSelected ? new Set() : new Set(allIds))}
                />
              </th>
            )}
            {columns.map((c) => {
              const dir = sort === c.key ? 'ascending' : sort === `-${c.key}` ? 'descending' : undefined;
              return (
                <th key={c.key} scope="col" aria-sort={dir ?? (c.sortable ? 'none' : undefined)} className={c.className}>
                  {c.sortable && onSort ? (
                    <button type="button" className="link" onClick={() => onSort(sort === c.key ? `-${c.key}` : c.key)}>
                      {c.header}
                      <span aria-hidden="true">{dir === 'ascending' ? ' ▲' : dir === 'descending' ? ' ▼' : ''}</span>
                    </button>
                  ) : (
                    c.header
                  )}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const id = rowKey(r);
            return (
              <tr key={id} aria-selected={selectable ? selected?.has(id) : undefined}>
                {selectable && (
                  <td>
                    <input type="checkbox" aria-label={t('common.selectRow', { id })} checked={selected?.has(id) ?? false} onChange={() => toggle(id)} />
                  </td>
                )}
                {columns.map((c) => (
                  <td key={c.key} className={c.className}>
                    {c.render ? c.render(r) : String((r as Record<string, unknown>)[c.key] ?? '—')}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export function Pagination<T>({ data, page, size, setPage, setSize }: { data?: Page<T>; page: number; size: number; setPage: (p: number) => void; setSize: (s: number) => void }) {
  const { t } = useTranslation();
  const id = useId();
  if (!data) return null;
  const total = data.total;
  const last = total !== null && !data.capped ? Math.max(0, Math.ceil(total / size) - 1) : null;
  const hasNext = last !== null ? page < last : data.items.length === size;
  return (
    <nav className="pagination" aria-label={t('common.pagination')}>
      <span aria-live="polite">
        {total === null
          ? t('common.pageN', { n: page + 1 })
          : data.capped
            ? t('common.moreThan', { n: fmtNumber(total) })
            : t('common.totalN', { n: fmtNumber(total) })}
      </span>
      <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>
        {t('common.previous')}
      </button>
      <button type="button" disabled={!hasNext} onClick={() => setPage(page + 1)}>
        {t('common.next')}
      </button>
      <label htmlFor={id}>{t('common.pageSize')}</label>
      <select id={id} value={size} onChange={(e) => setSize(Number(e.target.value))}>
        {[10, 25, 50, 100].map((n) => (
          <option key={n} value={n}>
            {n}
          </option>
        ))}
      </select>
    </nav>
  );
}

// ------------------------------------------------------------------ tabs

export function Tabs({ tabs, active, onChange, label }: { tabs: { id: string; label: string; badge?: ReactNode }[]; active: string; onChange: (id: string) => void; label: string }) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const onKey = (e: KeyboardEvent, i: number) => {
    let n = -1;
    if (e.key === 'ArrowRight') n = (i + 1) % tabs.length;
    else if (e.key === 'ArrowLeft') n = (i - 1 + tabs.length) % tabs.length;
    else if (e.key === 'Home') n = 0;
    else if (e.key === 'End') n = tabs.length - 1;
    if (n >= 0) {
      e.preventDefault();
      onChange(tabs[n].id);
      refs.current[n]?.focus();
    }
  };
  return (
    <div role="tablist" aria-label={label} className="tabs">
      {tabs.map((tb, i) => (
        <button
          key={tb.id}
          ref={(el) => {
            refs.current[i] = el;
          }}
          role="tab"
          type="button"
          id={`tab-${tb.id}`}
          aria-selected={active === tb.id}
          aria-controls={`panel-${tb.id}`}
          tabIndex={active === tb.id ? 0 : -1}
          onClick={() => onChange(tb.id)}
          onKeyDown={(e) => onKey(e, i)}
        >
          {tb.label}
          {tb.badge !== undefined && <span className="count">{tb.badge}</span>}
        </button>
      ))}
    </div>
  );
}

export function TabPanel({ id, active, children }: { id: string; active: string; children: ReactNode }) {
  if (id !== active) return null;
  return (
    <div role="tabpanel" id={`panel-${id}`} aria-labelledby={`tab-${id}`} tabIndex={0}>
      {children}
    </div>
  );
}

// ------------------------------------------------------------------ dialogs

export function Dialog({ open, title, onClose, children, footer, wide }: { open: boolean; title: string; onClose: () => void; children: ReactNode; footer?: ReactNode; wide?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  const hid = useId();
  const { t } = useTranslation();
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);
  return (
    <dialog ref={ref} aria-labelledby={hid} className={wide ? 'wide' : undefined} onClose={onClose} onCancel={onClose}>
      <div className="dialog-head">
        <h2 id={hid}>{title}</h2>
        <button type="button" className="icon" aria-label={t('common.close')} onClick={onClose}>
          ×
        </button>
      </div>
      <div className="dialog-body">{open && children}</div>
      {footer && <div className="dialog-foot toolbar">{footer}</div>}
    </dialog>
  );
}

export function PreviewList({ preview }: { preview?: ConsequencePreview }) {
  const { t } = useTranslation();
  if (!preview) return null;
  return (
    <div className="preview" aria-live="polite">
      <h3>{t('ops.consequences')}</h3>
      <ul>
        {preview.summary.map((s, i) => (
          <li key={i}>{s}</li>
        ))}
        {preview.affectedCount !== undefined && <li>{t('ops.affected', { n: fmtNumber(preview.affectedCount) })}</li>}
        {preview.openTasks?.map((o) => (
          <li key={o.state}>
            {t('ops.openTasksState', { state: o.state, n: o.count })}
          </li>
        ))}
        {preview.openOperationIds && preview.openOperationIds.length > 0 && <li>{t('ops.openOperations', { n: preview.openOperationIds.length })}</li>}
        {preview.tombstones !== undefined && <li>{t('ops.tombstones', { n: preview.tombstones })}</li>}
      </ul>
      {preview.requiresFourEyes && <Notice tone="warn">{t('ops.fourEyes')}</Notice>}
      {preview.limitExceeded && <Notice tone="bad">{t('ops.limitExceeded')}</Notice>}
      {preview.blocked && <Notice tone="bad">{preview.blockedReason ?? t('ops.blocked')}</Notice>}
    </div>
  );
}

/**
 * Every state-changing operator action: mandatory reason, server-computed consequence preview, audited (R63, O03, O08).
 */
export function ReasonDialog({
  open,
  title,
  onClose,
  onConfirm,
  preview,
  confirmLabel,
  busy,
  error,
  danger,
  children,
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  onConfirm: (reason: string) => void;
  preview?: ConsequencePreview;
  confirmLabel?: string;
  busy?: boolean;
  error?: Error;
  danger?: boolean;
  children?: ReactNode;
}) {
  const { t } = useTranslation();
  const [reason, setReason] = useState('');
  useEffect(() => {
    if (open) setReason('');
  }, [open]);
  const blocked = preview?.blocked || preview?.limitExceeded;
  return (
    <Dialog
      open={open}
      title={title}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className={danger ? 'danger' : 'primary'}
            disabled={busy || reason.trim().length < 5 || !!blocked}
            onClick={() => onConfirm(reason.trim())}
          >
            {busy ? t('common.working') : (confirmLabel ?? t('common.confirm'))}
          </button>
        </>
      }
    >
      {children}
      <PreviewList preview={preview} />
      <TextArea label={t('common.reason')} hint={t('common.reasonHint')} required value={reason} onChange={setReason} />
      <ProblemAlert error={error} />
    </Dialog>
  );
}

export function CopyText({ text }: { text: string }) {
  const { t } = useTranslation();
  const [done, setDone] = useState(false);
  return (
    <span className="copy">
      <code>{text}</code>
      <button
        type="button"
        className="link small"
        onClick={() => {
          void navigator.clipboard?.writeText(text).then(() => setDone(true));
        }}
      >
        {done ? t('common.copied') : t('common.copy')}
      </button>
    </span>
  );
}

export function JsonView({ value }: { value: unknown }) {
  return <pre className="json">{JSON.stringify(value, null, 2)}</pre>;
}
