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
  /** FAMILIA only. undefined = the default family 'familia-1'; null = an account with no family. */
  familiaId?: string | null;
}

export interface LoggedRequest {
  method: string;
  url: string;
  csrfHeader: string | null;
  body: unknown;
}

type Override = { status: number; body?: unknown } | 'network';

export type FakeInvitationStatus = 'PENDIENTE' | 'USADA' | 'REVOCADA' | 'EXPIRADA';

interface FakeFamily {
  id: string;
  nombreReferencia: string;
}

interface FakeInvitation {
  id: string;
  token: string;
  familyId: string;
  emailSugerido: string | null;
  creadoEn: string;
  expiraEn: string;
  usadoEn: string | null;
  revocadaEn: string | null;
}

const DAY = 86_400_000;

export interface FakeTutor {
  id: string;
  nombre: string;
  apellido: string;
  parentesco: string | null;
  telefono: string | null;
  email: string | null;
}

export interface FakeAthlete {
  id: string;
  nombre: string;
  apellido: string;
  /** Stored by the server; the list still sends it TRANSITIONALLY (retired later). The client must never use it. */
  fechaNacimiento?: string | null;
  /** What the server computes (backend `EdadDeportista`); null without a reliable birth date. The client shows it as is. */
  edad?: number | null;
  activo: boolean;
  /** Fields the REAL list never sends (DNI, address...): used to prove the client never renders what it does not need. */
  extra?: Record<string, unknown>;
}

interface PortalFamily {
  id: string;
  nombreReferencia: string | null;
  activa: boolean;
  tutores: FakeTutor[];
  /** athleteId -> link is ACTIVE. A revoked link keeps the athlete out of the family's list. */
  links: Map<string, boolean>;
}

export const VALID_TOTP = '123456';
export const FAKE_SECRET = 'JBSWY3DPEHPK3PXP';

export class FakeBackend {
  readonly requests: LoggedRequest[] = [];
  private users = new Map<string, FakeUser>();
  private session: { userId: string; pending: boolean } | null = null;
  private csrfToken: string | null = null;
  private overrides: { method: string; url: string; value: Override; once: boolean }[] = [];
  private holds: { method: string; url: string; deliver?: () => void }[] = [];
  private enrolledInFlight = new Set<string>();
  private families: FakeFamily[] = [];
  private invitations: FakeInvitation[] = [];
  private counter = 0;
  private portalFamilies = new Map<string, PortalFamily>();
  private athletes = new Map<string, FakeAthlete>();
  /** Mirrors FRONTEND_URL_BASE: when null the backend returns `enlaceRegistro: null`. */
  frontendBase: string | null = 'http://app.test';

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

  addFamily(nombreReferencia: string): FakeFamily {
    const family = { id: `fam-${++this.counter}`, nombreReferencia };
    this.families.push(family);
    return family;
  }

  /** Inserts an invitation in the given state and returns its (plaintext) token, as the link would carry it. */
  seedInvitation(options: { status: FakeInvitationStatus; family?: string; email?: string | null }): string {
    const family = this.families.find((f) => f.nombreReferencia === options.family) ?? this.addFamily(options.family ?? `Familia ${this.counter + 1}`);
    const now = Date.now();
    const invitation: FakeInvitation = {
      id: `inv-${++this.counter}`,
      token: `tok-${this.counter}-${'x'.repeat(20)}`,
      familyId: family.id,
      emailSugerido: options.email ?? null,
      creadoEn: new Date(now - 3 * DAY).toISOString(),
      expiraEn: new Date(now + 4 * DAY).toISOString(),
      usadoEn: null,
      revocadaEn: null,
    };
    if (options.status === 'EXPIRADA') invitation.expiraEn = new Date(now - DAY).toISOString();
    if (options.status === 'USADA') invitation.usadoEn = new Date(now - DAY).toISOString();
    if (options.status === 'REVOCADA') invitation.revocadaEn = new Date(now - DAY).toISOString();
    this.invitations.push(invitation);
    return invitation.token;
  }

