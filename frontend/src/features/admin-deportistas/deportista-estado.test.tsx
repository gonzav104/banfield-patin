import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import type { QueryClient } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { sessionKey } from '@/features/auth/session';
import type { FakeAdminAthlete, FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';

// MOCK backend (see src/test/fakeBackend.ts, which mirrors POST /api/admin/deportistas/{id}/activar|desactivar). Real-backend
// evidence lives in e2e/deportistas-edicion-estados.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const DNI = '30111322';
const location = () => screen.getByTestId('location').textContent ?? '';
const statusPosts = () => backend.requests.filter((r) => r.method === 'post' && /^\/admin\/deportistas\/[^/]+\/(activar|desactivar)$/.test(r.url));
const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function seed(over: Partial<FakeAdminAthlete> = {}) {
  return backend.addAdminAthlete({ nombre: 'Lucía', apellido: 'Sintética', dni: DNI, ...over });
}

async function openDetail(athlete: FakeAdminAthlete): Promise<{ queryClient: QueryClient; user: UserEvent }> {
  backend.signIn('marcos@example.com');
  const { queryClient } = renderApp(`/admin/deportistas/${athlete.id}`);
  await screen.findByRole('heading', { level: 1, name: `${athlete.nombre} ${athlete.apellido}` });
  return { queryClient, user: userEvent.setup() };
}

const status = () => screen.getByRole('region', { name: 'Estado' });

describe('athlete status: the action that fits the current state (mock backend)', () => {
  it('offers ONLY «Desactivar deportista» to an active athlete', async () => {
    await openDetail(seed());
    expect(within(status()).getByRole('button', { name: 'Desactivar deportista' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Reactivar|Activar deportista/ })).not.toBeInTheDocument();
    expect(statusPosts()).toHaveLength(0);
  });

  it('offers ONLY «Reactivar deportista» to an inactive athlete, from its own detail', async () => {
    await openDetail(seed({ activo: false }));
    expect(within(status()).getByRole('button', { name: 'Reactivar deportista' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Desactivar/ })).not.toBeInTheDocument();
    expect(status()).toHaveTextContent('El deportista está inactivo: su DNI sigue reservado.');
  });

  it('an inactive athlete found through the list («Inactivos») can be reactivated from its detail', async () => {
    const athlete = seed({ activo: false });
    backend.signIn('marcos@example.com');
    renderApp('/admin/deportistas?estado=INACTIVOS');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('link', { name: 'Sintética, Lucía' }));
    await user.click(await screen.findByRole('button', { name: 'Reactivar deportista' }));
    await user.click(screen.getByRole('button', { name: 'Sí, reactivar' }));
    expect(await screen.findByText('El deportista quedó activo.')).toBeInTheDocument();
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ activo: true, version: 1 });
  });

  it('keeps a family user out of the screen that carries the action', async () => {
    const athlete = seed();
    backend.signIn('lucia@example.com');
    renderApp(`/admin/deportistas/${athlete.id}`);
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Desactivar|Reactivar/ })).not.toBeInTheDocument();
    expect(statusPosts()).toHaveLength(0);
  });
});

