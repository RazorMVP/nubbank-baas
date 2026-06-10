import { cn } from '@/lib/cn';

export type StatusVariant = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

const VARIANT: Record<StatusVariant, string> = {
  success: 'bg-success-bg text-success',
  warning: 'bg-warning-bg text-warning',
  danger: 'bg-danger-bg text-danger',
  info: 'bg-tint-blue text-brand-primary',
  neutral: 'bg-border/40 text-muted',
};

export function StatusBadge({
  label,
  variant = 'neutral',
  className,
}: {
  label: string;
  variant?: StatusVariant;
  className?: string;
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-[var(--radius-pill)] px-2.5 py-0.5 text-xs font-medium',
        VARIANT[variant],
        className,
      )}
    >
      {label}
    </span>
  );
}
