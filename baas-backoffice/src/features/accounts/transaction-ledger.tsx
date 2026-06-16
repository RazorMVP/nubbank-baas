import { createColumnHelper } from '@tanstack/react-table';
import { DataTable } from '@/components/data-table';
import { formatDateTime, formatMoney } from '@/lib/format';
import type { AccountTransaction } from './use-accounts';

const col = createColumnHelper<AccountTransaction>();
// All display() columns so every TValue is unknown (same reasoning as accounts-list.tsx) —
// a typed accessor would make the helper array heterogeneous in TValue.
const columns = [
  col.display({ id: 'type', header: 'Type', cell: (ctx) => ctx.row.original.transactionType }),
  // Amount + running balance go through the shared formatMoney helper (Task 4) — never
  // re-inline currency formatting here.
  col.display({
    id: 'amount',
    header: 'Amount',
    cell: (ctx) => formatMoney(ctx.row.original.amount, ctx.row.original.currencyCode),
  }),
  col.display({
    id: 'runningBalance',
    header: 'Running balance',
    cell: (ctx) => formatMoney(ctx.row.original.runningBalance, ctx.row.original.currencyCode),
  }),
  col.display({
    id: 'reference',
    header: 'Reference',
    // reference is nullable on AccountTransaction — fall back to an em dash.
    cell: (ctx) => ctx.row.original.reference ?? '—',
  }),
  col.display({
    id: 'date',
    header: 'Date',
    cell: (ctx) => formatDateTime(ctx.row.original.createdAt),
  }),
];

export function TransactionLedger({ transactions }: { transactions: AccountTransaction[] }) {
  return <DataTable columns={columns} data={transactions} emptyMessage="No transactions yet" />;
}
