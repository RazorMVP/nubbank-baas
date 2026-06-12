import { useState } from 'react';
import { Link } from 'react-router-dom';
import { createColumnHelper } from '@tanstack/react-table';
import { DataTable } from '@/components/data-table';
import { PageHeader } from '@/components/page-header';
import { RequirePermission } from '@/components/require-permission';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { PERMISSIONS } from '@/lib/rbac';
import {
  useCustomers,
  useCreateCustomer,
  type CustomerRow,
  type CustomerWriteBody,
  type KycStatus,
} from './use-customers';
import { KycStatusBadge } from './kyc-status-badge';
import { CustomerFormModal } from './customer-form-modal';
import type { CustomerFormValues } from './customer-form';

const STATUSES: Array<KycStatus | ''> = ['', 'PENDING_KYC', 'ACTIVE', 'SUSPENDED', 'CLOSED'];

const col = createColumnHelper<CustomerRow>();
// All display() columns so every TValue is unknown — a typed accessor (e.g. kycStatus: KycStatus)
// would make the helper array heterogeneous in TValue, which TanStack can't unify to one ColumnDef.
const columns = [
  col.display({
    id: 'name',
    header: 'Name',
    cell: (ctx) => (
      <Link
        to={`/customers/${ctx.row.original.id}`}
        className="font-medium text-brand-primary"
      >
        {ctx.row.original.firstName} {ctx.row.original.lastName}
      </Link>
    ),
  }),
  col.display({ id: 'email', header: 'Email', cell: (ctx) => ctx.row.original.email }),
  col.display({
    id: 'externalReference',
    header: 'External ref',
    cell: (ctx) => ctx.row.original.externalReference,
  }),
  col.display({
    id: 'kyc',
    header: 'KYC',
    cell: (ctx) => <KycStatusBadge status={ctx.row.original.kycStatus} />,
  }),
];

export function CustomersList() {
  const [search, setSearch] = useState('');
  const [kycStatus, setKycStatus] = useState<KycStatus | ''>('');
  const [createOpen, setCreateOpen] = useState(false);
  const create = useCreateCustomer();

  const query = useCustomers({
    page: 0,
    size: 20,
    search: search || undefined,
    kycStatus: kycStatus || undefined,
  });

  return (
    <div className="space-y-4">
      <PageHeader
        title="Customers"
        action={
          <RequirePermission code={PERMISSIONS.CREATE_CUSTOMER}>
            <Button onClick={() => setCreateOpen(true)}>New customer</Button>
          </RequirePermission>
        }
      />
      <div className="flex gap-2">
        <Input
          placeholder="Search name or external ref…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="max-w-xs"
        />
        <select
          aria-label="Filter by KYC status"
          value={kycStatus}
          onChange={(e) => setKycStatus(e.target.value as KycStatus | '')}
          className="h-9 rounded-[var(--radius-control)] border border-border bg-surface px-3 text-sm shadow-sm"
        >
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s ? s.replaceAll('_', ' ') : 'All statuses'}
            </option>
          ))}
        </select>
      </div>
      {query.isError ? (
        <p className="px-4 py-10 text-center text-sm text-danger">Couldn't load customers.</p>
      ) : (
        <DataTable
          columns={columns}
          data={query.data?.items ?? []}
          emptyMessage={query.isLoading ? 'Loading…' : 'No customers'}
        />
      )}
      <CustomerFormModal
        open={createOpen}
        mode="create"
        onOpenChange={setCreateOpen}
        onSubmit={async (v) => {
          await create.mutateAsync(cleanCreate(v));
          setCreateOpen(false);
        }}
      />
    </div>
  );
}

// Strip empty-string optionals so the backend sees absent fields, not "".
// firstName/lastName are required by the form schema (min 1), so they always survive.
// Assembled field-by-field so the return is a real CustomerWriteBody — no cast needed.
function cleanCreate(v: CustomerFormValues): CustomerWriteBody {
  const body: CustomerWriteBody = { firstName: v.firstName, lastName: v.lastName };
  if (v.email) body.email = v.email;
  if (v.phone) body.phone = v.phone;
  if (v.dateOfBirth) body.dateOfBirth = v.dateOfBirth;
  if (v.gender) body.gender = v.gender;
  if (v.externalReference) body.externalReference = v.externalReference;
  if (v.bvn) body.bvn = v.bvn;
  if (v.nin) body.nin = v.nin;
  return body;
}
