import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import { AuthCallback } from './callback';
import { AuthContextProvider } from '@/auth/context';
import type { AuthProvider } from '@/auth/types';

function stubAuth(completeRedirectLogin: () => Promise<void>): AuthProvider {
  return {
    isAuthenticated: () => true,
    getUser: () => null,
    getAuthorities: () => [],
    getToken: async () => null,
    login: async () => {},
    completeRedirectLogin,
    logout: async () => {},
  };
}

function renderAt(auth: AuthProvider) {
  return render(
    <AuthContextProvider provider={auth}>
      <MemoryRouter initialEntries={['/auth/callback']}>
        <Routes>
          <Route path="/" element={<div>home</div>} />
          <Route path="/login" element={<div>login</div>} />
          <Route path="/auth/callback" element={<AuthCallback />} />
        </Routes>
      </MemoryRouter>
    </AuthContextProvider>,
  );
}

describe('AuthCallback', () => {
  it('completes the redirect login and navigates home on success', async () => {
    const complete = vi.fn(async () => {});
    renderAt(stubAuth(complete));
    await waitFor(() => expect(complete).toHaveBeenCalledOnce());
    await waitFor(() => expect(screen.getByText('home')).toBeInTheDocument());
  });

  it('shows an error state when the exchange fails', async () => {
    renderAt(stubAuth(async () => { throw new Error('exchange failed'); }));
    await waitFor(() => expect(screen.getByText(/sign-in failed/i)).toBeInTheDocument());
  });
});
