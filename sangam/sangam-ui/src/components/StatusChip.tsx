// SPDX-License-Identifier: Apache-2.0
/**
 * The one status chip (UX-SPEC 6.5): a plain word, an icon and a colour from status.ts. A verification result is
 * "Checked (n items)" only when n > 0; a check over nothing is "Not checked", never "verified".
 */
import { useTranslation } from 'react-i18next';
import { STATUS, statusOf, type Status } from './status';

type Props = { value: string; checked?: never } | { checked: number; value?: never } | { status: Status; value?: never; checked?: never };

export function StatusChip(props: Props) {
  const { t } = useTranslation();
  let status: Status | null;
  let raw: string | undefined;
  if ('checked' in props && props.checked !== undefined) status = props.checked > 0 ? 'checked' : 'notChecked';
  else if ('status' in props && props.status) status = props.status;
  else {
    raw = props.value;
    status = statusOf(raw ?? '');
  }
  if (!status) return <span className="badge badge-neutral">{t(`enum.${raw}`, { defaultValue: raw })}</span>;
  const { tone, icon } = STATUS[status];
  const n = 'checked' in props ? props.checked : undefined;
  return (
    <span className={`badge badge-${tone} status-chip`} data-status={status}>
      <span aria-hidden="true" className="status-icon">
        {icon}
      </span>{' '}
      {t(`status.${status}`, { count: n ?? 0 })}
    </span>
  );
}
