import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import { Topbar } from './topbar';
import { AuthContextProvider } from '@/auth/context';
import type { AuthProvider } from '@/auth/types';

function stubAuth(logout: () => Promise<void>): AuthProvider {
  return {
    isAuthenticated: () => true,
    isReady: () => true,
    getUser: () => ({ sub: 'u', name: 'Adaeze O.', email: 'a@n.test' }),
    getAuthorities: () => [],
    getToken: async () => null,
    login: async () => {},
    completeRedirectLogin: async () => {},
    logout,
  };
}

describe('Topbar', () => {
  it('signs out via the user menu', async () => {
    const logout = vi.fn(async () => {});
    render(
      <AuthContextProvider provider={stubAuth(logout)}>
        <MemoryRouter>
          <Topbar />
        </MemoryRouter>
      </AuthContextProvider>,
    );
    await userEvent.click(screen.getByRole('button', { name: /adaeze/i }));
    await userEvent.click(screen.getByRole('menuitem', { name: /sign out/i }));
    expect(logout).toHaveBeenCalledOnce();
  });
});
