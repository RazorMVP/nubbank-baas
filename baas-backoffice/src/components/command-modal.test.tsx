import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { z } from 'zod';
import { CommandModal } from './command-modal';
import { FormField } from './form-field';

const schema = z.object({ name: z.string().min(1, 'Required') });

describe('CommandModal', () => {
  it('submits valid values', async () => {
    const onSubmit = vi.fn(async () => {});
    render(
      <CommandModal
        open
        onOpenChange={() => {}}
        title="New customer"
        schema={schema}
        defaultValues={{ name: '' }}
        onSubmit={onSubmit}
      >
        {(form) => <FormField label="Name" error={form.formState.errors.name?.message}><input {...form.register('name')} /></FormField>}
      </CommandModal>,
    );
    await userEvent.type(screen.getByLabelText('Name'), 'Acme');
    await userEvent.click(screen.getByRole('button', { name: /save/i }));
    expect(onSubmit).toHaveBeenCalledWith({ name: 'Acme' }, expect.anything());
  });

  it('blocks submit and shows validation error', async () => {
    const onSubmit = vi.fn(async () => {});
    render(
      <CommandModal
        open
        onOpenChange={() => {}}
        title="New customer"
        schema={schema}
        defaultValues={{ name: '' }}
        onSubmit={onSubmit}
      >
        {(form) => <FormField label="Name" error={form.formState.errors.name?.message}><input {...form.register('name')} /></FormField>}
      </CommandModal>,
    );
    await userEvent.click(screen.getByRole('button', { name: /save/i }));
    expect(await screen.findByText('Required')).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });
});