  statusOfToken(token: string): FakeInvitationStatus | undefined {
    const invitation = this.invitations.find((i) => i.token === token);
    return invitation ? this.statusOf(invitation) : undefined;
  }

  invitationId(token: string): string {
    const invitation = this.invitations.find((i) => i.token === token);
    if (!invitation) throw new Error('Unknown invitation token');
    return invitation.id;
  }

  hasUser(email: string): boolean {
    return this.users.has(email);
  }

  private statusOf(i: FakeInvitation): FakeInvitationStatus {
    if (i.usadoEn) return 'USADA';
    if (i.revocadaEn) return 'REVOCADA';
    return new Date(i.expiraEn).getTime() <= Date.now() ? 'EXPIRADA' : 'PENDIENTE';
  }

  private view(i: FakeInvitation) {
    const family = this.families.find((f) => f.id === i.familyId)!;
    return {
      id: i.id,
      familia: family,
      emailSugerido: i.emailSugerido,
      estado: this.statusOf(i),
      creadoEn: i.creadoEn,
      expiraEn: i.expiraEn,
      usadoEn: i.usadoEn,
      revocadaEn: i.revocadaEn,
    };
  }

  private page<T>(items: T[], params: URLSearchParams) {
    const size = Number(params.get('size') ?? 20);
    const page = Number(params.get('page') ?? 0);
    return { contenido: items.slice(page * size, page * size + size), pagina: page, tamanio: size, totalElementos: items.length };
  }

