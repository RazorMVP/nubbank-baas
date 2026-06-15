import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import {
  useAccounts,
  useAccount,
  useAccountStatusEvents,
  useAccountTransactions,
} from './use-accounts';
import { ApiClientProvider } from '@/api/context';
import { ApiError } from '@/api/envelope';

function makeWrapper(getResult: unknown) {
  const client = {
    GET: vi.fn(async () => getResult),
    POST: vi.fn(async () => getResult),
    PUT: vi.fn(async () => getResult),
  };
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <ApiClientProvider client={client as never}>
      <QueryClientProvider client={qc}>{children}</QueryClientProvider>
    </ApiClientProvider>
  );
  return { Wrapper, client };
}
const ok = (data: unknown) => ({
  data: { data, meta: { requestId: 'r', timestamp: 't' }, errors: null },
  error: undefined,
  response: new Response(null, { status: 200 }),
});

describe('useAccounts', () => {
  it('normalizes the page and passes status+search params (undefined stripped)', async () => {
    const { Wrapper, client } = makeWrapper({
      data: {
        data: {
          content: [{ id: 'a1', accountNumber: '0123456789', customerId: 'c1',
            customerName: 'Ada Lovelace', accountTypeLabel: 'Savings', status: 'ACTIVE',
            balance: 0, currencyCode: 'NGN' }],
          number: 0, size: 20, totalElements: 1, totalPages: 1,
        },
        meta: null, errors: null,
      },
      error: undefined, response: new Response(null, { status: 200 }),
    });
    const { result } = renderHook(
      () => useAccounts({ page: 0, size: 20, status: 'ACTIVE', search: '012' }),
      { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.items[0].customerName).toBe('Ada Lovelace');
    expect(client.GET).toHaveBeenCalledWith('/baas/v1/accounts',
      { params: { query: { page: 0, size: 20, status: 'ACTIVE', search: '012' } } });
  });

  it('omits status+search from the query when undefined', async () => {
    const { Wrapper, client } = makeWrapper({
      data: { data: { content: [], number: 0, size: 20, totalElements: 0, totalPages: 0 },
        meta: null, errors: null },
      error: undefined, response: new Response(null, { status: 200 }),
    });
    const { result } = renderHook(() => useAccounts({ page: 0, size: 20 }), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.items).toHaveLength(0);
    expect(client.GET).toHaveBeenCalledWith('/baas/v1/accounts',
      { params: { query: { page: 0, size: 20 } } });
  });
});

describe('useAccount', () => {
  it('unwraps the detail', async () => {
    const { Wrapper } = makeWrapper(ok({ id: 'a1', accountNumber: '0123456789',
      customerId: 'c1', customerName: 'Ada Lovelace', accountTypeLabel: 'Savings',
      status: 'FROZEN', balance: 500, availableBalance: 500, currencyCode: 'NGN',
      minimumBalance: 0, allowOverdraft: false, overdraftLimit: 0, openedAt: 't' }));
    const { result } = renderHook(() => useAccount('a1'), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.status).toBe('FROZEN');
    expect(result.current.data?.availableBalance).toBe(500);
  });

  it('surfaces ApiError on a 403 error envelope', async () => {
    const { Wrapper } = makeWrapper({
      data: undefined,
      error: { data: null, meta: null,
        errors: [{ code: 'ERR_FORBIDDEN', message: 'denied', field: null, docsUrl: null }] },
      response: new Response(null, { status: 403 }),
    });
    const { result } = renderHook(() => useAccount('a1'), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect((result.current.error as ApiError).code).toBe('ERR_FORBIDDEN');
  });
});

describe('useAccountStatusEvents', () => {
  it('unwraps the event list', async () => {
    const { Wrapper } = makeWrapper(ok([{ id: 'e1', fromStatus: 'ACTIVE', toStatus: 'FROZEN',
      reason: 'legal hold', changedBy: 'op', changedAt: 't' }]));
    const { result } = renderHook(() => useAccountStatusEvents('a1'), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.[0].toStatus).toBe('FROZEN');
  });
});

describe('useAccountTransactions', () => {
  it('normalizes the transaction page and passes pagination', async () => {
    const { Wrapper, client } = makeWrapper({
      data: { data: { content: [{ id: 't1', accountId: 'a1', transactionType: 'CREDIT',
        amount: 2500, runningBalance: 2500, currencyCode: 'NGN', reference: 'OPENING_DEPOSIT',
        createdAt: 't' }], number: 0, size: 20, totalElements: 1, totalPages: 1 },
        meta: null, errors: null },
      error: undefined, response: new Response(null, { status: 200 }),
    });
    const { result } = renderHook(() => useAccountTransactions('a1', 0, 20), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.items[0].transactionType).toBe('CREDIT');
    expect(client.GET).toHaveBeenCalledWith('/baas/v1/accounts/{id}/transactions',
      { params: { path: { id: 'a1' }, query: { page: 0, size: 20 } } });
  });
});
