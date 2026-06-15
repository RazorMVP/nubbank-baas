import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { MoneyModal } from './money-modal';

describe('MoneyModal', () => {
  it('requires a positive amount and submits it', async () => {
    const onConfirm = vi.fn(async () => {});
    render(<MoneyModal open mode="deposit" onOpenChange={() => {}} onConfirm={onConfirm} />);
    // Empty amount → blocked.
    await userEvent.click(screen.getByRole('button', { name: /deposit/i }));
    expect(await screen.findByText(/amount must be greater than 0/i)).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();

    await userEvent.type(screen.getByLabelText(/amount/i), '500');
    await userEvent.type(screen.getByLabelText(/reference/i), 'cash-in');
    await userEvent.click(screen.getByRole('button', { name: /deposit/i }));
    expect(onConfirm).toHaveBeenCalledWith({ amount: 500, reference: 'cash-in' });
  });

  it('uses the Withdraw verb in withdraw mode', () => {
    render(<MoneyModal open mode="withdraw" onOpenChange={() => {}} onConfirm={vi.fn()} />);
    expect(screen.getByRole('heading', { name: /withdraw/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /withdraw/i })).toBeInTheDocument();
  });
});
