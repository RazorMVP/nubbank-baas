import { describe, it, expect } from 'vitest';
import { qk, toToastMessage } from './query';
import { ApiError } from './envelope';

describe('qk (query keys)', () => {
  it('builds stable keys with params', () => {
    expect(qk.list('customers', { page: 0, size: 20 })).toEqual([
      'customers',
      'list',
      { page: 0, size: 20 },
    ]);
    expect(qk.detail('customers', 'id-1')).toEqual(['customers', 'detail', 'id-1']);
  });
});

describe('toToastMessage', () => {
  it('uses the ApiError message', () => {
    expect(toToastMessage(new ApiError({ code: 'X', message: 'Boom', field: null, docsUrl: null }))).toBe(
      'Boom',
    );
  });
  it('falls back for unknown errors', () => {
    expect(toToastMessage(new Error('weird'))).toBe('weird');
    expect(toToastMessage('nope')).toBe('Something went wrong');
  });
});
