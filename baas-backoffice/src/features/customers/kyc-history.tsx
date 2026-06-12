import type { KycEvent } from './use-customers';

export function KycHistory({ events }: { events: KycEvent[] }) {
  if (events.length === 0) {
    return <p className="text-sm text-muted">No KYC events yet.</p>;
  }
  return (
    <ul className="space-y-2">
      {events.map((e) => (
        <li
          key={e.id}
          className="rounded-[var(--radius-control)] border border-border p-3 text-sm"
        >
          <div className="font-medium">
            {e.fromStatus} → {e.toStatus}
          </div>
          <div className="text-muted">{e.reason}</div>
          <div className="mt-1 text-xs text-muted">
            {e.changedBy ?? '—'} · {new Date(e.changedAt).toLocaleString()}
          </div>
        </li>
      ))}
    </ul>
  );
}
