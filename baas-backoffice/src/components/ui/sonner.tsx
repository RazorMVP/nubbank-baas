import { Toaster as SonnerToaster } from 'sonner';

type ToasterProps = React.ComponentProps<typeof SonnerToaster>;

const Toaster = ({ ...props }: ToasterProps) => {
  return (
    <SonnerToaster
      position="bottom-right"
      toastOptions={{
        classNames: {
          toast:
            'group toast group-[.toaster]:bg-[var(--color-surface)] group-[.toaster]:text-[var(--color-ink)] group-[.toaster]:border-[var(--color-border)] group-[.toaster]:shadow-lg',
          description: 'group-[.toast]:text-[var(--color-muted)]',
          actionButton:
            'group-[.toast]:bg-[var(--color-brand-primary)] group-[.toast]:text-white',
          cancelButton:
            'group-[.toast]:bg-[var(--color-bg-app)] group-[.toast]:text-[var(--color-muted)]',
        },
      }}
      {...props}
    />
  );
};

export { Toaster };
