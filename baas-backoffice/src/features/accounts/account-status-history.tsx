import { formatDateTime, humanizeStatus } from '@/lib/format';
import type { StatusEvent } from './use-accounts';

export function AccountStatusHistory({ events }: { events: StatusEvent[] }) {
  if (events.length === 0) {
    return <p className="text-sm text-muted">No status changes yet.</p>;
  }
  return (
    <ul className="space-y-2">
      {events.map((e) => (
        <li key={e.id} className="rounded-[var(--radius-control)] border border-border p-3 text-sm">
          <div className="font-medium">
            {humanizeStatus(e.fromStatus)} → {humanizeStatus(e.toStatus)}
          </div>
          <div className="text-muted">{e.reason}</div>
          <div className="mt-1 text-xs text-muted">
            {e.changedBy ?? '—'} · {formatDateTime(e.changedAt)}
          </div>
        </li>
      ))}
    </ul>
  );
}
