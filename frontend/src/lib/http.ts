import axios, { AxiosError, AxiosHeaders, isAxiosError } from 'axios';
import { ApiError } from './api-error';

/**
 * Single HTTP client for the whole app.
 *
 * - The session lives in an HttpOnly cookie set by the backend; the browser sends it (`withCredentials`).
 *   JavaScript never sees or stores the JWT.
 * - Writes need a CSRF header: the backend exposes the token in the readable XSRF-TOKEN cookie after
 *   `GET /api/auth/csrf`. The cookie is only readable when the SPA and the API share an origin
 *   (Vite proxy in development, reverse proxy in production).
 */
export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  withCredentials: true,
  timeout: 15_000,
  headers: { Accept: 'application/json' },
});

/**
 * Session events raised by the client:
 * - `lost`: the backend said the session is gone (401 NO_AUTENTICADO).
 * - `suspect`: an authenticated request got a 401 WITHOUT a code (for example from a proxy). The session is re-checked
 *   through /api/auth/me instead of being dropped: a 503 or a network failure there never closes it.
 */
export type SessionEvent = 'lost' | 'suspect';
type SessionEventListener = (event: SessionEvent) => void;
let sessionEventListener: SessionEventListener | null = null;

/** Registers the single callback for session events (wired by the app providers). */
export function setSessionEventListener(listener: SessionEventListener | null): void {
  sessionEventListener = listener;
}

// Public endpoints and the session check itself: a 401 from them says nothing new about an existing session.
const NO_SESSION_CHECK = new Set(['/auth/me', '/auth/csrf', '/auth/login', '/auth/admin/login', '/auth/invitaciones/validar', '/auth/registro/invitacion']);

function requestPath(error: unknown): string {
  const url = isAxiosError(error) ? (error.config?.url ?? '') : '';
  return url.split('?')[0] ?? '';
}

/** Normalises a failed request into an ApiError and raises the session events it implies. */
function rejectAsApiError(error: unknown): Promise<never> {
  const apiError = ApiError.from(error);
  if (apiError.kind === 'unauthenticated') {
    if (apiError.code === 'NO_AUTENTICADO') {
      sessionEventListener?.('lost');
    } else if (apiError.code === null && !NO_SESSION_CHECK.has(requestPath(error))) {
      sessionEventListener?.('suspect');
    }
  }
  return Promise.reject(apiError);
}

http.interceptors.response.use((response) => response, rejectAsApiError);

const CSRF_COOKIE = 'XSRF-TOKEN';
const DEFAULT_CSRF_HEADER = 'X-XSRF-TOKEN';

function readCookie(name: string): string | null {
  for (const part of document.cookie.split(';')) {
    const [key, ...rest] = part.trim().split('=');
    if (key === name) return decodeURIComponent(rest.join('='));
  }
  return null;
}

interface CsrfPayload {
  headerName?: unknown;
}

/**
 * Asks the backend for a CSRF token right before every write. Nothing is cached: login, logout and MFA
 * steps discard the previous token on the server, so a cached value would be stale after any session change.
 */
async function fetchCsrf(): Promise<{ headerName: string; token: string }> {
  const { data } = await http.get<CsrfPayload>('/auth/csrf');
  const token = readCookie(CSRF_COOKIE);
  if (!token) {
    throw new ApiError('csrf', 'No pudimos obtener el token de seguridad. Recargá la página e intentá de nuevo.');
  }
  const headerName = typeof data?.headerName === 'string' ? data.headerName : DEFAULT_CSRF_HEADER;
  return { headerName, token };
}

/**
 * Optional settings of one write. `headers` are sent as well (for example `Idempotency-Key`), but they can never replace
 * the CSRF header. `timeout` overrides the default 15 s (a file upload on a slow phone takes longer). `onUploadProgress`
 * receives the fraction sent, from 0 to 1, when the browser reports it.
 */
export interface WriteOptions {
  headers?: Record<string, string>;
  timeout?: number;
  onUploadProgress?: (fraction: number) => void;
}

/** Extra headers plus the CSRF header, which an extra header with the same name (in any case) can never replace. */
function withCsrfHeader(headers: Record<string, string> | undefined, headerName: string, token: string): Record<string, string> {
  const extra = Object.fromEntries(Object.entries(headers ?? {}).filter(([name]) => name.toLowerCase() !== headerName.toLowerCase()));
  return { ...extra, [headerName]: token };
}

