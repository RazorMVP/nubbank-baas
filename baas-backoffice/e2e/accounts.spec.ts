import { test, expect } from '@playwright/test';

// Self-contained happy path: the engine is fully route-stubbed (no backend needed).
// open /accounts → open an account (picking a stubbed customer) → deposit → freeze →
// confirm Withdraw is gone while FROZEN → unfreeze → withdraw the deposit back to a
// zero balance → close. Mirrors customers.spec.ts: dev-auth grants the authorities via
// the playwright.config webServer env (VITE_DEV_AUTHORITIES).
//
// Why the withdraw-to-zero before close: the engine state machine (spec §6) only enables
// CLOSE on an ACTIVE account whose balance == 0, and account-detail.tsx mirrors that gate
// (the Close button is filtered out while balance != 0). So after the deposit, the funds
// must be withdrawn before the Close button renders — the same constraint a real operator
// hits. The frozen-withdraw-blocked assertion (button absent on FROZEN) is unchanged.
test('account lifecycle happy path', async ({ page }) => {
  type StubAccount = { id: string; accountNumber: string; status: string; balance: number;
    [k: string]: unknown };
  const account: StubAccount = { id: 'a1', accountNumber: '0123456789', customerId: 'c1',
    customerName: 'Ada Lovelace', accountTypeLabel: 'Savings', status: 'ACTIVE', balance: 0,
    availableBalance: 0, currencyCode: 'NGN', minimumBalance: 0, allowOverdraft: false,
    overdraftLimit: 0, openedAt: '2026-06-10T10:00:00Z' };
  let opened = false;

  // Customer search used by the open-account picker.
  await page.route('**/baas/v1/customers**', async (route) => {
    return route.fulfill({
      json: {
        data: { content: [{ id: 'c1', firstName: 'Ada', lastName: 'Lovelace', email: 'a@x.com',
          kycStatus: 'ACTIVE', kycLevel: 'TIER_1', externalReference: 'ext-1', createdAt: 't' }],
          number: 0, size: 20, totalElements: 1, totalPages: 1 },
        meta: null, errors: null,
      },
    });
  });

  await page.route('**/baas/v1/accounts**', async (route) => {
    const url = route.request().url();
    const method = route.request().method();

    // Open account.
    if (method === 'POST' && url.endsWith('/accounts')) {
      opened = true;
      return route.fulfill({ json: { data: account, meta: null, errors: null } });
    }
    // Money commands — mutate the shared stub state so the detail page refetches the new
    // balance (deposit credits, withdraw debits) exactly as the engine would.
    if (url.includes('/deposit')) {
      account.balance += 500; account.availableBalance = account.balance;
      return route.fulfill({ json: { data: { id: 't1', accountId: 'a1', transactionType: 'CREDIT',
        amount: 500, runningBalance: account.balance, currencyCode: 'NGN', reference: null,
        createdAt: 't' }, meta: null, errors: null } });
    }
    if (url.includes('/withdraw')) {
      account.balance = 0; account.availableBalance = 0;
      return route.fulfill({ json: { data: { id: 't2', accountId: 'a1', transactionType: 'DEBIT',
        amount: 500, runningBalance: 0, currencyCode: 'NGN', reference: null,
        createdAt: 't' }, meta: null, errors: null } });
    }
    // Lifecycle commands.
    if (url.includes('/freeze')) {
      account.status = 'FROZEN';
      return route.fulfill({ json: { data: account, meta: null, errors: null } });
    }
    if (url.includes('/unfreeze')) {
      account.status = 'ACTIVE';
      return route.fulfill({ json: { data: account, meta: null, errors: null } });
    }
    if (url.includes('/close')) {
      account.status = 'CLOSED';
      return route.fulfill({ json: { data: account, meta: null, errors: null } });
    }
    if (url.includes('/status-events')) {
      return route.fulfill({ json: { data: [], meta: null, errors: null } });
    }
    if (url.includes('/transactions')) {
      return route.fulfill({ json: { data: { content: [], number: 0, size: 20,
        totalElements: 0, totalPages: 0 }, meta: null, errors: null } });
    }
    // Detail GET /accounts/a1
    if (url.match(/\/accounts\/a1(\?|$)/)) {
      return route.fulfill({ json: { data: account, meta: null, errors: null } });
    }
    // List GET /accounts
    return route.fulfill({ json: { data: { content: opened ? [account] : [], number: 0, size: 20,
      totalElements: opened ? 1 : 0, totalPages: 1 }, meta: null, errors: null } });
  });

  await page.goto('/accounts');
  await expect(page.getByRole('heading', { name: 'Accounts' })).toBeVisible();

  // Open an account.
  await page.getByRole('button', { name: /open account/i }).click();
  await page.getByLabel(/find customer/i).fill('ada');
  await page.getByRole('button', { name: /ada lovelace/i }).click();
  // CommandModal submit label is the verb "Open account".
  await page.getByRole('button', { name: /open account/i }).last().click();

  // Open the detail page.
  await page.getByRole('link', { name: '0123456789' }).click();
  await expect(page.getByRole('heading', { name: '0123456789' })).toBeVisible();

  // Deposit.
  await page.getByRole('button', { name: /deposit/i }).click();
  await page.getByLabel(/amount/i).fill('500');
  await page.getByRole('button', { name: /^deposit$/i }).last().click();

  // Freeze → Withdraw must disappear (debits blocked on FROZEN), Unfreeze appears.
  await page.getByRole('button', { name: /freeze/i }).click();
  await page.getByLabel(/reason/i).fill('legal hold');
  await page.getByRole('button', { name: /confirm/i }).click();
  await expect(page.getByRole('button', { name: /unfreeze/i })).toBeVisible();
  await expect(page.getByRole('button', { name: /^withdraw$/i })).toHaveCount(0);

  // Unfreeze → back to ACTIVE → Withdraw is allowed again.
  await page.getByRole('button', { name: /unfreeze/i }).click();
  await page.getByLabel(/reason/i).fill('released');
  await page.getByRole('button', { name: /confirm/i }).click();
  await expect(page.getByRole('button', { name: /^withdraw$/i })).toBeVisible();

  // Withdraw the deposit so the balance returns to 0 — close is gated on a zero balance.
  await page.getByRole('button', { name: /^withdraw$/i }).click();
  await page.getByLabel(/amount/i).fill('500');
  await page.getByRole('button', { name: /^withdraw$/i }).last().click();

  // Zero balance → Close becomes available.
  await expect(page.getByRole('button', { name: /^close$/i })).toBeVisible();
  await page.getByRole('button', { name: /^close$/i }).click();
  await page.getByLabel(/reason/i).fill('customer request');
  await page.getByRole('button', { name: /confirm/i }).click();
  await expect(page.getByText('CLOSED')).toBeVisible();
});
