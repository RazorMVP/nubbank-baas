import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { createColumnHelper } from '@tanstack/react-table';
import { DataTable } from './data-table';

interface Row { name: string; }
const col = createColumnHelper<Row>();
const columns = [col.accessor('name', { header: 'Name' })];

describe('DataTable', () => {
  it('renders headers and rows', () => {
    render(<DataTable columns={columns} data={[{ name: 'Amaka' }]} />);
    expect(screen.getByText('Name')).toBeInTheDocument();
    expect(screen.getByText('Amaka')).toBeInTheDocument();
  });

  it('shows the empty state when no rows', () => {
    render(<DataTable columns={columns} data={[]} emptyMessage="No customers" />);
    expect(screen.getByText('No customers')).toBeInTheDocument();
  });
});