/** State-changing request with a fresh CSRF header. Never retried automatically: a failed write is ambiguous. */
export async function apiWrite<T = void>(
  method: 'post' | 'put' | 'patch' | 'delete',
  url: string,
  data?: unknown,
  beforeWrite?: () => void,
  options: WriteOptions = {},
): Promise<T> {
  const { headerName, token } = await fetchCsrf();
  beforeWrite?.();
  const progress = options.onUploadProgress;
  const response = await http.request<T>({
    method,
    url,
    data,
    headers: withCsrfHeader(options.headers, headerName, token),
    ...(options.timeout === undefined ? {} : { timeout: options.timeout }),
    ...(progress ? { onUploadProgress: (event) => event.total && progress(Math.min(event.loaded / event.total, 1)) } : {}),
  });
  return response.data;
}

export async function apiGet<T>(url: string): Promise<T> {
  const response = await http.get<T>(url);
  return response.data;
}

/** Optional settings of a binary read. `headers` can never replace the CSRF header; `timeout` overrides the 60 s default. */
export interface BlobOptions {
  headers?: Record<string, string>;
  timeout?: number;
}

/** A binary answer, kept in memory only. `contentType` is the media type without parameters, lower-cased (null when absent). */
export interface BlobResponse {
  blob: Blob;
  contentType: string | null;
  /** The raw Content-Disposition header (null when absent or unreadable). The caller decides what name, if any, to trust. */
  contentDisposition: string | null;
}

/** Downloads of private files are larger than JSON answers: a slow connection needs more than the 15 s default. */
export const BLOB_TIMEOUT_MS = 60_000;
/** An error body bigger than this is not a JSON error of the API: it is never read. */
const MAX_ERROR_BODY_BYTES = 64 * 1024;

/** FileReader instead of `Blob.text()`, which some runtimes (jsdom among them) do not implement. */
function readBlobText(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '');
    reader.onerror = () => reject(reader.error ?? new Error('unreadable'));
    reader.readAsText(blob);
  });
}

async function readJsonError(data: unknown): Promise<unknown> {
  if (!(data instanceof Blob) || data.size === 0 || data.size > MAX_ERROR_BODY_BYTES) return {};
  try {
    const parsed: unknown = JSON.parse(await readBlobText(data));
    return parsed !== null && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {}; // not JSON (for example a proxy page): the error keeps its status and no code
  }
}

/**
 * GET of a private binary file with a fresh CSRF header (some file routes require it on reads, to keep a third-party page from
 * triggering an audited access). Never retried automatically: every successful read may be an audited access. Every status is
 * accepted at the transport level so that an error body, which arrives as a Blob, is read as JSON BEFORE it is normalised: the
 * caller gets the same `ApiError` (code included) and the same session events as with any other request. The server text is never
 * surfaced by this function; only `codigo` drives the UI.
 */
export async function apiGetBlob(url: string, options: BlobOptions = {}): Promise<BlobResponse> {
  const { headerName, token } = await fetchCsrf();
  const response = await http.request<unknown>({
    method: 'get',
    url,
    responseType: 'blob',
    headers: withCsrfHeader(options.headers, headerName, token),
    timeout: options.timeout ?? BLOB_TIMEOUT_MS,
    validateStatus: () => true,
  });
  if (response.status < 200 || response.status >= 300) {
    const data = await readJsonError(response.data);
    const failed = new AxiosError(`Request failed with status code ${response.status}`, AxiosError.ERR_BAD_RESPONSE, response.config, response.request, {
      ...response,
      data,
    });
    return rejectAsApiError(failed);
  }
  if (!(response.data instanceof Blob)) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  const headers = AxiosHeaders.from(response.headers as Record<string, string>);
  const rawType = headers.get('content-type');
  const contentType = typeof rawType === 'string' ? (rawType.split(';')[0]?.trim().toLowerCase() ?? '') || null : null;
  const rawDisposition = headers.get('content-disposition');
  return { blob: response.data, contentType, contentDisposition: typeof rawDisposition === 'string' ? rawDisposition : null };
}
