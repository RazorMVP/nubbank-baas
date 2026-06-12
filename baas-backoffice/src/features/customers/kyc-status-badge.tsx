import { StatusBadge, type StatusVariant } from '@/components/status-badge';
import type { KycStatus } from './use-customers';

const VARIANT: Record<KycStatus, StatusVariant> = {
  PENDING_KYC: 'warning',
  ACTIVE: 'success',
  SUSPENDED: 'danger',
  CLOSED: 'neutral',
};

export function KycStatusBadge({ status }: { status: KycStatus }) {
  return <StatusBadge label={status.replace('_', ' ')} variant={VARIANT[status] ?? 'neutral'} />;
}
