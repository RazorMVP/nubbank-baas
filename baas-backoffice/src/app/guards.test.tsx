import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect } from 'vitest';
import { RequireAuth, RequireRoutePermission } from './guards';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

function setup(authorities: string[], token: string | null, initial = '/secret') {
  const provider = createDevAuthProvider({ token, authorities, user: null });
  return render(
    <AuthContextProvider provider={provider}>
      <MemoryRouter initialEntries={[initial]}>
        <Routes>
          <Route path="/login" element={<div>login-page</div>} />
          <Route element={<RequireAuth />}>
            <Route
              path="/secret"
              element={
                <RequireRoutePermission code="READ_CUSTOMER">
                  <div>secret-content</div>
                </RequireRoutePermission>
              }
            />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContextProvider>,
  );
}

describe('route guards', () => {
  it('redirects to /login when unauthenticated', () => {
    setup([], null);
    expect(screen.getByText('login-page')).toBeInTheDocument();
  });
  it('shows not-permitted when authenticated but lacking permission', () => {
    setup([], 't');
    expect(screen.getByText(/not permitted/i)).toBeInTheDocument();
  });
  it('renders content when authorized', () => {
    setup(['READ_CUSTOMER'], 't');
    expect(screen.getByText('secret-content')).toBeInTheDocument();
  });
});
