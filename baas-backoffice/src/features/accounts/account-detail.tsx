import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { RequirePermission } from '@/components/require-permission';
import { useAuth } from '@/auth/context';
import { hasPermission, PERMISSIONS } from '@/lib/rbac';
import { formatDateTime, formatMoney } from '@/lib/format';
import {
  useAccount,
  useAccountStatusEvents,
  useAccountTransactions,
  useDeposit,
  useWithdraw,
  useAccountTransition,
  type AccountCommand,
  type AccountStatus,
} from './use-accounts';
import { AccountStatusBadge } from './account-status-badge';
import { AccountActionModal } from './account-action-modal';
import { AccountStatusHistory } from './account-status-history';
import { MoneyModal, type MoneyMode } from './money-modal';
import { TransactionLedger } from './transaction-ledger';

// `PERMISSIONS.UPDATE_ACCOUNT` is the engine code that gates every lifecycle transition (spec §3.3).

// Lifecycle commands available from each status — mirrors the engine state machine (spec §6):
// ACTIVE → freeze, close (close is additionally gated on a zero balance, see canClose);
// FROZEN → unfreeze; CLOSED → none.
const LIFECYCLE_ACTIONS: Record<AccountStatus, AccountCommand[]> = {
  ACTIVE: ['freeze', 'close'],
  FROZEN: ['unfreeze'],
  CLOSED: [],
};
// Money operations available from each status (legal-hold model, spec §6):
// deposit allowed on ACTIVE+FROZEN; withdraw on ACTIVE only; CLOSED blocks both.
const CAN_DEPOSIT: Record<AccountStatus, boolean> = { ACTIVE: true, FROZEN: true, CLOSED: false };
const CAN_WITHDRAW: Record<AccountStatus, boolean> = { ACTIVE: true, FROZEN: false, CLOSED: false };

const capitalize = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

export function AccountDetail() {
  const { id = '' } = useParams();
  const auth = useAuth();
  const account = useAccount(id);
  const events = useAccountStatusEvents(id);
  const txns = useAccountTransactions(id);
  const deposit = useDeposit(id);
  const withdraw = useWithdraw(id);
  const transition = useAccountTransition(id);
  const [action, setAction] = useState<AccountCommand | null>(null);
  const [money, setMoney] = useState<MoneyMode | null>(null);

  if (account.isLoading)
    return <p className="px-4 py-10 text-center text-sm text-muted">Loading…</p>;
  if (account.isError || !account.data)
    return <p className="px-4 py-10 text-center text-sm text-danger">Account not found.</p>;

  const a = account.data;
  // ?? [] guards against an out-of-union status arriving from the wire (.map on undefined would crash).
  // `close` additionally requires a zero balance (spec §6: close enabled only when balance == 0),
  // so it is filtered out while the account still holds funds.
  const lifecycle = (LIFECYCLE_ACTIONS[a.status] ?? []).filter(
    (cmd) => cmd !== 'close' || a.balance === 0,
  );
  const canDeposit =
    (CAN_DEPOSIT[a.status] ?? false) && hasPermission(auth.getAuthorities(), PERMISSIONS.DEPOSIT);
  const canWithdraw =
    (CAN_WITHDRAW[a.status] ?? false) && hasPermission(auth.getAuthorities(), PERMISSIONS.WITHDRAW);

  return (
    <div className="space-y-4">
      <div className="mb-4 flex items-start justify-between">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">{a.accountNumber}</h1>
          <div className="mt-1 flex items-center gap-2 text-sm text-muted">
            <AccountStatusBadge status={a.status} />
            <Link to={`/customers/${a.customerId}`} className="text-brand-primary">
              {a.customerName}
            </Link>
            <span>· {formatMoney(a.balance, a.currencyCode)}</span>
          </div>
        </div>
        <div className="flex gap-2">
          {canDeposit && <Button onClick={() => setMoney('deposit')}>Deposit</Button>}
          {canWithdraw && (
            <Button variant="outline" onClick={() => setMoney('withdraw')}>Withdraw</Button>
          )}
          <RequirePermission code={PERMISSIONS.UPDATE_ACCOUNT}>
            {lifecycle.map((cmd) => (
              <Button key={cmd} variant="outline" onClick={() => setAction(cmd)}>
                {capitalize(cmd)}
              </Button>
            ))}
          </RequirePermission>
        </div>
      </div>

      <div className="rounded-[var(--radius-card)] border border-border bg-surface p-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <Field label="Type" value={a.accountTypeLabel} />
          <Field label="Currency" value={a.currencyCode} />
          <Field label="Balance" value={formatMoney(a.balance, a.currencyCode)} />
          <Field label="Available balance" value={formatMoney(a.availableBalance, a.currencyCode)} />
          <Field label="Minimum balance" value={formatMoney(a.minimumBalance, a.currencyCode)} />
          {/* overdraftLimit is number | null; only render an amount when overdraft is
              allowed AND a limit is set, otherwise show "No overdraft". */}
          <Field
            label="Overdraft"
            value={
              a.allowOverdraft && a.overdraftLimit != null
                ? `Up to ${formatMoney(a.overdraftLimit, a.currencyCode)}`
                : 'No overdraft'
            }
          />
          <Field label="Opened" value={a.openedAt ? formatDateTime(a.openedAt) : null} />
        </div>
      </div>

      <div className="rounded-[var(--radius-card)] border border-border bg-surface p-4">
        <h2 className="mb-3 font-semibold">Transactions</h2>
        <TransactionLedger transactions={txns.data?.items ?? []} />
      </div>

      <div className="rounded-[var(--radius-card)] border border-border bg-surface p-4">
        <h2 className="mb-3 font-semibold">Status history</h2>
        <AccountStatusHistory events={events.data ?? []} />
      </div>

      {action && (
        <AccountActionModal
          open
          command={action}
          onOpenChange={() => setAction(null)}
          onConfirm={async (reason) => {
            await transition.mutateAsync({ command: action, reason });
            setAction(null);
          }}
        />
      )}

      {money && (
        <MoneyModal
          open
          mode={money}
          onOpenChange={() => setMoney(null)}
          onConfirm={async (body) => {
            if (money === 'deposit') await deposit.mutateAsync(body);
            else await withdraw.mutateAsync(body);
            setMoney(null);
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
