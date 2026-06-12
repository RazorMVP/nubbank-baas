import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { useCustomers, useCustomer, useCustomerKycEvents, useCreateCustomer, useUpdateCustomer, useKycTransition } from './use-customers';
import { ApiClientProvider } from '@/api/context';
import { ApiError } from '@/api/envelope';

function makeWrapper(getResult: unknown) {
  const client = { GET: vi.fn(async () => getResult), POST: vi.fn(async () => getResult), PUT: vi.fn(async () => getResult) };
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

  it('surfaces ApiError on a 403 error envelope', async () => {
    const { Wrapper } = makeWrapper({
      data: undefined,
      error: { data: null, meta: null,
        errors: [{ code: 'ERR_FORBIDDEN', message: 'denied', field: null, docsUrl: null }] },
      response: new Response(null, { status: 403 }),
    });
    const { result } = renderHook(() => useCustomer('c1'), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect((result.current.error as ApiError).code).toBe('ERR_FORBIDDEN');
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

describe('useKycTransition', () => {
  it('POSTs the command path with a reason', async () => {
    const { Wrapper, client } = makeWrapper(ok({ id: 'c1', kycStatus: 'ACTIVE' }));
    const { result } = renderHook(() => useKycTransition('c1'), { wrapper: Wrapper });
    await result.current.mutateAsync({ command: 'activate', reason: 'verified' });
    expect(client.POST).toHaveBeenCalledWith('/baas/v1/customers/{id}/activate',
      { params: { path: { id: 'c1' } }, body: { reason: 'verified' } });
  });

  it('POSTs the suspend command path', async () => {
    const { Wrapper, client } = makeWrapper(ok({ id: 'c1', kycStatus: 'SUSPENDED' }));
    const { result } = renderHook(() => useKycTransition('c1'), { wrapper: Wrapper });
    await result.current.mutateAsync({ command: 'suspend', reason: 'fraud flag' });
    expect(client.POST).toHaveBeenCalledWith('/baas/v1/customers/{id}/suspend',
      { params: { path: { id: 'c1' } }, body: { reason: 'fraud flag' } });
  });
});

describe('useCreateCustomer', () => {
  it('POSTs the create body', async () => {
    const { Wrapper, client } = makeWrapper(ok({ id: 'new' }));
    const { result } = renderHook(() => useCreateCustomer(), { wrapper: Wrapper });
    await result.current.mutateAsync({ firstName: 'A', lastName: 'B' });
    expect(client.POST).toHaveBeenCalledWith('/baas/v1/customers', { body: { firstName: 'A', lastName: 'B' } });
  });
});

describe('useUpdateCustomer', () => {
  it('PUTs the update body', async () => {
    const { Wrapper, client } = makeWrapper(ok({ id: 'c1', firstName: 'New' }));
    const { result } = renderHook(() => useUpdateCustomer('c1'), { wrapper: Wrapper });
    await result.current.mutateAsync({ firstName: 'New', lastName: 'B' });
    expect(client.PUT).toHaveBeenCalledWith('/baas/v1/customers/{id}',
      { params: { path: { id: 'c1' } }, body: { firstName: 'New', lastName: 'B' } });
  });
});
