export interface EnvelopeError {
  code: string;
  message: string;
  field: string | null;
  docsUrl: string | null;
}

export interface Envelope<T> {
  data: T | null;
  meta: { requestId: string; timestamp: string } | null;
  errors: EnvelopeError[] | null;
}

/** Spring Data Page<T> shape, as serialized inside `data`. */
export interface Page<T> {
  content: T[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface NormalizedPage<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export class ApiError extends Error {
  readonly code: string;
  readonly field: string | null;
  readonly docsUrl: string | null;
  readonly httpStatus?: number;
  constructor(e: EnvelopeError, httpStatus?: number) {
    super(e.message);
    this.name = 'ApiError';
    this.code = e.code;
    this.field = e.field;
    this.docsUrl = e.docsUrl;
    this.httpStatus = httpStatus;
  }
}

export function unwrap<T>(env: Envelope<T>, httpStatus?: number): T {
  if (env.errors && env.errors.length > 0) {
    throw new ApiError(env.errors[0], httpStatus);
  }
  return env.data as T;
}

/** The shape openapi-fetch returns from a request. On 2xx the parsed body is in
 * `data`; on non-2xx it is in `error` (the engine still returns the standard
 * envelope on errors). */
export interface FetchResult {
  data?: unknown;
  error?: unknown;
  response: Response;
}

/**
 * Unwrap an openapi-fetch result to its payload, always surfacing failures as
 * `ApiError` (never a raw `TypeError`). The single seam every data hook must use
 * so the error contract (401/403/field/transport) holds uniformly.
 */
export function unwrapResult<T>(result: FetchResult): T {
  const status = result.response?.status ?? 0;
  const envelope = (result.error ?? result.data) as Envelope<T> | null | undefined;

  if (envelope && envelope.errors && envelope.errors.length > 0) {
    throw new ApiError(envelope.errors[0], status);
  }
  if (status >= 400 || envelope == null) {
    throw new ApiError(
      {
        code: `ERR_HTTP_${status}`,
        message: status ? `Request failed (${status})` : 'No response from server',
        field: null,
        docsUrl: null,
      },
      status,
    );
  }
  return envelope.data as T;
}

export function extractPage<T>(page: Page<T>): NormalizedPage<T> {
  return {
    items: page.content,
    page: page.number,
    size: page.size,
    total: page.totalElements,
    totalPages: page.totalPages,
  };
}
