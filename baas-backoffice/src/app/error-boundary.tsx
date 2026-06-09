import { Component, type ErrorInfo, type ReactNode } from 'react';

interface State {
  hasError: boolean;
  message: string;
}

export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { hasError: false, message: '' };

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, message: error.message };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // eslint-disable-next-line no-console
    console.error('Unhandled UI error', error, info);
  }

  render(): ReactNode {
    if (this.state.hasError) {
      return (
        <div role="alert" className="flex min-h-screen items-center justify-center p-8">
          <div className="max-w-md rounded-[var(--radius-card)] bg-surface p-6 text-center shadow">
            <h1 className="mb-2 text-lg font-semibold">Something went wrong</h1>
            <p className="text-sm text-muted">{this.state.message}</p>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}
