import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { type ColumnDef } from '@tanstack/react-table';
import { DataTable } from '@/components/data-table';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { useAuth } from '@/auth/context';
import { hasPermission, PERMISSIONS } from '@/lib/rbac';
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

export function CustomersList() {
  const auth = useAuth();
  const canCreate = hasPermission(auth.getAuthorities(), PERMISSIONS.CREATE_CUSTOMER);
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

  const columns = useMemo<ColumnDef<CustomerRow>[]>(
    () => [
      {
        header: 'Name',
        cell: ({ row }) => (
          <Link to={`/customers/${row.original.id}`} className="font-medium text-brand-primary">
            {row.original.firstName} {row.original.lastName}
          </Link>
        ),
      },
      { header: 'Email', accessorKey: 'email' },
      { header: 'External ref', accessorKey: 'externalReference' },
      { header: 'KYC', cell: ({ row }) => <KycStatusBadge status={row.original.kycStatus} /> },
    ],
    [],
  );

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold">Customers</h1>
        {canCreate && <Button onClick={() => setCreateOpen(true)}>New customer</Button>}
      </div>
      <div className="flex gap-2">
        <Input
          placeholder="Search name or external ref…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="max-w-xs"
        />
        <select
          value={kycStatus}
          onChange={(e) => setKycStatus(e.target.value as KycStatus | '')}
          className="rounded-[var(--radius-control)] border border-border px-3 text-sm"
        >
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s ? s.replace('_', ' ') : 'All statuses'}
            </option>
          ))}
        </select>
      </div>
      <DataTable
        columns={columns}
        data={query.data?.items ?? []}
        emptyMessage={query.isLoading ? 'Loading…' : 'No customers'}
      />
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
