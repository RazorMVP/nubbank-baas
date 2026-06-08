import type { AuthProvider } from './types';
import { createDevAuthProvider } from './dev-provider';
import { createPkceAuthProvider } from './pkce-provider';

export interface AuthEnv {
  VITE_DEV_AUTH?: string;
  VITE_DEV_TOKEN?: string;
  VITE_DEV_AUTHORITIES?: string;
  VITE_OIDC_AUTHORITY?: string;
  VITE_OIDC_CLIENT_ID?: string;
  VITE_OIDC_REDIRECT_URI?: string;
}

export function createAuthProvider(env: AuthEnv): AuthProvider {
  if (env.VITE_DEV_AUTH === 'true') {
    return createDevAuthProvider({
      token: env.VITE_DEV_TOKEN ?? 'dev-token',
      authorities: (env.VITE_DEV_AUTHORITIES ?? '')
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean),
      user: { sub: 'dev', name: 'Dev Operator', email: 'dev@nubbank.test' },
    });
  }
  return createPkceAuthProvider({
    authority: env.VITE_OIDC_AUTHORITY ?? '',
    clientId: env.VITE_OIDC_CLIENT_ID ?? '',
    redirectUri: env.VITE_OIDC_REDIRECT_URI ?? '',
  });
}
