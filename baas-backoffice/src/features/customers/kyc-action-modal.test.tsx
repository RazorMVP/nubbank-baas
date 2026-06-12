import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { KycActionModal } from './kyc-action-modal';
import { KycHistory } from './kyc-history';
import type { KycEvent } from './use-customers';

describe('KycActionModal', () => {
  it('requires a reason and submits it', async () => {
    const onConfirm = vi.fn(async () => {});
    render(<KycActionModal open command="suspend" onOpenChange={() => {}} onConfirm={onConfirm} />);
    await userEvent.click(screen.getByRole('button', { name: /confirm/i }));
    expect(await screen.findByText(/reason is required/i)).toBeInTheDocument();
    expect(onConfirm).not.toHaveBeenCalled();

    await userEvent.type(screen.getByLabelText(/reason/i), 'fraud flag');
    await userEvent.click(screen.getByRole('button', { name: /confirm/i }));
    expect(onConfirm).toHaveBeenCalledWith('fraud flag');
  });
});

describe('KycHistory', () => {
  it('shows an empty state when there are no events', () => {
    render(<KycHistory events={[]} />);
    expect(screen.getByText(/no kyc events yet/i)).toBeInTheDocument();
  });

  it('renders a single event with its transition and reason', () => {
    const event: KycEvent = {
      id: 'e1',
      fromStatus: 'PENDING_KYC',
      toStatus: 'ACTIVE',
      reason: 'verified',
      changedBy: 'op@x.com',
      changedAt: '2026-06-10T10:00:00Z',
    };
    render(<KycHistory events={[event]} />);
    expect(screen.getByText(/PENDING_KYC → ACTIVE/)).toBeInTheDocument();
    expect(screen.getByText('verified')).toBeInTheDocument();
    expect(screen.getByText(/op@x\.com/)).toBeInTheDocument();
  });
});
