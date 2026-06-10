import type { AuthProvider, AuthUser } from './types';

export interface DevAuthConfig {
  token: string | null;
  authorities: string[];
  user: AuthUser | null;
}

export function createDevAuthProvider(config: DevAuthConfig): AuthProvider {
  let { token, authorities, user } = config;
  return {
    isAuthenticated: () => token !== null,
    isReady: () => true,
    getUser: () => user,
    getAuthorities: () => authorities,
    getToken: async () => token,
    login: async () => {
      // Dev login is a no-op: the configured token is the session.
      if (!token) {
        token = 'dev-token';
        user = user ?? { sub: 'dev', name: 'Dev Operator', email: 'dev@nubbank.test' };
      }
    },
    // No redirect leg in dev — the configured token is already the session.
    completeRedirectLogin: async () => {},
    logout: async () => {
      token = null;
    },
  };
}
