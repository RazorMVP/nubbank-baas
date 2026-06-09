import type { ReactNode } from 'react';
import { useAuth } from '@/auth/context';
import { hasPermission } from '@/lib/rbac';

export function RequirePermission({
  code,
  children,
  fallback = null,
}: {
  code: string;
  children: ReactNode;
  fallback?: ReactNode;
}) {
  const auth = useAuth();
  return hasPermission(auth.getAuthorities(), code) ? <>{children}</> : <>{fallback}</>;
}
