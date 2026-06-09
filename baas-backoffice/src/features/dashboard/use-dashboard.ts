import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '@/api/context';
import { qk } from '@/api/query';
import { unwrap, extractPage, type Envelope, type Page, type NormalizedPage } from '@/api/envelope';

export interface CustomerRow {
  id: string;
  firstName: string;
  lastName: string;
  kycStatus: string;
  accountNumber?: string;
  balance?: number;
}

export function useRecentCustomers(size = 5) {
  const client = useApiClient();
  return useQuery<NormalizedPage<CustomerRow>>({
    queryKey: qk.list('customers', { page: 0, size }),
    queryFn: async () => {
      const { data } = await client.GET('/baas/v1/customers', {
        params: { query: { page: 0, size } },
      });
      const env = data as Envelope<Page<CustomerRow>>;
      return extractPage(unwrap(env));
    },
  });
}
