import { describe, it, expect } from 'vitest';
import { createAuthProvider } from './create-provider';

describe('createAuthProvider', () => {
  it('returns a dev provider when VITE_DEV_AUTH=true', () => {
    const p = createAuthProvider({
      VITE_DEV_AUTH: 'true',
      VITE_DEV_AUTHORITIES: 'READ_CUSTOMER,CREATE_CUSTOMER',
    });
    expect(p.isAuthenticated()).toBe(true);
    expect(p.getAuthorities()).toContain('CREATE_CUSTOMER');
  });

  it('returns a PKCE provider when dev auth is off', () => {
    const p = createAuthProvider({
      VITE_DEV_AUTH: 'false',
      VITE_OIDC_AUTHORITY: 'https://kc.test/realms/p',
      VITE_OIDC_CLIENT_ID: 'baas-backoffice',
      VITE_OIDC_REDIRECT_URI: 'http://localhost:3001/auth/callback',
    });
    // PKCE provider starts unauthenticated (no stored user in jsdom).
    expect(p.isAuthenticated()).toBe(false);
  });
});
