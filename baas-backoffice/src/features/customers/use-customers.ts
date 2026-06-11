import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
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
  kycStatus?: KycStatus;
  search?: string;
}

export function useCustomers(params: CustomerListParams) {
  const client = useApiClient();
  const query: Record<string, unknown> = { page: params.page, size: params.size };
  if (params.kycStatus) query.kycStatus = params.kycStatus;
  if (params.search) query.search = params.search;
  return useQuery<NormalizedPage<CustomerRow>>({
    queryKey: qk.list('customers', query),
    queryFn: async () => {
      const result = await client.GET('/baas/v1/customers', { params: { query } } as never);
      return extractPage(unwrapResult<Page<CustomerRow>>(result));
    },
  });
}

export function useCustomer(id: string) {
  const client = useApiClient();
  return useQuery<CustomerDetail>({
    queryKey: qk.detail('customers', id),
    queryFn: async () => {
      const result = await client.GET('/baas/v1/customers/{id}', { params: { path: { id } } } as never);
      return unwrapResult<CustomerDetail>(result);
    },
  });
}

export function useCustomerKycEvents(id: string) {
  const client = useApiClient();
  return useQuery<KycEvent[]>({
    queryKey: [...qk.detail('customers', id), 'kyc-events'],
    queryFn: async () => {
      const result = await client.GET('/baas/v1/customers/{id}/kyc-events', { params: { path: { id } } } as never);
      return unwrapResult<KycEvent[]>(result);
    },
  });
}

// ─── Mutation hooks ────────────────────────────────────────────────────────

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
      const result = await client.PUT('/baas/v1/customers/{id}', { params: { path: { id } }, body } as never);
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
