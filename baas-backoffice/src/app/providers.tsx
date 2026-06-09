import { type ReactNode, useMemo } from 'react';
import { QueryClient, QueryClientProvider, MutationCache } from '@tanstack/react-query';
import { Toaster, toast } from 'sonner';
import { AuthContextProvider } from '@/auth/context';
import { createAuthProvider } from '@/auth/create-provider';
import { ApiClientProvider } from '@/api/context';
import { createApiClient } from '@/api/client';
import { toToastMessage } from '@/api/query';
import { ErrorBoundary } from './error-boundary';

export function AppProviders({ children }: { children: ReactNode }) {
  const env = import.meta.env as unknown as Record<string, string>;

  const auth = useMemo(() => createAuthProvider(env), [env]);

  const apiClient = useMemo(
    () =>
      createApiClient({
        baseUrl: env.VITE_API_BASE_URL ?? '',
        getToken: () => auth.getToken(),
      }),
    [auth, env],
  );

  const queryClient = useMemo(
    () =>
      new QueryClient({
        defaultOptions: { queries: { retry: 1, staleTime: 30_000 } },
        mutationCache: new MutationCache({
          onError: (error) => toast.error(toToastMessage(error)),
        }),
      }),
    [],
  );

  return (
    <ErrorBoundary>
      <AuthContextProvider provider={auth}>
        <ApiClientProvider client={apiClient}>
          <QueryClientProvider client={queryClient}>
            {children}
            <Toaster position="top-right" richColors />
          </QueryClientProvider>
        </ApiClientProvider>
      </AuthContextProvider>
    </ErrorBoundary>
  );
}
