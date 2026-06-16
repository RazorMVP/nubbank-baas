import { z } from 'zod';
import { CommandModal } from '@/components/command-modal';
import { FormField } from '@/components/form-field';
import { Input } from '@/components/ui/input';
import type { AccountCommand } from './use-accounts';

const schema = z.object({ reason: z.string().trim().min(1, 'Reason is required') });

const LABEL: Record<AccountCommand, string> = {
  freeze: 'Freeze account',
  unfreeze: 'Unfreeze account',
  close: 'Close account',
};

export function AccountActionModal({
  open,
  command,
  onOpenChange,
  onConfirm,
}: {
  open: boolean;
  command: AccountCommand;
  onOpenChange: (open: boolean) => void;
  onConfirm: (reason: string) => void | Promise<void>;
}) {
  return (
    <CommandModal
      open={open}
      onOpenChange={onOpenChange}
      title={LABEL[command]}
      submitLabel="Confirm"
      schema={schema}
      defaultValues={{ reason: '' }}
      onSubmit={async (v) => {
        await onConfirm(v.reason);
      }}
    >
      {(form) => (
        <FormField label="Reason" error={form.formState.errors.reason?.message}>
          <Input {...form.register('reason')} />
        </FormField>
      )}
    </CommandModal>
  );
}