  private invitationsRoute(
    method: string,
    path: string,
    params: URLSearchParams,
    body: unknown,
    current: FakeUser | undefined,
  ): { status: number; body?: unknown } | null {
    const unavailable = () => this.error(400, 'INVITACION_NO_DISPONIBLE', 'La invitación no está disponible.');
    const pending = (token: unknown) => this.invitations.find((i) => i.token === token && this.statusOf(i) === 'PENDIENTE');

    if (method === 'post' && path === '/auth/invitaciones/validar') {
      const invitation = pending((body as { token?: string }).token);
      if (!invitation) return unavailable();
      return { status: 200, body: { valida: true, escuelaNombre: 'Banfield Patín Carrera', emailSugerido: invitation.emailSugerido, expiraEn: invitation.expiraEn } };
    }

    if (method === 'post' && path === '/auth/registro/invitacion') {
      const input = body as { token: string; nombre: string; apellido: string; email: string; password: string };
      const invitation = pending(input.token);
      if (!invitation) return unavailable();
      if (input.password.length < 10) {
        return { status: 400, body: { codigo: 'VALIDACION', mensaje: 'Datos inválidos.', detalles: [{ campo: 'password', mensaje: 'La contraseña no cumple la política.' }] } };
      }
      if (this.users.has(input.email)) return this.error(409, 'EMAIL_YA_REGISTRADO', 'Ya existe una cuenta con ese correo.');
      const user: FakeUser = { id: `u-${++this.counter}`, nombre: input.nombre, apellido: input.apellido, email: input.email, password: input.password, rol: 'FAMILIA' };
      this.users.set(user.email, user);
      invitation.usadoEn = new Date().toISOString();
      // 201 and, deliberately, no session: the person signs in afterwards through /auth/login.
      return { status: 201, body: this.identity(user, false) };
    }

    if (!path.startsWith('/admin/')) return null;
    if (!this.session || !current) return this.error(401, 'NO_AUTENTICADO');
    if (this.session.pending || current.rol !== 'ADMIN') return this.error(403, 'ACCESO_DENEGADO', 'No tenés permiso para realizar esta acción.');

    if (method === 'get' && path === '/admin/familias') {
      const search = (params.get('busqueda') ?? '').toLowerCase();
      return { status: 200, body: this.page(this.families.filter((f) => f.nombreReferencia.toLowerCase().includes(search)), params) };
    }
    if (method === 'get' && path === '/admin/invitaciones') {
      const wanted = params.get('estado');
      const rows = this.invitations.filter((i) => !wanted || this.statusOf(i) === wanted).map((i) => this.view(i));
      return { status: 200, body: this.page(rows.reverse(), params) };
    }
    if (method === 'post' && path === '/admin/invitaciones') {
      const input = body as { familiaId?: string; nuevaFamilia?: { nombreReferencia: string }; emailSugerido?: string; diasVigencia?: number };
      const allowed = ['familiaId', 'nuevaFamilia', 'emailSugerido', 'diasVigencia'];
      if (Object.keys(input).some((k) => !allowed.includes(k)) || !!input.familiaId === !!input.nuevaFamilia) {
        return this.error(400, 'VALIDACION', 'Indicá familiaId o nuevaFamilia, pero no ambos ni ninguno.');
      }
      if (input.diasVigencia !== undefined && (input.diasVigencia < 1 || input.diasVigencia > 30)) {
        return this.error(400, 'VALIDACION', 'La vigencia pedida supera el máximo permitido de 30 días.');
      }
      let family = this.families.find((f) => f.id === input.familiaId);
      if (input.nuevaFamilia) family = this.addFamily(input.nuevaFamilia.nombreReferencia);
      if (!family) return this.error(404, 'FAMILIA_NO_ENCONTRADA', 'La familia no existe.');
      const token = this.seedInvitation({ status: 'PENDIENTE', family: family.nombreReferencia, email: input.emailSugerido ?? null });
      const invitation = this.invitations.find((i) => i.token === token)!;
      invitation.creadoEn = new Date().toISOString();
      invitation.expiraEn = new Date(Date.now() + (input.diasVigencia ?? 7) * DAY).toISOString();
      return {
        status: 201,
        body: {
          id: invitation.id,
          estado: 'PENDIENTE',
          token,
          enlaceRegistro: this.frontendBase ? `${this.frontendBase}/registro/invitacion#${token}` : null,
          expiraEn: invitation.expiraEn,
          familia: family,
          emailSugerido: invitation.emailSugerido,
        },
      };
    }
    const revoke = /^\/admin\/invitaciones\/([^/]+)\/revocar$/.exec(path);
    if (method === 'post' && revoke) {
      const invitation = this.invitations.find((i) => i.id === revoke[1]);
      if (!invitation) return this.error(404, 'INVITACION_NO_ENCONTRADA', 'La invitación no existe.');
      const status = this.statusOf(invitation);
      if (status === 'USADA') return this.error(409, 'INVITACION_NO_REVOCABLE', 'La invitación ya fue utilizada y no puede revocarse.');
      if (status !== 'REVOCADA') invitation.revocadaEn = new Date().toISOString();
      return { status: 200, body: this.view(invitation) };
    }
    return null;
  }

  // ---------- family portal (read-only) ----------

  addPortalFamily(id: string, nombreReferencia: string | null, tutores: FakeTutor[] = []): this {
    this.portalFamilies.set(id, { id, nombreReferencia, activa: true, tutores, links: new Map() });
    return this;
  }

  setPortalFamilyActive(id: string, activa: boolean): this {
    const family = this.portalFamilies.get(id);
    if (!family) throw new Error(`Unknown portal family ${id}`);
    family.activa = activa;
    return this;
  }

  addAthlete(athlete: Omit<FakeAthlete, 'id'>): FakeAthlete {
    const created = { ...athlete, id: `ath-${++this.counter}` };
    this.athletes.set(created.id, created);
    return created;
  }

  linkAthlete(familyId: string, athleteId: string): this {
    const family = this.portalFamilies.get(familyId);
    if (!family || !this.athletes.has(athleteId)) throw new Error('Unknown family or athlete');
    family.links.set(athleteId, true);
    return this;
  }

  revokeLink(familyId: string, athleteId: string): this {
    this.portalFamilies.get(familyId)?.links.set(athleteId, false);
    return this;
  }

