import { describe, it, expect } from 'vitest';
import { hasPermission, PERMISSIONS } from './rbac';

describe('hasPermission', () => {
  it('is true when the authority is present', () => {
    expect(hasPermission(['READ_CUSTOMER', 'CREATE_CUSTOMER'], 'CREATE_CUSTOMER')).toBe(true);
  });
  it('is false when absent', () => {
    expect(hasPermission(['READ_CUSTOMER'], 'CREATE_CUSTOMER')).toBe(false);
  });
  it('treats undefined permission requirement as always allowed', () => {
    expect(hasPermission([], undefined)).toBe(true);
  });
  it('exposes engine permission code constants', () => {
    expect(PERMISSIONS.READ_CUSTOMER).toBe('READ_CUSTOMER');
    expect(PERMISSIONS.APPROVE_LOAN).toBe('APPROVE_LOAN');
  });
});
