import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { AccountActionModal } from './account-action-modal';

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

  it('titles the modal by command (close)', () => {
    render(<AccountActionModal open command="close" onOpenChange={() => {}} onConfirm={vi.fn()} />);
    expect(screen.getByRole('heading', { name: /close account/i })).toBeInTheDocument();
  });
});
