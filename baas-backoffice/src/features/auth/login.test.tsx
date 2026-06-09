import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import { Login } from './login';
import { AuthContextProvider } from '@/auth/context';
import type { AuthProvider } from '@/auth/types';

function stubAuth(over: Partial<AuthProvider> = {}): AuthProvider {
  return {
    isAuthenticated: () => false,
    getUser: () => null,
    getAuthorities: () => [],
    getToken: async () => null,
    login: vi.fn(async () => {}),
    completeRedirectLogin: async () => {},
    logout: async () => {},
    ...over,
  };
}

describe('Login', () => {
  it('renders the NubBank sign-in and trusted-by stats', () => {
    render(
      <AuthContextProvider provider={stubAuth()}>
        <MemoryRouter><Login /></MemoryRouter>
      </AuthContextProvider>,
    );
    expect(screen.getByRole('heading', { name: /sign in/i })).toBeInTheDocument();
    expect(screen.getByText(/15 banks live/i)).toBeInTheDocument();
  });

  it('calls auth.login on submit', async () => {
    const login = vi.fn(async () => {});
    render(
      <AuthContextProvider provider={stubAuth({ login })}>
        <MemoryRouter><Login /></MemoryRouter>
      </AuthContextProvider>,
    );
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    expect(login).toHaveBeenCalledOnce();
  });
});
