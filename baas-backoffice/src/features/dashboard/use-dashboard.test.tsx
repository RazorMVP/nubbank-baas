import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { useRecentCustomers } from './use-dashboard';
import { ApiClientProvider } from '@/api/context';
import { ApiError } from '@/api/envelope';

function makeWrapper(getResult: unknown) {
  const client = {
    GET: vi.fn(async () => getResult),
  } as never;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <ApiClientProvider client={client}>
        <QueryClientProvider client={qc}>{children}</QueryClientProvider>
      </ApiClientProvider>
    );
  };
}

describe('useRecentCustomers', () => {
  it('normalizes the envelope+Page into items + total', async () => {
    const wrapper = makeWrapper({
      data: {
        data: {
          content: [{ id: 'c1', firstName: 'Chidi', lastName: 'Okafor', kycStatus: 'VERIFIED' }],
          number: 0,
          size: 5,
          totalElements: 42,
          totalPages: 9,
        },
        meta: { requestId: 'r', timestamp: 't' },
        errors: null,
      },
      error: undefined,
      response: new Response(null, { status: 200 }),
    });
    const { result } = renderHook(() => useRecentCustomers(5), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.total).toBe(42);
    expect(result.current.data?.items[0].firstName).toBe('Chidi');
  });

  it('surfaces ApiError on a 403 error envelope', async () => {
    const wrapper = makeWrapper({
      data: undefined,
      error: {
        data: null,
        meta: null,
        errors: [{ code: 'ERR_FORBIDDEN', message: 'denied', field: null, docsUrl: null }],
      },
      response: new Response(null, { status: 403 }),
    });
    const { result } = renderHook(() => useRecentCustomers(5), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect((result.current.error as ApiError).code).toBe('ERR_FORBIDDEN');
  });
});
