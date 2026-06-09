import { describe, it, expect } from 'vitest';
import { NAV_GROUPS, visibleNav } from './nav-config';

describe('nav-config', () => {
  it('groups nav to the engine modules (spec §5)', () => {
    const titles = NAV_GROUPS.map((g) => g.title);
    expect(titles).toEqual(['Overview', 'Banking', 'Finance', 'Admin']);
  });

  it('filters items by operator authorities', () => {
    const groups = visibleNav(['READ_CUSTOMER']); // only customers visible in Banking
    const banking = groups.find((g) => g.title === 'Banking');
    expect(banking?.items.map((i) => i.label)).toEqual(['Customers']);
  });

  it('drops a group entirely when no items are permitted', () => {
    const groups = visibleNav([]); // no authorities → only Overview (Dashboard has no gate)
    expect(groups.map((g) => g.title)).toEqual(['Overview']);
  });
});
