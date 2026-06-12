import { StatusBadge, type StatusVariant } from '@/components/status-badge';
import { humanizeStatus } from '@/lib/format';
import type { KycStatus } from './use-customers';

const VARIANT: Record<KycStatus, StatusVariant> = {
  PENDING_KYC: 'warning',
  ACTIVE: 'success',
  SUSPENDED: 'danger',
  CLOSED: 'neutral',
};

export function KycStatusBadge({ status }: { status: KycStatus }) {
  return <StatusBadge label={humanizeStatus(status)} variant={VARIANT[status]} />;
}
