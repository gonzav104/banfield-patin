import { fileURLToPath } from 'node:url';
import { adminCredentials, loadState, saveState } from './state.ts';
import { freshCode } from './totp.ts';

/**
 * Enrola al administrador del bootstrap con la API REAL (login, enrolar, confirmar con un codigo TOTP vigente) y guarda el secreto en
 * e2e/.state.json, igual que el primer escenario de auth.real.spec.ts. Lo usa cada shard de CI que NO ejecuta ese spec: todos los demas
 * dependen de un administrador enrolado y cada shard tiene su propia base, asi que su administrador tambien tiene que estar enrolado.
 * No es un escenario: no cuenta como prueba ni cambia ninguna. Nunca imprime el secreto, la contrasena ni los codigos.
 *
 * Uso (con el backend arriba): E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=... node e2e/support/enrol-admin.ts
 */
const BACKEND = process.env.E2E_BACKEND_URL ?? 'http://127.0.0.1:8080';

/** Cookies minimas de una sesion de API (sin navegador): la sesion HttpOnly y el token CSRF de doble envio. */
class Session {
  private readonly cookies = new Map<string, string>();

  private remember(response: Response): void {
    for (const header of response.headers.getSetCookie()) {
      const [pair = ''] = header.split(';');
      const index = pair.indexOf('=');
      if (index <= 0) continue;
      const name = pair.slice(0, index).trim();
      const value = pair.slice(index + 1).trim();
      if (value === '' || /;\s*max-age=0/i.test(header)) this.cookies.delete(name);
      else this.cookies.set(name, value);
    }
  }

  async call(method: 'GET' | 'POST', path: string, body?: unknown): Promise<unknown> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (this.cookies.size > 0) headers.Cookie = [...this.cookies].map(([name, value]) => `${name}=${value}`).join('; ');
    const csrf = this.cookies.get('XSRF-TOKEN');
    if (method !== 'GET' && csrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(csrf);
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(`${BACKEND}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    this.remember(response);
    if (!response.ok) throw new Error(`${method} ${path} -> HTTP ${response.status}`); // sin el cuerpo: podria traer datos sensibles
    return response.headers.get('content-type')?.includes('json') ? ((await response.json()) as unknown) : undefined;
  }
}

export async function enrolAdmin(): Promise<void> {
  if (loadState().adminSecret) {
    console.log('[e2e] el administrador ya esta enrolado: nada que hacer');
    return;
  }
  const { email, password } = adminCredentials();
  const session = new Session();
  await session.call('GET', '/api/auth/csrf');
  await session.call('POST', '/api/auth/admin/login', { email, password });
  const enrolment = (await session.call('POST', '/api/auth/admin/mfa/enrolar')) as { secretoBase32?: unknown };
  if (typeof enrolment?.secretoBase32 !== 'string' || !/^[A-Z2-7]{16,}$/.test(enrolment.secretoBase32)) {
    throw new Error('El enrolamiento no devolvio un secreto TOTP valido.');
  }
  const secret = enrolment.secretoBase32;
  const { code, step } = await freshCode(secret, null);
  await session.call('POST', '/api/auth/admin/mfa/confirmar', { codigo: code });
  saveState({ ...loadState(), adminSecret: secret, lastTotpStep: step });
  console.log('[e2e] administrador enrolado por API (el secreto queda solo en e2e/.state.json)');
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  enrolAdmin().catch((error: unknown) => {
    console.error(`[e2e] no se pudo enrolar al administrador: ${error instanceof Error ? error.message : 'error desconocido'}`);
    process.exit(1);
  });
}
