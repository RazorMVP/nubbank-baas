import { describe, it, expect } from 'vitest';
import { unwrap, unwrapResult, extractPage, ApiError, type Envelope, type Page } from './envelope';

describe('unwrap', () => {
  it('returns data on success', () => {
    const env: Envelope<{ id: string }> = {
      data: { id: 'x' },
      meta: { requestId: 'r1', timestamp: '2026-06-08T00:00:00Z' },
      errors: null,
    };
    expect(unwrap(env)).toEqual({ id: 'x' });
  });

  it('throws ApiError carrying the first error code/message/field', () => {
    const env: Envelope<null> = {
      data: null,
      meta: { requestId: 'r2', timestamp: '2026-06-08T00:00:00Z' },
      errors: [{ code: 'ERR_VALIDATION', message: 'bad', field: 'email', docsUrl: 'd' }],
    };
    try {
      unwrap(env);
      expect.unreachable('should throw');
    } catch (e) {
      expect(e).toBeInstanceOf(ApiError);
      const err = e as ApiError;
      expect(err.code).toBe('ERR_VALIDATION');
      expect(err.message).toBe('bad');
      expect(err.field).toBe('email');
    }
  });
});

describe('unwrapResult', () => {
  it('returns the data on a 200 success envelope', () => {
    const result = {
      data: { data: { id: 'x' }, meta: null, errors: null },
      error: undefined,
      response: new Response(null, { status: 200 }),
    };
    expect(unwrapResult<{ id: string }>(result)).toEqual({ id: 'x' });
  });

  it('throws ApiError with the envelope error code on a 403 error envelope', () => {
    const result = {
      data: undefined,
      error: {
        data: null,
        meta: null,
        errors: [{ code: 'ERR_FORBIDDEN', message: 'no', field: null, docsUrl: null }],
      },
      response: new Response(null, { status: 403 }),
    };
    try {
      unwrapResult(result);
      expect.unreachable('should throw');
    } catch (e) {
      expect(e).toBeInstanceOf(ApiError);
      const err = e as ApiError;
      expect(err.code).toBe('ERR_FORBIDDEN');
      expect(err.httpStatus).toBe(403);
    }
  });

  it('throws ApiError with ERR_HTTP_500 on a bare HTTP failure (no envelope)', () => {
    const result = {
      data: undefined,
      error: undefined,
      response: new Response(null, { status: 500 }),
    };
    try {
      unwrapResult(result);
      expect.unreachable('should throw');
    } catch (e) {
      expect(e).toBeInstanceOf(ApiError);
      const err = e as ApiError;
      expect(err.httpStatus).toBe(500);
      expect(err.code).toBe('ERR_HTTP_500');
    }
  });
});

describe('extractPage', () => {
  it('normalizes a Spring Page into {items, page, size, total, totalPages}', () => {
    const page: Page<number> = {
      content: [1, 2, 3],
      number: 0,
      size: 20,
      totalElements: 3,
      totalPages: 1,
    };
    expect(extractPage(page)).toEqual({
      items: [1, 2, 3],
      page: 0,
      size: 20,
      total: 3,
      totalPages: 1,
    });
  });
});
