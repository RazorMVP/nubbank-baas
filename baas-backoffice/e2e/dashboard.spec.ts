import { test, expect } from '@playwright/test';

test('dashboard renders recent customers from the engine', async ({ page }) => {
  await page.route('**/baas/v1/customers**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          content: [
            { id: 'c1', firstName: 'Chidi', lastName: 'Okafor', kycStatus: 'VERIFIED' },
            { id: 'c2', firstName: 'Amaka', lastName: 'Eze', kycStatus: 'PENDING' },
          ],
          number: 0, size: 5, totalElements: 2, totalPages: 1,
        },
        meta: { requestId: 'r', timestamp: 't' },
        errors: null,
      }),
    });
  });

  await page.goto('/');
  await expect(page.getByText('Recent customers')).toBeVisible();
  await expect(page.getByText('Chidi Okafor')).toBeVisible();
  await expect(page.getByText('Amaka Eze')).toBeVisible();
});
