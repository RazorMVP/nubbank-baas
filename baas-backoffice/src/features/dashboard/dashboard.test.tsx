import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { Dashboard } from './dashboard';
import { ApiClientProvider } from '@/api/context';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

const envelope = (data: unknown) => ({
  data: { data, meta: { requestId: 'r', timestamp: 't' }, errors: null },
  error: undefined,
  response: new Response(),
});

const SUMMARY = {
  totalCustomers: 5,
  kycPendingCustomers: 2,
  totalAccounts: 3,
  activeAccounts: 3,
  totalDeposits: 1000,
  totalLoans: 1,
  activeLoans: 1,
  cardsIssued: 4,
};

function wrap(children: ReactNode) {
  const client = {
    // Path-aware: the Dashboard now fetches both the recent-customers page and the summary tiles.
    GET: vi.fn(async (path: string) =>
      path === '/baas/v1/dashboard/summary'
        ? envelope(SUMMARY)
        : envelope({
            content: [{ id: 'c1', firstName: 'Chidi', lastName: 'Okafor', kycStatus: 'VERIFIED' }],
            number: 0,
            size: 5,
            totalElements: 1,
            totalPages: 1,
          }),
    ),
  } as never;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const auth = createDevAuthProvider({ token: 't', authorities: ['READ_CUSTOMER'], user: { sub: 'u', name: 'Adaeze O.', email: 'a@n.test' } });
  return render(
    <AuthContextProvider provider={auth}>
      <ApiClientProvider client={client}>
        <QueryClientProvider client={qc}>
          <MemoryRouter>{children}</MemoryRouter>
        </QueryClientProvider>
      </ApiClientProvider>
    </AuthContextProvider>,
  );
}

describe('Dashboard', () => {
  it('renders KPI tiles and the recent-customers row', async () => {
    wrap(<Dashboard />);
    expect(screen.getByText(/recent customers/i)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('Chidi Okafor')).toBeInTheDocument());
  });

  it('wires the summary endpoint into the KPI tiles', async () => {
    wrap(<Dashboard />);
    // totalDeposits 1000 → grouped naira; kycPending 2; cardsIssued 4; customers 5.
    await waitFor(() => expect(screen.getByText('1,000')).toBeInTheDocument());
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
  });
});
