import { createContext, useContext, type ReactNode } from 'react';
import type { Client } from 'openapi-fetch';
import type { paths } from './schema';

const ApiClientContext = createContext<Client<paths> | null>(null);

export function ApiClientProvider({
  client,
  children,
}: {
  client: Client<paths>;
  children: ReactNode;
}) {
  return <ApiClientContext.Provider value={client}>{children}</ApiClientContext.Provider>;
}

export function useApiClient(): Client<paths> {
  const ctx = useContext(ApiClientContext);
  if (!ctx) throw new Error('useApiClient must be used within ApiClientProvider');
  return ctx;
}
