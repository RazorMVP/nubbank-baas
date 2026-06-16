import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { OpenAccountModal } from './open-account-modal';
import type { OpenAccountBody } from './use-accounts';
import { ApiClientProvider } from '@/api/context';

// The modal's customer picker calls useCustomers → client.GET('/baas/v1/customers').
function wrap(customers: unknown[]) {
  const client = {
    GET: vi.fn(async () => ({
      data: { data: { content: customers, number: 0, size: 20,
        totalElements: customers.length, totalPages: 1 }, meta: null, errors: null },
      error: undefined, response: new Response(null, { status: 200 }),
    })),
    POST: vi.fn(),
    PUT: vi.fn(),
  } as never;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => (
    <ApiClientProvider client={client}>
      <QueryClientProvider client={qc}>{children}</QueryClientProvider>
    </ApiClientProvider>
  );
}

const CUSTOMER = { id: 'c1', firstName: 'Ada', lastName: 'Lovelace', email: 'a@x.com',
  kycStatus: 'ACTIVE', kycLevel: 'TIER_1', externalReference: 'ext-1', createdAt: 't' };

describe('OpenAccountModal', () => {
  it('blocks submit until a customer is picked', async () => {
    const onSubmit = vi.fn(async () => {});
    const Wrapper = wrap([CUSTOMER]);
    render(<OpenAccountModal open onOpenChange={() => {}} onSubmit={onSubmit} />,
      { wrapper: Wrapper });
    await userEvent.click(screen.getByRole('button', { name: /open account/i }));
    expect(await screen.findByText(/select a customer/i)).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('resolves the find-customer label to the search input (FormField labellable child)', () => {
    const Wrapper = wrap([CUSTOMER]);
    render(<OpenAccountModal open onOpenChange={() => {}} onSubmit={vi.fn()} />,
      { wrapper: Wrapper });
    // getByLabelText must land on the <input>, proving FormField wraps exactly the
    // labellable Input (not a non-labellable <div>).
    const input = screen.getByLabelText(/find customer/i);
    expect(input.tagName).toBe('INPUT');
  });

  it('submits customerId + type + currency after picking a customer', async () => {
    const onSubmit = vi.fn(async (_body: OpenAccountBody) => {});
    const Wrapper = wrap([CUSTOMER]);
    render(<OpenAccountModal open onOpenChange={() => {}} onSubmit={onSubmit} />,
      { wrapper: Wrapper });

    // Search → the customer result appears → click to select it.
    await userEvent.type(screen.getByLabelText(/find customer/i), 'ada');
    await userEvent.click(await screen.findByRole('button', { name: /ada lovelace/i }));

    await userEvent.click(screen.getByRole('button', { name: /open account/i }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    // The modal maps form values → OpenAccountBody before calling onSubmit. With no
    // opening deposit entered, the field is omitted entirely (not '' or 0/NaN).
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ customerId: 'c1', accountTypeLabel: 'Savings', currencyCode: 'NGN' }));
    expect(onSubmit.mock.calls[0][0]).not.toHaveProperty('openingDeposit');
  });

  it('coerces a non-empty opening deposit to a number at the body boundary', async () => {
    const onSubmit = vi.fn(async (_body: OpenAccountBody) => {});
    const Wrapper = wrap([CUSTOMER]);
    render(<OpenAccountModal open onOpenChange={() => {}} onSubmit={onSubmit} />,
      { wrapper: Wrapper });

    await userEvent.type(screen.getByLabelText(/find customer/i), 'ada');
    await userEvent.click(await screen.findByRole('button', { name: /ada lovelace/i }));
    await userEvent.type(screen.getByLabelText(/opening deposit/i), '250.5');

    await userEvent.click(screen.getByRole('button', { name: /open account/i }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    const body = onSubmit.mock.calls[0][0];
    expect(body).toMatchObject({ customerId: 'c1', openingDeposit: 250.5 });
    expect(typeof body.openingDeposit).toBe('number');
  });
});
