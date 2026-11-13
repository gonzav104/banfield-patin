import { beforeEach, describe, expect, it } from 'vitest';
import { apiWrite, http } from '@/lib/http';
import type { FakeBackend } from './fakeBackend';
import { admin, createBackend, family } from './render';

// The fake must behave like the real portal, or the tests built on it prove nothing. Each rule below was read from
// FamiliaPortalController/Service and their tests in backend/ (real-backend proof lives in e2e/portal.real.spec.ts).
let backend: FakeBackend;

beforeEach(() => {
  backend = createBackend();
});

const get = (url: string) => http.get<{ contenido: { nombre: string; activo: boolean }[]; tamanio: number; totalElementos: number }>(url).then((r) => r.data);

describe('FakeBackend family portal fidelity', () => {
  it('answers 401 to anonymous visitors and 403 to ADMIN (full or MFA-pending) sessions', async () => {
    await expect(http.get('/familia/mi-familia')).rejects.toMatchObject({ kind: 'unauthenticated', code: 'NO_AUTENTICADO' });
    backend.signIn(admin.email);
    await expect(http.get('/familia/deportistas')).rejects.toMatchObject({ kind: 'forbidden', code: 'ACCESO_DENEGADO' });
    backend.signIn(admin.email, true);
    await expect(http.get('/familia/deportistas')).rejects.toMatchObject({ kind: 'forbidden' });
  });

  it('is read-only: any write is 405 METODO_NO_PERMITIDO', async () => {
    backend.signIn(family.email);
    await expect(apiWrite('post', '/familia/deportistas', {})).rejects.toMatchObject({ status: 405, code: 'METODO_NO_PERMITIDO' });
  });

  it('gives an account with no family an empty list and 404 FAMILIA_NO_ENCONTRADA', async () => {
    backend.addUser({ ...family, id: 'u-nofam', email: 'sinfamilia@example.com', familiaId: null });
    backend.signIn('sinfamilia@example.com');
    expect(await get('/familia/deportistas')).toMatchObject({ contenido: [], totalElementos: 0 });
    await expect(http.get('/familia/mi-familia')).rejects.toMatchObject({ status: 404, code: 'FAMILIA_NO_ENCONTRADA' });
  });

  it('takes the family from the session and ignores any familiaId parameter', async () => {
    backend.addPortalFamily('familia-2', 'Otra');
    const other = backend.addAthlete({ nombre: 'Ajena', apellido: 'López', fechaNacimiento: null, activo: true });
    backend.linkAthlete('familia-2', other.id);
    backend.signIn(family.email);
    expect((await get('/familia/deportistas?familiaId=familia-2')).contenido).toEqual([]);
  });

  it('lists only ACTIVE links, keeps inactive athletes visible, and sorts by last name then first name ignoring case', async () => {
    const mk = (nombre: string, apellido: string, activo = true) => backend.addAthlete({ nombre, apellido, fechaNacimiento: null, activo });
    const [b, a2, a1, inactive, revoked] = [mk('Bruno', 'zárate'), mk('Zoe', 'Alvarez'), mk('ana', 'alvarez'), mk('Iván', 'Medina', false), mk('Rev', 'Oculto')];
    for (const x of [b, a2, a1, inactive, revoked]) backend.linkAthlete('familia-1', x.id);
    backend.revokeLink('familia-1', revoked.id);
    backend.signIn(family.email);

    const list = await get('/familia/deportistas');

    expect(list.contenido.map((x) => x.nombre)).toEqual(['ana', 'Zoe', 'Iván', 'Bruno']);
    expect(list.contenido.find((x) => x.nombre === 'Iván')?.activo).toBe(false);
    expect(list.totalElementos).toBe(4);
  });

  it('clamps page size to 1..100 and never answers 400', async () => {
    for (let i = 0; i < 3; i += 1) backend.linkAthlete('familia-1', backend.addAthlete({ nombre: `N${i}`, apellido: 'A', fechaNacimiento: null, activo: true }).id);
    backend.signIn(family.email);
    expect((await get('/familia/deportistas?size=0')).contenido).toHaveLength(1);
    expect((await get('/familia/deportistas?size=500')).tamanio).toBe(100);
    expect((await get('/familia/deportistas?page=-4&size=2')).contenido).toHaveLength(2);
  });
});
