# Accounts Track — Frontend (baas-backoffice) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Accounts feature in the tenant backoffice — a filterable/searchable account list, a detail page with money-movement + lifecycle actions and an append-only status timeline + transaction ledger, an open-account modal with a customer picker, and deposit/withdraw/freeze/unfreeze/close modals — consuming the `baas-engine` account endpoints.

**Architecture:** New `src/features/accounts/` feature mirroring the just-shipped `customers/` track exactly (list + detail + modals). Data via TanStack Query hooks (`useApiClient` + `unwrapResult`/`extractPage` + `qk` keys, clean-query-object stripping undefined, the `as never` openapi-fetch seam unwrapped to real local types). Forms via the Foundation `CommandModal` + `FormField` (React Hook Form + Zod). Two routes wired into the existing router under `RequireAuth → AppShell`, gated by `RequireRoutePermission('READ_ACCOUNT')`. Lifecycle/money buttons render only when the operator holds the permission AND the wire status allows the action (`ACTIONS` map + `?? []` runtime guard). The open-account modal's customer picker reuses the existing `useCustomers` hook (debounced `GET /baas/v1/customers?search=`). One new permission code `UPDATE_ACCOUNT` is added to `src/lib/rbac.ts`.

**Tech Stack:** React 19, Vite 6, TypeScript 5, TanStack Query 5, TanStack Table 8, React Router 7, React Hook Form 7 + Zod 3, `openapi-fetch`, Vitest 3 + React Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-06-14-accounts-track-design.md`

**Backend contract:** `docs/superpowers/plans/2026-06-14-accounts-backend-baas-engine.md` (the endpoints this consumes). The frontend degrades gracefully if the backend isn't deployed (queries error → empty/error states). Endpoints consumed, verbatim:
- `GET /baas/v1/accounts?page&size&status&search` → `Page<AccountSummaryResponse>`
- `GET /baas/v1/accounts/{id}` → `AccountDetailResponse`
- `GET /baas/v1/accounts/{id}/status-events` → `List<AccountStatusEventResponse>`
- `GET /baas/v1/accounts/{id}/transactions?page&size` → `Page<TransactionResponse>`
- `POST /baas/v1/accounts` (body `OpenAccountRequest` incl. optional `openingDeposit`) → `AccountDetailResponse`
- `POST /baas/v1/accounts/{id}/deposit` and `/withdraw` (body `TransactionRequest`) → `TransactionResponse`
- `POST /baas/v1/accounts/{id}/freeze` | `/unfreeze` | `/close` (body `AccountTransitionRequest { reason }`) → `AccountDetailResponse`

**Backend DTO field names (verbatim — the local TS types must match these exactly):**
- `AccountSummaryResponse(id, accountNumber, customerId, customerName, accountTypeLabel, status, balance, currencyCode)`
- `AccountDetailResponse(id, accountNumber, customerId, customerName, accountTypeLabel, status, balance, availableBalance, currencyCode, minimumBalance, allowOverdraft, overdraftLimit, openedAt)`
- `AccountStatusEventResponse(id, fromStatus, toStatus, reason, changedBy, changedAt)`
- `TransactionResponse(id, accountId, transactionType, amount, runningBalance, currencyCode, reference, createdAt)` — note: **no `description`** in the response shape
- `TransactionRequest(amount, reference, description)` — deposit/withdraw body
- `OpenAccountRequest(customerId, accountTypeLabel, accountName, currencyCode, minimumBalance, openingDeposit)`
- `AccountTransitionRequest(reason)`

**Branch:** `feat/baas-backoffice-accounts` (off `main`). Ships as its own PR. Zero file overlap with the backend PR (`feat/baas-engine-accounts-lifecycle`).

---

## Pre-flight

- [ ] **Create branch**

```bash
cd ~/nubbank-baas
git checkout main && git pull --ff-only origin main
git checkout -b feat/baas-backoffice-accounts
cd baas-backoffice && npm ci
```

## Foundation primitives this feature reuses (do NOT rebuild)

These are the real shipped contracts — read them before coding so the plan's prop usage is exact:

- `CommandModal<T extends FieldValues>({ open, onOpenChange, title, schema, defaultValues, onSubmit, submitLabel?, children: (form) => ReactNode })` — `src/components/command-modal.tsx`. `submitLabel` defaults to `'Save'`. **Does not reset the form on reopen** → always conditionally mount (`{open && <Modal open … />}`).
- `FormField({ label, error?, children: <single input element> })` — `src/components/form-field.tsx`. Clones its single child and injects `id` so `<label htmlFor>` + the input are wired for `getByLabelText`.
- `DataTable<TData,TValue>({ columns, data, emptyMessage? })` — `src/components/data-table.tsx`.
- `StatusBadge({ label, variant?: 'success'|'warning'|'danger'|'info'|'neutral', className? })` — `src/components/status-badge.tsx`. Pass `label` + `variant`; never `status`.
- `PageHeader({ title, action? })` — `src/components/page-header.tsx`. Renders an `<h1>` (role `heading`).
- `RequirePermission({ code, children, fallback? })` — `src/components/require-permission.tsx`. Renders children only when the operator holds `code`. Use this **component** for gating UI — do NOT call `hasPermission` + `useAuth` inline.
- `useApiClient()` → typed `openapi-fetch` client — `src/api/context.tsx` (provider: `ApiClientProvider`).
- `unwrapResult<T>(result)`, `extractPage<T>(page)`, `Page<T>`, `NormalizedPage<T>`, `ApiError` — `src/api/envelope.ts`.
- `qk.list(domain, params?)`, `qk.detail(domain, id)` — `src/api/query.ts`. `qk.list` returns `[domain, 'list', params]`; `qk.detail` returns `[domain, 'detail', id]`.
- `PERMISSIONS`, `hasPermission(authorities, code)` — `src/lib/rbac.ts`.
- `humanizeStatus(status)` (`_`→space), `formatDateTime(iso)` (en-GB short date+time) — `src/lib/format.ts`.
- `RequireRoutePermission`, `RequireAuth` — `src/app/guards.tsx`.
- `Input` — `src/components/ui/input.tsx`; `Button` — `src/components/ui/button.tsx`.
- `useCustomers(params)` — `src/features/customers/use-customers.ts` (reused by the open-account customer picker; returns `NormalizedPage<CustomerRow>`).

## Hook-test harness (referenced by every hook test below)

Mirrors `src/features/customers/use-customers.test.tsx` exactly:

```tsx
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { ApiClientProvider } from '@/api/context';

function makeWrapper(getResult: unknown) {
  const client = {
    GET: vi.fn(async () => getResult),
    POST: vi.fn(async () => getResult),
    PUT: vi.fn(async () => getResult),
  };
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <ApiClientProvider client={client as never}>
      <QueryClientProvider client={qc}>{children}</QueryClientProvider>
    </ApiClientProvider>
  );
  return { Wrapper, client };
}

// A 2xx openapi-fetch result with an envelope body:
const ok = (data: unknown) => ({
  data: { data, meta: { requestId: 'r', timestamp: 't' }, errors: null },
  error: undefined,
  response: new Response(null, { status: 200 }),
});
```

## Cast-free rule

Production code is **cast-free except the single established openapi-fetch `as never` seam** (the same one `use-customers.ts` uses on `client.GET/POST/PUT` inputs). Every `unwrapResult<T>` / `extractPage<T>` result is typed to a real local interface (`AccountDetail`, `AccountSummaryResponse`-derived `AccountRow`, etc.), which the tests assert against. List-page `clean*` helpers assemble the request body field-by-field so the return is a real typed body with no cast (mirrors `cleanCreate` in `customers-list.tsx`).

---

## Task 1: Types + read queries (`use-accounts.ts`)

**Files:**
- Modify: `src/api/schema.d.ts` (hand-seed the new account paths)
- Create: `src/features/accounts/use-accounts.ts`
- Test: `src/features/accounts/use-accounts.test.tsx`

- [ ] **Step 1: Hand-seed `schema.d.ts`**

Open `src/api/schema.d.ts`. Mirror the existing customer path entries (their shape is `parameters` + `responses: { 200: { content: { 'application/json': unknown } } }`, request bodies as `requestBody: { content: { 'application/json': unknown } }`). Insert the account paths **before** the `'/baas/v1/dashboard/summary'` entry. Each entry only needs to be present and accept the params/bodies — the hooks unwrap responses to local interfaces, so `unknown` content is correct (exactly as the customer paths do).

```ts
  '/baas/v1/accounts': {
    get: {
      parameters: { query?: { page?: number; size?: number; status?: string; search?: string } };
      responses: {
        200: { content: { 'application/json': unknown } };
      };
    };
    post: {
      requestBody: { content: { 'application/json': unknown } };
      responses: {
        200: { content: { 'application/json': unknown } };
      };
    };
  };
  '/baas/v1/accounts/{id}': {
    get: {
      parameters: { path: { id: string } };
      responses: {
        200: { content: { 'application/json': unknown } };
      };
    };
  };
  '/baas/v1/accounts/{id}/status-events': {
    get: {
      parameters: { path: { id: string } };
      responses: {
        200: { content: { 'application/json': unknown } };
      };
    };
  };
  '/baas/v1/accounts/{id}/transactions': {
    get: {
      parameters: { path: { id: string }; query?: { page?: number; size?: number } };
      responses: {
        200: { content: { 'application/json': unknown } };
      };
    };
  };
  '/baas/v1/accounts/{id}/deposit': {
    post: {
      parameters: { path: { id: string } };
      requestBody: { content: { 'application/json': unknown } };
      responses: {
        200: { content: { 'application/json': unknown } };
      };
    };
  };
  '/baas/v1/accounts/{id}/withdraw': {
    post: {
      parameters: { path: { id: string } };
      requestBody: { content: { 'application/json': unknown } };
      responses: {
        200: { content: { 'application/json': unknown } };
      };
    };
  };
  '/baas/v1/accounts/{id}/freeze': {
    post: {
      parameters: { path: { id: string } };
      requestBody: { content: { 'application/json': unknown } };
      responses: {
        200: { content: { 'application/json': unknown } };
      };
    };
  };
  '/baas/v1/accounts/{id}/unfreeze': {
    post: {
      parameters: { path: { id: string } };
      requestBody: { content: { 'application/json': unknown } };
      responses: {
        200: { content: { 'application/json': unknown } };
      };
    };
  };
  '/baas/v1/accounts/{id}/close': {
    post: {
      parameters: { path: { id: string } };
      requestBody: { content: { 'application/json': unknown } };
      responses: {
        200: { content: { 'application/json': unknown } };
      };
    };
  };
