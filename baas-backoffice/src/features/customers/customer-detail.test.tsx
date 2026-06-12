import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { CustomerDetail } from './customer-detail';
import { ApiClientProvider } from '@/api/context';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

function wrap(
  detail: unknown,
  events: unknown[],
  authorities = ['READ_CUSTOMER', 'UPDATE_CUSTOMER'],
  post = vi.fn(),
) {
  // `as never` is the accepted seam for the openapi-fetch client in tests —
  // mirrors customers-list.test.tsx / use-customers.test.tsx.
  const client = {
    GET: vi.fn(async (path: string) =>
      path.endsWith('/kyc-events')
        ? { data: { data: events, meta: null, errors: null }, error: undefined, response: new Response(null, { status: 200 }) }
        : { data: { data: detail, meta: null, errors: null }, error: undefined, response: new Response(null, { status: 200 }) }),
    POST: post,
    PUT: vi.fn(),
  } as never;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const auth = createDevAuthProvider({ token: 't', authorities, user: null });
  return ({ children }: { children: ReactNode }) => (
    <AuthContextProvider provider={auth}>
      <ApiClientProvider client={client}>
        <QueryClientProvider client={qc}>
          <MemoryRouter initialEntries={['/customers/c1']}>
            <Routes>
              <Route path="/customers/:id" element={children} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </ApiClientProvider>
    </AuthContextProvider>
  );
}

const PENDING_CUSTOMER = {
  id: 'c1',
  firstName: 'Ada',
  lastName: 'Lovelace',
  email: 'a@x.com',
  phone: null,
  dateOfBirth: null,
  gender: null,
  kycStatus: 'PENDING_KYC',
  kycLevel: 'NONE',
  bvnMasked: '•••••••1234',
  ninMasked: null,
  externalReference: 'ext-1',
  createdAt: 't',
  updatedAt: 't',
};

describe('CustomerDetail', () => {
  it('renders profile + only the Activate action for a PENDING_KYC customer', async () => {
    const Wrapper = wrap(PENDING_CUSTOMER, []);
    render(<CustomerDetail />, { wrapper: Wrapper });
    expect(await screen.findByText('Ada Lovelace')).toBeInTheDocument();
    expect(screen.getByText('•••••••1234')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /activate/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /suspend/i })).not.toBeInTheDocument();
  });

  it('renders the profile but no action or edit buttons without UPDATE_CUSTOMER', async () => {
    const Wrapper = wrap(PENDING_CUSTOMER, [], ['READ_CUSTOMER']);
    render(<CustomerDetail />, { wrapper: Wrapper });
    expect(await screen.findByText('Ada Lovelace')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /activate/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /edit/i })).not.toBeInTheDocument();
  });

  it('activates a customer through the modal', async () => {
    const post = vi.fn(async () => ({
      data: { data: { id: 'c1' }, meta: null, errors: null },
      error: undefined,
      response: new Response(null, { status: 200 }),
    }));
    const Wrapper = wrap(PENDING_CUSTOMER, [], ['READ_CUSTOMER', 'UPDATE_CUSTOMER'], post);
    render(<CustomerDetail />, { wrapper: Wrapper });

    await screen.findByText('Ada Lovelace');
    await userEvent.click(screen.getByRole('button', { name: /activate/i }));

    // Modal mounts: fill the reason field and confirm.
    await userEvent.type(await screen.findByLabelText(/reason/i), 'kyc done');
    await userEvent.click(screen.getByRole('button', { name: /confirm/i }));

    await waitFor(() => expect(post).toHaveBeenCalled());
    // useKycTransition POSTs to the literal path template `/baas/v1/customers/{id}/${command}`
    // (the real id `c1` travels via params.path.id), with the reason in the request body.
    const [path, opts] = post.mock.calls[0] as unknown as [string, { body?: { reason?: string } }];
    expect(path).toBe('/baas/v1/customers/{id}/activate');
    expect(opts?.body).toMatchObject({ reason: 'kyc done' });
  });
});
