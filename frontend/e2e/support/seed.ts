import { randomBytes, randomUUID } from 'node:crypto';
import { expect, type APIRequestContext, type Page } from '@playwright/test';
import { adminCredentials, loadState, saveState } from './state';
import { freshCode } from './totp';

/**
 * Synthetic seed data for the real portal E2E, created through the REAL admin API (never by SQL). Everything is
 * synthetic: names start with "E2E", DNIs are random 9-digit numbers starting with 9 and e-mails use example.test.
 */

export async function csrfHeaders(api: APIRequestContext): Promise<Record<string, string>> {
  await api.get('/api/auth/csrf');
  const state = await api.storageState();
  const cookie = state.cookies.find((c) => c.name === 'XSRF-TOKEN');
  if (!cookie) throw new Error('No XSRF-TOKEN cookie after GET /api/auth/csrf');
  return { 'X-XSRF-TOKEN': decodeURIComponent(cookie.value) };
}

/** Same steps as the invitations spec (password, then a fresh TOTP code of the enrolled administrator). */
export async function loginAdminUi(page: Page): Promise<void> {
  const { email, password } = adminCredentials();
  const state = loadState();
  if (!state.adminSecret) throw new Error('Run auth.real.spec.ts first: the administrator is not enrolled.');
  await page.goto('/admin/login');
  await page.getByLabel(/^Correo electrónico/).fill(email);
  await page.getByLabel(/^Contraseña/).fill(password);
  await page.getByRole('button', { name: 'Continuar' }).click();
  await expect(page).toHaveURL(/\/admin\/mfa$/);
  const { code, step } = await freshCode(state.adminSecret, state.lastTotpStep ?? null);
  saveState({ ...loadState(), lastTotpStep: step });
  await page.getByLabel(/^Código de 6 dígitos/).fill(code);
  await page.getByRole('button', { name: 'Verificar e ingresar' }).click();
  await expect(page).toHaveURL(/\/admin$/);
}

/** Today's calendar date in the SCHOOL's time zone (the one the backend uses to compute ages), as YYYY-MM-DD. */
export const schoolToday = (): string => new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Argentina/Buenos_Aires' }).format(new Date());

/**
 * A birth date that makes someone EXACTLY `age` years old today: born `age` years and one day ago, so the birthday has
 * already passed whatever day the test runs. It lets a test expect a precise age instead of a range.
 */
export function birthDateForAge(age: number): string {
  const [year, month, day] = schoolToday().split('-').map(Number) as [number, number, number];
  return new Date(Date.UTC(year - age, month - 1, day - 1)).toISOString().slice(0, 10);
}

/** The same date in the formats a screen or a log could print it in (ISO, dd/mm/yyyy, mm/dd/yyyy, dd-mm-yyyy). */
export function dateForms(iso: string): string[] {
  const [year, month, day] = iso.split('-') as [string, string, string];
  return [iso, `${day}/${month}/${year}`, `${month}/${day}/${year}`, `${day}-${month}-${year}`];
}

export const syntheticDni = (): string => `9${String(randomBytes(4).readUInt32BE() % 100_000_000).padStart(8, '0')}`;

export interface SeededFamily {
  familyId: string;
  email: string;
  password: string;
  nombre: string;
  name: string;
}

async function expectOk<T>(response: Awaited<ReturnType<APIRequestContext['post']>>, what: string): Promise<T> {
  expect(response.ok(), `${what}: HTTP ${response.status()}`).toBe(true);
  return (response.status() === 204 ? null : await response.json()) as T;
}

/** ADMIN-side seeding. `admin` is the request context of a browser context already signed in as the administrator. */
export type SportsRow = { categoria?: string; rama?: string; divisional?: string; numeroCorredor?: string; estadoDeportivo?: 'ACTIVO' | 'INACTIVO' | 'SUSPENDIDO' };

export class AdminSeed {
  constructor(private readonly admin: APIRequestContext) {}

  private async post<T = unknown>(url: string, data: unknown, what: string): Promise<T> {
    return expectOk<T>(await this.admin.post(url, { data, headers: await csrfHeaders(this.admin) }), what);
  }

  /** New family + invitation (admin API), then the family user registers with it (public API, no session). */
  async createFamilyWithUser(anonymous: APIRequestContext, name: string, user: { nombre: string; apellido: string; email: string; password: string }): Promise<SeededFamily> {
    const invitation = await this.post<{ token: string; familia: { id: string } }>('/api/admin/invitaciones', { nuevaFamilia: { nombreReferencia: name }, emailSugerido: user.email }, 'create invitation');
    await expectOk(
      await anonymous.post('/api/auth/registro/invitacion', { data: { token: invitation.token, ...user }, headers: await csrfHeaders(anonymous) }),
      'register family user',
    );
    return { familyId: invitation.familia.id, email: user.email, password: user.password, nombre: user.nombre, name };
  }

