import { describe, it, expect } from 'vitest';
import { parseAuthorities } from './pkce-provider';

describe('parseAuthorities', () => {
  it('reads a flat `authorities` claim', () => {
    expect(parseAuthorities({ authorities: ['READ_CUSTOMER', 'DEPOSIT'] })).toEqual([
      'READ_CUSTOMER',
      'DEPOSIT',
    ]);
  });
  it('falls back to realm_access.roles', () => {
    expect(parseAuthorities({ realm_access: { roles: ['APPROVE_LOAN'] } })).toEqual([
      'APPROVE_LOAN',
    ]);
  });
  it('returns [] when no recognized claim present', () => {
    expect(parseAuthorities({ sub: 'x' })).toEqual([]);
  });
});
