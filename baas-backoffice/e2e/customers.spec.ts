import { test, expect } from '@playwright/test';

// Self-contained happy path: the engine is fully route-stubbed (no backend needed).
// Walks open /customers → create a customer → open detail → activate via the KYC
// modal → see the humanised history transition.
test('customer lifecycle happy path', async ({ page }) => {
  type StubCustomer = { id: string; kycStatus: string; [k: string]: unknown };
  const customers: StubCustomer[] = [];
  const idFromPath = (u: string) => u.split('/customers/')[1]?.split(/[/?]/)[0];
  await page.route('**/baas/v1/customers**', async (route) => {
    const url = route.request().url();
    const method = route.request().method();
    if (method === 'POST' && url.endsWith('/customers')) {
      const body = route.request().postDataJSON();
      const c = { id: 'c1', ...body, kycStatus: 'PENDING_KYC', kycLevel: 'NONE', createdAt: 't', updatedAt: 't' };
      customers.push(c);
      return route.fulfill({ json: { data: c, meta: null, errors: null } });
    }
    if (url.includes('/activate')) {
      const target = customers.find((c) => c.id === idFromPath(url));
      if (target) target.kycStatus = 'ACTIVE';
      return route.fulfill({ json: { data: target, meta: null, errors: null } });
    }
    if (url.includes('/kyc-events')) {
      return route.fulfill({
        json: {
          data: [
            {
              id: 'e1',
              fromStatus: 'PENDING_KYC',
              toStatus: 'ACTIVE',
              reason: 'verified',
              changedBy: 'op',
              changedAt: '2026-06-10T10:00:00Z',
            },
          ],
          meta: null,
          errors: null,
        },
      });
    }
    const id = idFromPath(url);
    if (id) {
      return route.fulfill({ json: { data: customers.find((c) => c.id === id), meta: null, errors: null } });
    }
    return route.fulfill({
      json: {
        data: { content: customers, number: 0, size: 20, totalElements: customers.length, totalPages: 1 },
        meta: null,
        errors: null,
      },
    });
  });

  await page.goto('/customers');
  // ADAPTED (VERIFY #4): "Customers" renders both as a sidebar NavLink and the page
  // <h1>; getByText is ambiguous under strict mode, so target the heading role.
  await expect(page.getByRole('heading', { name: 'Customers' })).toBeVisible();

  await page.getByRole('button', { name: /new customer/i }).click();
  await page.getByLabel(/first name/i).fill('Grace');
  await page.getByLabel(/last name/i).fill('Hopper');
  // Create modal submit is the CommandModal default label "Save".
  await page.getByRole('button', { name: /save/i }).click();

  await page.getByRole('link', { name: /grace hopper/i }).click();
  // Activate button is gated by UPDATE_CUSTOMER — granted via the playwright
  // webServer env VITE_DEV_AUTHORITIES (VERIFY #2).
  await page.getByRole('button', { name: /activate/i }).click();
  await page.getByLabel(/reason/i).fill('verified');
  await page.getByRole('button', { name: /confirm/i }).click();

  // ADAPTED (VERIFY #3): KycHistory humanises both statuses (PENDING_KYC → "PENDING KYC").
  await expect(page.getByText('PENDING KYC → ACTIVE')).toBeVisible();
});
