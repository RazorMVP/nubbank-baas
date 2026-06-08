import createClient, { type Client, type ClientOptions, type Middleware } from 'openapi-fetch';
import type { paths } from './schema';

export interface ApiClientOptions {
  baseUrl: string;
  getToken: () => Promise<string | null>;
  /** Injectable for tests; defaults to global fetch.
   *  Uses the same narrow type as openapi-fetch ClientOptions.fetch:
   *  (input: Request) => Promise<Response>
   */
  fetch?: ClientOptions['fetch'];
}

export function createApiClient(opts: ApiClientOptions): Client<paths> {
  const authMiddleware: Middleware = {
    async onRequest({ request }) {
      const token = await opts.getToken();
      if (token) request.headers.set('Authorization', `Bearer ${token}`);
      return request;
    },
  };

  const client = createClient<paths>({
    baseUrl: opts.baseUrl,
    fetch: opts.fetch,
  });
  client.use(authMiddleware);
  return client;
}
