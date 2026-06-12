import { CommandModal } from '@/components/command-modal';
import { FormField } from '@/components/form-field';
import { Input } from '@/components/ui/input';
import { customerFormSchema, type CustomerFormValues } from './customer-form';

export function CustomerFormModal({
  open,
  mode,
  onOpenChange,
  onSubmit,
  defaultValues,
}: {
  open: boolean;
  mode: 'create' | 'edit';
  onOpenChange: (open: boolean) => void;
  onSubmit: (values: CustomerFormValues) => void | Promise<void>;
  defaultValues?: Partial<CustomerFormValues>;
}) {
  const base: CustomerFormValues = {
    firstName: '',
    lastName: '',
    email: '',
    phone: '',
    dateOfBirth: '',
    gender: '',
    externalReference: '',
    bvn: '',
    nin: '',
    ...defaultValues,
  };

  return (
    <CommandModal<CustomerFormValues>
      open={open}
      onOpenChange={onOpenChange}
      title={mode === 'create' ? 'New customer' : 'Edit customer'}
      schema={customerFormSchema}
      defaultValues={base}
      onSubmit={onSubmit}
    >
      {(form) => (
        <>
          <FormField label="First name" error={form.formState.errors.firstName?.message}>
            <Input {...form.register('firstName')} />
          </FormField>
          <FormField label="Last name" error={form.formState.errors.lastName?.message}>
            <Input {...form.register('lastName')} />
          </FormField>
          <FormField label="Email" error={form.formState.errors.email?.message}>
            <Input {...form.register('email')} />
          </FormField>
          <FormField label="Phone" error={form.formState.errors.phone?.message}>
            <Input {...form.register('phone')} />
          </FormField>
          <FormField label="Date of birth" error={form.formState.errors.dateOfBirth?.message}>
            <Input type="date" {...form.register('dateOfBirth')} />
          </FormField>
          <FormField label="Gender" error={form.formState.errors.gender?.message}>
            <Input {...form.register('gender')} />
          </FormField>
          {mode === 'create' && (
            <>
              <FormField label="External reference">
                <Input {...form.register('externalReference')} />
              </FormField>
              <FormField label="BVN">
                <Input {...form.register('bvn')} />
              </FormField>
              <FormField label="NIN">
                <Input {...form.register('nin')} />
              </FormField>
            </>
          )}
        </>
      )}
    </CommandModal>
  );
}
