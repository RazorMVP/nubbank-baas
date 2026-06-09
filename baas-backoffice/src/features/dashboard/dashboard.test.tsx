import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { Dashboard } from './dashboard';
import { ApiClientProvider } from '@/api/context';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

function wrap(children: ReactNode) {
  const client = {
    GET: vi.fn(async () => ({
      data: {
        data: { content: [{ id: 'c1', firstName: 'Chidi', lastName: 'Okafor', kycStatus: 'VERIFIED' }], number: 0, size: 5, totalElements: 1, totalPages: 1 },
        meta: { requestId: 'r', timestamp: 't' },
        errors: null,
      },
      error: undefined,
      response: new Response(),
    })),
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
});
