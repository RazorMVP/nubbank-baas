import { describe, it, expect, vi } from 'vitest';
import { parseAuthorities, fetchOperatorAuthorities } from './pkce-provider';

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

describe('fetchOperatorAuthorities', () => {
  const okResponse = (authorities: string[]) =>
    new Response(JSON.stringify({ data: { authorities }, meta: {}, errors: null }), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    });

  it('returns the authorities from /baas/v1/operators/me', async () => {
    const fetchFn = vi.fn(async () => okResponse(['READ_CUSTOMER', 'RUN_REPORT']));
    const result = await fetchOperatorAuthorities('http://engine', 'tok', fetchFn as never);
    expect(result).toEqual(['READ_CUSTOMER', 'RUN_REPORT']);
    // Sends the bearer token to the right path.
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('http://engine/baas/v1/operators/me');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer tok');
  });

  it('returns [] on a non-OK response (e.g. 401)', async () => {
    const fetchFn = vi.fn(async () => new Response(null, { status: 401 }));
    expect(await fetchOperatorAuthorities('http://engine', 'tok', fetchFn as never)).toEqual([]);
  });

  it('returns [] when the request throws (engine unreachable)', async () => {
    const fetchFn = vi.fn(async () => {
      throw new Error('network down');
    });
    expect(await fetchOperatorAuthorities('http://engine', 'tok', fetchFn as never)).toEqual([]);
  });
});
