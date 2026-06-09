import { cn } from '@/lib/cn';

export function KpiTile({
  label,
  value,
  delta,
  tone = 'tint',
}: {
  label: string;
  value: string;
  delta?: string;
  tone?: 'tint' | 'plain' | 'ink';
}) {
  return (
    <div
      className={cn(
        'rounded-[var(--radius-card)] p-4',
        tone === 'tint' && 'bg-tint-blue',
        tone === 'plain' && 'border border-border bg-surface',
        tone === 'ink' && 'bg-surface-ink text-white',
      )}
    >
      <p className={cn('text-[11px] uppercase tracking-wide', tone === 'ink' ? 'text-white/50' : 'text-muted')}>
        {label}
      </p>
      <p className="mt-1 text-2xl font-bold">{value}</p>
      {delta && <p className={cn('text-xs', tone === 'ink' ? 'text-brand-accent' : 'text-success')}>{delta}</p>}
    </div>
  );
}
