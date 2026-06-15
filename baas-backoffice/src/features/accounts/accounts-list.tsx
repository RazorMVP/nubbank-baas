import { useState } from 'react';
import { Link } from 'react-router-dom';
import { createColumnHelper } from '@tanstack/react-table';
import { DataTable } from '@/components/data-table';
import { PageHeader } from '@/components/page-header';
import { RequirePermission } from '@/components/require-permission';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { PERMISSIONS } from '@/lib/rbac';
import { humanizeStatus, formatMoney } from '@/lib/format';
import { useAccounts, useOpenAccount, type AccountRow, type AccountStatus } from './use-accounts';
import { AccountStatusBadge } from './account-status-badge';
import { OpenAccountModal } from './open-account-modal';

const STATUSES: Array<AccountStatus | ''> = ['', 'ACTIVE', 'FROZEN', 'CLOSED'];

const col = createColumnHelper<AccountRow>();
// All display() columns so every TValue is unknown — a typed accessor would make the
// helper array heterogeneous in TValue, which TanStack can't unify to one ColumnDef
// (same reasoning as customers-list.tsx).
const columns = [
  col.display({
    id: 'accountNumber',
    header: 'Account #',
    cell: (ctx) => (
      <Link to={`/accounts/${ctx.row.original.id}`} className="font-medium text-brand-primary">
        {ctx.row.original.accountNumber}
      </Link>
    ),
  }),
  col.display({ id: 'customer', header: 'Customer', cell: (ctx) => ctx.row.original.customerName }),
  col.display({ id: 'type', header: 'Type', cell: (ctx) => ctx.row.original.accountTypeLabel }),
  col.display({
    id: 'status',
    header: 'Status',
    cell: (ctx) => <AccountStatusBadge status={ctx.row.original.status} />,
  }),
  col.display({
    id: 'balance',
    header: 'Balance',
    cell: (ctx) => formatMoney(ctx.row.original.balance, ctx.row.original.currencyCode),
  }),
];

export function AccountsList() {
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<AccountStatus | ''>('');
  const [accountModalOpen, setAccountModalOpen] = useState(false);
  const open = useOpenAccount();

  const query = useAccounts({
    page: 0,
    size: 20,
    search: search || undefined,
    status: status || undefined,
  });

  return (
    <div className="space-y-4">
      <PageHeader
        title="Accounts"
        action={
          <RequirePermission code={PERMISSIONS.CREATE_ACCOUNT}>
            <Button onClick={() => setAccountModalOpen(true)}>Open account</Button>
          </RequirePermission>
        }
      />
      <div className="flex gap-2">
        <Input
          placeholder="Search account number…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="max-w-xs"
        />
        <select
          aria-label="Filter by status"
          value={status}
          onChange={(e) => setStatus(e.target.value as AccountStatus | '')}
          className="h-9 rounded-[var(--radius-control)] border border-border bg-surface px-3 text-sm shadow-sm"
        >
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s ? humanizeStatus(s) : 'All statuses'}
            </option>
          ))}
        </select>
      </div>
      {query.isError ? (
        <p className="px-4 py-10 text-center text-sm text-danger">Couldn't load accounts.</p>
      ) : (
        <DataTable
          columns={columns}
          data={query.data?.items ?? []}
          emptyMessage={query.isLoading ? 'Loading…' : 'No accounts'}
        />
      )}
      {accountModalOpen && (
        <OpenAccountModal
          open
          onOpenChange={setAccountModalOpen}
          // OpenAccountModal maps its form values to an OpenAccountBody internally (toBody),
          // so onSubmit receives a ready OpenAccountBody — pass it straight to the mutation.
          onSubmit={async (body) => {
            await open.mutateAsync(body);
            setAccountModalOpen(false);
          }}
        />
      )}
    </div>
  );
}