  /** A family created directly through the admin API (no invitation, no user). `version` is the optimistic revision. */
  async createFamily(name: string): Promise<{ id: string; version: string }> {
    return this.post('/api/admin/familias', { nombreReferencia: name }, `create family ${name}`);
  }

  /** Needs the revision currently stored (FE-05/U1): a brand-new family is "0". */
  async deactivateFamily(familyId: string, version: string): Promise<void> {
    await this.post(`/api/admin/familias/${familyId}/desactivar`, { versionEsperada: version }, 'deactivate family');
  }

  /**
   * The revision of a family as the backend stores it right now (FE-05/U1): a decimal string. It is read, never invented:
   * the tutor contract (FE-05/U4) requires the revision the caller observed.
   */
  private async familyVersion(familyId: string): Promise<string> {
    const family = await expectOk<{ version?: unknown }>(await this.admin.get(`/api/admin/familias/${familyId}`), `read family ${familyId}`);
    expect(typeof family.version === 'string' && /^[0-9]{1,19}$/.test(family.version), `family ${familyId}: version must be a decimal string`).toBe(true);
    return family.version as string;
  }

  /**
   * A tutor of an existing family, through the admin API. Since FE-05/U4 the creation must carry the family revision the caller
   * observed (`versionFamiliaEsperada`): it is read just before, with the same session, and sent unchanged (no retry, no bypass).
   * Returns the plain DNI (synthetic) so tests can prove it never leaks.
   */
  async createTutor(familyId: string, data: { nombre: string; apellido: string; telefono?: string; email?: string; parentesco?: string; withDni?: boolean }): Promise<{ id: string; dni: string | null }> {
    const { withDni = true, ...rest } = data;
    const dni = withDni ? syntheticDni() : null;
    const versionFamiliaEsperada = await this.familyVersion(familyId);
    const created = await this.post<{ id: string }>(
      `/api/admin/familias/${familyId}/tutores`,
      { ...rest, ...(dni ? { dni } : {}), versionFamiliaEsperada },
      `create tutor ${data.nombre}`,
    );
    return { id: created.id, dni };
  }

  async createAthlete(data: { nombre: string; apellido: string; fechaNacimiento?: string }): Promise<{ id: string; dni: string }> {
    const dni = syntheticDni();
    const created = await this.post<{ id: string }>('/api/admin/deportistas', { dni, ...data }, `create athlete ${data.nombre}`);
    return { id: created.id, dni };
  }

  /** An athlete with the exact data given (any DeportistaSolicitud field), through the real admin API. Returns the stored id and revision. */
  async createAthleteWith(data: Record<string, string>): Promise<{ id: string; version: string }> {
    return this.post('/api/admin/deportistas', data, `create athlete ${data.nombre ?? ''}`);
  }

  /**
   * The revision of an athlete's family links as the backend stores it right now (FE-05/U11-A): a decimal string read from
   * GET /api/admin/deportistas/{id}/vinculos. It is read, never invented: linking, revoking and changing the principal require
   * the revision the caller observed. It is independent of the athlete revision (`athleteVersion`).
   */
  private async linksVersion(athleteId: string): Promise<string> {
    const links = await expectOk<{ versionVinculos?: unknown }>(await this.admin.get(`/api/admin/deportistas/${athleteId}/vinculos`), `read links of athlete ${athleteId}`);
    expect(typeof links.versionVinculos === 'string' && /^[0-9]{1,19}$/.test(links.versionVinculos), `athlete ${athleteId}: versionVinculos must be a decimal string`).toBe(true);
    return links.versionVinculos as string;
  }

  /** Since FE-05/U11-A each athlete of the batch carries the link revision observed just before (read once per athlete, sent unchanged). */
  async link(familyId: string, athleteIds: string[]): Promise<void> {
    const versions = new Map<string, string>();
    for (const id of athleteIds) {
      if (!versions.has(id)) versions.set(id, await this.linksVersion(id));
    }
    const deportistas = athleteIds.map((deportistaId) => ({ deportistaId, versionEsperada: versions.get(deportistaId) }));
    await this.post(`/api/admin/familias/${familyId}/deportistas`, { deportistas }, 'link athletes');
  }

  /**
   * The revision of an athlete as the backend stores it right now (FE-05/U6): a decimal string. It is read, never invented:
   * the athlete contract requires the revision the caller observed to edit, activate or deactivate.
   */
  private async athleteVersion(athleteId: string): Promise<string> {
    const athlete = await expectOk<{ version?: unknown }>(await this.admin.get(`/api/admin/deportistas/${athleteId}`), `read athlete ${athleteId}`);
    expect(typeof athlete.version === 'string' && /^[0-9]{1,19}$/.test(athlete.version), `athlete ${athleteId}: version must be a decimal string`).toBe(true);
    return athlete.version as string;
  }

  /** Since FE-05/U6 the state change must carry the athlete revision the caller observed: it is read just before and sent unchanged. */
  async deactivate(athleteId: string): Promise<void> {
    await this.post(`/api/admin/deportistas/${athleteId}/desactivar`, { versionEsperada: await this.athleteVersion(athleteId) }, 'deactivate athlete');
  }

