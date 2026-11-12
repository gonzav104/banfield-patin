import { AxiosError, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios';
import { http } from '@/lib/http';

/**
 * In-memory stand-in for the Spring backend, installed as the axios adapter of the REAL http client.
 * Everything above the adapter (CSRF handling, error mapping, query cache, guards, pages) runs for real.
 * It mirrors the contract read from the backend controllers; it is a MOCK, not proof against the real server.
 */

export interface FakeUser {
  id: string;
  nombre: string;
  apellido: string;
  email: string;
  password: string;
  rol: 'ADMIN' | 'FAMILIA';
  mfaEnrolado?: boolean;
}

export interface LoggedRequest {
  method: string;
  url: string;
  csrfHeader: string | null;
  body: unknown;
}

type Override = { status: number; body?: unknown } | 'network';

export const VALID_TOTP = '123456';
export const FAKE_SECRET = 'JBSWY3DPEHPK3PXP';

export class FakeBackend {
  readonly requests: LoggedRequest[] = [];
  private users = new Map<string, FakeUser>();
  private session: { userId: string; pending: boolean } | null = null;
  private csrfToken: string | null = null;
  private overrides: { method: string; url: string; value: Override; once: boolean }[] = [];
  private enrolledInFlight = new Set<string>();

  addUser(user: FakeUser): this {
    this.users.set(user.email, user);
    return this;
  }

  /** Simulates a session cookie already present in the browser (page reload / returning visitor). */
  signIn(email: string, pending = false): this {
    const user = this.users.get(email);
    if (!user) throw new Error(`Unknown fake user ${email}`);
    this.session = { userId: user.id, pending };
    return this;
  }

  expireSession(): void {
    this.session = null;
  }

  /** Forces the next (or every) matching request to fail with a status or a network error. */
  override(method: 'get' | 'post', url: string, value: Override, options: { once?: boolean } = {}): this {
    this.overrides.push({ method, url, value, once: options.once ?? true });
    return this;
  }

  clearOverrides(): void {
    this.overrides = [];
  }

  writes(): LoggedRequest[] {
    return this.requests.filter((r) => r.method === 'post');
  }

  install(): void {
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/';
    http.defaults.adapter = this.adapter;
  }

  private setCsrfCookie(token: string | null): void {
    this.csrfToken = token;
    document.cookie = token ? `XSRF-TOKEN=${token}; path=/` : 'XSRF-TOKEN=; Max-Age=0; path=/';
  }

  private adapter: AxiosAdapter = (config) => {
    const method = (config.method ?? 'get').toLowerCase();
    const url = config.url ?? '';
    const body: unknown = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
    const csrfHeader = (config.headers.get('X-XSRF-TOKEN') as string | null | undefined) ?? null;
    this.requests.push({ method, url, csrfHeader, body });

    const index = this.overrides.findIndex((o) => o.method === method && o.url === url);
    if (index >= 0) {
      const override = this.overrides[index]!;
      if (override.once) this.overrides.splice(index, 1);
      if (override.value === 'network') return Promise.reject(new AxiosError('Network Error', 'ERR_NETWORK', config));
      return this.respond(config, override.value.status, override.value.body);
    }

    const result = this.route(method, url, body, csrfHeader);
    return this.respond(config, result.status, result.body);
  };

  private respond(config: InternalAxiosRequestConfig, status: number, data: unknown) {
    const response = { data, status, statusText: String(status), headers: {}, config, request: {} };
    if (status >= 400) {
      return Promise.reject(new AxiosError(`Request failed with status code ${status}`, 'ERR_BAD_RESPONSE', config, {}, response));
    }
    return Promise.resolve(response);
  }

  private error(status: number, codigo: string, mensaje = 'error') {
    return { status, body: { codigo, mensaje, detalles: [] } };
  }

  private identity(user: FakeUser, pending: boolean) {
    return {
      id: user.id,
      nombre: user.nombre,
      apellido: user.apellido,
      email: user.email,
      rol: user.rol,
      escuelaId: 'escuela-1',
      familiaId: user.rol === 'FAMILIA' ? 'familia-1' : null,
      mfaPendiente: pending,
      mfaEnrolado: user.rol === 'ADMIN' && !!user.mfaEnrolado,
    };
  }

  private route(method: string, url: string, body: unknown, csrfHeader: string | null): { status: number; body?: unknown } {
    if (method === 'get' && url === '/auth/csrf') {
      if (!this.csrfToken) this.setCsrfCookie(`csrf-${this.requests.length}`);
      return { status: 200, body: { headerName: 'X-XSRF-TOKEN', token: `masked-${this.csrfToken}` } };
    }

    if (method === 'post' && (!csrfHeader || csrfHeader !== this.csrfToken)) {
      return this.error(403, 'CSRF_INVALIDO', 'Token CSRF ausente o inválido.');
    }

    const sessionUserId = this.session?.userId;
    const current = sessionUserId ? [...this.users.values()].find((u) => u.id === sessionUserId) : undefined;

    if (method === 'get' && url === '/auth/me') {
      if (!this.session || !current) return this.error(401, 'NO_AUTENTICADO');
      return { status: 200, body: this.identity(current, this.session.pending) };
    }

    if (method === 'post' && (url === '/auth/login' || url === '/auth/admin/login')) {
      const wanted = url === '/auth/login' ? 'FAMILIA' : 'ADMIN';
      const { email, password } = body as { email: string; password: string };
      const user = this.users.get(email);
      if (!user || user.password !== password || user.rol !== wanted) return this.error(401, 'CREDENCIALES_INVALIDAS');
      this.session = { userId: user.id, pending: user.rol === 'ADMIN' };
      this.setCsrfCookie(null); // the backend discards the previous CSRF cookie on every session change
      return { status: 200, body: this.identity(user, user.rol === 'ADMIN') };
    }

    if (method === 'post' && url === '/auth/logout') {
      if (!this.session) return this.error(401, 'NO_AUTENTICADO');
      this.session = null;
      this.setCsrfCookie(null);
      return { status: 204 };
    }

    if (method === 'post' && url.startsWith('/auth/admin/mfa/')) {
      if (!this.session || !current) return this.error(401, 'NO_AUTENTICADO');
      if (!this.session.pending) return this.error(403, 'ACCESO_DENEGADO');
      if (url.endsWith('/enrolar')) {
        this.enrolledInFlight.add(current.id);
        return { status: 200, body: { otpauthUri: `otpauth://totp/Banfield:${current.email}?secret=${FAKE_SECRET}`, secretoBase32: FAKE_SECRET } };
      }
      const wasEnrolled = !!current.mfaEnrolado;
      if (url.endsWith('/verificar') && !wasEnrolled) return this.error(409, 'MFA_ESTADO_INVALIDO');
      if (url.endsWith('/confirmar') && (wasEnrolled || !this.enrolledInFlight.has(current.id))) return this.error(409, 'MFA_ESTADO_INVALIDO');
      if ((body as { codigo: string }).codigo !== VALID_TOTP) return this.error(401, 'CODIGO_MFA_INVALIDO');
      current.mfaEnrolado = true;
      this.session = { userId: current.id, pending: false };
      this.setCsrfCookie(null);
      return { status: 200, body: this.identity(current, false) };
    }

    return this.error(404, 'RECURSO_NO_ENCONTRADO');
  }
}
