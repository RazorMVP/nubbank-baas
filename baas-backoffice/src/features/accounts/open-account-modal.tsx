import { useState } from 'react';
import { CommandModal } from '@/components/command-modal';
import { FormField } from '@/components/form-field';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { useCustomers, type CustomerRow } from '@/features/customers/use-customers';
import { openAccountSchema, type OpenAccountValues } from './account-form';
import type { OpenAccountBody } from './use-accounts';

const ACCOUNT_TYPES = ['Savings', 'Checking', 'Current'];
const CURRENCIES = ['NGN', 'USD', 'GHS'];

// Map validated form values → the API body. openingDeposit is an optional string
// in the schema (input === output); convert it to a number here, at the body
// boundary: empty/undefined → omit; non-empty → Number(value).
function toBody(values: OpenAccountValues): OpenAccountBody {
  const { openingDeposit, ...rest } = values;
  const body: OpenAccountBody = { ...rest };
  if (openingDeposit !== undefined && openingDeposit !== '') {
    body.openingDeposit = Number(openingDeposit);
  }
  return body;
}

export function OpenAccountModal({
  open,
  onOpenChange,
  onSubmit,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (body: OpenAccountBody) => void | Promise<void>;
}) {
  return (
    <CommandModal<OpenAccountValues>
      open={open}
      onOpenChange={onOpenChange}
      title="Open account"
      submitLabel="Open account"
      schema={openAccountSchema}
      defaultValues={{
        customerId: '',
        accountTypeLabel: 'Savings',
        currencyCode: 'NGN',
        openingDeposit: '',
      }}
      onSubmit={(values) => onSubmit(toBody(values))}
    >
      {(form) => (
        <>
          <CustomerPicker
            error={form.formState.errors.customerId?.message}
            onPick={(c) => form.setValue('customerId', c.id, { shouldValidate: true })}
          />
          <FormField label="Account type" error={form.formState.errors.accountTypeLabel?.message}>
            <select {...form.register('accountTypeLabel')}>
              {ACCOUNT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>
          </FormField>
          <FormField label="Currency" error={form.formState.errors.currencyCode?.message}>
            <select {...form.register('currencyCode')}>
              {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
          </FormField>
          <FormField label="Opening deposit (optional)"
            error={form.formState.errors.openingDeposit?.message}>
            <Input type="number" step="0.01" min="0" {...form.register('openingDeposit')} />
          </FormField>
        </>
      )}
    </CommandModal>
  );
}

// Debounced customer search box. Reuses useCustomers (GET /baas/v1/customers?search=).
// The selected customer's name is tracked in local state (UI-only); picking a result
// reports the id up to the form via onPick. No schema field for the name → no cast.
//
// FormField clones its ONE labellable child and injects the id/htmlFor pairing, so the
// search Input is its only child. The results dropdown renders as a SIBLING below the
// FormField (not a second child inside it) — otherwise the label would target a
// non-labellable <div> wrapper and getByLabelText(/find customer/i) would not resolve.
function CustomerPicker({
  onPick,
  error,
}: {
  onPick: (c: CustomerRow) => void;
  error?: string;
}) {
  const [term, setTerm] = useState('');
  const [selectedName, setSelectedName] = useState<string | null>(null);
  // Only search once 2+ chars are typed; keeps the dropdown empty initially.
  const query = useCustomers({ page: 0, size: 5, search: term.length >= 2 ? term : undefined });
  return (
    <>
      <FormField label="Find customer" error={error}>
        <Input placeholder="Search customer name…" value={term}
          onChange={(e) => setTerm(e.target.value)} />
      </FormField>
      {selectedName && (
        <p className="-mt-2 mb-3 text-xs text-muted">Selected: {selectedName}</p>
      )}
      {term.length >= 2 && (
        <ul className="-mt-2 mb-3 space-y-1">
          {(query.data?.items ?? []).map((c) => (
            <li key={c.id}>
              <Button type="button" variant="outline" className="w-full justify-start"
                onClick={() => {
                  onPick(c);
                  setSelectedName(`${c.firstName} ${c.lastName}`);
                  setTerm('');
                }}>
                {c.firstName} {c.lastName}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
