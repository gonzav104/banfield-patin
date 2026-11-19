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
  /** Administrative listing data (GET /admin/familias/listado); the legacy search only reads the name. */
  activa: boolean;
  cantidadTutores: number;
  cantidadDeportistasActivos: number;
  /** Administrative detail (GET /admin/familias/{id}): tutors with the DNI ALREADY masked, as the backend sends it. */
  tutores: FakeAdminTutor[];
  version: number;
}

export interface FakeAdminTutor {
  id: string;
  nombre: string;
  apellido: string;
  dniEnmascarado: string | null;
  telefono: string | null;
  email: string | null;
  parentesco: string | null;
  activo: boolean;
  /** Only the individual tutor detail (GET /admin/tutores/{id}) carries the FULL DNI and the revision; the family summary never does. */
  dni?: string | null;
  version?: number;
}

export type FakeLinkState = 'PENDIENTE' | 'ACTIVO' | 'RECHAZADO' | 'REVOCADO';

/** A family-athlete link as the ADMIN endpoints of FE-05/U11-B see it (VinculoRespuesta): it never carries a DNI, a CUIL or who authorized it. */
export interface FakeAdminLink {
  id: string;
  familyId: string;
  athleteId: string;
  estado: FakeLinkState;
  esPrincipal: boolean;
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

/** An athlete as the ADMIN endpoints of FE-05/U7 see it (DeportistaDetalle). The list only shows the masked DNI; the detail the full one. */
export interface FakeAdminAthlete {
  id: string;
  dni: string;
  nombre: string;
  apellido: string;
  cuil: string | null;
  fechaNacimiento: string | null;
  nacionalidad: string | null;
  domicilio: string | null;
  otrosDatosDomicilio: string | null;
  localidad: string | null;
  partido: string | null;
  codigoPostal: string | null;
  telefonoContacto: string | null;
  emailFederativo: string | null;
  activo: boolean;
  version: number;
}

/** A season of the school (GET /admin/temporadas: TemporadaRespuesta). */
export interface FakeSeason {
  id: string;
  anio: number;
  activa: boolean;
}

export type FakeSportsStatus = 'ACTIVO' | 'INACTIVO' | 'SUSPENDIDO';

/** The sports data of ONE athlete in ONE season (deportista_temporada). `version` is the per-row revision (a decimal string on the wire). */
export interface FakeSportsRow {
  id: string;
  deportistaId: string;
  temporadaId: string;
  categoria: string | null;
  rama: string | null;
  divisional: string | null;
  numeroCorredor: string | null;
  estadoDeportivo: FakeSportsStatus;
  version: number;
}

const ATHLETE_FIELDS = ['dni', 'nombre', 'apellido', 'cuil', 'fechaNacimiento', 'nacionalidad', 'domicilio', 'otrosDatosDomicilio', 'localidad', 'partido', 'codigoPostal', 'telefonoContacto', 'emailFederativo'];

/** The backend's EnmascaradoDni: only the last three digits are shown. */
const maskDni = (dni: string) => '•'.repeat(Math.max(dni.length - 3, 0)) + dni.slice(Math.max(dni.length - 3, 0));

/** DocumentoIdentidad.cuilValido: 11 digits and the check digit. */
function validCuil(raw: string): boolean {
  const cuil = raw.replace(/[.\-\s]/g, '');
  if (!/^[0-9]{11}$/.test(cuil)) return false;
  const weights = [5, 4, 3, 2, 7, 6, 5, 4, 3, 2];
  const sum = weights.reduce((total, w, i) => total + Number(cuil[i]) * w, 0);
  const rest = 11 - (sum % 11);
  return rest !== 10 && Number(cuil[10]) === (rest === 11 ? 0 : rest);
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

export interface FakeCurrentSeason {
  anio: number;
  categoria: string | null;
  rama: string | null;
  divisional: string | null;
  numeroCorredor: string | null;
  estadoDeportivo: 'ACTIVO' | 'INACTIVO' | 'SUSPENDIDO';
}

const UUID_FORMAT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface FakeAthlete {
  id: string;
  nombre: string;
  apellido: string;
  /** Stored by the server and available to ADMIN; the family list does NOT send it (only `edad`). Tests may force it in via `extra`. */
  fechaNacimiento?: string | null;
  /** What the server computes (backend `EdadDeportista`); null without a reliable birth date. The client shows it as is. */
  edad?: number | null;
  /** The current-season data the server returns in the sheet (null = no data). The season SELECTION is the backend's. */
  vigente?: FakeCurrentSeason | null;
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
  private adminAthletes: FakeAdminAthlete[] = [];
  private adminLinks: FakeAdminLink[] = [];
  private adminLinkVersions = new Map<string, number>();
  private seasons: FakeSeason[] = [];
  private sportsRows: FakeSportsRow[] = [];
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

  addFamily(nombreReferencia: string, extra: Partial<Omit<FakeFamily, 'id' | 'nombreReferencia'>> = {}): FakeFamily {
    const family: FakeFamily = {
      id: `fam-${++this.counter}`,
      nombreReferencia,
      activa: true,
      cantidadTutores: 0,
      cantidadDeportistasActivos: 0,
      tutores: [],
      version: 0,
      ...extra,
    };
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

  /** Another administrator changes the family: the revision moves on, exactly as the real backend would. */
  changeFamilyBehindTheScenes(id: string, change: { nombreReferencia?: string; activa?: boolean }): void {
    const family = this.families.find((f) => f.id === id);
    if (!family) throw new Error(`Unknown fake family ${id}`);
    Object.assign(family, change);
    family.version += 1;
  }

  familyVersion(id: string): string {
    return String(this.families.find((f) => f.id === id)?.version);
  }

  /**
   * Mirrors PUT /admin/familias/{id} (FamiliaActualizacion) and POST .../activar|desactivar (FamiliaVersionEsperada):
   * bean validation first (400), then 404, then the revision (409 EDICION_DESACTUALIZADA, even when the value already
   * matches), then a no-op (same revision) or a change (revision + 1).
   */
  private writeFamily(id: string, status: 'activar' | 'desactivar' | undefined, body: unknown): { status: number; body?: unknown } {
    const input = (body ?? {}) as Record<string, unknown>;
    const allowed = status ? ['versionEsperada'] : ['nombreReferencia', 'versionEsperada'];
    if (Object.keys(input).some((k) => !allowed.includes(k))) return this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
    const bad = { status: 400, body: { codigo: 'VALIDACION', mensaje: 'Hay datos inválidos en la solicitud.', detalles: [{ campo: 'x', mensaje: 'detalle del servidor' }] } };
    if (input.versionEsperada === undefined || input.versionEsperada === null) return bad;
    if (typeof input.versionEsperada !== 'string' || !/^[0-9]{1,19}$/.test(input.versionEsperada)) return this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
    const name = typeof input.nombreReferencia === 'string' ? input.nombreReferencia.trim() : '';
    if (!status && (name === '' || name.length > 150)) return bad;
    const family = this.families.find((f) => f.id === id);
    if (!family) return this.error(404, 'FAMILIA_NO_ENCONTRADA', 'La familia no existe.');
    if (String(family.version) !== input.versionEsperada) {
      return this.error(409, 'EDICION_DESACTUALIZADA', 'El registro fue modificado. Es necesario volver a cargarlo antes de guardar.');
    }
    if (status) {
      const target = status === 'activar';
      if (family.activa !== target) {
        family.activa = target;
        family.version += 1;
      }
    } else if (family.nombreReferencia !== name) {
      family.nombreReferencia = name;
      family.version += 1;
    }
    return { status: 200, body: this.familyDetail(family) };
  }

  /** The backend's TutorResumen: masked DNI, no revision. */
  private tutorSummary(t: FakeAdminTutor, familiaId: string) {
    return { id: t.id, familiaId, nombre: t.nombre, apellido: t.apellido, dniEnmascarado: t.dniEnmascarado, telefono: t.telefono, email: t.email, parentesco: t.parentesco, activo: t.activo };
  }

  /** The backend's TutorRespuesta: FULL DNI and the revision (decimal string). */
  private tutorFull(t: FakeAdminTutor, familiaId: string) {
    return { id: t.id, familiaId, nombre: t.nombre, apellido: t.apellido, dni: t.dni ?? null, telefono: t.telefono, email: t.email, parentesco: t.parentesco, activo: t.activo, version: String(t.version ?? 0) };
  }

  private findTutor(id: string): { tutor: FakeAdminTutor; family: FakeFamily } | undefined {
    for (const family of this.families) {
      const tutor = family.tutores.find((t) => t.id === id);
      if (tutor) return { tutor, family };
    }
    return undefined;
  }

  /** What really happened on the server although the client never saw the answer (a lost response): the tutor exists, nothing else moved. */
  addTutorBehindTheScenes(familyId: string, data: { nombre: string; apellido: string; dni?: string }): FakeAdminTutor {
    const family = this.families.find((f) => f.id === familyId);
    if (!family) throw new Error(`Unknown fake family ${familyId}`);
    const dni = data.dni ?? null;
    const tutor: FakeAdminTutor = {
      id: `t-${++this.counter}`,
      nombre: data.nombre,
      apellido: data.apellido,
      dni,
      dniEnmascarado: dni ? `${'•'.repeat(Math.max(0, dni.length - 3))}${dni.slice(-3)}` : null,
      telefono: null,
      email: null,
      parentesco: null,
      activo: true,
      version: 0,
    };
    family.tutores.push(tutor);
    return tutor;
  }

  /**
   * Mirrors POST /admin/familias/{id}/tutores (TutorSolicitud): bean validation first (400), then 404, then FAMILIA_INACTIVA
   * (before the revision), then the family revision (409 EDICION_DESACTUALIZADA), then the write. Creating a tutor does NOT
   * move the family revision, exactly like the backend: a blind retry after a lost answer would create a SECOND tutor.
   */
  private createTutor(familyId: string, body: unknown): { status: number; body?: unknown } {
    const input = (body ?? {}) as Record<string, unknown>;
    const allowed = ['nombre', 'apellido', 'dni', 'telefono', 'email', 'parentesco', 'versionFamiliaEsperada'];
    if (Object.keys(input).some((k) => !allowed.includes(k))) return this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
    const bad = { status: 400, body: { codigo: 'VALIDACION', mensaje: 'Hay datos inválidos en la solicitud.', detalles: [{ campo: 'x', mensaje: 'detalle del servidor' }] } };
    const revision = input.versionFamiliaEsperada;
    if (revision === undefined || revision === null) return bad;
    if (typeof revision !== 'string' || !/^[0-9]{1,19}$/.test(revision)) return this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
    const text = (v: unknown) => (typeof v === 'string' ? v.trim() : '');
    const optional = (v: unknown) => (text(v) === '' ? null : text(v));
    const nombre = text(input.nombre);
    const apellido = text(input.apellido);
    const dniRaw = optional(input.dni);
    const dni = dniRaw === null ? null : dniRaw.replace(/[.\s]/g, '');
    if (nombre === '' || apellido === '' || nombre.length > 100 || apellido.length > 100) return bad;
    if (dniRaw !== null && (dniRaw.length > 20 || !/^[0-9]{7,9}$/.test(dni as string))) return bad;
    const email = optional(input.email);
    if (email !== null && (email.length > 180 || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email))) return bad;
    if ((optional(input.telefono)?.length ?? 0) > 40 || (optional(input.parentesco)?.length ?? 0) > 40) return bad;
    const family = this.families.find((f) => f.id === familyId);
    if (!family) return this.error(404, 'FAMILIA_NO_ENCONTRADA', 'La familia no existe.');
    if (!family.activa) return this.error(409, 'FAMILIA_INACTIVA', 'La familia esta inactiva. Reactivala para realizar esta operacion.');
    if (String(family.version) !== revision) {
      return this.error(409, 'EDICION_DESACTUALIZADA', 'El registro fue modificado. Es necesario volver a cargarlo antes de guardar.');
    }
    const tutor = this.addTutorBehindTheScenes(familyId, { nombre, apellido, ...(dni ? { dni } : {}) });
    tutor.telefono = optional(input.telefono);
    tutor.email = email === null ? null : email.toLowerCase();
    tutor.parentesco = optional(input.parentesco);
    return { status: 201, body: this.tutorFull(tutor, familyId) };
  }

  /** Another administrator edits the tutor while this one is editing: the tutor revision moves, the family revision does not. */
  changeTutorBehindTheScenes(id: string, change: { nombre?: string; apellido?: string; dni?: string | null; telefono?: string | null; email?: string | null; parentesco?: string | null }): void {
    const found = this.findTutor(id);
    if (!found) throw new Error(`Unknown fake tutor ${id}`);
    const { dni, ...rest } = change;
    Object.assign(found.tutor, rest);
    if (dni !== undefined) {
      found.tutor.dni = dni;
      found.tutor.dniEnmascarado = dni ? `${'•'.repeat(Math.max(0, dni.length - 3))}${dni.slice(-3)}` : null;
    }
    found.tutor.version = (found.tutor.version ?? 0) + 1;
  }

  tutorVersion(id: string): string {
    return String(this.findTutor(id)?.tutor.version ?? 0);
  }

  /**
   * Mirrors PUT /admin/tutores/{id} (TutorActualizacion, a COMPLETE replacement): bean validation first (400, both revisions are
   * required), then 404, then FAMILIA_INACTIVA (before the revisions), then the family revision and the tutor revision (one 409
   * EDICION_DESACTUALIZADA for both, even when the data is equal), then the write. Without changes it answers 200 without writing
   * and without moving the revision. Editing a tutor does NOT move the family revision.
   */
  private updateTutor(id: string, body: unknown): { status: number; body?: unknown } {
    const input = (body ?? {}) as Record<string, unknown>;
    const allowed = ['nombre', 'apellido', 'dni', 'telefono', 'email', 'parentesco', 'versionEsperada', 'versionFamiliaEsperada'];
    if (Object.keys(input).some((k) => !allowed.includes(k))) return this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
    const bad = { status: 400, body: { codigo: 'VALIDACION', mensaje: 'Hay datos inválidos en la solicitud.', detalles: [{ campo: 'x', mensaje: 'detalle del servidor' }] } };
    for (const key of ['versionEsperada', 'versionFamiliaEsperada']) {
      const revision = input[key];
      if (revision === undefined || revision === null) return bad;
      if (typeof revision !== 'string' || !/^[0-9]{1,19}$/.test(revision)) return this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
    }
    const text = (v: unknown) => (typeof v === 'string' ? v.trim() : '');
    const optional = (v: unknown) => (text(v) === '' ? null : text(v));
    const nombre = text(input.nombre);
    const apellido = text(input.apellido);
    const dniRaw = optional(input.dni);
    const dni = dniRaw === null ? null : dniRaw.replace(/[.\s]/g, '');
    if (nombre === '' || apellido === '' || nombre.length > 100 || apellido.length > 100) return bad;
    if (dniRaw !== null && (dniRaw.length > 20 || !/^[0-9]{7,9}$/.test(dni as string))) return bad;
    const emailRaw = optional(input.email);
    if (emailRaw !== null && (emailRaw.length > 180 || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(emailRaw))) return bad;
    const telefono = optional(input.telefono);
    const parentesco = optional(input.parentesco);
    if ((telefono?.length ?? 0) > 40 || (parentesco?.length ?? 0) > 40) return bad;
    const found = this.findTutor(id);
    if (!found) return this.error(404, 'TUTOR_NO_ENCONTRADO', 'El tutor no existe.');
    const { tutor, family } = found;
    if (!family.activa) return this.error(409, 'FAMILIA_INACTIVA', 'La familia esta inactiva. Reactivala para realizar esta operacion.');
    const stale = () => this.error(409, 'EDICION_DESACTUALIZADA', 'El registro fue modificado. Es necesario volver a cargarlo antes de guardar.');
    if (String(family.version) !== input.versionFamiliaEsperada) return stale();
    if (String(tutor.version ?? 0) !== input.versionEsperada) return stale();
    const email = emailRaw === null ? null : emailRaw.toLowerCase();
    const changed = tutor.nombre !== nombre || tutor.apellido !== apellido || (tutor.dni ?? null) !== dni || tutor.telefono !== telefono || tutor.email !== email || tutor.parentesco !== parentesco;
    if (changed) {
      Object.assign(tutor, { nombre, apellido, telefono, email, parentesco });
      tutor.dni = dni;
      tutor.dniEnmascarado = dni ? `${'•'.repeat(Math.max(0, dni.length - 3))}${dni.slice(-3)}` : null;
      tutor.version = (tutor.version ?? 0) + 1;
    }
    return { status: 200, body: this.tutorFull(tutor, family.id) };
  }


  /** An athlete that already exists in the school (GET/POST /admin/deportistas see it). */
  addAdminAthlete(data: Partial<Omit<FakeAdminAthlete, 'id' | 'version'>> & { nombre: string; apellido: string; dni: string }): FakeAdminAthlete {
    const athlete: FakeAdminAthlete = {
      id: `dep-${++this.counter}`,
      cuil: null,
      fechaNacimiento: null,
      nacionalidad: null,
      domicilio: null,
      otrosDatosDomicilio: null,
      localidad: null,
      partido: null,
      codigoPostal: null,
      telefonoContacto: null,
      emailFederativo: null,
      activo: true,
      version: 0,
      ...data,
    };
    this.adminAthletes.push(athlete);
    return athlete;
  }

  /** What really happened on the server although the client never saw the answer (a lost response): the athlete exists. */
  addAdminAthleteBehindTheScenes(data: { nombre: string; apellido: string; dni: string }): FakeAdminAthlete {
    return this.addAdminAthlete(data);
  }

  adminAthleteCount(): number {
    return this.adminAthletes.length;
  }

  private athleteDetail(a: FakeAdminAthlete) {
    const { version, ...rest } = a;
    return { ...rest, version: String(version) };
  }

  /**
   * Mirrors POST /admin/deportistas (DeportistaSolicitud): any other property -> 400 SOLICITUD_INVALIDA, bean validation (400
   * VALIDACION), then DNI_DUPLICADO (active holder) / DNI_RESERVADO_POR_INACTIVO (inactive holder: the unique index is not
   * partial), then CUIL_DUPLICADO. No revision travels: a new athlete has none. Blank optionals become null, e-mail lowercase.
   */
  private createAthlete(body: unknown): { status: number; body?: unknown } {
    const input = (body ?? {}) as Record<string, unknown>;
    if (Object.keys(input).some((k) => !ATHLETE_FIELDS.includes(k))) return this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
    const bad = { status: 400, body: { codigo: 'VALIDACION', mensaje: 'Hay datos inválidos en la solicitud.', detalles: [{ campo: 'x', mensaje: 'detalle del servidor' }] } };
    const text = (v: unknown) => (typeof v === 'string' ? v.trim() : '');
    const optional = (v: unknown) => (text(v) === '' ? null : text(v));
    const dniRaw = text(input.dni);
    const dni = dniRaw.replace(/[.\s]/g, '');
    const nombre = text(input.nombre);
    const apellido = text(input.apellido);
    if (dniRaw === '' || dniRaw.length > 20 || !/^[0-9]{7,9}$/.test(dni) || nombre === '' || apellido === '' || nombre.length > 100 || apellido.length > 100) return bad;
    const cuilRaw = optional(input.cuil);
    if (cuilRaw !== null && (cuilRaw.length > 20 || !validCuil(cuilRaw))) return bad;
    const birth = optional(input.fechaNacimiento);
    if (birth !== null && (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(birth) || birth >= new Date().toISOString().slice(0, 10))) return bad;
    const limits: Record<string, number> = { nacionalidad: 80, domicilio: 180, otrosDatosDomicilio: 250, localidad: 100, partido: 100, codigoPostal: 15, telefonoContacto: 40 };
    for (const [field, max] of Object.entries(limits)) if ((optional(input[field])?.length ?? 0) > max) return bad;
    const email = optional(input.emailFederativo);
    if (email !== null && (email.length > 180 || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email))) return bad;
    const holder = this.adminAthletes.find((a) => a.dni === dni);
    if (holder) return holder.activo ? this.error(409, 'DNI_DUPLICADO', 'Ya existe un deportista con ese DNI.') : this.error(409, 'DNI_RESERVADO_POR_INACTIVO', 'Ya existe un deportista inactivo con ese DNI.');
    const cuil = cuilRaw === null ? null : cuilRaw.replace(/[.\-\s]/g, '');
    if (cuil !== null && this.adminAthletes.some((a) => a.cuil === cuil)) return this.error(409, 'CUIL_DUPLICADO', 'Ya existe un deportista con ese CUIL.');
    const athlete = this.addAdminAthlete({
      dni,
      nombre,
      apellido,
      cuil,
      fechaNacimiento: birth,
      nacionalidad: optional(input.nacionalidad),
      domicilio: optional(input.domicilio),
      otrosDatosDomicilio: optional(input.otrosDatosDomicilio),
      localidad: optional(input.localidad),
      partido: optional(input.partido),
      codigoPostal: optional(input.codigoPostal),
      telefonoContacto: optional(input.telefonoContacto),
      emailFederativo: email === null ? null : email.toLowerCase(),
    });
    return { status: 201, body: this.athleteDetail(athlete) };
  }

  /** Another administrator edits the athlete while this one is editing: the revision moves on, as the real backend would. */
  changeAdminAthleteBehindTheScenes(id: string, change: Partial<Omit<FakeAdminAthlete, 'id' | 'version'>>): void {
    const athlete = this.adminAthletes.find((a) => a.id === id);
    if (!athlete) throw new Error(`Unknown fake athlete ${id}`);
    Object.assign(athlete, change);
    athlete.version += 1;
  }

  adminAthleteVersion(id: string): string {
    return String(this.adminAthletes.find((a) => a.id === id)?.version);
  }

  adminAthlete(id: string): FakeAdminAthlete | undefined {
    return this.adminAthletes.find((a) => a.id === id);
  }

  /** A season of the school. They are created here, as in the real system they are created by a different unit (U14). */
  addSeason(anio: number, activa = false): FakeSeason {
    const season: FakeSeason = { id: `temp-${++this.counter}`, anio, activa };
    this.seasons.push(season);
    return season;
  }

  /** The sports data an athlete already has in a season. */
  addSportsRow(athleteId: string, seasonId: string, data: Partial<Omit<FakeSportsRow, 'id' | 'deportistaId' | 'temporadaId'>> = {}): FakeSportsRow {
    const row: FakeSportsRow = {
      id: `dt-${++this.counter}`,
      deportistaId: athleteId,
      temporadaId: seasonId,
      categoria: null,
      rama: null,
      divisional: null,
      numeroCorredor: null,
      estadoDeportivo: 'ACTIVO',
      version: 0,
      ...data,
    };
    this.sportsRows.push(row);
    return row;
  }

  private sportsRowView(row: FakeSportsRow) {
    const { version, ...rest } = row;
    const season = this.seasons.find((t) => t.id === row.temporadaId);
    return { ...rest, anio: season?.anio ?? 0, version: String(version) };
  }

  /** Mirrors Pagina.pedir: a negative page is 0 and the size is cut to [1, 100]. Newest year first. */
  private seasonPage(params: URLSearchParams) {
    const page = Math.max(Number(params.get('page') ?? 0), 0);
    const size = Math.min(Math.max(Number(params.get('size') ?? 20), 1), 100);
    const all = [...this.seasons].sort((a, b) => b.anio - a.anio);
    return { contenido: all.slice(page * size, page * size + size), pagina: page, tamanio: size, totalElementos: all.length };
  }

  /** What the server really has for one athlete (read by the tests, never by the screens). */
  sportsRowsOf(athleteId: string): FakeSportsRow[] {
    return this.sportsRows.filter((r) => r.deportistaId === athleteId);
  }

  /**
   * The normalisation and the bean validation the sports DTOs share: blank optionals become null, category/branch/division are trimmed and
   * their inner spaces collapsed, the runner number is only trimmed (leading zeros kept), nothing may contain a control character and the
   * LENGTHS are those of the NORMALISED values. `valid: false` is a 400 VALIDACION.
   */
  private sportsText(input: Record<string, unknown>): { valid: boolean; values: { categoria: string | null; rama: string | null; divisional: string | null; numeroCorredor: string | null } } {
    const text = (value: unknown): string | null => (typeof value === 'string' ? (value.trim().replace(/\s+/g, ' ') || null) : null);
    const code = (value: unknown): string | null => (typeof value === 'string' ? (value.trim() || null) : null);
    const values = { categoria: text(input.categoria), rama: text(input.rama), divisional: text(input.divisional), numeroCorredor: code(input.numeroCorredor) };
    const limits: [keyof typeof values, number][] = [['categoria', 80], ['rama', 40], ['divisional', 80], ['numeroCorredor', 30]];
    const noControl = (value: string | null) => value === null || ![...value].some((character) => (character.codePointAt(0) ?? 0) < 32 || character === '\u007f');
    const textFields = [input.categoria, input.rama, input.divisional, input.numeroCorredor];
    const valid =
      !textFields.some((value) => value !== undefined && value !== null && typeof value !== 'string') &&
      !limits.some(([field, max]) => (values[field]?.length ?? 0) > max) &&
      Object.values(values).every(noControl);
    return { valid, values };
  }

  /**
   * Mirrors POST /admin/deportistas/{id}/temporadas (DeportistaTemporadaSolicitud, U9). A property that is not part of the request ->
   * 400 SOLICITUD_INVALIDA (also an unknown sports status); bean validation (a season id, the lengths of the NORMALISED values, no control
   * characters) -> 400 VALIDACION. Then, in the backend's order: 404 athlete, 404 season, 409 inactive athlete, 409 pair already there,
   * 409 runner number already taken in that season (by anybody). The status defaults to ACTIVO.
   */
  private createSportsRow(athleteId: string, body: unknown): { status: number; body?: unknown } {
    const input = (body ?? {}) as Record<string, unknown>;
    const allowed = ['temporadaId', 'categoria', 'rama', 'divisional', 'numeroCorredor', 'estadoDeportivo'];
    const invalidRequest = () => this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
    if (Object.keys(input).some((k) => !allowed.includes(k))) return invalidRequest();
    if (input.estadoDeportivo !== undefined && input.estadoDeportivo !== null && (typeof input.estadoDeportivo !== 'string' || !['ACTIVO', 'INACTIVO', 'SUSPENDIDO'].includes(input.estadoDeportivo))) return invalidRequest();
    const { valid, values } = this.sportsText(input);
    if (typeof input.temporadaId !== 'string' || !valid) return this.error(400, 'VALIDACION', 'Hay datos inválidos en la solicitud.');
    const athlete = this.adminAthletes.find((a) => a.id === athleteId);
    if (!athlete) return this.error(404, 'DEPORTISTA_NO_ENCONTRADO', 'El deportista no existe.');
    const season = this.seasons.find((t) => t.id === input.temporadaId);
    if (!season) return this.error(404, 'TEMPORADA_NO_ENCONTRADA', 'La temporada no existe.');
    if (!athlete.activo) return this.error(409, 'DEPORTISTA_INACTIVO', 'El deportista está inactivo.');
    if (this.sportsRows.some((r) => r.deportistaId === athleteId && r.temporadaId === season.id)) return this.error(409, 'DEPORTISTA_TEMPORADA_DUPLICADA', 'Ya tiene datos en esa temporada.');
    if (values.numeroCorredor !== null && this.sportsRows.some((r) => r.temporadaId === season.id && r.numeroCorredor === values.numeroCorredor)) {
      return this.error(409, 'NUMERO_CORREDOR_DUPLICADO', 'El número de corredor ya está en uso en esa temporada.');
    }
    const row = this.addSportsRow(athleteId, season.id, { ...values, estadoDeportivo: (input.estadoDeportivo as FakeSportsStatus | null | undefined) ?? 'ACTIVO' });
    return { status: 201, body: this.sportsRowView(row) };
  }

  /**
   * Mirrors PUT /admin/deportistas/{id}/temporadas/{temporadaId} (DeportistaTemporadaActualizacion, U9): a COMPLETE replacement protected by
   * the revision of THAT row. Deserialisation first (any other property, a revision that is not a decimal string within BIGINT, an unknown
   * status -> 400 SOLICITUD_INVALIDA), then bean validation (no revision or no status, the lengths -> 400 VALIDACION). Then the
   * backend's order: 404 athlete, season, row; the REVISION (409 EDICION_DESACTUALIZADA, even when the values sent equal the stored
   * ones); the uniqueness of the runner number in the season among the OTHER rows (409 NUMERO_CORREDOR_DUPLICADO); and the write. With
   * the current revision and nothing that really changes nothing is written and the revision does not move; every real write adds 1.
   */
  private updateSportsRow(athleteId: string, seasonId: string, body: unknown): { status: number; body?: unknown } {
    const input = (body ?? {}) as Record<string, unknown>;
    const allowed = ['categoria', 'rama', 'divisional', 'numeroCorredor', 'estadoDeportivo', 'versionEsperada'];
    const invalidRequest = () => this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
    if (Object.keys(input).some((k) => !allowed.includes(k))) return invalidRequest();
    const version = input.versionEsperada;
    if (version !== undefined && version !== null && (typeof version !== 'string' || !/^[0-9]{1,19}$/.test(version) || BigInt(version) > 9223372036854775807n)) return invalidRequest();
    const status = input.estadoDeportivo;
    if (status !== undefined && status !== null && (typeof status !== 'string' || !['ACTIVO', 'INACTIVO', 'SUSPENDIDO'].includes(status))) return invalidRequest();
    const { valid, values } = this.sportsText(input);
    if (version === undefined || version === null || status === undefined || status === null || !valid) return this.error(400, 'VALIDACION', 'Hay datos inválidos en la solicitud.');
    if (!this.adminAthletes.some((a) => a.id === athleteId)) return this.error(404, 'DEPORTISTA_NO_ENCONTRADO', 'El deportista no existe.');
    if (!this.seasons.some((t) => t.id === seasonId)) return this.error(404, 'TEMPORADA_NO_ENCONTRADA', 'La temporada no existe.');
    const row = this.sportsRows.find((r) => r.deportistaId === athleteId && r.temporadaId === seasonId);
    if (!row) return this.error(404, 'DEPORTISTA_TEMPORADA_NO_ENCONTRADA', 'El deportista no tiene datos deportivos en esa temporada.');
    if (BigInt(row.version) !== BigInt(version)) return this.error(409, 'EDICION_DESACTUALIZADA', 'Los datos cambiaron mientras los editabas.');
    if (values.numeroCorredor !== null && this.sportsRows.some((r) => r !== row && r.temporadaId === seasonId && r.numeroCorredor === values.numeroCorredor)) {
      return this.error(409, 'NUMERO_CORREDOR_DUPLICADO', 'El número de corredor ya está en uso en esa temporada.');
    }
    const next = { ...values, estadoDeportivo: status as FakeSportsStatus };
    const changes = (Object.keys(next) as (keyof typeof next)[]).some((field) => next[field] !== row[field]);
    if (changes) {
      Object.assign(row, next);
      row.version += 1;
    }
    return { status: 200, body: this.sportsRowView(row) };
  }

  /** What another administrator (or a request whose answer was lost) did on the server: a real write, so the revision moves when something changes. */
  changeSportsRowBehindTheScenes(athleteId: string, seasonId: string, change: Partial<Pick<FakeSportsRow, 'categoria' | 'rama' | 'divisional' | 'numeroCorredor' | 'estadoDeportivo'>>): void {
    const row = this.sportsRows.find((r) => r.deportistaId === athleteId && r.temporadaId === seasonId);
    if (!row) throw new Error('Unknown fake sports row');
    const changes = (Object.keys(change) as (keyof typeof change)[]).some((field) => change[field] !== undefined && change[field] !== row[field]);
    Object.assign(row, change);
    if (changes) row.version += 1;
  }

  /** The revision of one row as the server holds it (a decimal string, as on the wire). */
  sportsRowVersion(athleteId: string, seasonId: string): string {
    const row = this.sportsRows.find((r) => r.deportistaId === athleteId && r.temporadaId === seasonId);
    if (!row) throw new Error('Unknown fake sports row');
    return String(row.version);
  }

  /**
   * A link between a family and an athlete of the ADMIN model (GET /admin/deportistas/{id}/vinculos, GET /admin/familias/{id}/deportistas).
   * It sits on the families and athletes that already exist here; it does not touch the portal model (`linkAthlete`/`revokeLink`).
   * Mirrors rules F1 (at most one ACTIVE principal per athlete) and one link per pair; the principal flag only makes sense when ACTIVE.
   */
  addAdminLink(familyId: string, athleteId: string, data: { estado?: FakeLinkState; esPrincipal?: boolean } = {}): FakeAdminLink {
    if (!this.families.some((f) => f.id === familyId)) throw new Error('Unknown fake family');
    if (!this.adminAthletes.some((a) => a.id === athleteId)) throw new Error('Unknown fake athlete');
    if (this.adminLinks.some((l) => l.familyId === familyId && l.athleteId === athleteId)) throw new Error('The fake family and athlete are already linked');
    const link: FakeAdminLink = { id: `vin-${++this.counter}`, familyId, athleteId, estado: data.estado ?? 'ACTIVO', esPrincipal: data.esPrincipal ?? false };
    if (link.esPrincipal && link.estado !== 'ACTIVO') throw new Error('Only an ACTIVE fake link can be the principal');
    if (link.esPrincipal && this.adminLinks.some((l) => l.athleteId === athleteId && l.estado === 'ACTIVO' && l.esPrincipal)) throw new Error('The fake athlete already has a principal link');
    if (!this.adminLinkVersions.has(athleteId)) this.adminLinkVersions.set(athleteId, this.adminAthletes.find((a) => a.id === athleteId)!.version);
    this.adminLinks.push(link);
    return link;
  }

  /** Another administrator changes a link while this one is looking at it: the revision of that athlete's links moves on. Revoking never promotes another link. */
  changeAdminLinkBehindTheScenes(linkId: string, change: { estado?: FakeLinkState; esPrincipal?: boolean }): void {
    const link = this.adminLinks.find((l) => l.id === linkId);
    if (!link) throw new Error('Unknown fake link');
    Object.assign(link, change);
    if (link.estado !== 'ACTIVO') link.esPrincipal = false;
    const athlete = this.adminAthletes.find((a) => a.id === link.athleteId);
    if (athlete) this.adminLinkVersions.set(athlete.id, (this.adminLinkVersions.get(athlete.id) ?? athlete.version) + 1);
  }

  /** The count of links the ADMIN model holds (every state). */
  adminLinkCount(): number {
    return this.adminLinks.length;
  }

  private linkView(link: FakeAdminLink) {
    const family = this.families.find((f) => f.id === link.familyId)!;
    const athlete = this.adminAthletes.find((a) => a.id === link.athleteId)!;
    return {
      vinculoId: link.id,
      familiaId: family.id,
      familiaNombre: family.nombreReferencia,
      deportistaId: athlete.id,
      deportistaNombre: athlete.nombre,
      deportistaApellido: athlete.apellido,
      deportistaActivo: athlete.activo,
      estado: link.estado,
      esPrincipal: link.esPrincipal,
      autorizadoEn: link.estado === 'ACTIVO' || link.estado === 'REVOCADO' ? '2026-03-01T12:00:00Z' : null,
      versionVinculos: String(this.adminLinkVersions.get(athlete.id) ?? athlete.version),
    };
  }

  /**
   * Mirrors GET /admin/deportistas/{id}/vinculos (VinculosDeDeportista: the revision of the athlete's links and ALL its links, every state, from
   * the same snapshot; 404 DEPORTISTA_NO_ENCONTRADO) and GET /admin/familias/{id}/deportistas (the links of the family, every state, ordered by
   * surname and name; 404 FAMILIA_NO_ENCONTRADA). Writes also mirror the revocar/principal commands with aggregate revision checks.
   */
  private linksRoute(method: string, path: string, body: unknown): { status: number; body?: unknown } | null {
    const write = /^\/admin\/familias\/([^/]+)\/deportistas\/([^/]+)\/(revocar|principal)$/.exec(path);
    if (method === 'post' && write) {
      const family = this.families.find((f) => f.id === write[1]);
      const athlete = this.adminAthletes.find((a) => a.id === write[2]);
      const link = this.adminLinks.find((l) => l.familyId === write[1] && l.athleteId === write[2]);
      if (!family || !athlete || !link) return this.error(404, 'VINCULO_NO_ENCONTRADO', 'No existe.');
      if (write[3] === 'principal' && !family.activa) return this.error(409, 'FAMILIA_INACTIVA', 'Familia inactiva.');
      if ((body as { versionEsperada?: string })?.versionEsperada !== String(this.adminLinkVersions.get(athlete.id) ?? athlete.version)) return this.error(409, 'EDICION_DESACTUALIZADA', 'Revisión desactualizada.');
      if (write[3] === 'revocar' && link.estado === 'REVOCADO') return { status: 200, body: this.linkView(link) };
      if (link.estado !== 'ACTIVO') return this.error(409, 'VINCULO_NO_ACTIVO', 'Vínculo no activo.');
      if (write[3] === 'revocar') this.changeAdminLinkBehindTheScenes(link.id, { estado: 'REVOCADO' });
      else if (!link.esPrincipal) {
        this.adminLinks.filter((l) => l.athleteId === athlete.id).forEach((l) => { l.esPrincipal = false; });
        this.changeAdminLinkBehindTheScenes(link.id, { esPrincipal: true });
      }
      return { status: 200, body: this.linkView(link) };
    }
    const ofAthlete = /^\/admin\/deportistas\/([^/]+)\/vinculos$/.exec(path);
    const ofFamily = /^\/admin\/familias\/([^/]+)\/deportistas$/.exec(path);
    if (method !== 'get' || (!ofAthlete && !ofFamily)) return null;
    if (ofAthlete) {
      const athlete = this.adminAthletes.find((a) => a.id === ofAthlete[1]);
      if (!athlete) return this.error(404, 'DEPORTISTA_NO_ENCONTRADO', 'El deportista no existe.');
      const vinculos = this.adminLinks
        .filter((l) => l.athleteId === athlete.id)
        .map((l) => this.linkView(l))
        .sort((a, b) => a.familiaNombre.toLowerCase().localeCompare(b.familiaNombre.toLowerCase()) || a.vinculoId.localeCompare(b.vinculoId));
      return { status: 200, body: { deportistaId: athlete.id, versionVinculos: String(this.adminLinkVersions.get(athlete.id) ?? athlete.version), vinculos } };
    }
    const family = this.families.find((f) => f.id === ofFamily![1]);
    if (!family) return this.error(404, 'FAMILIA_NO_ENCONTRADA', 'La familia no existe.');
    const rows = this.adminLinks
      .filter((l) => l.familyId === family.id)
      .map((l) => this.linkView(l))
      .sort(
        (a, b) =>
          a.deportistaApellido.toLowerCase().localeCompare(b.deportistaApellido.toLowerCase()) ||
          a.deportistaNombre.toLowerCase().localeCompare(b.deportistaNombre.toLowerCase()) ||
          a.vinculoId.localeCompare(b.vinculoId),
      );
    return { status: 200, body: rows };
  }

  /**
   * Mirrors GET /admin/deportistas/{id}/temporadas[/{temporadaId}] (U9) and the POST of the history path. The history is an UNPAGED
   * array, newest year first; the single row answers 404 in the backend's order: athlete, then season, then the row (another school's
   * athlete is just «not found»).
   */
  private sportsRoute(method: string, path: string, body?: unknown): { status: number; body?: unknown } | null {
    const history = /^\/admin\/deportistas\/([^/]+)\/temporadas$/.exec(path);
    const one = /^\/admin\/deportistas\/([^/]+)\/temporadas\/([^/]+)$/.exec(path);
    if (method === 'post' && history) return this.createSportsRow(history[1]!, body);
    if (method === 'put' && one) return this.updateSportsRow(one[1]!, one[2]!, body);
    if (method !== 'get' || (!history && !one)) return null;
    const athleteId = (history ?? one)![1]!;
    if (!this.adminAthletes.some((a) => a.id === athleteId)) return this.error(404, 'DEPORTISTA_NO_ENCONTRADO', 'El deportista no existe.');
    if (history) {
      const rows = this.sportsRows.filter((r) => r.deportistaId === athleteId).map((r) => this.sportsRowView(r));
      return { status: 200, body: rows.sort((a, b) => b.anio - a.anio) };
    }
    const seasonId = one![2]!;
    if (!this.seasons.some((t) => t.id === seasonId)) return this.error(404, 'TEMPORADA_NO_ENCONTRADA', 'La temporada no existe.');
    const row = this.sportsRows.find((r) => r.deportistaId === athleteId && r.temporadaId === seasonId);
    if (!row) return this.error(404, 'DEPORTISTA_TEMPORADA_NO_ENCONTRADA', 'El deportista no tiene datos deportivos en esa temporada.');
    return { status: 200, body: this.sportsRowView(row) };
  }

  /**
   * Mirrors PUT /admin/deportistas/{id} (DeportistaActualizacion, a COMPLETE replacement): any other property -> 400
   * SOLICITUD_INVALIDA, bean validation (400 VALIDACION, the revision is required), 404, the revision (409 EDICION_DESACTUALIZADA,
   * BEFORE any uniqueness rule and even when the data already match), then DNI/CUIL uniqueness only when the value changes and
   * ignoring the athlete itself. A write with no change does not move the revision; a real one does. `activo` is never touched.
   */
  private updateAthlete(id: string, body: unknown): { status: number; body?: unknown } {
    const input = (body ?? {}) as Record<string, unknown>;
    if (Object.keys(input).some((k) => ![...ATHLETE_FIELDS, 'versionEsperada'].includes(k))) return this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
    const bad = { status: 400, body: { codigo: 'VALIDACION', mensaje: 'Hay datos inválidos en la solicitud.', detalles: [{ campo: 'x', mensaje: 'detalle del servidor' }] } };
    const revision = input.versionEsperada;
    if (revision === undefined || revision === null) return bad;
    if (typeof revision !== 'string' || !/^[0-9]{1,19}$/.test(revision)) return this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
    const text = (v: unknown) => (typeof v === 'string' ? v.trim() : '');
    const optional = (v: unknown) => (text(v) === '' ? null : text(v));
    const dniRaw = text(input.dni);
    const dni = dniRaw.replace(/[.\s]/g, '');
    const nombre = text(input.nombre);
    const apellido = text(input.apellido);
    if (dniRaw === '' || dniRaw.length > 20 || !/^[0-9]{7,9}$/.test(dni) || nombre === '' || apellido === '' || nombre.length > 100 || apellido.length > 100) return bad;
    const cuilRaw = optional(input.cuil);
    if (cuilRaw !== null && (cuilRaw.length > 20 || !validCuil(cuilRaw))) return bad;
    const birth = optional(input.fechaNacimiento);
    if (birth !== null && (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(birth) || birth >= new Date().toISOString().slice(0, 10))) return bad;
    const limits: Record<string, number> = { nacionalidad: 80, domicilio: 180, otrosDatosDomicilio: 250, localidad: 100, partido: 100, codigoPostal: 15, telefonoContacto: 40 };
    for (const [field, max] of Object.entries(limits)) if ((optional(input[field])?.length ?? 0) > max) return bad;
    const email = optional(input.emailFederativo);
    if (email !== null && (email.length > 180 || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email))) return bad;
    const athlete = this.adminAthletes.find((a) => a.id === id);
    if (!athlete) return this.error(404, 'DEPORTISTA_NO_ENCONTRADO', 'El deportista no existe.');
    if (String(athlete.version) !== revision) return this.error(409, 'EDICION_DESACTUALIZADA', 'El registro fue modificado. Es necesario volver a cargarlo antes de guardar.');
    const cuil = cuilRaw === null ? null : cuilRaw.replace(/[.\-\s]/g, '');
    if (dni !== athlete.dni) {
      const holder = this.adminAthletes.find((a) => a.dni === dni && a.id !== id);
      if (holder) return holder.activo ? this.error(409, 'DNI_DUPLICADO', 'Ya existe un deportista con ese DNI.') : this.error(409, 'DNI_RESERVADO_POR_INACTIVO', 'Ya existe un deportista inactivo con ese DNI.');
    }
    if (cuil !== null && cuil !== athlete.cuil && this.adminAthletes.some((a) => a.cuil === cuil && a.id !== id)) return this.error(409, 'CUIL_DUPLICADO', 'Ya existe un deportista con ese CUIL.');
    const next = {
      dni,
      nombre,
      apellido,
      cuil,
      fechaNacimiento: birth,
      nacionalidad: optional(input.nacionalidad),
      domicilio: optional(input.domicilio),
      otrosDatosDomicilio: optional(input.otrosDatosDomicilio),
      localidad: optional(input.localidad),
      partido: optional(input.partido),
      codigoPostal: optional(input.codigoPostal),
      telefonoContacto: optional(input.telefonoContacto),
      emailFederativo: email === null ? null : email.toLowerCase(),
    };
    const changed = (Object.keys(next) as (keyof typeof next)[]).some((key) => athlete[key] !== next[key]);
    if (changed) {
      Object.assign(athlete, next);
      athlete.version += 1;
    }
    return { status: 200, body: this.athleteDetail(athlete) };
  }