describe('athlete status: explicit confirmation (mock backend)', () => {
  it('asks first, states the verified consequences and writes nothing until confirmed; cancelling leaves everything as it was', async () => {
    const athlete = seed();
    const { user } = await openDetail(athlete);
    await user.click(within(status()).getByRole('button', { name: 'Desactivar deportista' }));
    const confirm = await screen.findByRole('group', { name: 'Confirmar desactivación' });
    expect(confirm).toHaveTextContent('¿Desactivar a Lucía Sintética?');
    expect(confirm).toHaveTextContent('El deportista no se elimina y sus vínculos familiares y datos deportivos no cambian.');
    expect(confirm).toHaveTextContent('su DNI sigue reservado');
    expect(statusPosts()).toHaveLength(0);
    await user.click(within(confirm).getByRole('button', { name: 'Cancelar' }));
    expect(screen.queryByRole('group', { name: 'Confirmar desactivación' })).not.toBeInTheDocument();
    expect(statusPosts()).toHaveLength(0);
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ activo: true, version: 0 });
  });

  it('deactivates after the confirmation, with the revision seen, and shows it only after the backend answered', async () => {
    const athlete = seed();
    athlete.version = 3;
    const { user } = await openDetail(athlete);
    const held = backend.hold('post', `/admin/deportistas/${athlete.id}/desactivar`);
    await user.click(within(status()).getByRole('button', { name: 'Desactivar deportista' }));
    await user.click(screen.getByRole('button', { name: 'Sí, desactivar' }));
    expect(await screen.findByRole('button', { name: 'Desactivando…' })).toBeDisabled();
    expect(screen.queryByText('El deportista quedó inactivo.')).not.toBeInTheDocument();
    expect(within(status()).getAllByText('Activo').length).toBeGreaterThan(0); // still active on screen: nothing confirmed yet
    held.release();
    expect(await screen.findByText('El deportista quedó inactivo.')).toBeInTheDocument();
    expect(statusPosts()).toHaveLength(1);
    expect(statusPosts()[0]!.url).toBe(`/admin/deportistas/${athlete.id}/desactivar`);
    expect(statusPosts()[0]!.body).toEqual({ versionEsperada: '3' }); // a decimal string, nothing else
    expect(screen.getAllByText('Inactivo')).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'Reactivar deportista' })).toBeInTheDocument();
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ activo: false, version: 4 });
    expect(backend.writes().map((r) => `${r.method} ${r.url}`)).toEqual([`post /admin/deportistas/${athlete.id}/desactivar`]); // no link, family or sports data touched
  });

  it('reactivates after the confirmation and states its own consequences', async () => {
    const athlete = seed({ activo: false });
    const { user } = await openDetail(athlete);
    await user.click(within(status()).getByRole('button', { name: 'Reactivar deportista' }));
    const confirm = await screen.findByRole('group', { name: 'Confirmar reactivación' });
    expect(confirm).toHaveTextContent('¿Reactivar a Lucía Sintética?');
    expect(confirm).toHaveTextContent('Sus vínculos familiares y datos deportivos no cambian.');
    await user.click(within(confirm).getByRole('button', { name: 'Sí, reactivar' }));
    expect(await screen.findByText('El deportista quedó activo.')).toBeInTheDocument();
    expect(statusPosts()[0]!.url).toBe(`/admin/deportistas/${athlete.id}/activar`);
    expect(screen.getByRole('button', { name: 'Desactivar deportista' })).toBeInTheDocument();
  });

  it('sends a single request however many times the confirmation is clicked while one is in flight', async () => {
    const athlete = seed();
    const { user } = await openDetail(athlete);
    const held = backend.hold('post', `/admin/deportistas/${athlete.id}/desactivar`);
    await user.click(within(status()).getByRole('button', { name: 'Desactivar deportista' }));
    const button = screen.getByRole('button', { name: 'Sí, desactivar' });
    await user.click(button);
    fireEvent.click(button);
    fireEvent.click(button);
    held.release();
    await screen.findByText('El deportista quedó inactivo.');
    expect(statusPosts()).toHaveLength(1);
    expect(backend.adminAthleteVersion(athlete.id)).toBe('1'); // moved exactly once
  });

  it('refreshes the list only with the confirmed data: the athlete leaves the «Activos» list after the deactivation', async () => {
    const athlete = seed();
    backend.signIn('marcos@example.com');
    renderApp('/admin/deportistas');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('link', { name: 'Sintética, Lucía' }));
    await user.click(await screen.findByRole('button', { name: 'Desactivar deportista' }));
    await user.click(screen.getByRole('button', { name: 'Sí, desactivar' }));
    await screen.findByText('El deportista quedó inactivo.');
    await user.click(screen.getByRole('link', { name: 'Volver al listado' }));
    expect(await screen.findByText('Todavía no hay deportistas registrados.')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Estado'), 'INACTIVOS');
    expect(await screen.findByText('Sintética, Lucía')).toBeInTheDocument();
    expect(athlete.id).toBeTruthy();
  });
});

