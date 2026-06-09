import { useState } from 'react';
import { NavLink } from 'react-router-dom';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useAuth } from '@/auth/context';
import { visibleNav } from './nav-config';
import { cn } from '@/lib/cn';

export function Sidebar() {
  const auth = useAuth();
  const [collapsed, setCollapsed] = useState(false);
  const groups = visibleNav(auth.getAuthorities());

  return (
    <aside
      className={cn(
        'flex h-screen flex-col bg-ink text-white transition-[width] duration-200',
        collapsed ? 'w-14' : 'w-60',
      )}
    >
      <div className="flex items-center justify-between px-3 py-4">
        <div className="flex items-center gap-2">
          <div className="flex h-8 w-8 items-center justify-center rounded-[var(--radius-control)] bg-brand-primary font-bold">
            N
          </div>
          {!collapsed && <span className="font-semibold">NubBank</span>}
        </div>
        <button
          type="button"
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          onClick={() => setCollapsed((c) => !c)}
          className="grid h-6 w-6 place-items-center rounded text-brand-accent hover:bg-white/10"
        >
          {collapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
        </button>
      </div>

      <nav className="flex-1 overflow-y-auto px-2">
        {groups.map((group) => (
          <div key={group.title} className="mb-4">
            {!collapsed && (
              <p className="px-2 pb-1 text-[10px] uppercase tracking-wide text-white/40">
                {group.title}
              </p>
            )}
            {group.items.map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.to === '/'}
                  className={({ isActive }) =>
                    cn(
                      'flex items-center gap-3 rounded-[var(--radius-control)] px-2 py-2 text-sm',
                      isActive
                        ? 'bg-white/10 border-l-2 border-brand-primary text-white'
                        : 'text-white/70 hover:bg-white/5',
                    )
                  }
                >
                  <Icon size={18} />
                  {!collapsed && <span>{item.label}</span>}
                </NavLink>
              );
            })}
          </div>
        ))}
      </nav>
    </aside>
  );
}
