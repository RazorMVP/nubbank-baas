import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
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
  overdraftLimit: number | null;
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