describe('athlete status: revision and errors (mock backend)', () => {
  it('sends the revision seen when the confirmation opened; if it went stale it is a conflict, explained, with nothing applied and no automatic retry', async () => {
    const athlete = seed();
    const { user } = await openDetail(athlete);
    await user.click(within(status()).getByRole('button', { name: 'Desactivar deportista' }));
    backend.changeAdminAthleteBehindTheScenes(athlete.id, { apellido: 'Gómez' }); // another administrator, revision 1
    await user.click(screen.getByRole('button', { name: 'Sí, desactivar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Otro administrador pudo haber modificado este deportista.');
    expect(statusPosts()[0]!.body).toEqual({ versionEsperada: '0' }); // never refreshed on the way
    await act(async () => wait(600));
    expect(statusPosts()).toHaveLength(1);
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ activo: true, apellido: 'Gómez' });
    expect(screen.queryByRole('button', { name: /^(Desactivar|Reactivar) deportista$/ })).not.toBeInTheDocument(); // blocked until the current data are seen
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Lucía Gómez' })).toBeInTheDocument(); // the current data, from the server
    expect(await screen.findByText('Mostramos los datos vigentes.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Desactivar deportista' }));
    await user.click(screen.getByRole('button', { name: 'Sí, desactivar' }));
    await screen.findByText('El deportista quedó inactivo.');
    expect(statusPosts()[1]!.body).toEqual({ versionEsperada: '1' }); // the new decision uses the current revision
  });

  it('a conflict whose current state already matches the one asked for is not presented as done by this request', async () => {
    const athlete = seed();
    const { user } = await openDetail(athlete);
    await user.click(within(status()).getByRole('button', { name: 'Desactivar deportista' }));
    backend.changeAdminAthleteBehindTheScenes(athlete.id, { activo: false }); // somebody else deactivated it first
    await user.click(screen.getByRole('button', { name: 'Sí, desactivar' }));
    await screen.findByRole('alert');
    expect(screen.queryByText('El deportista quedó inactivo.')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    expect(await screen.findByRole('button', { name: 'Reactivar deportista' })).toBeInTheDocument();
    expect(screen.queryByText('El deportista quedó inactivo.')).not.toBeInTheDocument();
    expect(statusPosts()).toHaveLength(1);
  });

  it.each([
    ['a 403', { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } }, 'No tenés permiso para cambiar el estado de deportistas.'],
    ['a 404 (also another school: the same answer)', { status: 404, body: { codigo: 'DEPORTISTA_NO_ENCONTRADO', mensaje: 'x' } }, 'El deportista ya no existe.'],
    ['an unrelated conflict', { status: 409, body: { codigo: 'CONFLICTO_CONCURRENCIA', mensaje: 'x' } }, 'Otra operación está en curso'],
    ['a 400', { status: 400, body: { codigo: 'VALIDACION', mensaje: 'detalle interno' } }, 'No pudimos procesar la solicitud.'],
    ['a CSRF failure', { status: 403, body: { codigo: 'CSRF_INVALIDO', mensaje: 'detalle interno' } }, 'No pudimos validar la solicitud'],
  ] as const)('explains %s with a fixed text and allows a manual retry', async (_name, answer, text) => {
    const athlete = seed();
    backend.override('post', `/admin/deportistas/${athlete.id}/desactivar`, answer, { once: true });
    const { user } = await openDetail(athlete);
    await user.click(within(status()).getByRole('button', { name: 'Desactivar deportista' }));
    await user.click(screen.getByRole('button', { name: 'Sí, desactivar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(text);
    expect(document.body).not.toHaveTextContent('detalle interno');
    expect(screen.getByRole('button', { name: 'Desactivar deportista' })).toBeEnabled();
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ activo: true });
  });

  it('sends a session that ended (401) to the admin login', async () => {
    const { user } = await openDetail(seed());
    await user.click(within(status()).getByRole('button', { name: 'Desactivar deportista' }));
    backend.expireSession();
    await user.click(screen.getByRole('button', { name: 'Sí, desactivar' }));
    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
  });
});

