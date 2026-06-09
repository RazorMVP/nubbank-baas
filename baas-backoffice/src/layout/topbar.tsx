import { Bell, Search } from 'lucide-react';
import { useAuth } from '@/auth/context';

export function Topbar() {
  const user = useAuth().getUser();
  const initials = (user?.name ?? 'NB')
    .split(' ')
    .map((w) => w[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();

  return (
    <header className="flex items-center justify-between border-b border-border bg-surface px-5 py-2.5">
      <div className="flex w-80 items-center gap-2 rounded-[var(--radius-pill)] border border-border bg-bg-app px-3 py-1.5 text-sm text-muted">
        <Search size={15} />
        <span>Search customers, accounts…</span>
      </div>
      <div className="flex items-center gap-4 text-muted">
        <button type="button" aria-label="Notifications" className="grid h-8 w-8 place-items-center rounded-[var(--radius-control)] hover:bg-bg-app">
          <Bell size={18} />
        </button>
        <span className="flex items-center gap-2 text-sm font-medium text-ink">
          <span className="grid h-7 w-7 place-items-center rounded-[var(--radius-control)] bg-brand-primary text-xs text-white">
            {initials}
          </span>
          {user?.name ?? 'Operator'}
        </span>
      </div>
    </header>
  );
}
