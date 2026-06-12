import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { CustomersList } from './customers-list';
import { ApiClientProvider } from '@/api/context';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

function wrap(
  getResult: unknown,
  authorities: string[] = ['READ_CUSTOMER', 'CREATE_CUSTOMER'],
) {
  // `as never` is the accepted seam for the openapi-fetch client in tests —
  // mirrors use-customers.test.tsx / dashboard.test.tsx.
  const client = { GET: vi.fn(async () => getResult), POST: vi.fn() } as never;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const auth = createDevAuthProvider({
    token: 't',
    authorities,
    user: null,
  });
  return ({ children }: { children: ReactNode }) => (
    <AuthContextProvider provider={auth}>
      <ApiClientProvider client={client}>
        <QueryClientProvider client={qc}>
          <MemoryRouter>{children}</MemoryRouter>
        </QueryClientProvider>
      </ApiClientProvider>
    </AuthContextProvider>
  );
}

const pageOf = (rows: unknown[]) => ({
  data: {
    data: { content: rows, number: 0, size: 20, totalElements: rows.length, totalPages: 1 },
    meta: null,
    errors: null,
  },
  error: undefined,
  response: new Response(null, { status: 200 }),
});

describe('CustomersList', () => {
  it('renders customer rows', async () => {
    const Wrapper = wrap(
      pageOf([
        {
          id: 'c1',
          firstName: 'John',
          lastName: 'Doe',
          email: 'j@x.com',
          kycStatus: 'PENDING_KYC',
          kycLevel: 'NONE',
          externalReference: 'ext-1',
          createdAt: 't',
        },
      ]),
    );
    render(<CustomersList />, { wrapper: Wrapper });
    expect(await screen.findByText('John Doe')).toBeInTheDocument();
  });

  it('shows the New customer button when permitted', async () => {
    const Wrapper = wrap(pageOf([]));
    render(<CustomersList />, { wrapper: Wrapper });
    expect(await screen.findByRole('button', { name: /new customer/i })).toBeInTheDocument();
  });

  it('hides the New customer button without CREATE_CUSTOMER', async () => {
    const Wrapper = wrap(pageOf([]), ['READ_CUSTOMER']); // read-only operator
    render(<CustomersList />, { wrapper: Wrapper });
    // Wait for the list to settle so we're asserting the rendered page, not a pre-render frame
    expect(await screen.findByRole('heading', { name: /customers/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /new customer/i })).not.toBeInTheDocument();
  });
});
