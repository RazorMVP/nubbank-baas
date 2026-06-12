import { z } from 'zod';
import { CommandModal } from '@/components/command-modal';
import { FormField } from '@/components/form-field';
import { Input } from '@/components/ui/input';
import type { KycCommand } from './use-customers';

const schema = z.object({ reason: z.string().min(1, 'Reason is required') });

const LABEL: Record<KycCommand, string> = {
  activate: 'Activate customer',
  suspend: 'Suspend customer',
  reactivate: 'Reactivate customer',
  close: 'Close customer',
};

export function KycActionModal({
  open,
  command,
  onOpenChange,
  onConfirm,
}: {
  open: boolean;
  command: KycCommand;
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
