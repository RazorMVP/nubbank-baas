import { useId, type ReactElement, cloneElement } from 'react';
import { Label } from '@/components/ui/label';

// InputElement covers all HTML input-like elements that accept id + className.
type InputElement = ReactElement<React.HTMLAttributes<HTMLElement> & { id?: string }>;

export function FormField({
  label,
  error,
  children,
}: {
  label: string;
  error?: string;
  children: InputElement;
}) {
  const id = useId();
  return (
    <div className="mb-3">
      <Label htmlFor={id} className="mb-1 block">
        {label}
      </Label>
      {cloneElement(children, {
        id,
        className:
          'w-full rounded-[var(--radius-control)] border border-border px-3 py-2 text-sm outline-none focus:border-brand-primary',
      })}
      {error && <p className="mt-1 text-xs text-danger">{error}</p>}
    </div>
  );
}