  /** Since FE-05/U11-A the revocation must carry the link revision observed: it is read just before and sent unchanged. */
  async revoke(familyId: string, athleteId: string): Promise<void> {
    await this.post(`/api/admin/familias/${familyId}/deportistas/${athleteId}/revocar`, { versionEsperada: await this.linksVersion(athleteId) }, 'revoke link');
  }

  /** The revision of a tutor as the backend stores it right now (FE-05/U4): a decimal string, read just before the write that carries it. */
  private async tutorVersion(tutorId: string): Promise<string> {
    const tutor = await expectOk<{ version?: unknown }>(await this.admin.get(`/api/admin/tutores/${tutorId}`), `read tutor ${tutorId}`);
    expect(typeof tutor.version === 'string' && /^[0-9]{1,19}$/.test(tutor.version), `tutor ${tutorId}: version must be a decimal string`).toBe(true);
    return tutor.version as string;
  }

  /**
   * An explicit responsibility (C2-B) of a tutor over an athlete, through the real admin API. It needs the four revisions the caller observed
   * (family of the tutor, athlete, tutor and links of the athlete): they are read just before and sent unchanged. The backend answers
   * 503 unless the disposable E2E backend enabled `banfield.responsables.alta-habilitada` (the default stays disabled). A fresh
   * `Idempotency-Key` per call: a new confirmation is a new key.
   */
  async addResponsable(athleteId: string, tutorId: string, familyId: string, tipo?: string | null): Promise<{ id: string; version: string }> {
    const body = {
      tutorId,
      ...(tipo === undefined ? {} : { tipo }),
      versionFamilia: await this.familyVersion(familyId),
      versionDeportista: await this.athleteVersion(athleteId),
      versionTutor: await this.tutorVersion(tutorId),
      versionVinculos: await this.linksVersion(athleteId),
    };
    const response = await this.admin.post(`/api/admin/deportistas/${athleteId}/vinculos-tutores`, {
      data: body,
      headers: { ...(await csrfHeaders(this.admin)), 'Idempotency-Key': randomUUID() },
    });
    return expectOk(response, 'add responsable');
  }

  /** Revokes one responsibility: the revision is read from the open list just before and sent unchanged. */
  async revokeResponsable(athleteId: string, responsableId: string): Promise<void> {
    const page = await expectOk<{ contenido: { id: string; version: string }[] }>(
      await this.admin.get(`/api/admin/deportistas/${athleteId}/vinculos-tutores?historial=false&page=0&size=100`), 'read responsables');
    const found = page.contenido.find((r) => r.id === responsableId);
    expect(found, 'the responsable to revoke must be open').toBeTruthy();
    await this.post(`/api/admin/deportistas/${athleteId}/vinculos-tutores/${responsableId}/revocar`, { versionEsperada: found!.version }, 'revoke responsable');
  }

  /** Deactivating a tutor closes its open responsibilities; reactivating it never reopens them. */
  async deactivateTutor(tutorId: string): Promise<void> {
    await this.post(`/api/admin/tutores/${tutorId}/desactivar`, { versionEsperada: await this.tutorVersion(tutorId) }, 'deactivate tutor');
  }

  async activateTutor(tutorId: string): Promise<void> {
    await this.post(`/api/admin/tutores/${tutorId}/activar`, { versionEsperada: await this.tutorVersion(tutorId) }, 'activate tutor');
  }

  /** The season of that year (created when missing); it is only created, not activated. */
  async season(anio: number): Promise<{ id: string; anio: number; activa: boolean }> {
    const page = await expectOk<{ contenido: { id: string; anio: number; activa: boolean }[] }>(await this.admin.get('/api/admin/temporadas?page=0&size=100'), 'list seasons');
    return page.contenido.find((t) => t.anio === anio) ?? (await this.post<{ id: string; anio: number; activa: boolean }>('/api/admin/temporadas', { anio }, `create season ${anio}`));
  }

  /**
   * Since FE-05/U13 the activation must carry the admin's intent: the id of the season observed as active (null when none).
   * It is read just before and sent unchanged, so a stale decision would be rejected with 409 EDICION_DESACTUALIZADA.
   */
  async activateSeason(seasonId: string): Promise<void> {
    const page = await expectOk<{ contenido: { id: string; activa: boolean }[] }>(await this.admin.get('/api/admin/temporadas?page=0&size=100'), 'list seasons');
    const observedActive = page.contenido.find((t) => t.activa)?.id ?? null;
    await this.post(`/api/admin/temporadas/${seasonId}/activar`, { activaEsperadaId: observedActive }, 'activate season');
  }

  /** The sports data of one athlete in one season (what the family sees as «Temporada vigente» when that season is active). */
  async addSportsRow(athleteId: string, seasonId: string, row: SportsRow): Promise<void> {
    await this.post(`/api/admin/deportistas/${athleteId}/temporadas`, { temporadaId: seasonId, ...row }, 'add sports row');
  }
}
