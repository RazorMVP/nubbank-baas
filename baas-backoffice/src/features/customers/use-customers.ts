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
