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
