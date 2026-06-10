import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { AuthContextProvider, useAuth } from './context';
import { createDevAuthProvider } from './dev-provider';

function Probe() {
  const auth = useAuth();
  return <div>{auth.getUser()?.name ?? 'anon'}</div>;
}

describe('AuthContext', () => {
  it('exposes the provider via useAuth', () => {
    const provider = createDevAuthProvider({
      token: 't',
      authorities: [],
      user: { sub: 'u', name: 'Tunde B.', email: 't@nub.test' },
    });
    render(
      <AuthContextProvider provider={provider}>
        <Probe />
      </AuthContextProvider>,
    );
    expect(screen.getByText('Tunde B.')).toBeInTheDocument();
  });
});
