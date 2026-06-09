import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { useRecentCustomers } from './use-dashboard';
import { ApiClientProvider } from '@/api/context';

function makeWrapper(getResult: unknown) {
  const client = {
    GET: vi.fn(async () => ({ data: getResult, error: undefined, response: new Response() })),
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
        content: [{ id: 'c1', firstName: 'Chidi', lastName: 'Okafor', kycStatus: 'VERIFIED' }],
        number: 0,
        size: 5,
        totalElements: 42,
        totalPages: 9,
      },
      meta: { requestId: 'r', timestamp: 't' },
      errors: null,
    });
    const { result } = renderHook(() => useRecentCustomers(5), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.total).toBe(42);
    expect(result.current.data?.items[0].firstName).toBe('Chidi');
  });
});
