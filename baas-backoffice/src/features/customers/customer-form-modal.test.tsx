import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { CustomerFormModal } from './customer-form-modal';

describe('CustomerFormModal', () => {
  it('blocks submit when required fields are empty', async () => {
    const onSubmit = vi.fn(async () => {});
    render(<CustomerFormModal open mode="create" onOpenChange={() => {}} onSubmit={onSubmit} />);
    await userEvent.click(screen.getByRole('button', { name: /save/i }));
    expect(await screen.findByText(/first name is required/i)).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('submits the entered values', async () => {
    const onSubmit = vi.fn(async () => {});
    render(<CustomerFormModal open mode="create" onOpenChange={() => {}} onSubmit={onSubmit} />);
    await userEvent.type(screen.getByLabelText(/first name/i), 'John');
    await userEvent.type(screen.getByLabelText(/last name/i), 'Doe');
    await userEvent.click(screen.getByRole('button', { name: /save/i }));
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ firstName: 'John', lastName: 'Doe' }),
      expect.anything());
  });

  it('shows email validation error for malformed email', async () => {
    const onSubmit = vi.fn(async () => {});
    render(<CustomerFormModal open mode="create" onOpenChange={() => {}} onSubmit={onSubmit} />);
    await userEvent.type(screen.getByLabelText(/first name/i), 'John');
    await userEvent.type(screen.getByLabelText(/last name/i), 'Doe');
    await userEvent.type(screen.getByLabelText(/email/i), 'notanemail');
    await userEvent.click(screen.getByRole('button', { name: /save/i }));
    expect(await screen.findByText(/email must be valid/i)).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('omits BVN/NIN in edit mode', () => {
    render(<CustomerFormModal open mode="edit" onOpenChange={() => {}} onSubmit={vi.fn()}
      defaultValues={{ firstName: 'A', lastName: 'B' }} />);
    expect(screen.queryByLabelText(/external reference/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/bvn/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/nin/i)).not.toBeInTheDocument();
  });
});
