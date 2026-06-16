import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { AccountActionModal } from './account-action-modal';
import { AccountStatusHistory } from './account-status-history';
import type { StatusEvent } from './use-accounts';

describe('AccountActionModal', () => {
  it('requires a reason and submits it', async () => {
    const onConfirm = vi.fn(async () => {});
    render(<AccountActionModal open command="freeze" onOpenChange={() => {}} onConfirm={onConfirm} />);
    await userEvent.click(screen.getByRole('button', { name: /confirm/i }));
    expect(await screen.findByText(/reason is required/i)).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();

    await userEvent.type(screen.getByLabelText(/reason/i), 'legal hold');
    await userEvent.click(screen.getByRole('button', { name: /confirm/i }));
    expect(onConfirm).toHaveBeenCalledWith('legal hold');
  });

  it.each([
    ['freeze', 'Freeze account'],
    ['unfreeze', 'Unfreeze account'],
    ['close', 'Close account'],
  ] as const)('titles the modal correctly for command "%s"', (command, expectedTitle) => {
    render(<AccountActionModal open command={command} onOpenChange={() => {}} onConfirm={vi.fn()} />);
    expect(screen.getByRole('heading', { name: expectedTitle })).toBeInTheDocument();
  });
});

describe('AccountStatusHistory', () => {
  it('shows an empty state when there are no events', () => {
    render(<AccountStatusHistory events={[]} />);
    expect(screen.getByText('No status changes yet.')).toBeInTheDocument();
  });

  it('renders a status transition and reason', () => {
    const event: StatusEvent = {
      id: 'ev1',
      fromStatus: 'ACTIVE',
      toStatus: 'FROZEN',
      reason: 'suspected fraud',
      changedBy: 'admin@bank.com',
      changedAt: '2026-06-12T09:00:00Z',
    };
    render(<AccountStatusHistory events={[event]} />);
    expect(screen.getByText(/ACTIVE → FROZEN/)).toBeInTheDocument();
    expect(screen.getByText('suspected fraud')).toBeInTheDocument();
    expect(screen.getByText(/admin@bank\.com/)).toBeInTheDocument();
  });

  it('renders a dash when changedBy is null', () => {
    const event: StatusEvent = {
      id: 'ev2',
      fromStatus: 'ACTIVE',
      toStatus: 'CLOSED',
      reason: 'customer request',
      changedBy: null,
      changedAt: '2026-06-12T10:00:00Z',
    };
    render(<AccountStatusHistory events={[event]} />);
    expect(screen.getByText(/—/)).toBeInTheDocument();
    expect(screen.queryByText('null')).not.toBeInTheDocument();
  });
});
