import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StrictMode } from 'react';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '@/app/App';
import { AppProviders, createQueryClient } from '@/app/providers';
import { sessionKey } from '@/features/auth/session';
import type { FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';
import { athleteLinksSchema, familyAthletesSchema } from './schemas';
import { getAthleteLinks, getFamilyAthletes } from './api';
import { rawLink, seedMixedLinks, seedAthlete } from './links-fixtures';

let backend: FakeBackend;
beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: 'otra.admin@example.com', mfaEnrolado: true });
});
afterEach(() => vi.restoreAllMocks());
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 400)); });
const notFound = (code: string) => ({ status: 404, body: { codigo: code, mensaje: 'x' } });
const location = () => screen.getByTestId('location').textContent ?? '';

function renderWithNavigation(path: string, strict = false) {
  const queryClient = createQueryClient();
  let navigateTo: (to: string) => void = () => undefined;
  function Probe() {
    const navigate = useNavigate();
    navigateTo = (to) => void navigate(to);
    return null;
  }
  const tree = (
    <AppProviders client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <App />
        <Probe />
      </MemoryRouter>
    </AppProviders>
  );
  render(strict ? <StrictMode>{tree}</StrictMode> : tree);
  return { queryClient, go: (to: string) => act(() => { navigateTo(to); return Promise.resolve(); }) };
}

const SECRETS = ['SECRETDNI99887766', 'SECRETCUIL2099887766X', 'SECRETDOMICILIO-Calle-9', 'SECRETAUTORIZADOPOR', 'SECRETUSUARIOID', 'SECRETESCUELAID', 'SECRETAUTORIZADOEN', 'SECRETTOKEN', 'SECRETEXTRA', '777000777'];
const hostileRow = (row: Record<string, unknown>) => ({
  ...row,
  dni: SECRETS[0], cuil: SECRETS[1], domicilio: SECRETS[2], autorizadoPor: SECRETS[3], usuarioId: SECRETS[4], escuelaId: SECRETS[5],
  autorizadoEn: SECRETS[6], token: SECRETS[7], extra: { nested: SECRETS[8] },
  'data-x': SECRETS[8],
});

