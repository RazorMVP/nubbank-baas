import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { RequirePermission } from '@/components/require-permission';
import { PERMISSIONS } from '@/lib/rbac';
import { formatDateTime } from '@/lib/format';
import {
  useCustomer,
  useCustomerKycEvents,
  useUpdateCustomer,
  useKycTransition,
  type KycCommand,
  type KycStatus,
} from './use-customers';
import { KycStatusBadge } from './kyc-status-badge';
import { KycActionModal } from './kyc-action-modal';
import { KycHistory } from './kyc-history';
import { CustomerFormModal } from './customer-form-modal';

// KYC actions available from each status — derived purely from status, not permissions.
// The whole button group is gated by <RequirePermission code={UPDATE_CUSTOMER}>.
const ACTIONS: Record<KycStatus, KycCommand[]> = {
  PENDING_KYC: ['activate'],
  ACTIVE: ['suspend', 'close'],
  SUSPENDED: ['reactivate', 'close'],
  CLOSED: [],
};

const capitalize = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

export function CustomerDetail() {
  const { id = '' } = useParams();
  const customer = useCustomer(id);
  const events = useCustomerKycEvents(id);
  const update = useUpdateCustomer(id);
  const transition = useKycTransition(id);
  const [editOpen, setEditOpen] = useState(false);
  const [action, setAction] = useState<KycCommand | null>(null);

  if (customer.isLoading) return <p className="px-4 py-10 text-center text-sm text-muted">Loading…</p>;
  if (customer.isError || !customer.data)
    return <p className="px-4 py-10 text-center text-sm text-danger">Customer not found.</p>;

  const c = customer.data;
  // ?? [] guards against an out-of-union kycStatus arriving from the wire (TS can't prove wire data; .map on undefined would crash the page).
  const actions = ACTIONS[c.kycStatus] ?? [];

  return (
    <div className="space-y-4">
      {/* PageHeader has no subtitle slot, so we render a custom header that matches its
          h1 classes (text-xl font-semibold tracking-tight) for size consistency with the list. */}
      <div className="mb-4 flex items-start justify-between">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">
            {c.firstName} {c.lastName}
          </h1>
          <div className="mt-1 flex items-center gap-2 text-sm text-muted">
            <KycStatusBadge status={c.kycStatus} />
            <span>{c.externalReference ?? '—'}</span>
          </div>
        </div>
        <RequirePermission code={PERMISSIONS.UPDATE_CUSTOMER}>
          <div className="flex gap-2">
            {actions.map((cmd) => (
              <Button key={cmd} variant="outline" onClick={() => setAction(cmd)}>
                {capitalize(cmd)}
              </Button>
            ))}
            <Button onClick={() => setEditOpen(true)}>Edit</Button>
          </div>
        </RequirePermission>
      </div>

      <div className="rounded-[var(--radius-card)] border border-border bg-surface p-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <Field label="Email" value={c.email} />
          <Field label="Phone" value={c.phone} />
          <Field label="Date of birth" value={c.dateOfBirth} />
          <Field label="Gender" value={c.gender} />
          <Field label="KYC level" value={c.kycLevel} />
          <Field label="BVN" value={c.bvnMasked} />
          <Field label="NIN" value={c.ninMasked} />
          <Field label="Created" value={c.createdAt ? formatDateTime(c.createdAt) : null} />
          <Field label="Updated" value={c.updatedAt ? formatDateTime(c.updatedAt) : null} />
        </div>
      </div>

      <div className="rounded-[var(--radius-card)] border border-border bg-surface p-4">
        <h2 className="mb-3 font-semibold">KYC history</h2>
        <KycHistory events={events.data ?? []} />
      </div>

      {action && (
        <KycActionModal
          open
          command={action}
          onOpenChange={() => setAction(null)}
          onConfirm={async (reason) => {
            await transition.mutateAsync({ command: action, reason });
            setAction(null);
          }}
        />
      )}

      {/* Conditionally mounted so each open is a fresh form seeded from the current customer —
          CommandModal does not reset on reopen, so an always-mounted modal would show stale values. */}
      {editOpen && (
        <CustomerFormModal
          open
          mode="edit"
          onOpenChange={setEditOpen}
          defaultValues={{
            firstName: c.firstName,
            lastName: c.lastName,
            email: c.email ?? '',
            phone: c.phone ?? '',
            dateOfBirth: c.dateOfBirth ?? '',
            gender: c.gender ?? '',
          }}
          onSubmit={async (v) => {
            await update.mutateAsync({
              firstName: v.firstName,
              lastName: v.lastName,
              email: v.email || undefined,
              phone: v.phone || undefined,
              dateOfBirth: v.dateOfBirth || undefined,
              gender: v.gender || undefined,
            });
            setEditOpen(false);
          }}
        />
      )}
    </div>
  );
}

function Field({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div>
      <div className="text-xs text-muted">{label}</div>
      <div>{value || '—'}</div>
    </div>
  );
}
