import { describe, it, expect } from 'vitest';
import { createDevAuthProvider } from './dev-provider';

describe('DevAuthProvider', () => {
  it('is authenticated with configured token + authorities', async () => {
    const p = createDevAuthProvider({
      token: 'dev-tok',
      authorities: ['READ_CUSTOMER', 'CREATE_CUSTOMER'],
      user: { sub: 'u1', name: 'Adaeze O.', email: 'a@nub.test' },
    });
    expect(p.isAuthenticated()).toBe(true);
    expect(p.getAuthorities()).toEqual(['READ_CUSTOMER', 'CREATE_CUSTOMER']);
    expect(await p.getToken()).toBe('dev-tok');
    expect(p.getUser()?.name).toBe('Adaeze O.');
  });

  it('is unauthenticated when no token', () => {
    const p = createDevAuthProvider({ token: null, authorities: [], user: null });
    expect(p.isAuthenticated()).toBe(false);
  });
});
