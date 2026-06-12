import { describe, it, expect } from 'vitest';
import { humanizeStatus, formatDateTime } from './format';

describe('humanizeStatus', () => {
  it('replaces a single underscore with a space', () => {
    expect(humanizeStatus('PENDING_KYC')).toBe('PENDING KYC');
  });

  it('replaces every underscore (multi-underscore case)', () => {
    expect(humanizeStatus('TRANSFER_IN_PROGRESS')).toBe('TRANSFER IN PROGRESS');
  });
});

describe('formatDateTime', () => {
  it('formats an ISO string as en-GB short date-time (dd/mm/yyyy, hh:mm)', () => {
    // Mid-year date+time well inside a single UTC day; the year and structural
    // shape are stable across timezones even though the exact day/hour may shift.
    const out = formatDateTime('2026-06-10T10:00:00Z');
    expect(out).toMatch(/^\d{2}\/\d{2}\/\d{4}, \d{2}:\d{2}$/);
    expect(out).toContain('2026');
  });
});
