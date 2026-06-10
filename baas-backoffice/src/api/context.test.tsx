import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { ApiClientProvider, useApiClient } from './context';
import { createApiClient } from './client';

function Probe() {
  const client = useApiClient();
  return <div>{typeof client.GET === 'function' ? 'has-client' : 'no-client'}</div>;
}

describe('ApiClientProvider', () => {
  it('exposes the client via useApiClient', () => {
    const client = createApiClient({ baseUrl: 'http://e', getToken: async () => null });
    render(
      <ApiClientProvider client={client}>
        <Probe />
      </ApiClientProvider>,
    );
    expect(screen.getByText('has-client')).toBeInTheDocument();
  });
});