  /** Mirrors POST /admin/deportistas/{id}/activar|desactivar: 404, the revision (even when the state already matches), then an idempotent change that moves the revision only when the state really changes. */
  private writeAthleteStatus(id: string, action: 'activar' | 'desactivar', body: unknown): { status: number; body?: unknown } {
    const input = (body ?? {}) as Record<string, unknown>;
    if (Object.keys(input).some((k) => k !== 'versionEsperada')) return this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
    if (input.versionEsperada === undefined || input.versionEsperada === null) {
      return { status: 400, body: { codigo: 'VALIDACION', mensaje: 'Hay datos inválidos en la solicitud.', detalles: [{ campo: 'x', mensaje: 'detalle del servidor' }] } };
    }
    if (typeof input.versionEsperada !== 'string' || !/^[0-9]{1,19}$/.test(input.versionEsperada)) return this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
    const athlete = this.adminAthletes.find((a) => a.id === id);
    if (!athlete) return this.error(404, 'DEPORTISTA_NO_ENCONTRADO', 'El deportista no existe.');
    if (String(athlete.version) !== input.versionEsperada) return this.error(409, 'EDICION_DESACTUALIZADA', 'El registro fue modificado. Es necesario volver a cargarlo antes de guardar.');
    const target = action === 'activar';
    if (athlete.activo !== target) {
      athlete.activo = target;
      athlete.version += 1;
    }
    return { status: 200, body: this.athleteDetail(athlete) };
  }

