import { describe, it, expect, vi } from 'vitest';
import { createApiClient } from './client';
import type { ClientOptions } from 'openapi-fetch';

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** Helper: cast a vi.fn mock's first captured call arg to Request.
 *  openapi-fetch calls fetch(request: Request) with a single Request object,
 *  but vi.fn() infers a zero-arg tuple without an explicit type annotation.
 *  We cast through unknown to extract the Request for header assertions. */
function capturedRequest(
  spy: ReturnType<typeof vi.fn>,
  callIndex = 0,
): Request {
  return spy.mock.calls[callIndex][0] as unknown as Request;
}

describe('createApiClient', () => {
  it('injects the bearer token from getToken()', async () => {
    const fetchSpy: ClientOptions['fetch'] = vi.fn(async () =>
      jsonResponse({ data: { ok: true }, meta: null, errors: null }),
    );
    const client = createApiClient({
      baseUrl: 'http://engine',
      getToken: async () => 'tok-123',
      fetch: fetchSpy,
    });
    await client.GET('/baas/v1/customers', {});
    const req = capturedRequest(fetchSpy as ReturnType<typeof vi.fn>);
    expect(req.headers.get('Authorization')).toBe('Bearer tok-123');
  });

  it('omits Authorization when no token', async () => {
    const fetchSpy: ClientOptions['fetch'] = vi.fn(async () =>
      jsonResponse({ data: null, meta: null, errors: null }),
    );
    const client = createApiClient({
      baseUrl: 'http://engine',
      getToken: async () => null,
      fetch: fetchSpy,
    });
    await client.GET('/baas/v1/customers', {});
    const req = capturedRequest(fetchSpy as ReturnType<typeof vi.fn>);
    expect(req.headers.get('Authorization')).toBeNull();
  });
});
