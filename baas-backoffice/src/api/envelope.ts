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

export function extractPage<T>(page: Page<T>): NormalizedPage<T> {
  return {
    items: page.content,
    page: page.number,
    size: page.size,
    total: page.totalElements,
    totalPages: page.totalPages,
  };
}