  /** Mirrors GET /admin/deportistas: `estado` defaults to ACTIVOS (unknown -> 400), «contains» search over names and DNI prefix, order apellido, nombre, id. */
  private listAthletes(params: URLSearchParams): { status: number; body?: unknown } {
    const estado = (params.get('estado') ?? '').trim() || 'ACTIVOS';
    if (!['TODOS', 'ACTIVOS', 'INACTIVOS'].includes(estado)) return this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
    const search = (params.get('busqueda') ?? '').trim().slice(0, 100).toLowerCase();
    const digits = search.replace(/[.\s]/g, '');
    const dniSearch = /^[0-9]+$/.test(digits) ? digits : '';
    const rows = this.adminAthletes
      .filter((a) => (estado === 'TODOS' ? true : estado === 'ACTIVOS' ? a.activo : !a.activo))
      .filter(
        (a) =>
          search === '' ||
          [a.apellido, a.nombre, `${a.nombre} ${a.apellido}`, `${a.apellido} ${a.nombre}`].some((v) => v.toLowerCase().includes(search)) ||
          (dniSearch !== '' && a.dni.startsWith(dniSearch)),
      )
      .sort((a, b) => a.apellido.toLowerCase().localeCompare(b.apellido.toLowerCase()) || a.nombre.toLowerCase().localeCompare(b.nombre.toLowerCase()) || a.id.localeCompare(b.id))
      // DeportistaResumen: the DNI is MASKED and nothing else of the detail travels.
      .map((a) => ({ id: a.id, nombre: a.nombre, apellido: a.apellido, dniEnmascarado: maskDni(a.dni), fechaNacimiento: a.fechaNacimiento, activo: a.activo }));
    return { status: 200, body: this.page(rows, params) };
  }