  private portalRoute(method: string, path: string, params: URLSearchParams, current: FakeUser | undefined): { status: number; body?: unknown } | null {
    if (!path.startsWith('/familia/')) return null;
    if (!this.session || !current) return this.error(401, 'NO_AUTENTICADO');
    if (this.session.pending || current.rol !== 'FAMILIA') return this.error(403, 'ACCESO_DENEGADO', 'No tenés permiso para realizar esta acción.');
    if (method !== 'get') return this.error(405, 'METODO_NO_PERMITIDO', 'Método no permitido.');

    // Like the real backend: the family comes from the session, never from the request (a `familiaId` parameter is ignored).
    const familyId = this.identity(current, false).familiaId;
    const family = familyId ? this.portalFamilies.get(familyId) : undefined;

    if (path === '/familia/mi-familia') {
      if (!family || !family.activa) return this.error(404, 'FAMILIA_NO_ENCONTRADA', 'La familia no existe.');
      return { status: 200, body: { id: family.id, nombreReferencia: family.nombreReferencia, tutores: family.tutores } };
    }
    if (path === '/familia/deportistas') {
      const visible = family ? [...family.links].filter(([, active]) => active).map(([id]) => this.athletes.get(id)!) : [];
      visible.sort(
        (a, b) =>
          a.apellido.localeCompare(b.apellido, 'es', { sensitivity: 'base' }) ||
          a.nombre.localeCompare(b.nombre, 'es', { sensitivity: 'base' }) ||
          a.id.localeCompare(b.id),
      );
      // Like the real API: out-of-range values are clamped (size 1..100, page >= 0), never a 400.
      const rawSize = Number(params.get('size') ?? 20);
      const size = Number.isFinite(rawSize) ? Math.min(Math.max(Math.trunc(rawSize), 1), 100) : 20;
      const rawPage = Number(params.get('page') ?? 0);
      const page = Number.isFinite(rawPage) ? Math.max(Math.trunc(rawPage), 0) : 0;
      const contenido = visible.slice(page * size, page * size + size).map((a) => ({
        id: a.id,
        nombre: a.nombre,
        apellido: a.apellido,
        fechaNacimiento: a.fechaNacimiento ?? null, // transitional: still sent by the real list (U2), retired in U4
        edad: a.edad ?? null,
        activo: a.activo,
        ...a.extra,
      }));
      return { status: 200, body: { contenido, pagina: page, tamanio: size, totalElementos: visible.length } };
    }
    return this.error(404, 'RECURSO_NO_ENCONTRADO');
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
    this.holds = [];
  }

  /**
   * Holds the NEXT matching request: the server answers as of now, but the client only receives the response when
   * `release()` is called. Used to reproduce late responses that arrive after the identity changed.
   */
  hold(method: 'get' | 'post', url: string): { release: () => void } {
    const entry: { method: string; url: string; deliver?: () => void } = { method, url };
    this.holds.push(entry);
    return { release: () => entry.deliver?.() };
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

    // The answer is computed NOW (with the identity and data of this moment); a held request only delays its delivery.
    const result = this.route(method, url, body, csrfHeader);
    const holdIndex = this.holds.findIndex((h) => h.method === method && h.url === url);
    if (holdIndex >= 0) {
      const [hold] = this.holds.splice(holdIndex, 1);
      return new Promise((resolve, reject) => {
        hold!.deliver = () => {
          void this.respond(config, result.status, result.body).then(resolve, reject);
        };
      });
    }
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
      familiaId: user.rol === 'FAMILIA' ? (user.familiaId === undefined ? 'familia-1' : user.familiaId) : null,
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

    const parsed = new URL(url, 'http://fake.local');
    const handled = this.invitationsRoute(method, parsed.pathname, parsed.searchParams, body, current);
    if (handled) return handled;
    const portal = this.portalRoute(method, parsed.pathname, parsed.searchParams, current);
    if (portal) return portal;

    return this.error(404, 'RECURSO_NO_ENCONTRADO');
  }
}
