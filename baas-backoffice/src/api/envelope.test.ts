import { describe, it, expect } from 'vitest';
import { unwrap, extractPage, ApiError, type Envelope, type Page } from './envelope';

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
