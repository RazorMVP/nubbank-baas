import type { ReactNode } from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '@/auth/context';
import { hasPermission } from '@/lib/rbac';

export function RequireAuth() {
  const auth = useAuth();
  if (!auth.isReady()) {
    return (
      <div className="grid min-h-screen place-items-center text-muted">Loading…</div>
    );
  }
  return auth.isAuthenticated() ? <Outlet /> : <Navigate to="/login" replace />;
}

export function RequireRoutePermission({
  code,
  children,
}: {
  code: string;
  children: ReactNode;
}) {
  const auth = useAuth();
  if (hasPermission(auth.getAuthorities(), code)) return <>{children}</>;
  return (
    <div className="grid place-items-center py-24 text-center">
      <div>
        <h2 className="text-lg font-semibold">Not permitted</h2>
        <p className="text-sm text-muted">You don't have access to this area.</p>
      </div>
    </div>
  );
}