describe('athlete status: an answer that cannot be confirmed (mock backend)', () => {
  async function lost(kind: 'network' | { status: number; body: unknown }, apply?: (a: FakeAdminAthlete) => void) {
    const athlete = seed();
    backend.override('post', `/admin/deportistas/${athlete.id}/desactivar`, kind, { once: true });
    const { user } = await openDetail(athlete);
    await user.click(within(status()).getByRole('button', { name: 'Desactivar deportista' }));
    if (apply) apply(athlete); // what really happened on the server although the answer never arrived
    await user.click(screen.getByRole('button', { name: 'Sí, desactivar' }));
    await screen.findByText(/No pudimos confirmar si el cambio de estado se aplicó/);
    return { athlete, user };
  }

  it.each([
    ['a lost response (network)', 'network' as const],
    ['a 500', { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'detalle interno' } }],
    ['a 503', { status: 503, body: { codigo: 'NO_DISPONIBLE', mensaje: 'x' } }],
    ['a 200 whose body cannot be read', { status: 200, body: { basura: true } }],
  ])('does not say it failed or applied, does not retry and blocks any resend until the state is verified (%s)', async (_name, kind) => {
    await lost(kind);
    await act(async () => wait(700));
    expect(statusPosts()).toHaveLength(1);
    expect(screen.queryByText('El deportista quedó inactivo.')).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('detalle interno');
    expect(screen.queryByRole('button', { name: /^(Desactivar|Reactivar) deportista$/ })).not.toBeInTheDocument(); // no way to resend yet
    expect(screen.getByRole('button', { name: 'Verificar estado vigente' })).toBeEnabled();
  });

  it('request NOT applied: the verification sees the same revision, says so without claiming it failed, and an explicit retry applies it once', async () => {
    const { athlete, user } = await lost('network');
    await user.click(screen.getByRole('button', { name: 'Verificar estado vigente' }));
    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(group).toHaveTextContent('No vemos el cambio aplicado.');
    expect(group).toHaveTextContent('se rechazará por revisión obsoleta en lugar de aplicarse dos veces');
    expect(group).not.toHaveTextContent(/no se aplicó|falló/i);
    expect(statusPosts()).toHaveLength(1);
    await user.click(within(group).getByRole('button', { name: 'Volver a intentar' }));
    await user.click(await screen.findByRole('button', { name: 'Sí, desactivar' }));
    await screen.findByText('El deportista quedó inactivo.');
    expect(statusPosts()).toHaveLength(2);
    expect(statusPosts()[1]!.body).toEqual({ versionEsperada: '0' }); // the SAME revision, by an explicit decision
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ activo: false, version: 1 });
  });

  it('request APPLIED: the verification sees the state asked for but never presents it as the result of this request', async () => {
    const { athlete, user } = await lost('network', (a) => backend.changeAdminAthleteBehindTheScenes(a.id, { activo: false }));
    await user.click(screen.getByRole('button', { name: 'Verificar estado vigente' }));
    expect(await screen.findByText(/El deportista cambió desde tu solicitud\. Otra operación pudo haberlo modificado: no podemos saber si el cambio actual fue tu solicitud\./)).toBeInTheDocument();
    expect(screen.queryByText('El deportista quedó inactivo.')).not.toBeInTheDocument();
    expect(screen.queryByText(/se desactivó correctamente|Desactivación confirmada/)).not.toBeInTheDocument();
    expect(statusPosts()).toHaveLength(1);
    // The current state is shown, and the action that fits it is offered for a NEW decision: nothing is resent for the old one.
    expect(await screen.findByRole('button', { name: 'Reactivar deportista' })).toBeInTheDocument();
    expect(backend.adminAthleteVersion(athlete.id)).toBe('1');
  });

  it('a verification that fails keeps the resend blocked', async () => {
    const { athlete, user } = await lost('network');
    backend.override('get', `/admin/deportistas/${athlete.id}`, 'network', { once: true });
    await user.click(screen.getByRole('button', { name: 'Verificar estado vigente' }));
    expect(await screen.findByText(/No pudimos conectar con el servidor/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^(Desactivar|Reactivar) deportista$/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verificar estado vigente' })).toBeEnabled();
  });
});

describe('athlete status: identity and privacy (mock backend)', () => {
  it('drops a pending confirmation when the session now belongs to another administrator', async () => {
    backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: 'otra.admin@example.com', mfaEnrolado: true });
    const athlete = seed();
    const { user, queryClient } = await openDetail(athlete);
    await user.click(within(status()).getByRole('button', { name: 'Desactivar deportista' }));
    expect(screen.getByRole('group', { name: 'Confirmar desactivación' })).toBeInTheDocument();
    backend.signIn('otra.admin@example.com');
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    });
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    await waitFor(() => expect(screen.queryByRole('group', { name: 'Confirmar desactivación' })).not.toBeInTheDocument());
    expect(statusPosts()).toHaveLength(0);
  });

  it('keeps the personal data out of the address, storage and console while changing the state', async () => {
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    const pattern = new RegExp(`${DNI}|Sintética|Lucía`);
    try {
      const athlete = seed();
      backend.override('post', `/admin/deportistas/${athlete.id}/desactivar`, 'network', { once: true });
      const { user } = await openDetail(athlete);
      await user.click(within(status()).getByRole('button', { name: 'Desactivar deportista' }));
      await user.click(screen.getByRole('button', { name: 'Sí, desactivar' }));
      await user.click(await screen.findByRole('button', { name: 'Verificar estado vigente' }));
      await screen.findByRole('group', { name: 'Verificación' });
      expect(location()).not.toMatch(pattern);
      expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toMatch(pattern);
      expect(spies.flatMap((spy) => spy.mock.calls).flat().join(' ')).not.toMatch(pattern);
    } finally {
      spies.forEach((spy) => spy.mockRestore());
    }
  });
});
