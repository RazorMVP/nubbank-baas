# Customers Track — Frontend (baas-backoffice) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Customers feature in the tenant backoffice — a filterable/searchable list, a detail page with KYC lifecycle actions + history, and create/edit modals — consuming the `baas-engine` customer endpoints.

**Architecture:** New `src/features/customers/` feature following the Foundation's "list + detail + modals" shape. Data via TanStack Query hooks (`useApiClient` + `unwrapResult`/`extractPage`). Forms via the Foundation `CommandModal` + `FormField` (React Hook Form + Zod). Two routes wired into the existing router under `RequireAuth → AppShell`, gated by `RequireRoutePermission('READ_CUSTOMER')`. This feature is the **template** every later domain track copies.

**Tech Stack:** React 19, Vite 6, TypeScript 5, TanStack Query 5, TanStack Table 8, React Router 7, React Hook Form 7 + Zod 3, `openapi-fetch`, Vitest 3 + React Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-06-10-customers-track-design.md`

**Backend contract:** `docs/superpowers/plans/2026-06-10-customers-backend-baas-engine.md` (the endpoints this consumes). The frontend degrades gracefully if the backend isn't deployed (queries error → empty/error states).

**Branch:** `feat/baas-backoffice-customers` (off `main`). Ships as its own PR.

---

## Pre-flight

- [ ] **Create branch**

```bash
cd ~/nubbank-baas
git checkout main && git pull --ff-only origin main
git checkout -b feat/baas-backoffice-customers
cd baas-backoffice && npm ci
```

## Foundation primitives this feature reuses (do NOT rebuild)

- `CommandModal<T>({ open, onOpenChange, title, schema, defaultValues, onSubmit, submitLabel?, children: (form) => ReactNode })` — `src/components/command-modal.tsx`
- `FormField({ label, error?, children: <single input element> })` — `src/components/form-field.tsx`
- `DataTable<TData,TValue>({ columns, data, emptyMessage? })` — `src/components/data-table.tsx`
- `StatusBadge({ label, variant?: 'success'|'warning'|'danger'|'info'|'neutral' })` — `src/components/status-badge.tsx`
- `useApiClient()` → typed `openapi-fetch` client — `src/api/context.tsx`
- `unwrapResult<T>(result)`, `extractPage<T>(page)`, `Page<T>`, `NormalizedPage<T>`, `ApiError` — `src/api/envelope.ts`
- `qk.list(domain, params)`, `qk.detail(domain, id)` — `src/api/query.ts`
- `PERMISSIONS`, `hasPermission(authorities, code)` — `src/lib/rbac.ts`
- `useAuth().getAuthorities()` — `src/auth/context.ts`
- `RequireRoutePermission`, `RequireAuth` — `src/app/guards.tsx`
- `Input` — `src/components/ui/input.tsx`; `Button` — `src/components/ui/button.tsx`

## Hook-test harness (referenced by every hook test below)

Mirrors `src/features/dashboard/use-dashboard.test.tsx`:

```tsx
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { vi } from 'vitest';
import type { ReactNode } from 'react';
import { ApiClientProvider } from '@/api/context';

