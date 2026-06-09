import { useState } from 'react';
import { Bell, Search, ChevronDown } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/context';

export function Topbar() {
  const auth = useAuth();
  const navigate = useNavigate();
  const user = auth.getUser();
  const [open, setOpen] = useState(false);
  const initials = (user?.name ?? 'NB')
    .split(' ')
    .map((w) => w[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();

  async function onSignOut() {
    setOpen(false);
    await auth.logout();
    navigate('/login', { replace: true });
  }

  return (
    <header className="flex items-center justify-between border-b border-border bg-surface px-5 py-2.5">
      <div className="flex w-80 items-center gap-2 rounded-[var(--radius-pill)] border border-border bg-bg-app px-3 py-1.5 text-sm text-muted">
        <Search size={15} />
        <span>Search customers, accounts…</span>
      </div>
      <div className="flex items-center gap-4 text-muted">
        <button
          type="button"
          aria-label="Notifications"
          className="grid h-8 w-8 place-items-center rounded-[var(--radius-control)] hover:bg-bg-app"
        >
          <Bell size={18} />
        </button>
        <div className="relative">
          <button
            type="button"
            onClick={() => setOpen((o) => !o)}
            className="flex items-center gap-2 text-sm font-medium text-ink"
          >
            <span className="grid h-7 w-7 place-items-center rounded-[var(--radius-control)] bg-brand-primary text-xs text-white">
              {initials}
            </span>
            {user?.name ?? 'Operator'}
            <ChevronDown size={14} />
          </button>
          {open && (
            <div
              role="menu"
              className="absolute right-0 z-10 mt-2 w-40 rounded-[var(--radius-control)] border border-border bg-surface py-1 shadow-lg"
            >
              <button
                role="menuitem"
                type="button"
                onClick={onSignOut}
                className="block w-full px-3 py-2 text-left text-sm text-ink hover:bg-bg-app"
              >
                Sign out
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}
