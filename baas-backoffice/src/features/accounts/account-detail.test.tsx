import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { AccountDetail } from './account-detail';
import { ApiClientProvider } from '@/api/context';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

// Routes the four GETs by URL suffix: status-events, transactions, or the detail itself.
// `post` is injectable (defaults to vi.fn()) so a mutation test can spy on the POST call —
// mirrors the customer-detail.test.tsx harness. Existing tests pass no post and are untouched.
function wrap(
  detail: unknown,
  events: unknown[],
  txns: unknown[],
  authorities = ['READ_ACCOUNT', 'UPDATE_ACCOUNT', 'DEPOSIT', 'WITHDRAW'],
  post = vi.fn(),
) {
  const env = (data: unknown) => ({
    data: { data, meta: null, errors: null }, error: undefined,
    response: new Response(null, { status: 200 }),
  });
  const pageEnv = (rows: unknown[]) => env({ content: rows, number: 0, size: 20,
    totalElements: rows.length, totalPages: 1 });
  const client = {
    GET: vi.fn(async (path: string) => {
      if (path.endsWith('/status-events')) return env(events);
      if (path.endsWith('/transactions')) return pageEnv(txns);
      return env(detail);
    }),
    POST: post, PUT: vi.fn(),
  } as never;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const auth = createDevAuthProvider({ token: 't', authorities, user: null });
  return ({ children }: { children: ReactNode }) => (
    <AuthContextProvider provider={auth}>
      <ApiClientProvider client={client}>
        <QueryClientProvider client={qc}>
          <MemoryRouter initialEntries={['/accounts/a1']}>
            <Routes>
              <Route path="/accounts/:id" element={children} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </ApiClientProvider>
    </AuthContextProvider>
  );
}

const ACTIVE_ACCOUNT = { id: 'a1', accountNumber: '0123456789', customerId: 'c1',
  customerName: 'Ada Lovelace', accountTypeLabel: 'Savings', status: 'ACTIVE', balance: 0,
  availableBalance: 0, currencyCode: 'NGN', minimumBalance: 0, allowOverdraft: false,
  overdraftLimit: 0, openedAt: '2026-06-10T10:00:00Z' };

const FROZEN_ACCOUNT = { ...ACTIVE_ACCOUNT, status: 'FROZEN', balance: 500, availableBalance: 500 };

describe('AccountDetail', () => {
  it('renders an ACTIVE account with freeze/close/deposit/withdraw actions', async () => {
    const Wrapper = wrap(ACTIVE_ACCOUNT, [], []);
    render(<AccountDetail />, { wrapper: Wrapper });
    expect(await screen.findByText('0123456789')).toBeInTheDocument();
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /freeze/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /close/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /deposit/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /withdraw/i })).toBeInTheDocument();
    // unfreeze is NOT valid from ACTIVE.
    expect(screen.queryByRole('button', { name: /unfreeze/i })).not.toBeInTheDocument();
  });

  it('renders a FROZEN account with only unfreeze + deposit (no withdraw, no close)', async () => {
    const Wrapper = wrap(FROZEN_ACCOUNT, [], []);
    render(<AccountDetail />, { wrapper: Wrapper });
    await screen.findByText('0123456789');
    expect(screen.getByRole('button', { name: /unfreeze/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /deposit/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /withdraw/i })).not.toBeInTheDocument();
    // Anchored names so /freeze/ does not match the rendered "Unfreeze" button.
    expect(screen.queryByRole('button', { name: /^close$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^freeze$/i })).not.toBeInTheDocument();
  });

  it('hides all action buttons for a read-only operator', async () => {
    const Wrapper = wrap(ACTIVE_ACCOUNT, [], [], ['READ_ACCOUNT']);
    render(<AccountDetail />, { wrapper: Wrapper });
    await screen.findByText('0123456789');
    expect(screen.queryByRole('button', { name: /freeze/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /deposit/i })).not.toBeInTheDocument();
  });

  it('hides close on an ACTIVE account with a non-zero balance', async () => {
    // close is in LIFECYCLE_ACTIONS for ACTIVE but is filtered out unless balance === 0
    // (the close-balance guard, spec §6). freeze stays available.
    const Wrapper = wrap({ ...ACTIVE_ACCOUNT, balance: 500, availableBalance: 500 }, [], []);
    render(<AccountDetail />, { wrapper: Wrapper });
    await screen.findByText('0123456789');
    expect(screen.queryByRole('button', { name: /^close$/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^freeze$/i })).toBeInTheDocument();
  });

  it('deposits through the MoneyModal and POSTs to the deposit endpoint', async () => {
    const post = vi.fn(async (
      _path: string,
      _opts: { params?: { path?: { id?: string } }; body?: { amount?: number } },
    ) => ({
      data: { data: { id: 'tx1' }, meta: null, errors: null },
      error: undefined,
      response: new Response(null, { status: 200 }),
    }));
    const Wrapper = wrap(ACTIVE_ACCOUNT, [], [],
      ['READ_ACCOUNT', 'UPDATE_ACCOUNT', 'DEPOSIT', 'WITHDRAW'], post);
    render(<AccountDetail />, { wrapper: Wrapper });

    await screen.findByText('0123456789');
    // Header "Deposit" button opens the MoneyModal.
    await userEvent.click(screen.getByRole('button', { name: /deposit/i }));

    // Modal mounts with its own "Deposit" title; scope the amount field + submit to the
    // dialog so the header trigger button (also named "Deposit") is not matched.
    const dialog = await screen.findByRole('dialog');
    // The dialog title and the submit button are both "Deposit"; assert via the heading
    // role so the title check does not also match the submit button.
    expect(within(dialog).getByRole('heading', { name: /deposit/i })).toBeInTheDocument();
    await userEvent.type(within(dialog).getByLabelText(/amount/i), '250');
    await userEvent.click(within(dialog).getByRole('button', { name: /deposit/i }));

    await waitFor(() => expect(post).toHaveBeenCalled());
    // useDeposit POSTs to the literal template `/baas/v1/accounts/{id}/deposit`
    // (the real id `a1` travels via params.path.id) with { amount: 250 } in the body.
    const [path, opts] = post.mock.calls[0];
    expect(path).toBe('/baas/v1/accounts/{id}/deposit');
    expect(opts?.params?.path?.id).toBe('a1');
    expect(opts?.body).toMatchObject({ amount: 250 });
  });
});
