import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import { ApiClientProvider } from '@/api/context';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';
import { customerRoutes } from './router';

// `as never` is the accepted seam for the openapi-fetch client in tests —
// mirrors customers-list.test.tsx / use-customers.test.tsx.
function emptyPage() {
  return {
    data: {
      data: { content: [], number: 0, size: 20, totalElements: 0, totalPages: 0 },
      meta: null,
      errors: null,
    },
    error: undefined,
    response: new Response(null, { status: 200 }),
  };
}

describe('router — customer routes', () => {
  it('customers route renders the list when authorised', async () => {
    const client = { GET: vi.fn(async () => emptyPage()), POST: vi.fn() } as never;
    const auth = createDevAuthProvider({ token: 't', authorities: ['READ_CUSTOMER'], user: null });
    const router = createMemoryRouter(customerRoutes, { initialEntries: ['/customers'] });
    render(
      <AuthContextProvider provider={auth}>
        <ApiClientProvider client={client}>
          <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
            <RouterProvider router={router} />
          </QueryClientProvider>
        </ApiClientProvider>
      </AuthContextProvider>,
    );
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /customers/i })).toBeInTheDocument(),
    );
  });

  it('blocks the customers route without READ_CUSTOMER (no blank screen, no crash)', async () => {
    const client = { GET: vi.fn(async () => emptyPage()), POST: vi.fn() } as never;
    const auth = createDevAuthProvider({ token: 't', authorities: [], user: null });
    const router = createMemoryRouter(customerRoutes, { initialEntries: ['/customers'] });
    render(
      <AuthContextProvider provider={auth}>
        <ApiClientProvider client={client}>
          <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
            <RouterProvider router={router} />
          </QueryClientProvider>
        </ApiClientProvider>
      </AuthContextProvider>,
    );
    await waitFor(() => expect(screen.getByText(/not permitted/i)).toBeInTheDocument());
  });
});
