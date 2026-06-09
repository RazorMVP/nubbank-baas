import { ApiError } from './envelope';

/** Stable query keys: [domain, kind, ...]. Used by every domain's hooks. */
export const qk = {
  list: (domain: string, params?: Record<string, unknown>) =>
    [domain, 'list', params] as [string, 'list', Record<string, unknown> | undefined],
  detail: (domain: string, id: string) =>
    [domain, 'detail', id] as [string, 'detail', string],
};

export function toToastMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return 'Something went wrong';
}
