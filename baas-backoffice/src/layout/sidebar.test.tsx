import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect } from 'vitest';
import { Sidebar } from './sidebar';
import { AuthContextProvider } from '@/auth/context';
import { createDevAuthProvider } from '@/auth/dev-provider';

function renderSidebar(authorities: string[]) {
  const provider = createDevAuthProvider({ token: 't', authorities, user: null });
  return render(
    <AuthContextProvider provider={provider}>
      <MemoryRouter initialEntries={['/']}>
        <Sidebar />
      </MemoryRouter>
    </AuthContextProvider>,
  );
}

describe('Sidebar', () => {
  it('shows the NubBank brand and permitted nav labels when expanded', () => {
    renderSidebar(['READ_CUSTOMER']);
    expect(screen.getByText('NubBank')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /customers/i })).toBeInTheDocument();
  });

  it('toggles collapse via the control', async () => {
    renderSidebar(['READ_CUSTOMER']);
    const toggle = screen.getByRole('button', { name: /collapse sidebar/i });
    await userEvent.click(toggle);
    expect(screen.getByRole('button', { name: /expand sidebar/i })).toBeInTheDocument();
  });
});
