import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { useCustomers, useCustomer, useCustomerKycEvents } from './use-customers';
import { ApiClientProvider } from '@/api/context';

function makeWrapper(getResult: unknown) {
  const client = { GET: vi.fn(async () => getResult), POST: vi.fn(), PUT: vi.fn() };
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
  error: undefined, response: new Response(null, { status: 200 }),
});

describe('useCustomers', () => {
  it('normalizes the page and passes filter+search params', async () => {
    const { Wrapper, client } = makeWrapper({
      data: { data: { content: [{ id: 'c1', firstName: 'John', lastName: 'Doe', email: 'j@x.com',
        kycStatus: 'PENDING_KYC', kycLevel: 'NONE', externalReference: 'ext-1', createdAt: 't' }],
        number: 0, size: 20, totalElements: 1, totalPages: 1 }, meta: null, errors: null },
      error: undefined, response: new Response(null, { status: 200 }) });
    const { result } = renderHook(
      () => useCustomers({ page: 0, size: 20, kycStatus: 'PENDING_KYC', search: 'joh' }),
      { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.items[0].firstName).toBe('John');
    expect(client.GET).toHaveBeenCalledWith('/baas/v1/customers',
      { params: { query: { page: 0, size: 20, kycStatus: 'PENDING_KYC', search: 'joh' } } });
  });
});

describe('useCustomer', () => {
  it('unwraps the detail', async () => {
    const { Wrapper } = makeWrapper(ok({ id: 'c1', firstName: 'Ada', lastName: 'L',
      bvnMasked: '•••••••1234', kycStatus: 'ACTIVE' }));
    const { result } = renderHook(() => useCustomer('c1'), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.bvnMasked).toBe('•••••••1234');
  });
});

describe('useCustomerKycEvents', () => {
  it('unwraps the event list', async () => {
    const { Wrapper } = makeWrapper(ok([{ id: 'e1', fromStatus: 'PENDING_KYC', toStatus: 'ACTIVE',
      reason: 'ok', changedBy: 'op', changedAt: 't' }]));
    const { result } = renderHook(() => useCustomerKycEvents('c1'), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.[0].toStatus).toBe('ACTIVE');
  });
});