function makeWrapper(getResult: unknown, mutResult?: unknown) {
  const client = {
    GET: vi.fn(async () => getResult),
    POST: vi.fn(async () => mutResult ?? getResult),
    PUT: vi.fn(async () => mutResult ?? getResult),
  } as never;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <ApiClientProvider client={client}>
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

---

## Task 1: Types + read queries (`use-customers.ts`)

**Files:**
- Modify: `src/api/schema.d.ts` (hand-seed new customer paths)
- Create: `src/features/customers/use-customers.ts`
- Test: `src/features/customers/use-customers.test.tsx`

- [ ] **Step 1: Hand-seed `schema.d.ts`**

Open `src/api/schema.d.ts`, find the existing `'/baas/v1/customers'` and `'/baas/v1/dashboard/summary'` entries, and **mirror their structure** to add: `'/baas/v1/customers/{id}'` (GET → `CustomerDetail`, PUT → body `UpdateCustomerRequest`), `'/baas/v1/customers/{id}/kyc-events'` (GET → `KycEvent[]`), and `'/baas/v1/customers/{id}/activate' | '/suspend' | '/reactivate' | '/close'` (POST → body `{ reason: string }`). Add `kycStatus?` and `search?` to the existing `GET '/baas/v1/customers'` query params. The hooks below cast responses to local interfaces, so the path entries only need to be present and accept the params/bodies — copy the shape of the existing entries exactly.

- [ ] **Step 2: Write the failing hook test**

```tsx
import { describe, it, expect } from 'vitest';
import { useCustomers, useCustomer, useCustomerKycEvents } from './use-customers';
// + the hook-test harness (makeWrapper, ok) from the preamble

describe('useCustomers', () => {
  it('normalizes the page and passes filter+search params', async () => {
    const { Wrapper, client } = makeWrapper({
      data: { data: { content: [{ id: 'c1', firstName: 'John', lastName: 'Doe', email: 'j@x.com',
        kycStatus: 'PENDING_KYC', kycLevel: 'NONE', externalReference: 'ext-1', createdAt: 't' }],
        number: 0, size: 20, totalElements: 1, totalPages: 1 }, meta: null, errors: null },
      error: undefined, response: new Response(null, { status: 200 }) });
    const { result } = renderHook(
      () => useCustomers({ page: 0, size: 20, kycStatus: 'PENDING_KYC', search: 'joh' }),
      { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.items[0].firstName).toBe('John');
    expect(client.GET).toHaveBeenCalledWith('/baas/v1/customers',
      { params: { query: { page: 0, size: 20, kycStatus: 'PENDING_KYC', search: 'joh' } } });
  });
});

describe('useCustomer', () => {
  it('unwraps the detail', async () => {
    const { Wrapper } = makeWrapper(ok({ id: 'c1', firstName: 'Ada', lastName: 'L',
      bvnMasked: '•••••••1234', kycStatus: 'ACTIVE' }));
    const { result } = renderHook(() => useCustomer('c1'), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.bvnMasked).toBe('•••••••1234');
  });
});

describe('useCustomerKycEvents', () => {
  it('unwraps the event list', async () => {
    const { Wrapper } = makeWrapper(ok([{ id: 'e1', fromStatus: 'PENDING_KYC', toStatus: 'ACTIVE',
      reason: 'ok', changedBy: 'op', changedAt: 't' }]));
    const { result } = renderHook(() => useCustomerKycEvents('c1'), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.[0].toStatus).toBe('ACTIVE');
  });
});
```

(Prepend the harness imports + `makeWrapper`/`ok` from the preamble.)

- [ ] **Step 3: Run test to verify it fails**

Run: `npm test -- use-customers`
Expected: FAIL — `use-customers.ts` does not exist.

- [ ] **Step 4: Implement `use-customers.ts` (queries)**

```ts
import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '@/api/context';
import { qk } from '@/api/query';
import { unwrapResult, extractPage, type Page, type NormalizedPage } from '@/api/envelope';

export type KycStatus = 'PENDING_KYC' | 'ACTIVE' | 'SUSPENDED' | 'CLOSED';

export interface CustomerRow {
  id: string;
  externalReference: string | null;
  firstName: string;
  lastName: string;
  email: string | null;
  kycStatus: KycStatus;
  kycLevel: string;
  createdAt: string;
}

export interface CustomerDetail extends CustomerRow {
  phone: string | null;
  dateOfBirth: string | null;
  gender: string | null;
  bvnMasked: string | null;
  ninMasked: string | null;
  updatedAt: string;
}

export interface KycEvent {
  id: string;
  fromStatus: string;
  toStatus: string;
  reason: string;
  changedBy: string | null;
  changedAt: string;
}

export interface CustomerListParams {
  page: number;
  size: number;
  kycStatus?: string;
  search?: string;
}

export function useCustomers(params: CustomerListParams) {
  const client = useApiClient();
  return useQuery<NormalizedPage<CustomerRow>>({
    queryKey: qk.list('customers', params as unknown as Record<string, unknown>),
    queryFn: async () => {
      const result = await client.GET('/baas/v1/customers', { params: { query: params } } as never);
      return extractPage(unwrapResult<Page<CustomerRow>>(result));
    },
  });
}

export function useCustomer(id: string) {
  const client = useApiClient();
  return useQuery<CustomerDetail>({
    queryKey: qk.detail('customers', id),
    queryFn: async () => {
      const result = await client.GET('/baas/v1/customers/{id}',
        { params: { path: { id } } } as never);
      return unwrapResult<CustomerDetail>(result);
    },
  });
}

export function useCustomerKycEvents(id: string) {
  const client = useApiClient();
  return useQuery<KycEvent[]>({
    queryKey: [...qk.detail('customers', id), 'kyc-events'],
    queryFn: async () => {
      const result = await client.GET('/baas/v1/customers/{id}/kyc-events',
        { params: { path: { id } } } as never);
      return unwrapResult<KycEvent[]>(result);
    },
  });
}
```

- [ ] **Step 5: Run test + typecheck to verify pass**

Run: `npm test -- use-customers && npm run typecheck`
Expected: PASS / clean.

- [ ] **Step 6: Commit**

```bash
git add src/api/schema.d.ts src/features/customers/use-customers.ts src/features/customers/use-customers.test.tsx
git commit -m "feat(backoffice): customer read hooks + schema types"
```

---

## Task 2: Mutation hooks (create / update / KYC transition)

**Files:**
- Modify: `src/features/customers/use-customers.ts`
- Modify: `src/features/customers/use-customers.test.tsx`

- [ ] **Step 1: Write the failing test** (append)

```tsx
import { useCreateCustomer, useUpdateCustomer, useKycTransition } from './use-customers';

describe('useKycTransition', () => {
  it('POSTs the command path with a reason', async () => {
    const { Wrapper, client } = makeWrapper(ok({ id: 'c1', kycStatus: 'ACTIVE' }));
    const { result } = renderHook(() => useKycTransition('c1'), { wrapper: Wrapper });
    await result.current.mutateAsync({ command: 'activate', reason: 'verified' });
    expect(client.POST).toHaveBeenCalledWith('/baas/v1/customers/{id}/activate',
      { params: { path: { id: 'c1' } }, body: { reason: 'verified' } });
  });
});

describe('useCreateCustomer', () => {
  it('POSTs the create body', async () => {
    const { Wrapper, client } = makeWrapper(ok({ id: 'new' }));
    const { result } = renderHook(() => useCreateCustomer(), { wrapper: Wrapper });
    await result.current.mutateAsync({ firstName: 'A', lastName: 'B' });
    expect(client.POST).toHaveBeenCalledWith('/baas/v1/customers', { body: { firstName: 'A', lastName: 'B' } });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- use-customers`
Expected: FAIL — mutation hooks not exported.

- [ ] **Step 3: Implement (append to `use-customers.ts`)**

```ts
import { useMutation, useQueryClient } from '@tanstack/react-query';

export interface CustomerWriteBody {
  firstName: string;
  lastName: string;
  email?: string;
  phone?: string;
  dateOfBirth?: string;
  gender?: string;
  externalReference?: string;
  bvn?: string;
  nin?: string;
}

export function useCreateCustomer() {
  const client = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CustomerWriteBody) => {
      const result = await client.POST('/baas/v1/customers', { body } as never);
      return unwrapResult<CustomerDetail>(result);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['customers', 'list'] }),
  });
}

export interface CustomerUpdateBody {
  firstName: string;
  lastName: string;
  email?: string;
  phone?: string;
  dateOfBirth?: string;
  gender?: string;
}

export function useUpdateCustomer(id: string) {
  const client = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CustomerUpdateBody) => {
      const result = await client.PUT('/baas/v1/customers/{id}',
        { params: { path: { id } }, body } as never);
      return unwrapResult<CustomerDetail>(result);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: qk.detail('customers', id) });
      qc.invalidateQueries({ queryKey: ['customers', 'list'] });
    },
  });
}

export type KycCommand = 'activate' | 'suspend' | 'reactivate' | 'close';

export function useKycTransition(id: string) {
  const client = useApiClient();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ command, reason }: { command: KycCommand; reason: string }) => {
      const result = await client.POST(`/baas/v1/customers/{id}/${command}` as never,
        { params: { path: { id } }, body: { reason } } as never);
      return unwrapResult<CustomerDetail>(result);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: qk.detail('customers', id) });
      qc.invalidateQueries({ queryKey: ['customers', 'list'] });
    },
  });
}
```

- [ ] **Step 4: Run test + typecheck**

Run: `npm test -- use-customers && npm run typecheck`
Expected: PASS / clean.

- [ ] **Step 5: Commit**

```bash
git add src/features/customers/use-customers.ts src/features/customers/use-customers.test.tsx
git commit -m "feat(backoffice): customer create/update/KYC-transition mutation hooks"
```

---

## Task 3: Customer form (create/edit) + Zod schema

**Files:**
- Create: `src/features/customers/customer-form.ts` (Zod schema + field list)
- Create: `src/features/customers/customer-form-modal.tsx`
- Test: `src/features/customers/customer-form-modal.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { CustomerFormModal } from './customer-form-modal';

describe('CustomerFormModal', () => {
  it('blocks submit when required fields are empty', async () => {
    const onSubmit = vi.fn(async () => {});
    render(<CustomerFormModal open mode="create" onOpenChange={() => {}} onSubmit={onSubmit} />);
    await userEvent.click(screen.getByRole('button', { name: /save/i }));
    expect(await screen.findByText(/first name is required/i)).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('submits the entered values', async () => {
    const onSubmit = vi.fn(async () => {});
    render(<CustomerFormModal open mode="create" onOpenChange={() => {}} onSubmit={onSubmit} />);
    await userEvent.type(screen.getByLabelText(/first name/i), 'John');
    await userEvent.type(screen.getByLabelText(/last name/i), 'Doe');
    await userEvent.click(screen.getByRole('button', { name: /save/i }));
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ firstName: 'John', lastName: 'Doe' }),
      expect.anything());
  });

  it('omits BVN/NIN in edit mode', () => {
    render(<CustomerFormModal open mode="edit" onOpenChange={() => {}} onSubmit={vi.fn()}
      defaultValues={{ firstName: 'A', lastName: 'B' }} />);
    expect(screen.queryByLabelText(/bvn/i)).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- customer-form-modal`
Expected: FAIL — component does not exist.

- [ ] **Step 3: Implement**

`customer-form.ts`:

```ts
import { z } from 'zod';

export const customerFormSchema = z.object({
  firstName: z.string().min(1, 'First name is required'),
  lastName: z.string().min(1, 'Last name is required'),
  email: z.string().email('Email must be valid').optional().or(z.literal('')),
  phone: z.string().optional().or(z.literal('')),
  dateOfBirth: z.string().optional().or(z.literal('')),
  gender: z.string().optional().or(z.literal('')),
  externalReference: z.string().optional().or(z.literal('')),
  bvn: z.string().optional().or(z.literal('')),
  nin: z.string().optional().or(z.literal('')),
});

export type CustomerFormValues = z.infer<typeof customerFormSchema>;
```

`customer-form-modal.tsx`:

```tsx
import { CommandModal } from '@/components/command-modal';
import { FormField } from '@/components/form-field';
import { Input } from '@/components/ui/input';
import { customerFormSchema, type CustomerFormValues } from './customer-form';

export function CustomerFormModal({
  open, mode, onOpenChange, onSubmit, defaultValues,
}: {
  open: boolean;
  mode: 'create' | 'edit';
  onOpenChange: (open: boolean) => void;
  onSubmit: (values: CustomerFormValues) => void | Promise<void>;
  defaultValues?: Partial<CustomerFormValues>;
}) {
  const base: CustomerFormValues = {
    firstName: '', lastName: '', email: '', phone: '', dateOfBirth: '',
    gender: '', externalReference: '', bvn: '', nin: '',
    ...defaultValues,
  };
  return (
    <CommandModal
      open={open}
      onOpenChange={onOpenChange}
      title={mode === 'create' ? 'New customer' : 'Edit customer'}
      schema={customerFormSchema}
      defaultValues={base as never}
      onSubmit={onSubmit as never}
    >
      {(form) => (
        <>
          <FormField label="First name" error={form.formState.errors.firstName?.message}>
            <Input {...form.register('firstName')} />
          </FormField>
          <FormField label="Last name" error={form.formState.errors.lastName?.message}>
            <Input {...form.register('lastName')} />
          </FormField>
          <FormField label="Email" error={form.formState.errors.email?.message}>
            <Input {...form.register('email')} />
          </FormField>
          <FormField label="Phone"><Input {...form.register('phone')} /></FormField>
          <FormField label="Date of birth"><Input type="date" {...form.register('dateOfBirth')} /></FormField>
          <FormField label="Gender"><Input {...form.register('gender')} /></FormField>
          {mode === 'create' && (
            <>
              <FormField label="External reference"><Input {...form.register('externalReference')} /></FormField>
              <FormField label="BVN"><Input {...form.register('bvn')} /></FormField>
              <FormField label="NIN"><Input {...form.register('nin')} /></FormField>
            </>
          )}
        </>
      )}
    </CommandModal>
  );
}
```

- [ ] **Step 4: Run test + typecheck**

Run: `npm test -- customer-form-modal && npm run typecheck`
Expected: PASS / clean.

- [ ] **Step 5: Commit**

```bash
git add src/features/customers/customer-form.ts src/features/customers/customer-form-modal.tsx \
        src/features/customers/customer-form-modal.test.tsx
git commit -m "feat(backoffice): customer create/edit form modal (RHF + Zod)"
```

---

## Task 4: Customers list page

**Files:**
- Create: `src/features/customers/kyc-status-badge.tsx` (status → StatusBadge variant)
- Create: `src/features/customers/customers-list.tsx`
- Test: `src/features/customers/customers-list.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { CustomersList } from './customers-list';
import { ApiClientProvider } from '@/api/context';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

function wrap(getResult: unknown) {
  const client = { GET: vi.fn(async () => getResult), POST: vi.fn() } as never;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const auth = createDevAuthProvider({ token: 't',
    authorities: ['READ_CUSTOMER', 'CREATE_CUSTOMER'], user: null });
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

describe('CustomersList', () => {
  it('renders customer rows', async () => {
    const Wrapper = wrap(pageOf([{ id: 'c1', firstName: 'John', lastName: 'Doe', email: 'j@x.com',
      kycStatus: 'PENDING_KYC', kycLevel: 'NONE', externalReference: 'ext-1', createdAt: 't' }]));
    render(<CustomersList />, { wrapper: Wrapper });
    expect(await screen.findByText('John Doe')).toBeInTheDocument();
  });

  it('shows the New customer button when permitted', async () => {
    const Wrapper = wrap(pageOf([]));
    render(<CustomersList />, { wrapper: Wrapper });
    expect(await screen.findByRole('button', { name: /new customer/i })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- customers-list`
Expected: FAIL — component does not exist.

- [ ] **Step 3: Implement**

`kyc-status-badge.tsx`:

```tsx
import { StatusBadge, type StatusVariant } from '@/components/status-badge';

const VARIANT: Record<string, StatusVariant> = {
  PENDING_KYC: 'warning', ACTIVE: 'success', SUSPENDED: 'danger', CLOSED: 'neutral',
};

export function KycStatusBadge({ status }: { status: string }) {
  return <StatusBadge label={status.replace('_', ' ')} variant={VARIANT[status] ?? 'neutral'} />;
}
```

`customers-list.tsx`:

```tsx
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { type ColumnDef } from '@tanstack/react-table';
import { DataTable } from '@/components/data-table';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { useAuth } from '@/auth/context';
import { hasPermission, PERMISSIONS } from '@/lib/rbac';
import { useCustomers, useCreateCustomer, type CustomerRow } from './use-customers';
import { KycStatusBadge } from './kyc-status-badge';
import { CustomerFormModal } from './customer-form-modal';

const STATUSES = ['', 'PENDING_KYC', 'ACTIVE', 'SUSPENDED', 'CLOSED'];

export function CustomersList() {
  const auth = useAuth();
  const canCreate = hasPermission(auth.getAuthorities(), PERMISSIONS.CREATE_CUSTOMER);
  const [search, setSearch] = useState('');
  const [kycStatus, setKycStatus] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const create = useCreateCustomer();

  const query = useCustomers({ page: 0, size: 20, search: search || undefined,
    kycStatus: kycStatus || undefined });

  const columns = useMemo<ColumnDef<CustomerRow>[]>(() => [
    { header: 'Name', cell: ({ row }) => (
        <Link to={`/customers/${row.original.id}`} className="font-medium text-brand-primary">
          {row.original.firstName} {row.original.lastName}
        </Link>) },
    { header: 'Email', accessorKey: 'email' },
    { header: 'External ref', accessorKey: 'externalReference' },
    { header: 'KYC', cell: ({ row }) => <KycStatusBadge status={row.original.kycStatus} /> },
  ], []);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold">Customers</h1>
        {canCreate && <Button onClick={() => setCreateOpen(true)}>New customer</Button>}
      </div>
      <div className="flex gap-2">
        <Input placeholder="Search name or external ref…" value={search}
          onChange={(e) => setSearch(e.target.value)} className="max-w-xs" />
        <select value={kycStatus} onChange={(e) => setKycStatus(e.target.value)}
          className="rounded-[var(--radius-control)] border border-border px-3 text-sm">
          {STATUSES.map((s) => <option key={s} value={s}>{s ? s.replace('_', ' ') : 'All statuses'}</option>)}
        </select>
      </div>
      <DataTable columns={columns} data={query.data?.items ?? []}
        emptyMessage={query.isLoading ? 'Loading…' : 'No customers'} />
      <CustomerFormModal open={createOpen} mode="create" onOpenChange={setCreateOpen}
        onSubmit={async (v) => { await create.mutateAsync(cleanCreate(v)); setCreateOpen(false); }} />
    </div>
  );
}

// Strip empty-string optionals so the backend sees absent fields, not "".
function cleanCreate(v: Record<string, string | undefined>) {
  const out: Record<string, string> = {};
  for (const [k, val] of Object.entries(v)) if (val) out[k] = val;
  return out as never;
}
```

- [ ] **Step 4: Run test + typecheck**

Run: `npm test -- customers-list && npm run typecheck`
Expected: PASS / clean.

- [ ] **Step 5: Commit**

```bash
git add src/features/customers/kyc-status-badge.tsx src/features/customers/customers-list.tsx \
        src/features/customers/customers-list.test.tsx
git commit -m "feat(backoffice): customers list (filter + search + create)"
```

---

## Task 5: KYC action modal + history timeline

**Files:**
- Create: `src/features/customers/kyc-action-modal.tsx`
- Create: `src/features/customers/kyc-history.tsx`
- Test: `src/features/customers/kyc-action-modal.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { KycActionModal } from './kyc-action-modal';

describe('KycActionModal', () => {
  it('requires a reason and submits it', async () => {
    const onConfirm = vi.fn(async () => {});
    render(<KycActionModal open command="suspend" onOpenChange={() => {}} onConfirm={onConfirm} />);
    await userEvent.click(screen.getByRole('button', { name: /confirm/i }));
    expect(await screen.findByText(/reason is required/i)).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();

    await userEvent.type(screen.getByLabelText(/reason/i), 'fraud flag');
    await userEvent.click(screen.getByRole('button', { name: /confirm/i }));
    expect(onConfirm).toHaveBeenCalledWith('fraud flag');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- kyc-action-modal`
Expected: FAIL — component does not exist.

- [ ] **Step 3: Implement**

`kyc-action-modal.tsx`:

```tsx
import { z } from 'zod';
import { CommandModal } from '@/components/command-modal';
import { FormField } from '@/components/form-field';
import { Input } from '@/components/ui/input';
import type { KycCommand } from './use-customers';

const schema = z.object({ reason: z.string().min(1, 'Reason is required') });
const LABEL: Record<KycCommand, string> = {
  activate: 'Activate customer', suspend: 'Suspend customer',
  reactivate: 'Reactivate customer', close: 'Close customer',
};

export function KycActionModal({
  open, command, onOpenChange, onConfirm,
}: {
  open: boolean;
  command: KycCommand;
  onOpenChange: (open: boolean) => void;
  onConfirm: (reason: string) => void | Promise<void>;
}) {
  return (
    <CommandModal open={open} onOpenChange={onOpenChange} title={LABEL[command]}
      submitLabel="Confirm" schema={schema} defaultValues={{ reason: '' }}
      onSubmit={async (v) => { await onConfirm(v.reason); }}>
      {(form) => (
        <FormField label="Reason" error={form.formState.errors.reason?.message}>
          <Input {...form.register('reason')} />
        </FormField>
      )}
    </CommandModal>
  );
}
```

`kyc-history.tsx`:

```tsx
import type { KycEvent } from './use-customers';

export function KycHistory({ events }: { events: KycEvent[] }) {
  if (events.length === 0) return <p className="text-sm text-muted">No KYC events yet.</p>;
  return (
    <ul className="space-y-2">
      {events.map((e) => (
        <li key={e.id} className="rounded-[var(--radius-control)] border border-border p-3 text-sm">
          <div className="font-medium">{e.fromStatus} → {e.toStatus}</div>
          <div className="text-muted">{e.reason}</div>
          <div className="mt-1 text-xs text-muted">{e.changedBy ?? '—'} · {new Date(e.changedAt).toLocaleString()}</div>
        </li>
      ))}
    </ul>
  );
}
```

- [ ] **Step 4: Run test + typecheck**

Run: `npm test -- kyc-action-modal && npm run typecheck`
Expected: PASS / clean.

- [ ] **Step 5: Commit**

```bash
git add src/features/customers/kyc-action-modal.tsx src/features/customers/kyc-history.tsx \
        src/features/customers/kyc-action-modal.test.tsx
git commit -m "feat(backoffice): KYC action modal + history timeline"
```

---

## Task 6: Customer detail page

**Files:**
- Create: `src/features/customers/customer-detail.tsx`
- Test: `src/features/customers/customer-detail.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import type { ReactNode } from 'react';
import { CustomerDetail } from './customer-detail';
import { ApiClientProvider } from '@/api/context';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

function wrap(detail: unknown, events: unknown[]) {
  const client = {
    GET: vi.fn(async (path: string) =>
      path.endsWith('/kyc-events')
        ? { data: { data: events, meta: null, errors: null }, error: undefined, response: new Response(null, { status: 200 }) }
        : { data: { data: detail, meta: null, errors: null }, error: undefined, response: new Response(null, { status: 200 }) }),
    POST: vi.fn(), PUT: vi.fn(),
  } as never;
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const auth = createDevAuthProvider({ token: 't', authorities: ['READ_CUSTOMER', 'UPDATE_CUSTOMER'], user: null });
  return ({ children }: { children: ReactNode }) => (
    <AuthContextProvider provider={auth}>
      <ApiClientProvider client={client}>
        <QueryClientProvider client={qc}>
          <MemoryRouter initialEntries={['/customers/c1']}>
            <Routes><Route path="/customers/:id" element={children} /></Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </ApiClientProvider>
    </AuthContextProvider>
  );
}

describe('CustomerDetail', () => {
  it('renders profile + only the Activate action for a PENDING_KYC customer', async () => {
    const Wrapper = wrap({ id: 'c1', firstName: 'Ada', lastName: 'Lovelace', email: 'a@x.com',
      kycStatus: 'PENDING_KYC', kycLevel: 'NONE', bvnMasked: '•••••••1234', externalReference: 'ext-1',
      createdAt: 't', updatedAt: 't' }, []);
    render(<CustomerDetail />, { wrapper: Wrapper });
    expect(await screen.findByText('Ada Lovelace')).toBeInTheDocument();
    expect(screen.getByText('•••••••1234')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /activate/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /suspend/i })).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- customer-detail`
Expected: FAIL — component does not exist.

- [ ] **Step 3: Implement**

```tsx
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { useAuth } from '@/auth/context';
import { hasPermission, PERMISSIONS } from '@/lib/rbac';
import {
  useCustomer, useCustomerKycEvents, useUpdateCustomer, useKycTransition,
  type KycCommand,
} from './use-customers';
import { KycStatusBadge } from './kyc-status-badge';
import { KycActionModal } from './kyc-action-modal';
import { KycHistory } from './kyc-history';
import { CustomerFormModal } from './customer-form-modal';

// Which commands are valid from a given status (mirrors the engine state machine).
const ACTIONS: Record<string, KycCommand[]> = {
  PENDING_KYC: ['activate'], ACTIVE: ['suspend', 'close'],
  SUSPENDED: ['reactivate', 'close'], CLOSED: [],
};

export function CustomerDetail() {
  const { id = '' } = useParams();
  const auth = useAuth();
  const canEdit = hasPermission(auth.getAuthorities(), PERMISSIONS.UPDATE_CUSTOMER);
  const customer = useCustomer(id);
  const events = useCustomerKycEvents(id);
  const update = useUpdateCustomer(id);
  const transition = useKycTransition(id);
  const [editOpen, setEditOpen] = useState(false);
  const [action, setAction] = useState<KycCommand | null>(null);

  if (customer.isLoading) return <p className="text-muted">Loading…</p>;
  if (customer.isError || !customer.data) return <p className="text-danger">Customer not found.</p>;
  const c = customer.data;
  const actions = canEdit ? (ACTIONS[c.kycStatus] ?? []) : [];

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-lg font-semibold">{c.firstName} {c.lastName}</h1>
          <div className="mt-1 flex items-center gap-2 text-sm text-muted">
            <KycStatusBadge status={c.kycStatus} />
            <span>· {c.externalReference ?? 'no external ref'}</span>
          </div>
        </div>
        <div className="flex gap-2">
          {actions.map((cmd) => (
            <Button key={cmd} variant="outline" onClick={() => setAction(cmd)}>
              {cmd.charAt(0).toUpperCase() + cmd.slice(1)}
            </Button>
          ))}
          {canEdit && <Button onClick={() => setEditOpen(true)}>Edit</Button>}
        </div>
      </div>

      <section className="grid grid-cols-2 gap-3 rounded-[var(--radius-card)] border border-border bg-surface p-4 text-sm">
        <Field label="Email" value={c.email} />
        <Field label="Phone" value={c.phone} />
        <Field label="Date of birth" value={c.dateOfBirth} />
        <Field label="Gender" value={c.gender} />
        <Field label="BVN" value={c.bvnMasked} />
        <Field label="NIN" value={c.ninMasked} />
      </section>

      <section>
        <h2 className="mb-2 text-sm font-semibold">KYC history</h2>
        <KycHistory events={events.data ?? []} />
      </section>

      {action && (
        <KycActionModal open command={action} onOpenChange={() => setAction(null)}
          onConfirm={async (reason) => { await transition.mutateAsync({ command: action, reason }); setAction(null); }} />
      )}
      <CustomerFormModal open={editOpen} mode="edit" onOpenChange={setEditOpen}
        defaultValues={{ firstName: c.firstName, lastName: c.lastName, email: c.email ?? '',
          phone: c.phone ?? '', dateOfBirth: c.dateOfBirth ?? '', gender: c.gender ?? '' }}
        onSubmit={async (v) => {
          await update.mutateAsync({ firstName: v.firstName, lastName: v.lastName,
            email: v.email || undefined, phone: v.phone || undefined,
            dateOfBirth: v.dateOfBirth || undefined, gender: v.gender || undefined });
          setEditOpen(false);
        }} />
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

- [ ] **Step 4: Run test + typecheck**

Run: `npm test -- customer-detail && npm run typecheck`
Expected: PASS / clean.

- [ ] **Step 5: Commit**

```bash
git add src/features/customers/customer-detail.tsx src/features/customers/customer-detail.test.tsx
git commit -m "feat(backoffice): customer detail page (profile + KYC actions + history + edit)"
```

---

## Task 7: Wire routes

**Files:**
- Modify: `src/app/router.tsx`
- Test: `src/app/router.test.tsx` (extend or create)

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import { ApiClientProvider } from '@/api/context';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';
import { customerRoutes } from './router';   // export the customer route objects for testability

it('customers route renders the list', async () => {
  const client = { GET: vi.fn(async () => ({ data: { data: { content: [], number: 0, size: 20,
    totalElements: 0, totalPages: 0 }, meta: null, errors: null }, error: undefined,
    response: new Response(null, { status: 200 }) })), POST: vi.fn() } as never;
  const auth = createDevAuthProvider({ token: 't', authorities: ['READ_CUSTOMER'], user: null });
  const router = createMemoryRouter(customerRoutes, { initialEntries: ['/customers'] });
  render(
    <AuthContextProvider provider={auth}>
      <ApiClientProvider client={client}>
        <QueryClientProvider client={new QueryClient()}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </ApiClientProvider>
    </AuthContextProvider>);
  await waitFor(() => expect(screen.getByText('Customers')).toBeInTheDocument());
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- router`
Expected: FAIL — `customerRoutes` not exported / routes not wired.

- [ ] **Step 3: Implement** — add the customer routes under the `AppShell` children and export them for the test:

```tsx
import { RequireRoutePermission } from './guards';
import { CustomersList } from '@/features/customers/customers-list';
import { CustomerDetail } from '@/features/customers/customer-detail';

export const customerRoutes = [
  { path: '/customers', element: (
      <RequireRoutePermission code="READ_CUSTOMER"><CustomersList /></RequireRoutePermission>) },
  { path: '/customers/:id', element: (
      <RequireRoutePermission code="READ_CUSTOMER"><CustomerDetail /></RequireRoutePermission>) },
];
```

Then add them to the `AppShell` children array:

```tsx
      {
        element: <AppShell />,
        children: [
          { index: true, element: <Dashboard /> },
          ...customerRoutes,
        ],
      },
```

- [ ] **Step 4: Run test + typecheck**

Run: `npm test -- router && npm run typecheck`
Expected: PASS / clean.

- [ ] **Step 5: Commit**

```bash
git add src/app/router.tsx src/app/router.test.tsx
git commit -m "feat(backoffice): wire /customers and /customers/:id routes"
```

---

## Task 8: Playwright e2e — list → create → detail → activate → history

**Files:**
- Create: `e2e/customers.spec.ts`

- [ ] **Step 1: Write the e2e test**

```ts
import { test, expect } from '@playwright/test';

// Dev-auth mode (VITE_DEV_AUTH=true) is set by playwright.config webServer env, granting
// READ_CUSTOMER+CREATE_CUSTOMER. The engine is NOT running in CI, so this test stubs the API.
test('customer lifecycle happy path', async ({ page }) => {
  // Route-stub the engine so the e2e is self-contained (no backend needed).
  const customers: any[] = [];
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
      customers[0].kycStatus = 'ACTIVE';
      return route.fulfill({ json: { data: customers[0], meta: null, errors: null } });
    }
    if (url.includes('/kyc-events')) {
      return route.fulfill({ json: { data: [{ id: 'e1', fromStatus: 'PENDING_KYC', toStatus: 'ACTIVE',
        reason: 'verified', changedBy: 'op', changedAt: 't' }], meta: null, errors: null } });
    }
    if (url.includes('/customers/c1')) {
      return route.fulfill({ json: { data: customers[0], meta: null, errors: null } });
    }
    return route.fulfill({ json: { data: { content: customers, number: 0, size: 20,
      totalElements: customers.length, totalPages: 1 }, meta: null, errors: null } });
  });

  await page.goto('/customers');
  await expect(page.getByText('Customers')).toBeVisible();
  await page.getByRole('button', { name: /new customer/i }).click();
  await page.getByLabel(/first name/i).fill('Grace');
  await page.getByLabel(/last name/i).fill('Hopper');
  await page.getByRole('button', { name: /save/i }).click();

  await page.getByRole('link', { name: /grace hopper/i }).click();
  await page.getByRole('button', { name: /activate/i }).click();
  await page.getByLabel(/reason/i).fill('verified');
  await page.getByRole('button', { name: /confirm/i }).click();

  await expect(page.getByText('PENDING_KYC → ACTIVE')).toBeVisible();
});
```

- [ ] **Step 2: Run the e2e**

Run: `npm run test:e2e -- customers`
Expected: PASS (Playwright self-starts the dev server in dev-auth mode).

- [ ] **Step 3: Commit**

```bash
git add e2e/customers.spec.ts
git commit -m "test(backoffice): customers e2e — create → activate → history"
```

---

## Task 9: Docs

**Files:**
- Modify: `docs/backoffice-operations.md`

- [ ] **Step 1: Update `backoffice-operations.md`** — add `/customers` and `/customers/:id` to the Routes table (status ✅ live), and add the consumed endpoints to the "Engine endpoints consumed" table: `GET/POST /baas/v1/customers`, `GET/PUT /baas/v1/customers/{id}`, `POST /baas/v1/customers/{id}/{activate|suspend|reactivate|close}`, `GET /baas/v1/customers/{id}/kyc-events`.

- [ ] **Step 2: Commit**

```bash
git add docs/backoffice-operations.md
git commit -m "docs(backoffice-operations): Customers routes + consumed endpoints"
```

---

## Task 10: Full verification + push

- [ ] **Step 1: Verify all green**

Run:
```bash
npm run typecheck && npm test && npm run build
```
Expected: typecheck clean; all tests pass; build succeeds.

- [ ] **Step 2: Push + open PR** (via `finishing-a-development-branch`)

```bash
git push -u origin feat/baas-backoffice-customers
```

---

## Notes for the implementer

- **The `as never` casts on `client.GET/POST/PUT`** are a pragmatic bridge: `openapi-fetch` is strict about path/param types, and the hand-seeded `schema.d.ts` may not perfectly type the dynamic command path. The hooks cast inputs and unwrap results to the local interfaces (`CustomerDetail`, `KycEvent`, etc.), which the tests assert against. This mirrors how the Foundation's dashboard/operator hooks bridge the typed client.
- **`useKycTransition` builds the path** as `/baas/v1/customers/{id}/${command}` — the four commands map 1:1 to the engine endpoints.
- **Action visibility = the engine state machine** (`ACTIONS` map). Keep it in sync with the backend: PENDING_KYC→activate; ACTIVE→suspend/close; SUSPENDED→reactivate/close; CLOSED→none. The detail test asserts only Activate shows for PENDING_KYC.
- **`cleanCreate`** strips empty-string optionals so the backend receives absent fields (not `""`), which matters for `@Email` validation on an optional email.
- The nav item for `/customers` already exists in `src/layout/nav-config.ts` (gated `READ_CUSTOMER`); this task only wires the **routes**.
- Do not rebuild Foundation primitives (CommandModal, FormField, DataTable, StatusBadge, Input, Button) — import them.
