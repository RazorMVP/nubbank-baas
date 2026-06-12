import { createBrowserRouter } from 'react-router-dom';
import { AppShell } from '@/layout/app-shell';
import { RequireAuth, RequireRoutePermission } from './guards';
import { Login } from '@/features/auth/login';
import { AuthCallback } from '@/features/auth/callback';
import { Dashboard } from '@/features/dashboard/dashboard';
import { CustomersList } from '@/features/customers/customers-list';
import { CustomerDetail } from '@/features/customers/customer-detail';
import { PERMISSIONS } from '@/lib/rbac';

// Exported so a test can mount these standalone with createMemoryRouter.
// Absolute paths resolve both on their own and when spread under the pathless
// AppShell layout route.
export const customerRoutes = [
  {
    path: '/customers',
    element: (
      <RequireRoutePermission code={PERMISSIONS.READ_CUSTOMER}>
        <CustomersList />
      </RequireRoutePermission>
    ),
  },
  {
    path: '/customers/:id',
    element: (
      <RequireRoutePermission code={PERMISSIONS.READ_CUSTOMER}>
        <CustomerDetail />
      </RequireRoutePermission>
    ),
  },
];

export const router = createBrowserRouter([
  { path: '/login', element: <Login /> },
  { path: '/auth/callback', element: <AuthCallback /> },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppShell />,
        children: [{ index: true, element: <Dashboard /> }, ...customerRoutes],
      },
    ],
  },
]);
