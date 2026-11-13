import { randomBytes } from 'node:crypto';
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

  async createAthlete(data: { nombre: string; apellido: string; fechaNacimiento?: string }): Promise<{ id: string; dni: string }> {
    const dni = syntheticDni();
    const created = await this.post<{ id: string }>('/api/admin/deportistas', { dni, ...data }, `create athlete ${data.nombre}`);
    return { id: created.id, dni };
  }

  async link(familyId: string, athleteIds: string[]): Promise<void> {
    await this.post(`/api/admin/familias/${familyId}/deportistas`, { deportistaIds: athleteIds }, 'link athletes');
  }

  async deactivate(athleteId: string): Promise<void> {
    await this.post(`/api/admin/deportistas/${athleteId}/desactivar`, {}, 'deactivate athlete');
  }

  async revoke(familyId: string, athleteId: string): Promise<void> {
    await this.post(`/api/admin/familias/${familyId}/deportistas/${athleteId}/revocar`, {}, 'revoke link');
  }
}
