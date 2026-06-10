import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { StatusBadge } from './status-badge';

describe('StatusBadge', () => {
  it('renders the label', () => {
    render(<StatusBadge label="VERIFIED" variant="success" />);
    expect(screen.getByText('VERIFIED')).toBeInTheDocument();
  });

  it('applies the variant token class', () => {
    render(<StatusBadge label="PENDING" variant="warning" />);
    expect(screen.getByText('PENDING')).toHaveClass('text-warning');
  });

  it('defaults to neutral when variant omitted', () => {
    render(<StatusBadge label="DRAFT" />);
    expect(screen.getByText('DRAFT')).toHaveClass('text-muted');
  });
});