describe('privacy: a hostile payload never reaches the DOM (also attributes), storage, console, address, cache or requests', () => {
  it('athlete screen', async () => {
    const { athlete } = seedMixedLinks(backend);
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((m) => vi.spyOn(console, m).mockImplementation(() => undefined));
    backend.signIn('marcos@example.com');
    // Take the real fake answer and decorate it with extra keys.
    const probe = await import('@/lib/http').then((m) => m.apiGet<{ vinculos: Record<string, unknown>[]; versionVinculos: string }>(`/admin/deportistas/${athlete.id}/vinculos`));
    backend.requests.length = 0;
    const body = { ...probe, versionVinculos: '777000777', usuarioId: SECRETS[4], dni: SECRETS[0], vinculos: probe.vinculos.map((r) => hostileRow({ ...r, versionVinculos: '777000777' })) };
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, { status: 200, body }, { once: false });
    backend.override('get', `/admin/deportistas/${athlete.id}`, { status: 200, body: { id: athlete.id, nombre: 'Lucía', apellido: 'Sintética', activo: true, dni: SECRETS[0], cuil: SECRETS[1], domicilio: SECRETS[2], version: '777000777' } }, { once: false });
    const { queryClient } = renderApp(`/admin/deportistas/${athlete.id}/vinculos`);
    await screen.findByRole('link', { name: 'Familia Gómez' });
    await settle();
    const html = document.body.innerHTML;
    const cache = JSON.stringify(queryClient.getQueryCache().getAll().map((q) => [q.queryKey, q.state.data]));
    const stored = JSON.stringify([{ ...localStorage }, { ...sessionStorage }]);
    const logged = spies.flatMap((s) => s.mock.calls).map((c) => c.map(String).join(' ')).join('\n');
    const reqs = JSON.stringify(backend.requests);
    for (const s of SECRETS) {
      expect(html, `html ${s}`).not.toContain(s);
      expect(stored, `storage ${s}`).not.toContain(s);
      expect(logged, `console ${s}`).not.toContain(s);
      expect(location(), `url ${s}`).not.toContain(s);
      expect(reqs, `req ${s}`).not.toContain(s);
      if (s !== '777000777') expect(cache, `cache ${s}`).not.toContain(s);
    }
    expect(window.history.state === null || !JSON.stringify(window.history.state).includes('777000777')).toBe(true);
    expect(document.documentElement.outerHTML).not.toContain('777000777');
  });

  it('family screen', async () => {
    const { gomez } = seedMixedLinks(backend);
    const fam = gomez;
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((m) => vi.spyOn(console, m).mockImplementation(() => undefined));
    backend.signIn('marcos@example.com');
    const probe = await import('@/lib/http').then((m) => m.apiGet<Record<string, unknown>[]>(`/admin/familias/${fam.id}/deportistas`));
    backend.requests.length = 0;
    backend.override('get', `/admin/familias/${fam.id}/deportistas`, { status: 200, body: probe.map((r) => hostileRow({ ...r, versionVinculos: '777000777' })) }, { once: false });
    const { queryClient } = renderApp(`/admin/familias/${fam.id}`);
    await screen.findByRole('heading', { level: 3, name: 'Vínculos activos' });
    await settle();
    const html = document.documentElement.outerHTML;
    const cache = JSON.stringify(queryClient.getQueryCache().getAll().map((q) => [q.queryKey, q.state.data]));
    const stored = JSON.stringify([{ ...localStorage }, { ...sessionStorage }]);
    const logged = spies.flatMap((s) => s.mock.calls).map((c) => c.map(String).join(' ')).join('\n');
    for (const s of SECRETS) {
      expect(html, `html ${s}`).not.toContain(s);
      expect(stored).not.toContain(s);
      expect(logged).not.toContain(s);
      expect(location()).not.toContain(s);
      expect(JSON.stringify(backend.requests)).not.toContain(s);
      if (s !== '777000777') expect(cache, `cache ${s}`).not.toContain(s);
    }
  });
});

