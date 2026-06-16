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
