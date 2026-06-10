import { createContext, useContext, type ReactNode } from 'react';
import type { AuthProvider } from './types';

const AuthContext = createContext<AuthProvider | null>(null);

export function AuthContextProvider({
  provider,
  children,
}: {
  provider: AuthProvider;
  children: ReactNode;
}) {
  return <AuthContext.Provider value={provider}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthProvider {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthContextProvider');
  return ctx;
}
