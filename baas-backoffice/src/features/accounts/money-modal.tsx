import { z } from 'zod';
import { CommandModal } from '@/components/command-modal';
import { FormField } from '@/components/form-field';
import { Input } from '@/components/ui/input';
import type { MoneyBody } from './use-accounts';

export type MoneyMode = 'deposit' | 'withdraw';

// amount is modelled as a string (NOT z.coerce.number()): coercion makes the Zod
// *input* type `unknown` while the *output* is `number`, so z.infer diverges from the
// input and breaks CommandModal's ZodType<T> (input === output). Same correction Task 3
// applied to openingDeposit. We .refine the string parses to a number > 0 here, then
// convert to a number at the API-body boundary in onSubmit.
// reference is an optional free-text string (.or('') accepts the empty default).
const schema = z.object({
  amount: z
    .string()
    .refine((v) => v !== '' && !isNaN(Number(v)) && Number(v) > 0, 'Amount must be greater than 0'),
  reference: z.string().optional().or(z.literal('')),
});
type MoneyValues = z.infer<typeof schema>;

const TITLE: Record<MoneyMode, string> = { deposit: 'Deposit', withdraw: 'Withdraw' };

// Map validated form values → the API body. amount is a string in the schema
// (input === output); convert it to a number here, at the body boundary. reference is
// omitted when empty so the body is exactly { amount } or { amount, reference }.
function toBody(values: MoneyValues): MoneyBody {
  const body: MoneyBody = { amount: Number(values.amount) };
  if (values.reference) body.reference = values.reference;
  return body;
}

export function MoneyModal({
  open,
  mode,
  onOpenChange,
  onConfirm,
}: {
  open: boolean;
  mode: MoneyMode;
  onOpenChange: (open: boolean) => void;
  onConfirm: (body: MoneyBody) => void | Promise<void>;
}) {
  return (
    <CommandModal<MoneyValues>
      open={open}
      onOpenChange={onOpenChange}
      title={TITLE[mode]}
      submitLabel={TITLE[mode]}
      schema={schema}
      defaultValues={{ amount: '', reference: '' }}
      onSubmit={(values) => onConfirm(toBody(values))}
    >
      {(form) => (
        <>
          <FormField label="Amount" error={form.formState.errors.amount?.message}>
            <Input type="number" step="0.01" min="0.01" {...form.register('amount')} />
          </FormField>
          <FormField label="Reference (optional)">
            <Input {...form.register('reference')} />
          </FormField>
        </>
      )}
    </CommandModal>
  );
}
