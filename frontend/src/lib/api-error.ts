import { isAxiosError } from 'axios';

export type ApiErrorKind =
  | 'network'
  | 'unauthenticated'
  | 'forbidden'
  | 'csrf'
  | 'unavailable'
  | 'validation'
  | 'conflict'
  | 'server'
  | 'unknown';

export interface ApiFieldDetail {
  campo?: string | null;
  mensaje?: string | null;
}

interface BackendErrorBody {
  codigo?: unknown;
  mensaje?: unknown;
  detalles?: unknown;
}

/** Normalised error for every failed backend interaction. Never carries credentials or tokens. */
export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number | null;
  readonly code: string | null;
  readonly details: ApiFieldDetail[];

  constructor(
    kind: ApiErrorKind,
    message: string,
    options: { status?: number | null; code?: string | null; details?: ApiFieldDetail[] } = {},
  ) {
    super(message);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = options.status ?? null;
    this.code = options.code ?? null;
    this.details = options.details ?? [];
  }

  static from(error: unknown): ApiError {
    if (error instanceof ApiError) return error;
    if (!isAxiosError(error)) {
      return new ApiError('unknown', 'Ocurrió un error inesperado.');
    }
    const response = error.response;
    if (!response) {
      return new ApiError('network', 'No pudimos conectar con el servidor.');
    }
    const body = (response.data ?? {}) as BackendErrorBody;
    const code = typeof body.codigo === 'string' ? body.codigo : null;
    const message = typeof body.mensaje === 'string' ? body.mensaje : 'La solicitud no pudo completarse.';
    const details = Array.isArray(body.detalles) ? (body.detalles as ApiFieldDetail[]) : [];
    const options = { status: response.status, code, details };
    const status = response.status;

    if (status === 401) return new ApiError('unauthenticated', message, options);
    if (status === 403) return new ApiError(code === 'CSRF_INVALIDO' ? 'csrf' : 'forbidden', message, options);
    if (status === 400) return new ApiError('validation', message, options);
    if (status === 409) return new ApiError('conflict', message, options);
    if (status === 503 || status === 502 || status === 504) return new ApiError('unavailable', message, options);
    if (status >= 500) return new ApiError('server', message, options);
    return new ApiError('unknown', message, options);
  }
}

export function isApiError(error: unknown, kind?: ApiErrorKind): error is ApiError {
  return error instanceof ApiError && (kind === undefined || error.kind === kind);
}
