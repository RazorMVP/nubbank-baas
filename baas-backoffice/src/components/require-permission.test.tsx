import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { RequirePermission } from './require-permission';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

function wrap(authorities: string[], ui: React.ReactNode) {
  const provider = createDevAuthProvider({ token: 't', authorities, user: null });
  return render(<AuthContextProvider provider={provider}>{ui}</AuthContextProvider>);
}

describe('RequirePermission', () => {
  it('renders children when authorized', () => {
    wrap(['CREATE_CUSTOMER'], (
      <RequirePermission code="CREATE_CUSTOMER">
        <button>New customer</button>
      </RequirePermission>
    ));
    expect(screen.getByRole('button', { name: 'New customer' })).toBeInTheDocument();
  });

  it('renders nothing when unauthorized and no fallback', () => {
    wrap([], (
      <RequirePermission code="CREATE_CUSTOMER">
        <button>New customer</button>
      </RequirePermission>
    ));
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('renders the fallback when unauthorized', () => {
    wrap([], (
      <RequirePermission code="CREATE_CUSTOMER" fallback={<span>Not permitted</span>}>
        <button>New customer</button>
      </RequirePermission>
    ));
    expect(screen.getByText('Not permitted')).toBeInTheDocument();
  });
});
