import axios, { isAxiosError } from 'axios';
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

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    const apiError = ApiError.from(error);
    if (apiError.kind === 'unauthenticated') {
      if (apiError.code === 'NO_AUTENTICADO') {
        sessionEventListener?.('lost');
      } else if (apiError.code === null && !NO_SESSION_CHECK.has(requestPath(error))) {
        sessionEventListener?.('suspect');
      }
    }
    return Promise.reject(apiError);
  },
);

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

/** State-changing request with a fresh CSRF header. Never retried automatically: a failed write is ambiguous. */
export async function apiWrite<T = void>(
  method: 'post' | 'put' | 'patch' | 'delete',
  url: string,
  data?: unknown,
): Promise<T> {
  const { headerName, token } = await fetchCsrf();
  const response = await http.request<T>({ method, url, data, headers: { [headerName]: token } });
  return response.data;
}

export async function apiGet<T>(url: string): Promise<T> {
  const response = await http.get<T>(url);
  return response.data;
}
