import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { AccountsList } from './accounts-list';
import { ApiClientProvider } from '@/api/context';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

function wrap(getResult: unknown, authorities: string[] = ['READ_ACCOUNT', 'CREATE_ACCOUNT']) {
  const client = { GET: vi.fn(async () => getResult), POST: vi.fn() } as never;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const auth = createDevAuthProvider({ token: 't', authorities, user: null });
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
  data: { data: { content: rows, number: 0, size: 20, totalElements: rows.length, totalPages: 1 },
    meta: null, errors: null },
  error: undefined, response: new Response(null, { status: 200 }),
});

const ROW = { id: 'a1', accountNumber: '0123456789', customerId: 'c1',
  customerName: 'Ada Lovelace', accountTypeLabel: 'Savings', status: 'ACTIVE',
  balance: 2500, currencyCode: 'NGN' };

describe('AccountsList', () => {
  it('renders account rows (number link + customer name)', async () => {
    const Wrapper = wrap(pageOf([ROW]));
    render(<AccountsList />, { wrapper: Wrapper });
    expect(await screen.findByRole('link', { name: '0123456789' })).toBeInTheDocument();
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument();
  });

  it('shows the Open account button when permitted', async () => {
    const Wrapper = wrap(pageOf([]));
    render(<AccountsList />, { wrapper: Wrapper });
    expect(await screen.findByRole('button', { name: /open account/i })).toBeInTheDocument();
  });

  it('hides the Open account button without CREATE_ACCOUNT', async () => {
    const Wrapper = wrap(pageOf([]), ['READ_ACCOUNT']);
    render(<AccountsList />, { wrapper: Wrapper });
    expect(await screen.findByRole('heading', { name: /accounts/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /open account/i })).not.toBeInTheDocument();
  });
});
