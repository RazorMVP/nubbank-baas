import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { TransactionLedger } from './transaction-ledger';
import { formatMoney } from '@/lib/format';
import type { AccountTransaction } from './use-accounts';

const TXNS: AccountTransaction[] = [
  { id: 't1', accountId: 'a1', transactionType: 'CREDIT', amount: 2500, runningBalance: 2500,
    currencyCode: 'NGN', reference: 'OPENING_DEPOSIT', createdAt: '2026-06-10T10:00:00Z' },
  { id: 't2', accountId: 'a1', transactionType: 'DEBIT', amount: 100, runningBalance: 2400,
    currencyCode: 'NGN', reference: null, createdAt: '2026-06-11T09:00:00Z' },
];

describe('TransactionLedger', () => {
  it('renders CREDIT and DEBIT rows with amount, running balance, reference', () => {
    render(<TransactionLedger transactions={TXNS} />);
    expect(screen.getByText('CREDIT')).toBeInTheDocument();
    expect(screen.getByText('DEBIT')).toBeInTheDocument();
    expect(screen.getByText('OPENING_DEPOSIT')).toBeInTheDocument();
    // Amount + running balance render via the shared formatMoney helper (mirrors
    // accounts-list.test.tsx), not an inlined "NGN 2500" string. Row 1's amount and
    // running balance are both 2500, so the formatted value appears twice — assert via
    // getAllByText.
    expect(screen.getAllByText(formatMoney(2500, 'NGN')).length).toBeGreaterThan(0);
    expect(screen.getByText(formatMoney(2400, 'NGN'))).toBeInTheDocument();
  });

  it('renders an empty message when there are no transactions', () => {
    render(<TransactionLedger transactions={[]} />);
    expect(screen.getByText(/no transactions/i)).toBeInTheDocument();
  });
});