```

- [ ] **Step 2: Write the failing hook test**

```tsx
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import {
  useAccounts,
  useAccount,
  useAccountStatusEvents,
  useAccountTransactions,
} from './use-accounts';
import { ApiClientProvider } from '@/api/context';
import { ApiError } from '@/api/envelope';

function makeWrapper(getResult: unknown) {
  const client = {
    GET: vi.fn(async () => getResult),
    POST: vi.fn(async () => getResult),
    PUT: vi.fn(async () => getResult),
  };
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <ApiClientProvider client={client as never}>
      <QueryClientProvider client={qc}>{children}</QueryClientProvider>
    </ApiClientProvider>
  );
  return { Wrapper, client };
}
const ok = (data: unknown) => ({
  data: { data, meta: { requestId: 'r', timestamp: 't' }, errors: null },
  error: undefined,
  response: new Response(null, { status: 200 }),
});

describe('useAccounts', () => {
  it('normalizes the page and passes status+search params (undefined stripped)', async () => {
    const { Wrapper, client } = makeWrapper({
      data: {
        data: {
          content: [{ id: 'a1', accountNumber: '0123456789', customerId: 'c1',
            customerName: 'Ada Lovelace', accountTypeLabel: 'Savings', status: 'ACTIVE',
            balance: 0, currencyCode: 'NGN' }],
          number: 0, size: 20, totalElements: 1, totalPages: 1,
        },
        meta: null, errors: null,
      },
      error: undefined, response: new Response(null, { status: 200 }),
    });
    const { result } = renderHook(
      () => useAccounts({ page: 0, size: 20, status: 'ACTIVE', search: '012' }),
      { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.items[0].customerName).toBe('Ada Lovelace');
    expect(client.GET).toHaveBeenCalledWith('/baas/v1/accounts',
      { params: { query: { page: 0, size: 20, status: 'ACTIVE', search: '012' } } });
  });

  it('omits status+search from the query when undefined', async () => {
    const { Wrapper, client } = makeWrapper({
      data: { data: { content: [], number: 0, size: 20, totalElements: 0, totalPages: 0 },
        meta: null, errors: null },
      error: undefined, response: new Response(null, { status: 200 }),
    });
    const { result } = renderHook(() => useAccounts({ page: 0, size: 20 }), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(client.GET).toHaveBeenCalledWith('/baas/v1/accounts',
      { params: { query: { page: 0, size: 20 } } });
  });
});

describe('useAccount', () => {
  it('unwraps the detail', async () => {
    const { Wrapper } = makeWrapper(ok({ id: 'a1', accountNumber: '0123456789',
      customerId: 'c1', customerName: 'Ada Lovelace', accountTypeLabel: 'Savings',
      status: 'FROZEN', balance: 500, availableBalance: 500, currencyCode: 'NGN',
      minimumBalance: 0, allowOverdraft: false, overdraftLimit: 0, openedAt: 't' }));
    const { result } = renderHook(() => useAccount('a1'), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.status).toBe('FROZEN');
    expect(result.current.data?.availableBalance).toBe(500);
  });

  it('surfaces ApiError on a 403 error envelope', async () => {
    const { Wrapper } = makeWrapper({
      data: undefined,
      error: { data: null, meta: null,
        errors: [{ code: 'ERR_FORBIDDEN', message: 'denied', field: null, docsUrl: null }] },
      response: new Response(null, { status: 403 }),
    });
    const { result } = renderHook(() => useAccount('a1'), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect((result.current.error as ApiError).code).toBe('ERR_FORBIDDEN');
  });
});

describe('useAccountStatusEvents', () => {
  it('unwraps the event list', async () => {
    const { Wrapper } = makeWrapper(ok([{ id: 'e1', fromStatus: 'ACTIVE', toStatus: 'FROZEN',
      reason: 'legal hold', changedBy: 'op', changedAt: 't' }]));
    const { result } = renderHook(() => useAccountStatusEvents('a1'), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.[0].toStatus).toBe('FROZEN');
  });
});

describe('useAccountTransactions', () => {
  it('normalizes the transaction page and passes pagination', async () => {
    const { Wrapper, client } = makeWrapper({
      data: { data: { content: [{ id: 't1', accountId: 'a1', transactionType: 'CREDIT',
        amount: 2500, runningBalance: 2500, currencyCode: 'NGN', reference: 'OPENING_DEPOSIT',
        createdAt: 't' }], number: 0, size: 20, totalElements: 1, totalPages: 1 },
        meta: null, errors: null },
      error: undefined, response: new Response(null, { status: 200 }),
    });
    const { result } = renderHook(() => useAccountTransactions('a1', 0, 20), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.items[0].transactionType).toBe('CREDIT');
    expect(client.GET).toHaveBeenCalledWith('/baas/v1/accounts/{id}/transactions',
      { params: { path: { id: 'a1' }, query: { page: 0, size: 20 } } });
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd baas-backoffice && npx vitest run src/features/accounts/use-accounts.test.tsx`
Expected: FAIL — `use-accounts.ts` does not exist (module not found).

- [ ] **Step 4: Implement `use-accounts.ts` (types + queries)**

```ts
import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '@/api/context';
import { qk } from '@/api/query';
import { unwrapResult, extractPage, type Page, type NormalizedPage } from '@/api/envelope';

export type AccountStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED';

// Mirrors backend AccountSummaryResponse(id, accountNumber, customerId, customerName,
// accountTypeLabel, status, balance, currencyCode).
export interface AccountRow {
  id: string;
  accountNumber: string;
  customerId: string;
  customerName: string;
  accountTypeLabel: string;
  status: AccountStatus;
  balance: number;
  currencyCode: string;
}

// Mirrors backend AccountDetailResponse — adds availableBalance, minimumBalance,
// allowOverdraft, overdraftLimit, openedAt.
export interface AccountDetail {
  id: string;
  accountNumber: string;
  customerId: string;
  customerName: string;
  accountTypeLabel: string;
  status: AccountStatus;
  balance: number;
  availableBalance: number;
  currencyCode: string;
  minimumBalance: number;
  allowOverdraft: boolean;
  overdraftLimit: number;
  openedAt: string;
}

// Mirrors backend TransactionResponse — note: no `description` in the response shape.
export interface AccountTransaction {
  id: string;
  accountId: string;
  transactionType: 'CREDIT' | 'DEBIT';
  amount: number;
  runningBalance: number;
  currencyCode: string;
  reference: string | null;
  createdAt: string;
}

// Mirrors backend AccountStatusEventResponse.
export interface StatusEvent {
  id: string;
  fromStatus: string;
  toStatus: string;
  reason: string;
  changedBy: string | null;
  changedAt: string;
}

export interface AccountListParams {
  page: number;
  size: number;
  status?: AccountStatus;
  search?: string;
}

export function useAccounts(params: AccountListParams) {
  const client = useApiClient();
  // Build a clean query object so undefined status/search are not sent as `undefined`
  // (mirrors useCustomers). page/size are always present.
  const query: Record<string, unknown> = { page: params.page, size: params.size };
  if (params.status) query.status = params.status;
  if (params.search) query.search = params.search;
  return useQuery<NormalizedPage<AccountRow>>({
    queryKey: qk.list('accounts', query),
    queryFn: async () => {
      const result = await client.GET('/baas/v1/accounts', { params: { query } } as never);
      return extractPage(unwrapResult<Page<AccountRow>>(result));
    },
  });
}

export function useAccount(id: string) {
  const client = useApiClient();
  return useQuery<AccountDetail>({
    queryKey: qk.detail('accounts', id),
    queryFn: async () => {
      const result = await client.GET('/baas/v1/accounts/{id}', { params: { path: { id } } } as never);
      return unwrapResult<AccountDetail>(result);
    },
  });
}

export function useAccountStatusEvents(id: string) {
  const client = useApiClient();
  return useQuery<StatusEvent[]>({
    queryKey: [...qk.detail('accounts', id), 'status-events'],
    queryFn: async () => {
      const result = await client.GET('/baas/v1/accounts/{id}/status-events',
        { params: { path: { id } } } as never);
      return unwrapResult<StatusEvent[]>(result);
    },
  });
}

export function useAccountTransactions(id: string, page = 0, size = 20) {
  const client = useApiClient();
  return useQuery<NormalizedPage<AccountTransaction>>({
    queryKey: [...qk.detail('accounts', id), 'transactions', page, size],
    queryFn: async () => {
      const result = await client.GET('/baas/v1/accounts/{id}/transactions',
        { params: { path: { id }, query: { page, size } } } as never);
      return extractPage(unwrapResult<Page<AccountTransaction>>(result));
    },
  });
}
```

- [ ] **Step 5: Run test + typecheck to verify pass**

Run: `cd baas-backoffice && npx vitest run src/features/accounts/use-accounts.test.tsx && npm run typecheck`
Expected: PASS / clean.

- [ ] **Step 6: Commit**

```bash
git add src/api/schema.d.ts src/features/accounts/use-accounts.ts \
        src/features/accounts/use-accounts.test.tsx
git commit -m "feat(backoffice): account read hooks + schema types

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Mutation hooks (open / deposit / withdraw / lifecycle transition)

**Files:**
- Modify: `src/features/accounts/use-accounts.ts`
- Modify: `src/features/accounts/use-accounts.test.tsx`

- [ ] **Step 1: Write the failing test** (append to `use-accounts.test.tsx`)

```tsx
import {
  useOpenAccount,
  useDeposit,
  useWithdraw,
  useAccountTransition,
} from './use-accounts';

describe('useOpenAccount', () => {
  it('POSTs the open body', async () => {
    const { Wrapper, client } = makeWrapper(ok({ id: 'a1', status: 'ACTIVE' }));
    const { result } = renderHook(() => useOpenAccount(), { wrapper: Wrapper });
    await result.current.mutateAsync({ customerId: 'c1', accountTypeLabel: 'Savings',
      currencyCode: 'NGN', openingDeposit: 2500 });
    expect(client.POST).toHaveBeenCalledWith('/baas/v1/accounts',
      { body: { customerId: 'c1', accountTypeLabel: 'Savings', currencyCode: 'NGN',
        openingDeposit: 2500 } });
  });
});

describe('useDeposit', () => {
  it('POSTs the deposit body to the deposit path', async () => {
    const { Wrapper, client } = makeWrapper(ok({ id: 't1', transactionType: 'CREDIT' }));
    const { result } = renderHook(() => useDeposit('a1'), { wrapper: Wrapper });
    await result.current.mutateAsync({ amount: 500, reference: 'cash-in' });
    expect(client.POST).toHaveBeenCalledWith('/baas/v1/accounts/{id}/deposit',
      { params: { path: { id: 'a1' } }, body: { amount: 500, reference: 'cash-in' } });
  });
});

describe('useWithdraw', () => {
  it('POSTs the withdraw body to the withdraw path', async () => {
    const { Wrapper, client } = makeWrapper(ok({ id: 't2', transactionType: 'DEBIT' }));
    const { result } = renderHook(() => useWithdraw('a1'), { wrapper: Wrapper });
    await result.current.mutateAsync({ amount: 100 });
    expect(client.POST).toHaveBeenCalledWith('/baas/v1/accounts/{id}/withdraw',
      { params: { path: { id: 'a1' } }, body: { amount: 100 } });
  });
});

describe('useAccountTransition', () => {
  it('POSTs the freeze command path with a reason', async () => {
    const { Wrapper, client } = makeWrapper(ok({ id: 'a1', status: 'FROZEN' }));
    const { result } = renderHook(() => useAccountTransition('a1'), { wrapper: Wrapper });
    await result.current.mutateAsync({ command: 'freeze', reason: 'legal hold' });
    expect(client.POST).toHaveBeenCalledWith('/baas/v1/accounts/{id}/freeze',
      { params: { path: { id: 'a1' } }, body: { reason: 'legal hold' } });
  });

  it('POSTs the close command path', async () => {
    const { Wrapper, client } = makeWrapper(ok({ id: 'a1', status: 'CLOSED' }));
    const { result } = renderHook(() => useAccountTransition('a1'), { wrapper: Wrapper });
    await result.current.mutateAsync({ command: 'close', reason: 'customer request' });
    expect(client.POST).toHaveBeenCalledWith('/baas/v1/accounts/{id}/close',
      { params: { path: { id: 'a1' } }, body: { reason: 'customer request' } });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-backoffice && npx vitest run src/features/accounts/use-accounts.test.tsx`
Expected: FAIL — mutation hooks not exported.

- [ ] **Step 3: Implement (append to `use-accounts.ts`)**

Add `useMutation, useQueryClient` to the top import:

```ts
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
```

Then append:

```ts
// ─── Mutation hooks ────────────────────────────────────────────────────────

// Mirrors backend OpenAccountRequest. accountName/minimumBalance are accepted by the
// backend but the open-account modal (spec §10 YAGNI) only sends the four fields below.
export interface OpenAccountBody {
  customerId: string;
  accountTypeLabel: string;
  currencyCode?: string;
  openingDeposit?: number;
}

export function useOpenAccount() {
  const client = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: OpenAccountBody) => {
      const result = await client.POST('/baas/v1/accounts', { body } as never);
      return unwrapResult<AccountDetail>(result);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['accounts', 'list'] }),
  });
}

// Mirrors backend TransactionRequest. reference/description are optional.
export interface MoneyBody {
  amount: number;
  reference?: string;
  description?: string;
}

export function useDeposit(id: string) {
  const client = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: MoneyBody) => {
      const result = await client.POST('/baas/v1/accounts/{id}/deposit',
        { params: { path: { id } }, body } as never);
      return unwrapResult<AccountTransaction>(result);
    },
    onSuccess: () => invalidateAccount(qc, id),
  });
}

export function useWithdraw(id: string) {
  const client = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: MoneyBody) => {
      const result = await client.POST('/baas/v1/accounts/{id}/withdraw',
        { params: { path: { id } }, body } as never);
      return unwrapResult<AccountTransaction>(result);
    },
    onSuccess: () => invalidateAccount(qc, id),
  });
}

export type AccountCommand = 'freeze' | 'unfreeze' | 'close';

export function useAccountTransition(id: string) {
  const client = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ command, reason }: { command: AccountCommand; reason: string }) => {
      // Path built from the AccountCommand union — all three commands share the same shape
      // (path param + { reason } body). The backend requires a non-blank reason on every
      // transition (400 otherwise), so `reason` is intentionally required, not optional.
      const result = await client.POST(`/baas/v1/accounts/{id}/${command}` as never,
        { params: { path: { id } }, body: { reason } } as never);
      return unwrapResult<AccountDetail>(result);
    },
    onSuccess: () => invalidateAccount(qc, id),
  });
}

// Money + lifecycle mutations all change the same account's detail, ledger, status
// timeline, and its row in the list — invalidate all four so the UI refetches.
function invalidateAccount(
  qc: ReturnType<typeof useQueryClient>,
  id: string,
): void {
  qc.invalidateQueries({ queryKey: qk.detail('accounts', id) });
  qc.invalidateQueries({ queryKey: ['accounts', 'list'] });
}
```

> `invalidateQueries({ queryKey: qk.detail('accounts', id) })` matches `['accounts','detail',id]` and — because TanStack Query matches by key **prefix** — also invalidates the status-events key `['accounts','detail',id,'status-events']` and the transactions key `['accounts','detail',id,'transactions',page,size]`. So a single `qk.detail` invalidation refreshes detail + status timeline + ledger together; the extra `['accounts','list']` line refreshes the row in the list.

- [ ] **Step 4: Run test + typecheck**

Run: `cd baas-backoffice && npx vitest run src/features/accounts/use-accounts.test.tsx && npm run typecheck`
Expected: PASS / clean.

- [ ] **Step 5: Commit**

```bash
git add src/features/accounts/use-accounts.ts src/features/accounts/use-accounts.test.tsx
git commit -m "feat(backoffice): account open/deposit/withdraw/lifecycle-transition mutation hooks

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Open-account form (Zod schema) + open-account modal with customer picker

**Files:**
- Create: `src/features/accounts/account-form.ts` (Zod schema)
- Create: `src/features/accounts/open-account-modal.tsx`
- Test: `src/features/accounts/open-account-modal.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { OpenAccountModal } from './open-account-modal';
import { ApiClientProvider } from '@/api/context';

// The modal's customer picker calls useCustomers → client.GET('/baas/v1/customers').
function wrap(customers: unknown[]) {
  const client = {
    GET: vi.fn(async () => ({
      data: { data: { content: customers, number: 0, size: 20,
        totalElements: customers.length, totalPages: 1 }, meta: null, errors: null },
      error: undefined, response: new Response(null, { status: 200 }),
    })),
    POST: vi.fn(),
    PUT: vi.fn(),
  } as never;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => (
    <ApiClientProvider client={client}>
      <QueryClientProvider client={qc}>{children}</QueryClientProvider>
    </ApiClientProvider>
  );
}

const CUSTOMER = { id: 'c1', firstName: 'Ada', lastName: 'Lovelace', email: 'a@x.com',
  kycStatus: 'ACTIVE', kycLevel: 'TIER_1', externalReference: 'ext-1', createdAt: 't' };

describe('OpenAccountModal', () => {
  it('blocks submit until a customer is picked', async () => {
    const onSubmit = vi.fn(async () => {});
    const Wrapper = wrap([CUSTOMER]);
    render(<OpenAccountModal open onOpenChange={() => {}} onSubmit={onSubmit} />,
      { wrapper: Wrapper });
    await userEvent.click(screen.getByRole('button', { name: /open account/i }));
    expect(await screen.findByText(/select a customer/i)).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('submits customerId + type + currency after picking a customer', async () => {
    const onSubmit = vi.fn(async () => {});
    const Wrapper = wrap([CUSTOMER]);
    render(<OpenAccountModal open onOpenChange={() => {}} onSubmit={onSubmit} />,
      { wrapper: Wrapper });

    // Search → the customer result appears → click to select it.
    await userEvent.type(screen.getByLabelText(/find customer/i), 'ada');
    await userEvent.click(await screen.findByRole('button', { name: /ada lovelace/i }));

    await userEvent.click(screen.getByRole('button', { name: /open account/i }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ customerId: 'c1', accountTypeLabel: 'Savings', currencyCode: 'NGN' }),
      expect.anything());
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-backoffice && npx vitest run src/features/accounts/open-account-modal.test.tsx`
Expected: FAIL — component does not exist.

- [ ] **Step 3: Implement**

`account-form.ts`:

```ts
import { z } from 'zod';

export const openAccountSchema = z.object({
  customerId: z.string().min(1, 'Select a customer'),
  accountTypeLabel: z.string().min(1, 'Account type is required'),
  currencyCode: z.string().min(1).default('NGN'),
  // optional opening deposit; coerce the text input to a number, must be >= 0 when present.
  openingDeposit: z.coerce.number().min(0, 'Opening deposit cannot be negative').optional(),
});

export type OpenAccountValues = z.infer<typeof openAccountSchema>;
```

`open-account-modal.tsx`:

```tsx
import { useState } from 'react';
import { CommandModal } from '@/components/command-modal';
import { FormField } from '@/components/form-field';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { useCustomers, type CustomerRow } from '@/features/customers/use-customers';
import { openAccountSchema, type OpenAccountValues } from './account-form';

const ACCOUNT_TYPES = ['Savings', 'Checking', 'Current'];
const CURRENCIES = ['NGN', 'USD', 'GHS'];

export function OpenAccountModal({
  open,
  onOpenChange,
  onSubmit,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (values: OpenAccountValues) => void | Promise<void>;
}) {
  return (
    <CommandModal<OpenAccountValues>
      open={open}
      onOpenChange={onOpenChange}
      title="Open account"
      submitLabel="Open account"
      schema={openAccountSchema}
      defaultValues={{
        customerId: '',
        accountTypeLabel: 'Savings',
        currencyCode: 'NGN',
        openingDeposit: undefined,
      }}
      onSubmit={onSubmit}
    >
      {(form) => (
        <>
          <CustomerPicker
            error={form.formState.errors.customerId?.message}
            onPick={(c) => form.setValue('customerId', c.id, { shouldValidate: true })}
          />
          <FormField label="Account type" error={form.formState.errors.accountTypeLabel?.message}>
            <select {...form.register('accountTypeLabel')}>
              {ACCOUNT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>
          </FormField>
          <FormField label="Currency" error={form.formState.errors.currencyCode?.message}>
            <select {...form.register('currencyCode')}>
              {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
          </FormField>
          <FormField label="Opening deposit (optional)"
            error={form.formState.errors.openingDeposit?.message}>
            <Input type="number" step="0.01" min="0" {...form.register('openingDeposit')} />
          </FormField>
        </>
      )}
    </CommandModal>
  );
}

// Debounced customer search box. Reuses useCustomers (GET /baas/v1/customers?search=).
// The selected customer's name is tracked in local state (UI-only); picking a result
// reports the id up to the form via onPick. No schema field for the name → no cast.
function CustomerPicker({
  onPick,
  error,
}: {
  onPick: (c: CustomerRow) => void;
  error?: string;
}) {
  const [term, setTerm] = useState('');
  const [selectedName, setSelectedName] = useState<string | null>(null);
  // Only search once 2+ chars are typed; keeps the dropdown empty initially.
  const query = useCustomers({ page: 0, size: 5, search: term.length >= 2 ? term : undefined });
  return (
    <FormField label="Find customer" error={error}>
      <div>
        <Input placeholder="Search customer name…" value={term}
          onChange={(e) => setTerm(e.target.value)} />
        {selectedName && (
          <p className="mt-1 text-xs text-muted">Selected: {selectedName}</p>
        )}
        {term.length >= 2 && (
          <ul className="mt-1 space-y-1">
            {(query.data?.items ?? []).map((c) => (
              <li key={c.id}>
                <Button type="button" variant="outline" className="w-full justify-start"
                  onClick={() => {
                    onPick(c);
                    setSelectedName(`${c.firstName} ${c.lastName}`);
                    setTerm('');
                  }}>
                  {c.firstName} {c.lastName}
                </Button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </FormField>
  );
}
```

> `FormField` clones a **single** child element and injects `id`; `CustomerPicker` wraps its `Input` + results list in one `<div>` so the child count is exactly one. The selected customer name is kept in `CustomerPicker`'s own `useState` (UI-only) — there is **no** `as never` here and no unschemed RHF field. Picking a result calls `form.setValue('customerId', c.id, { shouldValidate: true })`, so the body sent to `useOpenAccount` is exactly `{ customerId, accountTypeLabel, currencyCode, openingDeposit? }`. `openingDeposit` defaults to `undefined`; `z.coerce.number()` only runs when the field has a value, so an untouched opening-deposit submits without it (the list-page `cleanOpen` helper then omits it when not positive).

- [ ] **Step 4: Run test + typecheck**

Run: `cd baas-backoffice && npx vitest run src/features/accounts/open-account-modal.test.tsx && npm run typecheck`
Expected: PASS / clean.

- [ ] **Step 5: Commit**

```bash
git add src/features/accounts/account-form.ts src/features/accounts/open-account-modal.tsx \
        src/features/accounts/open-account-modal.test.tsx
git commit -m "feat(backoffice): open-account modal with debounced customer picker (RHF + Zod)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Accounts list page + status badge

**Files:**
- Create: `src/features/accounts/account-status-badge.tsx` (status → StatusBadge variant)
- Create: `src/features/accounts/accounts-list.tsx`
- Test: `src/features/accounts/accounts-list.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { AccountsList } from './accounts-list';
import { ApiClientProvider } from '@/api/context';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

function wrap(getResult: unknown, authorities: string[] = ['READ_ACCOUNT', 'CREATE_ACCOUNT']) {
  const client = { GET: vi.fn(async () => getResult), POST: vi.fn() } as never;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const auth = createDevAuthProvider({ token: 't', authorities, user: null });
  return ({ children }: { children: ReactNode }) => (
    <AuthContextProvider provider={auth}>
      <ApiClientProvider client={client}>
        <QueryClientProvider client={qc}>
          <MemoryRouter>{children}</MemoryRouter>
        </QueryClientProvider>
      </ApiClientProvider>
    </AuthContextProvider>
  );
}

const pageOf = (rows: unknown[]) => ({
  data: { data: { content: rows, number: 0, size: 20, totalElements: rows.length, totalPages: 1 },
    meta: null, errors: null },
  error: undefined, response: new Response(null, { status: 200 }),
});

const ROW = { id: 'a1', accountNumber: '0123456789', customerId: 'c1',
  customerName: 'Ada Lovelace', accountTypeLabel: 'Savings', status: 'ACTIVE',
  balance: 2500, currencyCode: 'NGN' };

describe('AccountsList', () => {
  it('renders account rows (number link + customer name)', async () => {
    const Wrapper = wrap(pageOf([ROW]));
    render(<AccountsList />, { wrapper: Wrapper });
    expect(await screen.findByRole('link', { name: '0123456789' })).toBeInTheDocument();
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument();
  });

  it('shows the Open account button when permitted', async () => {
    const Wrapper = wrap(pageOf([]));
    render(<AccountsList />, { wrapper: Wrapper });
    expect(await screen.findByRole('button', { name: /open account/i })).toBeInTheDocument();
  });

  it('hides the Open account button without CREATE_ACCOUNT', async () => {
    const Wrapper = wrap(pageOf([]), ['READ_ACCOUNT']);
    render(<AccountsList />, { wrapper: Wrapper });
    expect(await screen.findByRole('heading', { name: /accounts/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /open account/i })).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-backoffice && npx vitest run src/features/accounts/accounts-list.test.tsx`
Expected: FAIL — component does not exist.

- [ ] **Step 3: Implement**

`account-status-badge.tsx`:

```tsx
import { StatusBadge, type StatusVariant } from '@/components/status-badge';
import { humanizeStatus } from '@/lib/format';
import type { AccountStatus } from './use-accounts';

const VARIANT: Record<AccountStatus, StatusVariant> = {
  ACTIVE: 'success',
  FROZEN: 'warning',
  CLOSED: 'neutral',
};

export function AccountStatusBadge({ status }: { status: AccountStatus }) {
  return <StatusBadge label={humanizeStatus(status)} variant={VARIANT[status]} />;
}
```

`accounts-list.tsx`:

```tsx
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { createColumnHelper } from '@tanstack/react-table';
import { DataTable } from '@/components/data-table';
import { PageHeader } from '@/components/page-header';
import { RequirePermission } from '@/components/require-permission';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { PERMISSIONS } from '@/lib/rbac';
import { humanizeStatus } from '@/lib/format';
import {
  useAccounts,
  useOpenAccount,
  type AccountRow,
  type AccountStatus,
  type OpenAccountBody,
} from './use-accounts';
import { AccountStatusBadge } from './account-status-badge';
import { OpenAccountModal } from './open-account-modal';
import type { OpenAccountValues } from './account-form';

const STATUSES: Array<AccountStatus | ''> = ['', 'ACTIVE', 'FROZEN', 'CLOSED'];

const col = createColumnHelper<AccountRow>();
// All display() columns so every TValue is unknown — a typed accessor would make the
// helper array heterogeneous in TValue, which TanStack can't unify to one ColumnDef
// (same reasoning as customers-list.tsx).
const columns = [
  col.display({
    id: 'accountNumber',
    header: 'Account #',
    cell: (ctx) => (
      <Link to={`/accounts/${ctx.row.original.id}`} className="font-medium text-brand-primary">
        {ctx.row.original.accountNumber}
      </Link>
    ),
  }),
  col.display({ id: 'customer', header: 'Customer', cell: (ctx) => ctx.row.original.customerName }),
  col.display({ id: 'type', header: 'Type', cell: (ctx) => ctx.row.original.accountTypeLabel }),
  col.display({
    id: 'status',
    header: 'Status',
    cell: (ctx) => <AccountStatusBadge status={ctx.row.original.status} />,
  }),
  col.display({
    id: 'balance',
    header: 'Balance',
    cell: (ctx) => `${ctx.row.original.currencyCode} ${ctx.row.original.balance}`,
  }),
];

export function AccountsList() {
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<AccountStatus | ''>('');
  const [openOpen, setOpenOpen] = useState(false);
  const open = useOpenAccount();

  const query = useAccounts({
    page: 0,
    size: 20,
    search: search || undefined,
    status: status || undefined,
  });

  return (
    <div className="space-y-4">
      <PageHeader
        title="Accounts"
        action={
          <RequirePermission code={PERMISSIONS.CREATE_ACCOUNT}>
            <Button onClick={() => setOpenOpen(true)}>Open account</Button>
          </RequirePermission>
        }
      />
      <div className="flex gap-2">
        <Input
          placeholder="Search account number…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="max-w-xs"
        />
        <select
          aria-label="Filter by status"
          value={status}
          onChange={(e) => setStatus(e.target.value as AccountStatus | '')}
          className="h-9 rounded-[var(--radius-control)] border border-border bg-surface px-3 text-sm shadow-sm"
        >
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s ? humanizeStatus(s) : 'All statuses'}
            </option>
          ))}
        </select>
      </div>
      {query.isError ? (
        <p className="px-4 py-10 text-center text-sm text-danger">Couldn't load accounts.</p>
      ) : (
        <DataTable
          columns={columns}
          data={query.data?.items ?? []}
          emptyMessage={query.isLoading ? 'Loading…' : 'No accounts'}
        />
      )}
      {openOpen && (
        <OpenAccountModal
          open
          onOpenChange={setOpenOpen}
          onSubmit={async (v) => {
            await open.mutateAsync(cleanOpen(v));
            setOpenOpen(false);
          }}
        />
      )}
    </div>
  );
}

// Assemble the open-account body field-by-field so the return is a real OpenAccountBody —
// no cast. openingDeposit is only sent when the operator entered a positive amount;
// 0/undefined is omitted (the backend defaults to a zero-balance account, no transaction).
function cleanOpen(v: OpenAccountValues): OpenAccountBody {
  const body: OpenAccountBody = {
    customerId: v.customerId,
    accountTypeLabel: v.accountTypeLabel,
    currencyCode: v.currencyCode,
  };
  if (v.openingDeposit && v.openingDeposit > 0) body.openingDeposit = v.openingDeposit;
  return body;
}
```

- [ ] **Step 4: Run test + typecheck**

Run: `cd baas-backoffice && npx vitest run src/features/accounts/accounts-list.test.tsx && npm run typecheck`
Expected: PASS / clean.

- [ ] **Step 5: Commit**

```bash
git add src/features/accounts/account-status-badge.tsx src/features/accounts/accounts-list.tsx \
        src/features/accounts/accounts-list.test.tsx
git commit -m "feat(backoffice): accounts list (status filter + account-number search + open)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Money modal (deposit / withdraw)

**Files:**
- Create: `src/features/accounts/money-modal.tsx`
- Test: `src/features/accounts/money-modal.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { MoneyModal } from './money-modal';

describe('MoneyModal', () => {
  it('requires a positive amount and submits it', async () => {
    const onConfirm = vi.fn(async () => {});
    render(<MoneyModal open mode="deposit" onOpenChange={() => {}} onConfirm={onConfirm} />);
    // Empty amount → blocked.
    await userEvent.click(screen.getByRole('button', { name: /deposit/i }));
    expect(await screen.findByText(/amount must be greater than 0/i)).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();

    await userEvent.type(screen.getByLabelText(/amount/i), '500');
    await userEvent.type(screen.getByLabelText(/reference/i), 'cash-in');
    await userEvent.click(screen.getByRole('button', { name: /deposit/i }));
    expect(onConfirm).toHaveBeenCalledWith({ amount: 500, reference: 'cash-in' });
  });

  it('uses the Withdraw verb in withdraw mode', () => {
    render(<MoneyModal open mode="withdraw" onOpenChange={() => {}} onConfirm={vi.fn()} />);
    expect(screen.getByRole('heading', { name: /withdraw/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /withdraw/i })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-backoffice && npx vitest run src/features/accounts/money-modal.test.tsx`
Expected: FAIL — component does not exist.

- [ ] **Step 3: Implement**

`money-modal.tsx`:

```tsx
import { z } from 'zod';
import { CommandModal } from '@/components/command-modal';
import { FormField } from '@/components/form-field';
import { Input } from '@/components/ui/input';
import type { MoneyBody } from './use-accounts';

export type MoneyMode = 'deposit' | 'withdraw';

const schema = z.object({
  amount: z.coerce.number().gt(0, 'Amount must be greater than 0'),
  reference: z.string().optional().or(z.literal('')),
});
type MoneyValues = z.infer<typeof schema>;

const TITLE: Record<MoneyMode, string> = { deposit: 'Deposit', withdraw: 'Withdraw' };

export function MoneyModal({
  open,
  mode,
  onOpenChange,
  onConfirm,
}: {
  open: boolean;
  mode: MoneyMode;
  onOpenChange: (open: boolean) => void;
  onConfirm: (body: MoneyBody) => void | Promise<void>;
}) {
  return (
    <CommandModal<MoneyValues>
      open={open}
      onOpenChange={onOpenChange}
      title={TITLE[mode]}
      submitLabel={TITLE[mode]}
      schema={schema}
      defaultValues={{ amount: 0, reference: '' }}
      onSubmit={async (v) => {
        // Assemble the body field-by-field so it is a real MoneyBody — no cast.
        const body: MoneyBody = { amount: v.amount };
        if (v.reference) body.reference = v.reference;
        await onConfirm(body);
      }}
    >
      {(form) => (
        <>
          <FormField label="Amount" error={form.formState.errors.amount?.message}>
            <Input type="number" step="0.01" {...form.register('amount')} />
          </FormField>
          <FormField label="Reference (optional)">
            <Input {...form.register('reference')} />
          </FormField>
        </>
      )}
    </CommandModal>
  );
}
```

> The `defaultValues.amount` starts at `0`; the schema's `gt(0, …)` rejects an unedited submit, surfacing "Amount must be greater than 0" (matching the backend `@DecimalMin("0.01")`). `z.coerce.number()` turns the text input into a real number before validation, so `onConfirm` receives `{ amount: 500 }`, not `{ amount: "500" }`.

- [ ] **Step 4: Run test + typecheck**

Run: `cd baas-backoffice && npx vitest run src/features/accounts/money-modal.test.tsx && npm run typecheck`
Expected: PASS / clean.

- [ ] **Step 5: Commit**

```bash
git add src/features/accounts/money-modal.tsx src/features/accounts/money-modal.test.tsx
git commit -m "feat(backoffice): deposit/withdraw money modal (amount > 0, optional reference)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Account action modal (freeze / unfreeze / close) + status history

**Files:**
- Create: `src/features/accounts/account-action-modal.tsx`
- Create: `src/features/accounts/account-status-history.tsx`
- Test: `src/features/accounts/account-action-modal.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { AccountActionModal } from './account-action-modal';

describe('AccountActionModal', () => {
  it('requires a reason and submits it', async () => {
    const onConfirm = vi.fn(async () => {});
    render(<AccountActionModal open command="freeze" onOpenChange={() => {}} onConfirm={onConfirm} />);
    await userEvent.click(screen.getByRole('button', { name: /confirm/i }));
    expect(await screen.findByText(/reason is required/i)).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();

    await userEvent.type(screen.getByLabelText(/reason/i), 'legal hold');
    await userEvent.click(screen.getByRole('button', { name: /confirm/i }));
    expect(onConfirm).toHaveBeenCalledWith('legal hold');
  });

  it('titles the modal by command (close)', () => {
    render(<AccountActionModal open command="close" onOpenChange={() => {}} onConfirm={vi.fn()} />);
    expect(screen.getByRole('heading', { name: /close account/i })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-backoffice && npx vitest run src/features/accounts/account-action-modal.test.tsx`
Expected: FAIL — component does not exist.

- [ ] **Step 3: Implement**

`account-action-modal.tsx`:

```tsx
import { z } from 'zod';
import { CommandModal } from '@/components/command-modal';
import { FormField } from '@/components/form-field';
import { Input } from '@/components/ui/input';
import type { AccountCommand } from './use-accounts';

const schema = z.object({ reason: z.string().trim().min(1, 'Reason is required') });

const LABEL: Record<AccountCommand, string> = {
  freeze: 'Freeze account',
  unfreeze: 'Unfreeze account',
  close: 'Close account',
};

export function AccountActionModal({
  open,
  command,
  onOpenChange,
  onConfirm,
}: {
  open: boolean;
  command: AccountCommand;
  onOpenChange: (open: boolean) => void;
  onConfirm: (reason: string) => void | Promise<void>;
}) {
  return (
    <CommandModal
      open={open}
      onOpenChange={onOpenChange}
      title={LABEL[command]}
      submitLabel="Confirm"
      schema={schema}
      defaultValues={{ reason: '' }}
      onSubmit={async (v) => {
        await onConfirm(v.reason);
      }}
    >
      {(form) => (
        <FormField label="Reason" error={form.formState.errors.reason?.message}>
          <Input {...form.register('reason')} />
        </FormField>
      )}
    </CommandModal>
  );
}
```

`account-status-history.tsx`:

```tsx
import { formatDateTime, humanizeStatus } from '@/lib/format';
import type { StatusEvent } from './use-accounts';

export function AccountStatusHistory({ events }: { events: StatusEvent[] }) {
  if (events.length === 0) {
    return <p className="text-sm text-muted">No status changes yet.</p>;
  }
  return (
    <ul className="space-y-2">
      {events.map((e) => (
        <li key={e.id} className="rounded-[var(--radius-control)] border border-border p-3 text-sm">
          <div className="font-medium">
            {humanizeStatus(e.fromStatus)} → {humanizeStatus(e.toStatus)}
          </div>
          <div className="text-muted">{e.reason}</div>
          <div className="mt-1 text-xs text-muted">
            {e.changedBy ?? '—'} · {formatDateTime(e.changedAt)}
          </div>
        </li>
      ))}
    </ul>
  );
}
```

- [ ] **Step 4: Run test + typecheck**

Run: `cd baas-backoffice && npx vitest run src/features/accounts/account-action-modal.test.tsx && npm run typecheck`
Expected: PASS / clean.

- [ ] **Step 5: Commit**

```bash
git add src/features/accounts/account-action-modal.tsx \
        src/features/accounts/account-status-history.tsx \
        src/features/accounts/account-action-modal.test.tsx
git commit -m "feat(backoffice): freeze/unfreeze/close action modal + status history timeline

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Transaction ledger

**Files:**
- Create: `src/features/accounts/transaction-ledger.tsx`
- Test: `src/features/accounts/transaction-ledger.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { TransactionLedger } from './transaction-ledger';
import type { AccountTransaction } from './use-accounts';

const TXNS: AccountTransaction[] = [
  { id: 't1', accountId: 'a1', transactionType: 'CREDIT', amount: 2500, runningBalance: 2500,
    currencyCode: 'NGN', reference: 'OPENING_DEPOSIT', createdAt: '2026-06-10T10:00:00Z' },
  { id: 't2', accountId: 'a1', transactionType: 'DEBIT', amount: 100, runningBalance: 2400,
    currencyCode: 'NGN', reference: null, createdAt: '2026-06-11T09:00:00Z' },
];

describe('TransactionLedger', () => {
  it('renders CREDIT and DEBIT rows with amount, running balance, reference', () => {
    render(<TransactionLedger transactions={TXNS} />);
    expect(screen.getByText('CREDIT')).toBeInTheDocument();
    expect(screen.getByText('DEBIT')).toBeInTheDocument();
    expect(screen.getByText('OPENING_DEPOSIT')).toBeInTheDocument();
    expect(screen.getByText('NGN 2500')).toBeInTheDocument();
  });

  it('renders an empty message when there are no transactions', () => {
    render(<TransactionLedger transactions={[]} />);
    expect(screen.getByText(/no transactions/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-backoffice && npx vitest run src/features/accounts/transaction-ledger.test.tsx`
Expected: FAIL — component does not exist.

- [ ] **Step 3: Implement**

`transaction-ledger.tsx`:

```tsx
import { createColumnHelper } from '@tanstack/react-table';
import { DataTable } from '@/components/data-table';
import { formatDateTime } from '@/lib/format';
import type { AccountTransaction } from './use-accounts';

const col = createColumnHelper<AccountTransaction>();
const columns = [
  col.display({ id: 'type', header: 'Type', cell: (ctx) => ctx.row.original.transactionType }),
  col.display({
    id: 'amount',
    header: 'Amount',
    cell: (ctx) => `${ctx.row.original.currencyCode} ${ctx.row.original.amount}`,
  }),
  col.display({
    id: 'runningBalance',
    header: 'Running balance',
    cell: (ctx) => `${ctx.row.original.currencyCode} ${ctx.row.original.runningBalance}`,
  }),
  col.display({
    id: 'reference',
    header: 'Reference',
    cell: (ctx) => ctx.row.original.reference ?? '—',
  }),
  col.display({
    id: 'date',
    header: 'Date',
    cell: (ctx) => formatDateTime(ctx.row.original.createdAt),
  }),
];

export function TransactionLedger({ transactions }: { transactions: AccountTransaction[] }) {
  return <DataTable columns={columns} data={transactions} emptyMessage="No transactions yet" />;
}
```

- [ ] **Step 4: Run test + typecheck**

Run: `cd baas-backoffice && npx vitest run src/features/accounts/transaction-ledger.test.tsx && npm run typecheck`
Expected: PASS / clean.

- [ ] **Step 5: Commit**

```bash
git add src/features/accounts/transaction-ledger.tsx \
        src/features/accounts/transaction-ledger.test.tsx
git commit -m "feat(backoffice): transaction ledger (CREDIT/DEBIT rows + running balance)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Account detail page

**Files:**
- Create: `src/features/accounts/account-detail.tsx`
- Test: `src/features/accounts/account-detail.test.tsx`

> The detail page assembles everything: header (account #, status badge, customer link, balance), a details card, an action button group, the ledger, and the status history. The action group is gated by status + permission via the `ACTIONS` map (per spec §6) with a `?? []` runtime guard. Money buttons gate on `DEPOSIT`/`WITHDRAW`; lifecycle buttons on `UPDATE_ACCOUNT`.

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { AccountDetail } from './account-detail';
import { ApiClientProvider } from '@/api/context';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

// Routes the four GETs by URL suffix: status-events, transactions, or the detail itself.
function wrap(
  detail: unknown,
  events: unknown[],
  txns: unknown[],
  authorities = ['READ_ACCOUNT', 'UPDATE_ACCOUNT', 'DEPOSIT', 'WITHDRAW'],
) {
  const env = (data: unknown) => ({
    data: { data, meta: null, errors: null }, error: undefined,
    response: new Response(null, { status: 200 }),
  });
  const pageEnv = (rows: unknown[]) => env({ content: rows, number: 0, size: 20,
    totalElements: rows.length, totalPages: 1 });
  const client = {
    GET: vi.fn(async (path: string) => {
      if (path.endsWith('/status-events')) return env(events);
      if (path.endsWith('/transactions')) return pageEnv(txns);
      return env(detail);
    }),
    POST: vi.fn(), PUT: vi.fn(),
  } as never;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const auth = createDevAuthProvider({ token: 't', authorities, user: null });
  return ({ children }: { children: ReactNode }) => (
    <AuthContextProvider provider={auth}>
      <ApiClientProvider client={client}>
        <QueryClientProvider client={qc}>
          <MemoryRouter initialEntries={['/accounts/a1']}>
            <Routes>
              <Route path="/accounts/:id" element={children} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </ApiClientProvider>
    </AuthContextProvider>
  );
}

const ACTIVE_ACCOUNT = { id: 'a1', accountNumber: '0123456789', customerId: 'c1',
  customerName: 'Ada Lovelace', accountTypeLabel: 'Savings', status: 'ACTIVE', balance: 0,
  availableBalance: 0, currencyCode: 'NGN', minimumBalance: 0, allowOverdraft: false,
  overdraftLimit: 0, openedAt: '2026-06-10T10:00:00Z' };

const FROZEN_ACCOUNT = { ...ACTIVE_ACCOUNT, status: 'FROZEN', balance: 500, availableBalance: 500 };

describe('AccountDetail', () => {
  it('renders an ACTIVE account with freeze/close/deposit/withdraw actions', async () => {
    const Wrapper = wrap(ACTIVE_ACCOUNT, [], []);
    render(<AccountDetail />, { wrapper: Wrapper });
    expect(await screen.findByText('0123456789')).toBeInTheDocument();
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /freeze/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /close/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /deposit/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /withdraw/i })).toBeInTheDocument();
    // unfreeze is NOT valid from ACTIVE.
    expect(screen.queryByRole('button', { name: /unfreeze/i })).not.toBeInTheDocument();
  });

  it('renders a FROZEN account with only unfreeze + deposit (no withdraw, no close)', async () => {
    const Wrapper = wrap(FROZEN_ACCOUNT, [], []);
    render(<AccountDetail />, { wrapper: Wrapper });
    await screen.findByText('0123456789');
    expect(screen.getByRole('button', { name: /unfreeze/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /deposit/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /withdraw/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /close|freeze/i })).not.toBeInTheDocument();
  });

  it('hides all action buttons for a read-only operator', async () => {
    const Wrapper = wrap(ACTIVE_ACCOUNT, [], [], ['READ_ACCOUNT']);
    render(<AccountDetail />, { wrapper: Wrapper });
    await screen.findByText('0123456789');
    expect(screen.queryByRole('button', { name: /freeze/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /deposit/i })).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-backoffice && npx vitest run src/features/accounts/account-detail.test.tsx`
Expected: FAIL — component does not exist.

- [ ] **Step 3: Implement**

`account-detail.tsx`:

```tsx
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { RequirePermission } from '@/components/require-permission';
import { useAuth } from '@/auth/context';
import { hasPermission, PERMISSIONS } from '@/lib/rbac';
import { formatDateTime } from '@/lib/format';
import {
  useAccount,
  useAccountStatusEvents,
  useAccountTransactions,
  useDeposit,
  useWithdraw,
  useAccountTransition,
  type AccountCommand,
  type AccountStatus,
} from './use-accounts';
import { AccountStatusBadge } from './account-status-badge';
import { AccountActionModal } from './account-action-modal';
import { AccountStatusHistory } from './account-status-history';
import { MoneyModal, type MoneyMode } from './money-modal';
import { TransactionLedger } from './transaction-ledger';

// Lifecycle commands available from each status — mirrors the engine state machine (spec §6):
// ACTIVE → freeze, close (close balance-zero guard is enforced server-side, 409 on nonzero);
// FROZEN → unfreeze; CLOSED → none.
const LIFECYCLE_ACTIONS: Record<AccountStatus, AccountCommand[]> = {
  ACTIVE: ['freeze', 'close'],
  FROZEN: ['unfreeze'],
  CLOSED: [],
};
// Money operations available from each status (legal-hold model, spec §3.2):
// deposit allowed on ACTIVE+FROZEN; withdraw on ACTIVE only; CLOSED blocks both.
const CAN_DEPOSIT: Record<AccountStatus, boolean> = { ACTIVE: true, FROZEN: true, CLOSED: false };
const CAN_WITHDRAW: Record<AccountStatus, boolean> = { ACTIVE: true, FROZEN: false, CLOSED: false };

const capitalize = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

export function AccountDetail() {
  const { id = '' } = useParams();
  const auth = useAuth();
  const account = useAccount(id);
  const events = useAccountStatusEvents(id);
  const txns = useAccountTransactions(id);
  const deposit = useDeposit(id);
  const withdraw = useWithdraw(id);
  const transition = useAccountTransition(id);
  const [action, setAction] = useState<AccountCommand | null>(null);
  const [money, setMoney] = useState<MoneyMode | null>(null);

  if (account.isLoading)
    return <p className="px-4 py-10 text-center text-sm text-muted">Loading…</p>;
  if (account.isError || !account.data)
    return <p className="px-4 py-10 text-center text-sm text-danger">Account not found.</p>;

  const a = account.data;
  // ?? [] guards against an out-of-union status arriving from the wire (.map on undefined would crash).
  const lifecycle = LIFECYCLE_ACTIONS[a.status] ?? [];
  const canDeposit = (CAN_DEPOSIT[a.status] ?? false) && hasPermission(auth.getAuthorities(), PERMISSIONS.DEPOSIT);
  const canWithdraw = (CAN_WITHDRAW[a.status] ?? false) && hasPermission(auth.getAuthorities(), PERMISSIONS.WITHDRAW);

  return (
    <div className="space-y-4">
      <div className="mb-4 flex items-start justify-between">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">{a.accountNumber}</h1>
          <div className="mt-1 flex items-center gap-2 text-sm text-muted">
            <AccountStatusBadge status={a.status} />
            <Link to={`/customers/${a.customerId}`} className="text-brand-primary">
              {a.customerName}
            </Link>
            <span>· {a.currencyCode} {a.balance}</span>
          </div>
        </div>
        <div className="flex gap-2">
          {canDeposit && <Button onClick={() => setMoney('deposit')}>Deposit</Button>}
          {canWithdraw && (
            <Button variant="outline" onClick={() => setMoney('withdraw')}>Withdraw</Button>
          )}
          <RequirePermission code={PERMISSIONS.UPDATE_ACCOUNT}>
            {lifecycle.map((cmd) => (
              <Button key={cmd} variant="outline" onClick={() => setAction(cmd)}>
                {capitalize(cmd)}
              </Button>
            ))}
          </RequirePermission>
        </div>
      </div>

      <div className="rounded-[var(--radius-card)] border border-border bg-surface p-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <Field label="Type" value={a.accountTypeLabel} />
          <Field label="Currency" value={a.currencyCode} />
          <Field label="Balance" value={`${a.currencyCode} ${a.balance}`} />
          <Field label="Available balance" value={`${a.currencyCode} ${a.availableBalance}`} />
          <Field label="Minimum balance" value={`${a.currencyCode} ${a.minimumBalance}`} />
          <Field label="Overdraft" value={a.allowOverdraft ? `Up to ${a.currencyCode} ${a.overdraftLimit}` : 'Not allowed'} />
          <Field label="Opened" value={a.openedAt ? formatDateTime(a.openedAt) : null} />
        </div>
      </div>

      <div className="rounded-[var(--radius-card)] border border-border bg-surface p-4">
        <h2 className="mb-3 font-semibold">Transactions</h2>
        <TransactionLedger transactions={txns.data?.items ?? []} />
      </div>

      <div className="rounded-[var(--radius-card)] border border-border bg-surface p-4">
        <h2 className="mb-3 font-semibold">Status history</h2>
        <AccountStatusHistory events={events.data ?? []} />
      </div>

      {action && (
        <AccountActionModal
          open
          command={action}
          onOpenChange={() => setAction(null)}
          onConfirm={async (reason) => {
            await transition.mutateAsync({ command: action, reason });
            setAction(null);
          }}
        />
      )}

      {money && (
        <MoneyModal
          open
          mode={money}
          onOpenChange={() => setMoney(null)}
          onConfirm={async (body) => {
            if (money === 'deposit') await deposit.mutateAsync(body);
            else await withdraw.mutateAsync(body);
            setMoney(null);
          }}
        />
      )}
    </div>
  );
}

function Field({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div>
      <div className="text-xs text-muted">{label}</div>
      <div>{value || '—'}</div>
    </div>
  );
}
```

> The lifecycle button group is wrapped in `<RequirePermission code={UPDATE_ACCOUNT}>` (one permission for all three commands, per spec §3.3) — same shape as the Customers detail page. Deposit/Withdraw use `hasPermission` inline (not the wrapper component) because each gates on a **different** permission (`DEPOSIT` vs `WITHDRAW`) AND a different status predicate, so wrapping each in its own `RequirePermission` would still need the status guard alongside — the inline boolean keeps both checks in one place. Both action modals and the money modal are conditionally mounted (`{action && …}`, `{money && …}`) so each open is a fresh form (`CommandModal` does not reset on reopen).

- [ ] **Step 4: Run test + typecheck**

Run: `cd baas-backoffice && npx vitest run src/features/accounts/account-detail.test.tsx && npm run typecheck`
Expected: PASS / clean.

- [ ] **Step 5: Commit**

```bash
git add src/features/accounts/account-detail.tsx src/features/accounts/account-detail.test.tsx
git commit -m "feat(backoffice): account detail page (header + actions + ledger + status history)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: RBAC permission constant + wire routes

**Files:**
- Modify: `src/lib/rbac.ts` (add `UPDATE_ACCOUNT`)
- Modify: `src/app/router.tsx` (add `accountRoutes`)
- Modify: `src/app/router.test.tsx` (extend)

- [ ] **Step 1: Write the failing test** (append to `router.test.tsx`)

```tsx
import { accountRoutes } from './router';

describe('router — account routes', () => {
  it('accounts route renders the list when authorised', async () => {
    const client = { GET: vi.fn(async () => emptyPage()), POST: vi.fn() } as never;
    const auth = createDevAuthProvider({ token: 't', authorities: ['READ_ACCOUNT'], user: null });
    const router = createMemoryRouter(accountRoutes, { initialEntries: ['/accounts'] });
    render(
      <AuthContextProvider provider={auth}>
        <ApiClientProvider client={client}>
          <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
            <RouterProvider router={router} />
          </QueryClientProvider>
        </ApiClientProvider>
      </AuthContextProvider>,
    );
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /accounts/i })).toBeInTheDocument(),
    );
  });

  it('blocks the accounts route without READ_ACCOUNT (no blank screen, no crash)', async () => {
    const client = { GET: vi.fn(async () => emptyPage()), POST: vi.fn() } as never;
    const auth = createDevAuthProvider({ token: 't', authorities: [], user: null });
    const router = createMemoryRouter(accountRoutes, { initialEntries: ['/accounts'] });
    render(
      <AuthContextProvider provider={auth}>
        <ApiClientProvider client={client}>
          <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
            <RouterProvider router={router} />
          </QueryClientProvider>
        </ApiClientProvider>
      </AuthContextProvider>,
    );
    await waitFor(() => expect(screen.getByText(/not permitted/i)).toBeInTheDocument());
  });
});
```

(`emptyPage`, the imports, and `createMemoryRouter`/`RouterProvider` are already present at the top of `router.test.tsx` from the customer-route tests — reuse them.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd baas-backoffice && npx vitest run src/app/router.test.tsx`
Expected: FAIL — `accountRoutes` not exported / routes not wired.

- [ ] **Step 3: Implement**

In `src/lib/rbac.ts` add `UPDATE_ACCOUNT` to the `PERMISSIONS` object, right after `CREATE_ACCOUNT`:

```ts
  READ_ACCOUNT: 'READ_ACCOUNT',
  CREATE_ACCOUNT: 'CREATE_ACCOUNT',
  UPDATE_ACCOUNT: 'UPDATE_ACCOUNT',
  DEPOSIT: 'DEPOSIT',
```

In `src/app/router.tsx` add the imports and export the account routes (mirror `customerRoutes`):

```tsx
import { AccountsList } from '@/features/accounts/accounts-list';
import { AccountDetail } from '@/features/accounts/account-detail';
```

```tsx
export const accountRoutes: RouteObject[] = [
  {
    path: '/accounts',
    element: (
      <RequireRoutePermission code={PERMISSIONS.READ_ACCOUNT}>
        <AccountsList />
      </RequireRoutePermission>
    ),
  },
  {
    path: '/accounts/:id',
    element: (
      <RequireRoutePermission code={PERMISSIONS.READ_ACCOUNT}>
        <AccountDetail />
      </RequireRoutePermission>
    ),
  },
];
```

Then spread them into the `AppShell` children array alongside `customerRoutes`:

```tsx
        children: [{ index: true, element: <Dashboard /> }, ...customerRoutes, ...accountRoutes],
```

- [ ] **Step 4: Run test + typecheck**

Run: `cd baas-backoffice && npx vitest run src/app/router.test.tsx && npm run typecheck`
Expected: PASS / clean.

- [ ] **Step 5: Commit**

```bash
git add src/lib/rbac.ts src/app/router.tsx src/app/router.test.tsx
git commit -m "feat(backoffice): UPDATE_ACCOUNT permission + wire /accounts and /accounts/:id routes

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: Playwright e2e — open → deposit → freeze → withdraw-blocked → unfreeze → close

**Files:**
- Create: `e2e/accounts.spec.ts`
- Modify: `playwright.config.ts` (extend `VITE_DEV_AUTHORITIES` so the operator can act on accounts)

- [ ] **Step 1: Grant the account authorities to the Playwright dev-auth operator**

The e2e operator's authorities come from `playwright.config.ts` → `webServer.env.VITE_DEV_AUTHORITIES`. Today it is `READ_CUSTOMER,CREATE_CUSTOMER,UPDATE_CUSTOMER`. Add the five account codes so the account actions render:

```ts
      VITE_DEV_AUTHORITIES:
        'READ_CUSTOMER,CREATE_CUSTOMER,UPDATE_CUSTOMER,READ_ACCOUNT,CREATE_ACCOUNT,UPDATE_ACCOUNT,DEPOSIT,WITHDRAW',
```

- [ ] **Step 2: Write the e2e test**

```ts
import { test, expect } from '@playwright/test';

// Self-contained happy path: the engine is fully route-stubbed (no backend needed).
// open /accounts → open an account (picking a stubbed customer) → deposit → freeze →
// confirm Withdraw is gone while FROZEN → unfreeze → close. Mirrors customers.spec.ts:
// dev-auth grants the authorities via playwright.config webServer env.
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
    // Lifecycle + money commands.
    if (url.includes('/deposit')) {
      account.balance = 500; account.availableBalance = 500;
      return route.fulfill({ json: { data: { id: 't1', accountId: 'a1', transactionType: 'CREDIT',
        amount: 500, runningBalance: 500, currencyCode: 'NGN', reference: null,
        createdAt: 't' }, meta: null, errors: null } });
    }
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
  await expect(page.getByRole('button', { name: /withdraw/i })).toHaveCount(0);

  // Unfreeze → back to ACTIVE → Close.
  await page.getByRole('button', { name: /unfreeze/i }).click();
  await page.getByLabel(/reason/i).fill('released');
  await page.getByRole('button', { name: /confirm/i }).click();
  await expect(page.getByRole('button', { name: /^close$/i })).toBeVisible();

  await page.getByRole('button', { name: /^close$/i }).click();
  await page.getByLabel(/reason/i).fill('customer request');
  await page.getByRole('button', { name: /confirm/i }).click();
  await expect(page.getByText('CLOSED')).toBeVisible();
});
```

> Disambiguation notes mirroring `customers.spec.ts`: the "Open account" button text appears both on the list page and as the modal's submit label, so use `.last()` after the modal opens. "Deposit" is both the header action and the modal submit label → use `.last()` + the anchored `/^deposit$/i`. "Close" / "Withdraw" use anchored regexes (`/^close$/i`) so they don't also match "Closed"/"Withdrawn" text. The `**/baas/v1/customers**` route is registered before `**/baas/v1/accounts**`; Playwright matches the most-recently-registered route first, but the patterns are disjoint so ordering is immaterial.

- [ ] **Step 3: Run the e2e**

Run: `cd baas-backoffice && npm run test:e2e -- accounts`
Expected: PASS (Playwright self-starts the dev server in dev-auth mode with the extended authorities).

- [ ] **Step 4: Commit**

```bash
git add e2e/accounts.spec.ts playwright.config.ts
git commit -m "test(backoffice): accounts e2e — open → deposit → freeze → withdraw-blocked → unfreeze → close

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: Nav verification + docs

**Files:**
- Modify: `docs/backoffice-operations.md`

> The `/accounts` nav item already exists in `src/layout/nav-config.ts` (Banking group, gated `READ_ACCOUNT`) — no nav change is needed; this task only wires the **routes** (done in Task 9) and updates the operations doc.

- [ ] **Step 1: Update `docs/backoffice-operations.md`**

1. **RBAC — permission codes consumed:** add `UPDATE_ACCOUNT` to the inline code list (between `CREATE_ACCOUNT` and `DEPOSIT`). Note in the prose that `UPDATE_ACCOUNT` is **new** — it is not yet in the engine's V2 migration; it is seeded by the Accounts backend track's `V6` migration (PR A).

2. **Routes table:** add two rows after the `/customers/:id` row:

```
| `/accounts` | `AccountsList` | `RequireAuth` → `AppShell` → `RequireRoutePermission(READ_ACCOUNT)` | ✅ live |
| `/accounts/:id` | `AccountDetail` | `RequireAuth` → `AppShell` → `RequireRoutePermission(READ_ACCOUNT)` | ✅ live |
```

3. **Nav-routes table:** flip the Accounts row marker from `/accounts` to `/accounts ✅ wired` (mirroring the Customers row).

4. **Engine endpoints consumed:** append these rows:

```
| `GET /baas/v1/accounts` | `useAccounts` | Paginated list with `status` (ACTIVE&#124;FROZEN&#124;CLOSED) and `search` (account-number prefix) filters |
| `POST /baas/v1/accounts` | `useOpenAccount` | Open account against a customer; optional `openingDeposit` (≥ 0) writes one CREDIT transaction |
| `GET /baas/v1/accounts/{id}` | `useAccount` | Account detail (balance, availableBalance, minimumBalance, overdraft, openedAt) |
| `POST /baas/v1/accounts/{id}/{deposit&#124;withdraw}` | `useDeposit`/`useWithdraw` | Money movement; deposit allowed on ACTIVE+FROZEN, withdraw on ACTIVE only |
| `POST /baas/v1/accounts/{id}/{freeze&#124;unfreeze&#124;close}` | `useAccountTransition` | Lifecycle state machine; `reason` body required (400 on blank); gated by `UPDATE_ACCOUNT` |
| `GET /baas/v1/accounts/{id}/status-events` | `useAccountStatusEvents` | Append-only lifecycle history (`fromStatus`, `toStatus`, `reason`, `changedBy`, `changedAt`) |
| `GET /baas/v1/accounts/{id}/transactions` | `useAccountTransactions` | Paginated CREDIT/DEBIT ledger (`amount`, `runningBalance`, `reference`, `createdAt`) |
```

5. **Known follow-ups:** append an Accounts-track follow-up note (mirroring the Customers-track one) about query-key namespacing:

```
- **Accounts query-key namespacing review** — `useAccounts` (list page) keys under
  `qk.list('accounts', …)`, distinct from every `qk.list('customers', …)` key, so there is no
  cross-feature cache collision today (different list domain). A deliberate review should confirm
  this stays true if a future dashboard adds an accounts widget; consider a dedicated
  `['accounts', 'recent']` key for any such widget to keep the separation explicit. (Surfaced FE Task 1–2.)
```

- [ ] **Step 2: Commit**

```bash
git add docs/backoffice-operations.md
git commit -m "docs(backoffice-operations): Accounts routes + UPDATE_ACCOUNT + consumed endpoints

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: Full verification + push

- [ ] **Step 1: Verify all green**

Run:
```bash
cd baas-backoffice && npm run typecheck && npm test && npm run build
```
Expected: typecheck clean; all tests pass (the existing customer/dashboard/router suites + the new `accounts/*` suites); build succeeds.

- [ ] **Step 2: Push + open PR** (via `finishing-a-development-branch`)

```bash
git push -u origin feat/baas-backoffice-accounts
```

The Figma "Accounts — As Built" frames (spec §7) and the BaaS skill SESSION COMPLETION GATE items are completed before merge per the spec — they are a shared/PR-B deliverable, not blocked by this code.

---

## Notes for the implementer

- **The `as never` casts on `client.GET/POST/PUT`** are the single accepted seam: `openapi-fetch` is strict about path/param types, and the hand-seeded `schema.d.ts` accepts `unknown` bodies. The hooks cast inputs and unwrap results to the local interfaces (`AccountDetail`, `AccountRow`, `AccountTransaction`, `StatusEvent`), which the tests assert against. This mirrors `use-customers.ts` exactly. **No other production cast is allowed** — the open-account modal tracks the selected customer name in `CustomerPicker`'s own `useState` (UI-only) rather than an unschemed RHF field, so there is no extra cast anywhere in the feature.
- **`useAccountTransition` builds the path** as `/baas/v1/accounts/{id}/${command}` — the three commands (`freeze|unfreeze|close`) map 1:1 to the engine endpoints; the real `id` travels via `params.path.id`.
- **Mutation invalidation** — `invalidateAccount(qc, id)` invalidates `qk.detail('accounts', id)` (which prefix-matches detail + status-events + transactions keys) plus `['accounts','list']` so the list row refreshes. `useOpenAccount` invalidates only `['accounts','list']` (no detail exists yet).
- **Action visibility = the engine state machine + the money-gating model + permissions.** Lifecycle: ACTIVE→freeze/close, FROZEN→unfreeze, CLOSED→none (gated by `UPDATE_ACCOUNT`). Money: deposit on ACTIVE+FROZEN (gated by `DEPOSIT`), withdraw on ACTIVE only (gated by `WITHDRAW`). Keep these maps in sync with spec §3.2/§3.6 and the backend. The `?? []` / `?? false` guards protect against an out-of-union wire status.
- **Close from FROZEN, and close with non-zero balance, are blocked server-side** (400 `INVALID_ACCOUNT_TRANSITION` / 409 `ACCOUNT_BALANCE_NONZERO`). The UI only hides the Close button on FROZEN (close isn't in `LIFECYCLE_ACTIONS.FROZEN`); the zero-balance guard is enforced by the backend and surfaces as a toasted `ApiError` from the global `MutationCache` `onError` if an operator closes a funded ACTIVE account. No client-side balance check is duplicated (single source of truth = backend).
- **Customer picker reuses `useCustomers`** — a cross-feature import (`@/features/customers/use-customers`). This is intentional: the open-account flow needs the same search the Customers list uses. Customer-name search in the **accounts list** itself is deferred (`DEF-1C-32`, spec §3.4/§9) — the accounts list searches account-number only.
- **Do not rebuild Foundation primitives** (CommandModal, FormField, DataTable, StatusBadge, PageHeader, RequirePermission, Input, Button) — import them.
- **The nav item for `/accounts` already exists** in `src/layout/nav-config.ts` (gated `READ_ACCOUNT`); Task 9 only wires the **routes**.
- **`UPDATE_ACCOUNT` is new on both sides** — added to `rbac.ts` here (PR B) and seeded by the backend's `V6` migration (PR A). The PRs are independently mergeable; if PR A is undeployed, the lifecycle endpoints simply 403 and the UI surfaces a toasted error.