describe('privacy: going from the athlete detail to the links screen leaves no DNI in the cache', () => {
  it('after navigating from the athlete detail (full read) to the links screen the DNI is not in the cache', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    const { queryClient, go } = renderWithNavigation(`/admin/deportistas/${athlete.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    await go(`/admin/deportistas/${athlete.id}/vinculos`);
    await screen.findByRole('link', { name: 'Familia Gómez' });
    await settle();
    const cache = JSON.stringify(queryClient.getQueryCache().getAll().map((q) => [q.queryKey, q.state.data]));
    expect(cache).not.toContain('30111322');
    expect(cache).not.toContain('20301113227');
    expect(cache).not.toContain('Calle Falsa');
    // and after leaving the screen entirely nothing of the links remains
    await go('/admin/deportistas');
    await screen.findByRole('heading', { name: 'Deportistas' });
    await settle();
    const after = queryClient.getQueryCache().getAll().filter((q) => q.queryKey[0] === 'admin' && /athlete-links|family-athletes/.test(String(q.queryKey[1])));
    expect(after).toEqual([]);
  });
});

describe('isolation: identity and resource while answers are pending', () => {
  it('stale answer of identity A, released after switching to identity B who sees NEWER data, never overwrites', async () => {
    const { athlete, gomezLink } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    const held = backend.hold('get', `/admin/deportistas/${athlete.id}/vinculos`);
    const { queryClient } = renderApp(`/admin/deportistas/${athlete.id}/vinculos`);
    await screen.findByText('Cargando los vínculos…');
    // another admin revokes behind the scenes, then identity switches
    backend.changeAdminLinkBehindTheScenes(gomezLink.id, { estado: 'REVOCADO' });
    backend.signIn('otra.admin@example.com');
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await screen.findByRole('heading', { level: 2, name: 'Vínculos anteriores' });
    held.release();
    await settle();
    const group = screen.getByRole('list', { name: 'Vínculos anteriores' });
    expect(within(group).getByText('Familia Gómez')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Vínculos activos' })).not.toHaveTextContent('Familia Gómez');
    const data = queryClient.getQueryData<{ vinculos: { familiaNombre: string; estado: string }[] }>(['admin', 'athlete-links', athlete.id]);
    expect(data?.vinculos.find((v) => v.familiaNombre === 'Familia Gómez')?.estado).toBe('REVOCADO');
  });

  it('sign-out during pending: late answer is not drawn, not cached, no console noise', async () => {
    const { athlete } = seedMixedLinks(backend);
    const spies = (['warn', 'error'] as const).map((m) => vi.spyOn(console, m).mockImplementation(() => undefined));
    backend.signIn('marcos@example.com');
    const held = backend.hold('get', `/admin/deportistas/${athlete.id}/vinculos`);
    const { queryClient } = renderWithNavigation(`/admin/deportistas/${athlete.id}/vinculos`, true);
    await screen.findByText('Cargando los vínculos…');
    (backend as unknown as { session: unknown }).session = null;
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await settle();
    held.release();
    await settle();
    expect(screen.queryByText('Familia Gómez')).not.toBeInTheDocument();
    expect(document.body.innerHTML).not.toContain('Familia Gómez');
    expect(queryClient.getQueryCache().getAll().filter((q) => q.queryKey[0] === 'admin')).toEqual([]);
    expect(spies.flatMap((s) => s.mock.calls)).toEqual([]);
  });

  it('family A -> family B while A is pending: B never shows A athletes, not even in an intermediate frame; A is not cached', async () => {
    const a = seedMixedLinks(backend);
    const otherAthlete = backend.addAdminAthlete({ nombre: 'Zoe', apellido: 'Zubiri', dni: '30999888' });
    const famB = backend.addFamily('Familia Bravo');
    backend.addAdminLink(famB.id, otherAthlete.id, { estado: 'ACTIVO', esPrincipal: true });
    backend.signIn('marcos@example.com');
    const held = backend.hold('get', `/admin/familias/${a.gomez.id}/deportistas`);
    const { queryClient, go } = renderWithNavigation(`/admin/familias/${a.gomez.id}`);
    await screen.findByText('Cargando los deportistas vinculados…');
    const frames: string[] = [];
    const observer = new MutationObserver(() => frames.push(document.body.textContent ?? ''));
    observer.observe(document.body, { childList: true, subtree: true, characterData: true });
    await go(`/admin/familias/${famB.id}`);
    await screen.findByRole('link', { name: 'Zoe Zubiri' });
    held.release();
    await settle();
    observer.disconnect();
    for (const frame of frames) {
      if (frame.includes('Familia Bravo')) expect(frame, 'A athlete under B header').not.toContain('Lucía Sintética');
    }
    expect(screen.queryByText('Lucía Sintética')).not.toBeInTheDocument();
    expect(queryClient.getQueryData(['admin', 'family-athletes', a.gomez.id])).toBeUndefined();
  });

  it('athlete A late answer after opening athlete B does not populate B', async () => {
    const a = seedMixedLinks(backend);
    const b = backend.addAdminAthlete({ nombre: 'Eva', apellido: 'Otero', dni: '30222333' });
    const fam = backend.addFamily('Familia Otero');
    backend.addAdminLink(fam.id, b.id, { estado: 'ACTIVO', esPrincipal: true });
    backend.signIn('marcos@example.com');
    const held = backend.hold('get', `/admin/deportistas/${a.athlete.id}/vinculos`);
    const { go } = renderWithNavigation(`/admin/deportistas/${a.athlete.id}/vinculos`, true);
    await screen.findByText('Cargando los vínculos…');
    await go(`/admin/deportistas/${b.id}/vinculos`);
    await screen.findByRole('link', { name: 'Familia Otero' });
    held.release();
    await settle();
    expect(screen.queryByText('Familia Gómez')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Familias vinculadas' })).toBeInTheDocument();
  });
});

describe('contract: the schemas against the real backend DTO', () => {
  const header = (v: unknown) => ({ deportistaId: 'd', versionVinculos: v, vinculos: [] });
  it.each([
    ['0', true], ['1', true], ['9223372036854775807', true], ['9999999999999999999', true], ['01', true],
    [' 1', false], ['+1', false], ['1 ', false], ['1\n', false], ['-1', false], ['1.0', false], ['12345678901234567890', false], ['', false],
    [1, false], [null, false], [undefined, false], [true, false], ['１', false],
  ])('versionVinculos %j accepted=%s', (value, ok) => {
    expect(athleteLinksSchema.safeParse(header(value)).success).toBe(ok);
  });
  it('closed enum and extras dropped, autorizadoEn null/absent', () => {
    for (const estado of ['PENDIENTE', 'ACTIVO', 'RECHAZADO', 'REVOCADO']) expect(familyAthletesSchema.safeParse([rawLink({ estado })]).success).toBe(true);
    for (const estado of ['activo', 'EXPIRADO', '', null, 1]) expect(familyAthletesSchema.safeParse([rawLink({ estado })]).success).toBe(false);
    const row = rawLink({ autorizadoEn: null });
    const parsed = familyAthletesSchema.parse([{ ...row, dni: '1', extra: 1 }]);
    expect(Object.keys(parsed[0]!)).not.toContain('dni');
    expect(Object.keys(parsed[0]!)).not.toContain('autorizadoEn');
    const absent: Record<string, unknown> = { ...row };
    delete absent.autorizadoEn;
    expect(familyAthletesSchema.safeParse([absent]).success).toBe(true);
    for (const missing of ['vinculoId', 'familiaId', 'deportistaId', 'estado', 'esPrincipal', 'deportistaActivo', 'versionVinculos', 'familiaNombre']) {
      const copy: Record<string, unknown> = { ...row };
      delete copy[missing];
      expect(familyAthletesSchema.safeParse([copy]).success, missing).toBe(false);
    }
  });
  it('family list accepts rows of DIFFERENT athletes with DIFFERENT versions; athlete list rejects a differing row version', async () => {
    backend.signIn('marcos@example.com');
    const fam = backend.addFamily('Familia Varias');
    const a1 = backend.addAdminAthlete({ nombre: 'A', apellido: 'Uno', dni: '1' });
    const a2 = backend.addAdminAthlete({ nombre: 'B', apellido: 'Dos', dni: '2' });
    a1.version = 1; a2.version = 22;
    backend.addAdminLink(fam.id, a1.id, {});
    backend.addAdminLink(fam.id, a2.id, {});
    const rows = await getFamilyAthletes(fam.id);
    expect(rows.map((r) => r.versionVinculos).sort()).toEqual(['1', '22']);
    backend.override('get', `/admin/deportistas/${a1.id}/vinculos`, { status: 200, body: { deportistaId: a1.id, versionVinculos: '5', vinculos: [rawLink({ deportistaId: a1.id, versionVinculos: '6' })] } });
    await expect(getAthleteLinks(a1.id)).rejects.toMatchObject({ message: 'El servidor respondió con datos que no corresponden.' });
  });
  it('encodes unsafe ids in the path (defence in depth, direct api call)', async () => {
    backend.signIn('marcos@example.com');
    await getAthleteLinks('a/../b?x=1').catch(() => undefined);
    expect(backend.requests.map((r) => r.url).join('|')).toContain('a%2F..%2Fb%3Fx%3D1');
  });
});

describe('errors: matrix of statuses and the family section while the name is being edited', () => {
  it.each([
    ['400', { status: 400, body: { codigo: 'SOLICITUD_INVALIDA', mensaje: 'SECRETMSG' } }, 'No encontramos este deportista'],
    ['404', notFound('DEPORTISTA_NO_ENCONTRADO'), 'No encontramos este deportista'],
  ])('athlete screen %s -> uniform not found', async (_n, resp, heading) => {
    const { athlete } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, resp, { once: false });
    renderApp(`/admin/deportistas/${athlete.id}/vinculos`);
    await screen.findByRole('heading', { name: heading });
    expect(document.body.innerHTML).not.toContain('SECRETMSG');
  });
  it.each([
    ['403', { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'SECRETMSG' } }],
    ['500', { status: 500, body: { codigo: 'ERROR', mensaje: 'SECRETMSG' } }],
    ['network', 'network'],
    ['401 expired', { status: 401, body: { codigo: 'NO_AUTENTICADO', mensaje: 'SECRETMSG' } }],
  ])('athlete screen %s -> fixed message, no server text', async (_n, resp) => {
    const { athlete } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, resp as never, { once: false });
    renderApp(`/admin/deportistas/${athlete.id}/vinculos`);
    await settle();
    expect(document.body.innerHTML).not.toContain('SECRETMSG');
    expect(document.body.innerHTML).not.toContain('Familia Gómez');
  });
  it('family section with 500 while EDITING the name: page stays, draft stays', async () => {
    const { gomez } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    backend.override('get', `/admin/familias/${gomez.id}/deportistas`, { status: 500, body: { codigo: 'X', mensaje: 'SECRETMSG' } }, { once: false });
    renderApp(`/admin/familias/${gomez.id}`);
    const user = userEvent.setup();
    await screen.findByRole('button', { name: 'Reintentar' });
    await user.click(screen.getByRole('button', { name: /editar/i }));
    const box = await screen.findByRole('textbox');
    await user.clear(box);
    await user.type(box, 'Nuevo nombre');
    await settle();
    expect(screen.getByRole('textbox')).toHaveValue('Nuevo nombre');
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument();
  });
  it('family section never resolving while editing: the edit works', async () => {
    const { gomez } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    backend.hold('get', `/admin/familias/${gomez.id}/deportistas`);
    renderApp(`/admin/familias/${gomez.id}`);
    const user = userEvent.setup();
    await screen.findByText('Cargando los deportistas vinculados…');
    await user.click(screen.getByRole('button', { name: /editar/i }));
    const box = await screen.findByRole('textbox');
    await user.clear(box);
    await user.type(box, 'Otro');
    expect(screen.getByRole('textbox')).toHaveValue('Otro');
    expect(screen.getByText('Cargando los deportistas vinculados…')).toBeInTheDocument();
  });
  it('family section 404 FAMILIA_NO_ENCONTRADA after the family loaded: generic fixed error, no server text', async () => {
    const { gomez } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    backend.override('get', `/admin/familias/${gomez.id}/deportistas`, notFound('FAMILIA_NO_ENCONTRADA'), { once: false });
    renderApp(`/admin/familias/${gomez.id}`);
    await screen.findByRole('button', { name: 'Reintentar' });
    expect(screen.getByRole('heading', { level: 1, name: 'Familia Gómez' })).toBeInTheDocument();
  });
});

describe('edge data and the existing detail screens', () => {
  it('two ACTIVE principal rows: nothing crashes (both flagged)', async () => {
    const athlete = seedAthlete(backend);
    const f1 = backend.addFamily('Familia Uno'); const f2 = backend.addFamily('Familia Dos');
    backend.addAdminLink(f1.id, athlete.id, { estado: 'ACTIVO' }); backend.addAdminLink(f2.id, athlete.id, { estado: 'ACTIVO' });
    backend.signIn('marcos@example.com');
    const real = await import('@/lib/http').then((m) => m.apiGet<{ vinculos: Record<string, unknown>[] }>(`/admin/deportistas/${athlete.id}/vinculos`));
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, { status: 200, body: { ...real, vinculos: real.vinculos.map((r) => ({ ...r, esPrincipal: true })) } }, { once: false });
    renderApp(`/admin/deportistas/${athlete.id}/vinculos`);
    await screen.findByRole('link', { name: 'Familia Uno' });
    expect(screen.getAllByText('Familia principal')).toHaveLength(2);
  });
  it('PENDIENTE with esPrincipal true (V1 default) is not shown as principal and sits under previous', async () => {
    const athlete = seedAthlete(backend);
    const f1 = backend.addFamily('Familia Uno');
    backend.addAdminLink(f1.id, athlete.id, { estado: 'PENDIENTE' });
    backend.signIn('marcos@example.com');
    const real = await import('@/lib/http').then((m) => m.apiGet<{ vinculos: Record<string, unknown>[] }>(`/admin/deportistas/${athlete.id}/vinculos`));
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, { status: 200, body: { ...real, vinculos: real.vinculos.map((r) => ({ ...r, esPrincipal: true })) } }, { once: false });
    renderApp(`/admin/deportistas/${athlete.id}/vinculos`);
    await screen.findByRole('link', { name: 'Familia Uno' });
    expect(screen.queryByText('Familia principal')).not.toBeInTheDocument();
    expect(screen.getByText('Vínculo pendiente')).toBeInTheDocument();
    expect(screen.getByText('No tiene familias con vínculo activo.')).toBeInTheDocument();
  });
  it('very long unbreakable names carry break-words/min-w-0 classes', async () => {
    const long = 'Ñ'.repeat(120);
    const fam = backend.addFamily(long);
    const a = backend.addAdminAthlete({ nombre: long, apellido: long, dni: '1' });
    backend.addAdminLink(fam.id, a.id, {});
    backend.signIn('marcos@example.com');
    renderApp(`/admin/familias/${fam.id}`);
    const link = await screen.findByRole('link', { name: `${long} ${long}` });
    expect(link.className).toContain('break-words');
    expect(link.className).toContain('min-w-0');
  });
  it('StrictMode: one GET each, screen renders', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    renderWithNavigation(`/admin/deportistas/${athlete.id}/vinculos`, true);
    await screen.findByRole('link', { name: 'Familia Gómez' });
    expect(backend.requests.filter((r) => r.url.endsWith('/vinculos')).length).toBeLessThanOrEqual(1);
    expect(backend.requests.filter((r) => r.method !== 'get' && !r.url.startsWith('/auth/'))).toEqual([]);
  });
  it('athlete detail makes NO links request; family detail makes exactly one extra GET', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    renderApp(`/admin/deportistas/${athlete.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    await settle();
    expect(backend.requests.filter((r) => /vinculos|\/deportistas$/.test(r.url))).toEqual([]);
    expect(location()).toBe(`/admin/deportistas/${athlete.id}`);
  });
});

describe('retry: when only the header read failed, or both', () => {
  it('header 500 once, links OK: «Reintentar» loads the screen (retry must refetch the failed header too)', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    backend.override('get', `/admin/deportistas/${athlete.id}`, { status: 500, body: { codigo: 'X', mensaje: 'x' } });
    renderApp(`/admin/deportistas/${athlete.id}/vinculos`);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Reintentar' }));
    await screen.findByRole('link', { name: 'Familia Gómez' });
    expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument();
  });
  it('both reads failed once: one «Reintentar» loads both', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    backend.override('get', `/admin/deportistas/${athlete.id}`, { status: 500, body: { codigo: 'X', mensaje: 'x' } });
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, { status: 500, body: { codigo: 'X', mensaje: 'x' } });
    renderApp(`/admin/deportistas/${athlete.id}/vinculos`);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Reintentar' }));
    await screen.findByRole('link', { name: 'Familia Gómez' });
  });
});