  private familyDetail(f: FakeFamily) {
    return { id: f.id, nombreReferencia: f.nombreReferencia, activa: f.activa, tutores: f.tutores.map((t) => this.tutorSummary(t, f.id)), version: String(f.version) };
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
    if (method === 'get' && path === '/admin/familias/listado') {
      // Mirrors the real endpoint: estado TODOS|ACTIVOS|INACTIVOS (unknown -> 400), case-insensitive name search
      // (trimmed, at most 100 characters) and the backend order `lower(nombre), id`.
      const estado = (params.get('estado') ?? '').trim() || 'TODOS';
      if (!['TODOS', 'ACTIVOS', 'INACTIVOS'].includes(estado)) return this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
      const search = (params.get('busqueda') ?? '').trim().slice(0, 100).toLowerCase();
      const rows = this.families
        .filter((f) => (estado === 'TODOS' ? true : estado === 'ACTIVOS' ? f.activa : !f.activa))
        .filter((f) => f.nombreReferencia.toLowerCase().includes(search))
        .sort((a, b) => a.nombreReferencia.toLowerCase().localeCompare(b.nombreReferencia.toLowerCase()) || a.id.localeCompare(b.id))
        .map(({ id, nombreReferencia, activa, cantidadTutores, cantidadDeportistasActivos }) => ({ id, nombreReferencia, activa, cantidadTutores, cantidadDeportistasActivos }));
      return { status: 200, body: this.page(rows, params) };
    }
    const links = this.linksRoute(method, path, body);
    if (links) return links;
    const detail = /^\/admin\/familias\/([^/]+)$/.exec(path);
    if (method === 'get' && detail) {
      const family = this.families.find((f) => f.id === detail[1]);
      if (!family) return this.error(404, 'FAMILIA_NO_ENCONTRADA', 'La familia no existe.');
      return { status: 200, body: this.familyDetail(family) };
    }
    const tutorDetail = /^\/admin\/tutores\/([^/]+)$/.exec(path);
    if (method === 'get' && tutorDetail) {
      const found = this.findTutor(tutorDetail[1]!);
      if (!found) return this.error(404, 'TUTOR_NO_ENCONTRADO', 'El tutor no existe.');
      return { status: 200, body: this.tutorFull(found.tutor, found.family.id) };
    }
    if (method === 'put' && tutorDetail) return this.updateTutor(tutorDetail[1]!, body);
    const tutorCreate = /^\/admin\/familias\/([^/]+)\/tutores$/.exec(path);
    if (method === 'post' && tutorCreate) return this.createTutor(tutorCreate[1]!, body);
    const familyWrite = /^\/admin\/familias\/([^/]+)(?:\/(activar|desactivar))?$/.exec(path);
    if ((method === 'put' && familyWrite && !familyWrite[2]) || (method === 'post' && familyWrite?.[2])) {
      return this.writeFamily(familyWrite[1]!, familyWrite[2] as 'activar' | 'desactivar' | undefined, body);
    }
    if (method === 'post' && path === '/admin/familias') {
      // Mirrors FamiliaSolicitud: only nombreReferencia, @NotBlank, at most 150 characters, the backend strips it.
      const input = (body ?? {}) as Record<string, unknown>;
      if (Object.keys(input).some((k) => k !== 'nombreReferencia')) return this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
      const name = typeof input.nombreReferencia === 'string' ? input.nombreReferencia.trim() : '';
      if (name === '' || name.length > 150) {
        return { status: 400, body: { codigo: 'VALIDACION', mensaje: 'Hay datos inválidos en la solicitud.', detalles: [{ campo: 'nombreReferencia', mensaje: 'detalle del servidor' }] } };
      }
      return { status: 201, body: this.familyDetail(this.addFamily(name)) };
    }
    if (method === 'get' && path === '/admin/deportistas') return this.listAthletes(params);
    if (method === 'post' && path === '/admin/deportistas') return this.createAthlete(body);
    const athleteDetail = /^\/admin\/deportistas\/([^/]+)$/.exec(path);
    if (method === 'get' && athleteDetail) {
      const athlete = this.adminAthletes.find((a) => a.id === athleteDetail[1]);
      if (!athlete) return this.error(404, 'DEPORTISTA_NO_ENCONTRADO', 'El deportista no existe.');
      return { status: 200, body: this.athleteDetail(athlete) };
    }
    if (method === 'get' && path === '/admin/temporadas') return { status: 200, body: this.seasonPage(params) };
    const sports = this.sportsRoute(method, path, body);
    if (sports) return sports;
    const athleteWrite = /^\/admin\/deportistas\/([^/]+)(?:\/(activar|desactivar))?$/.exec(path);
    if (method === 'put' && athleteWrite && !athleteWrite[2]) return this.updateAthlete(athleteWrite[1]!, body);
    if (method === 'post' && athleteWrite?.[2]) return this.writeAthleteStatus(athleteWrite[1]!, athleteWrite[2] as 'activar' | 'desactivar', body);
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
    const created = { ...athlete, id: `00000000-0000-4000-8000-${String(++this.counter).padStart(12, '0')}` }; // UUID, like the real ids
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
    const sheet = /^\/familia\/deportistas\/([^/]+)\/ficha$/.exec(path);
    if (sheet) {
      // Mirrors GET /api/familia/deportistas/{id}/ficha: a closed list of fields, and ONE 404 for every cause.
      const id = sheet[1]!;
      if (!UUID_FORMAT.test(id)) return this.error(400, 'SOLICITUD_INVALIDA', 'La solicitud no es válida.');
      const athlete = family && family.links.get(id) === true ? this.athletes.get(id) : undefined;
      if (!athlete) return this.error(404, 'DEPORTISTA_NO_ENCONTRADO', 'El deportista no existe.');
      return {
        status: 200,
        body: { nombre: athlete.nombre, apellido: athlete.apellido, edad: athlete.edad ?? null, activo: athlete.activo, temporadaVigente: athlete.vigente ?? null, ...athlete.extra },
      };
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
        edad: a.edad ?? null, // the birth date is NOT sent in the list anymore (backend U4)
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
  override(method: 'get' | 'post' | 'put', url: string, value: Override, options: { once?: boolean } = {}): this {
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
  hold(method: 'get' | 'post' | 'put', url: string): { release: () => void } {
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

    if (method !== 'get' && (!csrfHeader || csrfHeader !== this.csrfToken)) {
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
