import { createBrowserRouter } from 'react-router-dom';
import { AppShell } from '@/layout/app-shell';
import { RequireAuth } from './guards';
import { Login } from '@/features/auth/login';
import { AuthCallback } from '@/features/auth/callback';
import { Dashboard } from '@/features/dashboard/dashboard';

export const router = createBrowserRouter([
  { path: '/login', element: <Login /> },
  { path: '/auth/callback', element: <AuthCallback /> },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppShell />,
        children: [{ index: true, element: <Dashboard /> }],
      },
    ],
  },
]);
