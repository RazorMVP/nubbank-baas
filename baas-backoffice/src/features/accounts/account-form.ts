import { z } from 'zod';

export const openAccountSchema = z.object({
  customerId: z.string().min(1, 'Select a customer'),
  accountTypeLabel: z.string().min(1, 'Account type is required'),
  // No .default() here: a default makes the Zod *input* type optional while the
  // *output* stays required, which diverges z.infer from the input and breaks
  // CommandModal's ZodType<T>. The 'NGN' default is supplied via the modal's
  // RHF defaultValues instead (same pattern as customer-form-modal).
  currencyCode: z.string().min(1),
  // Optional opening deposit modelled as an optional string (same pattern the
  // customers form uses for optional fields), NOT z.coerce.number() — coercion
  // changes the input type (unknown) vs output type (number) and breaks
  // input === output. We validate it parses to a number >= 0 here, then convert
  // to a number at the API-body boundary in onSubmit.
  // .optional() allows undefined (field omitted); .or(z.literal('')) allows the
  // empty-string an untyped <input type="number"> emits; .refine enforces ≥ 0 only
  // when a non-empty value is present.
  openingDeposit: z
    .string()
    .optional()
    .or(z.literal(''))
    .refine(
      (v) => v === '' || v === undefined || (!isNaN(Number(v)) && Number(v) >= 0),
      'Opening deposit must be ≥ 0',
    ),
});

export type OpenAccountValues = z.infer<typeof openAccountSchema>;
