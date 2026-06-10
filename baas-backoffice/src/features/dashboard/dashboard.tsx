import { createColumnHelper } from '@tanstack/react-table';
import { useAuth } from '@/auth/context';
import { PageHeader } from '@/components/page-header';
import { DataTable } from '@/components/data-table';
import { StatusBadge, type StatusVariant } from '@/components/status-badge';
import { RequirePermission } from '@/components/require-permission';
import { PERMISSIONS } from '@/lib/rbac';
import { Button } from '@/components/ui/button';
import { KpiTile } from './kpi-tile';
import { useRecentCustomers, useDashboardSummary, type CustomerRow } from './use-dashboard';

const NAIRA = new Intl.NumberFormat('en-NG');
/** Tile value: the number (grouped) when loaded, else an em dash. */
const tile = (n: number | null | undefined, fmt: (v: number) => string = String) =>
  n === null || n === undefined ? '—' : fmt(n);

const KYC_VARIANT: Record<string, StatusVariant> = {
  VERIFIED: 'success',
  PENDING: 'warning',
  KYC_PENDING: 'warning',
  REJECTED: 'danger',
};

const col = createColumnHelper<CustomerRow>();
const columns = [
  col.accessor((r) => `${r.firstName} ${r.lastName}`, { id: 'name', header: 'Name' }),
  col.accessor('kycStatus', {
    header: 'KYC',
    cell: (ctx) => (
      <StatusBadge label={ctx.getValue()} variant={KYC_VARIANT[ctx.getValue()] ?? 'neutral'} />
    ),
  }),
];

export function Dashboard() {
  const name = useAuth().getUser()?.name?.split(' ')[0] ?? 'there';
  const customers = useRecentCustomers(5);
  const summary = useDashboardSummary().data;

  return (
    <div>
      <PageHeader title={`Good afternoon, ${name}`} />

      <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <KpiTile label="Customers" value={tile(summary?.totalCustomers)} tone="tint" />
        <KpiTile
          label="Deposits (₦)"
          value={tile(summary?.totalDeposits, (v) => NAIRA.format(v))}
          tone="plain"
        />
        <KpiTile label="KYC pending" value={tile(summary?.kycPendingCustomers)} tone="plain" />
        {/* cardsIssued is null when card-service is down → tile() renders an em dash. */}
        <KpiTile label="Cards issued" value={tile(summary?.cardsIssued)} tone="ink" />
      </div>

      <div className="rounded-[var(--radius-card)] border border-border bg-surface">
        <div className="flex items-center justify-between border-b border-border px-4 py-3">
          <span className="font-semibold">Recent customers</span>
          <RequirePermission code={PERMISSIONS.CREATE_CUSTOMER}>
            <Button size="sm">+ New customer</Button>
          </RequirePermission>
        </div>
        <div className="p-2">
          {customers.isError ? (
            <p className="px-4 py-10 text-center text-sm text-danger">Couldn't load customers.</p>
          ) : (
            <DataTable
              columns={columns}
              data={customers.data?.items ?? []}
              emptyMessage={customers.isLoading ? 'Loading…' : 'No customers yet'}
            />
          )}
        </div>
      </div>
    </div>
  );
}
