import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { FakeBackend } from '@/test/fakeBackend';
import { sessionKey } from '@/features/auth/session';
import { admin, createBackend, renderApp } from '@/test/render';

// MOCK backend (see src/test/fakeBackend.ts, which mirrors the real contract of GET /api/admin/deportistas). Real-backend
// evidence lives in e2e/deportistas-consulta-alta.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const listRequests = () => backend.requests.filter((r) => r.method === 'get' && /^\/admin\/deportistas(\?|$)/.test(r.url));
const lastList = () => new URLSearchParams(listRequests().at(-1)!.url.split('?')[1] ?? '');
const location = () => screen.getByTestId('location').textContent ?? '';

async function openAthletes(path = '/admin/deportistas') {
  backend.signIn('marcos@example.com');
  const rendered = renderApp(path);
  await screen.findByRole('heading', { name: 'Deportistas' });
  return rendered;
}

let dniCounter = 30_000_000;
function seed(nombre: string, apellido: string, over: Partial<Parameters<FakeBackend['addAdminAthlete']>[0]> = {}) {
  dniCounter += 1;
  return backend.addAdminAthlete({ nombre, apellido, dni: String(dniCounter), ...over });
}

function seedMany(count: number) {
  for (let n = 1; n <= count; n += 1) seed('Ana', `Apellido ${String(n).padStart(3, '0')}`);
}

describe('admin access to the athlete list (mock backend)', () => {
  it('sends anonymous visitors to the admin login', async () => {
    renderApp('/admin/deportistas');
    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
    expect(listRequests()).toHaveLength(0);
  });

  it('keeps a family user out and never requests the list', async () => {
    backend.signIn('lucia@example.com');
    renderApp('/admin/deportistas');
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(listRequests()).toHaveLength(0);
  });

  it('keeps a password-only admin session out and never requests the list', async () => {
    backend.signIn('marcos@example.com', true);
    renderApp('/admin/deportistas');
    expect(await screen.findByRole('heading', { name: 'Verificación en dos pasos' })).toBeInTheDocument();
    expect(listRequests()).toHaveLength(0);
  });

  it('is reachable from the admin navigation', async () => {
    backend.signIn('marcos@example.com');
    renderApp('/admin');
    await userEvent.setup().click(await screen.findByRole('link', { name: 'Deportistas' }));
    expect(await screen.findByRole('heading', { name: 'Deportistas' })).toBeInTheDocument();
    expect(location()).toBe('/admin/deportistas');
  });

  it('shows the permission error when the API answers 403', async () => {
    backend.signIn('marcos@example.com');
    backend.override('get', '/admin/deportistas?estado=ACTIVOS&page=0&size=20', { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } });
    renderApp('/admin/deportistas');
    expect(await screen.findByRole('alert')).toHaveTextContent('No tenés permiso para ver los deportistas.');
  });
});

describe('athlete list (mock backend)', () => {
  it('requests the active athletes, first page of 20, and shows name, masked DNI and status, nothing else', async () => {
    seed('Lucía', 'Zapata', { dni: '30111322', fechaNacimiento: '2012-05-04', emailFederativo: 'secreto@example.test', domicilio: 'Calle Privada 123' });
    seed('Mateo', 'Álvarez', { dni: '40222333', activo: false });
    await openAthletes();
    const rows = await screen.findAllByRole('listitem');
    expect(listRequests()[0]!.url).toBe('/admin/deportistas?estado=ACTIVOS&page=0&size=20');
    expect(rows).toHaveLength(1); // the inactive one is not in the default view
    expect(rows[0]).toHaveTextContent('Zapata, Lucía');
    expect(rows[0]).toHaveTextContent('•••••322');
    expect(within(rows[0]!).getByText('Activo')).toBeInTheDocument();
    // The summary also carries the birth date; the list does not read it, and the detail data never reaches it.
    expect(document.body).not.toHaveTextContent(/30111322|2012|05\/04|secreto|Calle Privada/);
  });

  it('orders by last name and then name, and links each row to its detail', async () => {
    const zapata = seed('Lucía', 'Zapata');
    seed('Bruno', 'Álvarez');
    await openAthletes();
    const rows = await screen.findAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('Álvarez, Bruno');
    expect(rows[1]).toHaveTextContent('Zapata, Lucía');
    expect(within(rows[1]!).getByRole('link', { name: 'Zapata, Lucía' })).toHaveAttribute('href', `/admin/deportistas/${zapata.id}`);
  });

  it('announces how many athletes match, in singular and plural', async () => {
    seedMany(1);
    await openAthletes();
    await screen.findAllByRole('listitem');
    expect(screen.getByText('1 deportista')).toHaveAttribute('role', 'status');
  });

  it('shows a loading state while the list is on its way', async () => {
    seedMany(1);
    const held = backend.hold('get', '/admin/deportistas?estado=ACTIVOS&page=0&size=20');
    backend.signIn('marcos@example.com');
    renderApp('/admin/deportistas');
    expect(await screen.findByText('Cargando deportistas…')).toBeInTheDocument();
    held.release();
    expect(await screen.findByText('Apellido 001, Ana')).toBeInTheDocument();
    expect(screen.queryByText('Cargando deportistas…')).not.toBeInTheDocument();
  });

  it('shows an empty state when the school has no athletes', async () => {
    await openAthletes();
    expect(await screen.findByText('Todavía no hay deportistas registrados.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Limpiar filtros' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Nuevo deportista' })).toHaveAttribute('href', '/admin/deportistas/nuevo');
  });

  it('shows a safe error with a working retry', async () => {
    seedMany(1);
    backend.override('get', '/admin/deportistas?estado=ACTIVOS&page=0&size=20', { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'detalle interno' } });
    await openAthletes();
    expect(await screen.findByRole('alert')).toHaveTextContent('Ocurrió un problema en el servidor');
    expect(screen.getByRole('alert')).not.toHaveTextContent('detalle interno');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByText('Apellido 001, Ana')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('explains a network failure', async () => {
    backend.override('get', '/admin/deportistas?estado=ACTIVOS&page=0&size=20', 'network');
    backend.signIn('marcos@example.com');
    renderApp('/admin/deportistas');
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos conectar con el servidor');
  });

  it('rejects a response with an unexpected shape without drawing it', async () => {
    backend.override('get', '/admin/deportistas?estado=ACTIVOS&page=0&size=20', { status: 200, body: { contenido: [{ id: 1 }] } });
    backend.signIn('marcos@example.com');
    renderApp('/admin/deportistas');
    expect(await screen.findByRole('alert')).toHaveTextContent('Ocurrió un problema en el servidor');
  });
});

describe('status filter (mock backend)', () => {
  beforeEach(() => {
    seed('Ana', 'Activa Uno');
    seed('Iván', 'Inactivo Uno', { activo: false });
  });

  it('asks the backend for the chosen status and keeps it in the address', async () => {
    await openAthletes();
    await screen.findByText('Activa Uno, Ana');
    await userEvent.setup().selectOptions(screen.getByLabelText('Estado'), 'INACTIVOS');
    await waitFor(() => expect(screen.queryByText('Activa Uno, Ana')).not.toBeInTheDocument());
    expect(screen.getByText('Inactivo Uno, Iván')).toBeInTheDocument();
    expect(within(screen.getAllByRole('listitem')[0]!).getByText('Inactivo')).toBeInTheDocument();
    expect(lastList().get('estado')).toBe('INACTIVOS');
    expect(location()).toContain('estado=INACTIVOS');
  });

  it('«Todos» shows both and always sends the status explicitly (the backend default is ACTIVOS)', async () => {
    await openAthletes();
    await screen.findByText('Activa Uno, Ana');
    await userEvent.setup().selectOptions(screen.getByLabelText('Estado'), 'TODOS');
    expect(await screen.findByText('Inactivo Uno, Iván')).toBeInTheDocument();
    expect(lastList().get('estado')).toBe('TODOS');
    expect(location()).toBe('/admin/deportistas?estado=TODOS');
    expect(listRequests().every((r) => new URLSearchParams(r.url.split('?')[1]).has('estado'))).toBe(true);
  });

  it('starts from the status in the address, and returning to «Activos» clears it from the address', async () => {
    await openAthletes('/admin/deportistas?estado=INACTIVOS');
    expect(await screen.findByText('Inactivo Uno, Iván')).toBeInTheDocument();
    expect(screen.getByLabelText('Estado')).toHaveValue('INACTIVOS');
    await userEvent.setup().selectOptions(screen.getByLabelText('Estado'), 'ACTIVOS');
    expect(await screen.findByText('Activa Uno, Ana')).toBeInTheDocument();
    expect(location()).toBe('/admin/deportistas');
  });

  it('ignores an unknown status in the address instead of sending it to the backend', async () => {
    await openAthletes('/admin/deportistas?estado=CUALQUIERA');
    expect(await screen.findByText('Activa Uno, Ana')).toBeInTheDocument();
    expect(screen.getByLabelText('Estado')).toHaveValue('ACTIVOS');
    expect(listRequests().every((r) => !r.url.includes('CUALQUIERA'))).toBe(true);
  });
});

describe('search (mock backend)', () => {
  beforeEach(() => {
    seed('Lucía', 'Gómez', { dni: '31000111' });
    seed('Pedro', 'Pereira', { dni: '32000222' });
    seed('Paula', 'Pereyra', { dni: '33000333' });
  });

  it('searches by name after a pause, sending a single request and keeping the term out of the address and storage', async () => {
    await openAthletes();
    await screen.findByText('Gómez, Lucía');
    const before = listRequests().length;
    await userEvent.setup().type(screen.getByRole('searchbox', { name: 'Buscar por nombre, apellido o DNI' }), 'pere');
    await waitFor(() => expect(screen.queryByText('Gómez, Lucía')).not.toBeInTheDocument());
    expect(screen.getByText('Pereira, Pedro')).toBeInTheDocument();
    expect(screen.getByText('Pereyra, Paula')).toBeInTheDocument();
    expect(lastList().get('busqueda')).toBe('pere');
    const sent = listRequests().slice(before).map((r) => new URLSearchParams(r.url.split('?')[1]).get('busqueda'));
    expect(sent).toEqual(['pere']); // no request per keystroke
    expect(location()).toBe('/admin/deportistas');
    expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toContain('pere');
  });

  it('searches by DNI without writing it anywhere, and still shows only the masked DNI', async () => {
    await openAthletes();
    await screen.findByText('Gómez, Lucía');
    await userEvent.setup().type(screen.getByRole('searchbox'), '32.000');
    expect(await screen.findByText('Pereira, Pedro')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('Gómez, Lucía')).not.toBeInTheDocument());
    expect(location()).toBe('/admin/deportistas');
    expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toContain('32.000');
    expect(document.body).not.toHaveTextContent('32000222');
    expect(screen.getAllByRole('listitem')[0]).toHaveTextContent('•••••222');
  });

  it('trims the term and limits the field to the 100 characters the backend accepts', async () => {
    await openAthletes();
    await screen.findByText('Gómez, Lucía');
    const box = screen.getByRole('searchbox');
    expect(box).toHaveAttribute('maxlength', '100');
    await userEvent.setup().type(box, '   gómez   ');
    await waitFor(() => expect(lastList().get('busqueda')).toBe('gómez'));
  });

  it('offers to clear the filters when nothing matches, and clearing restores the list', async () => {
    await openAthletes();
    await screen.findByText('Gómez, Lucía');
    const user = userEvent.setup();
    await user.type(screen.getByRole('searchbox'), 'zzz');
    expect(await screen.findByText('No encontramos deportistas con esos criterios.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Limpiar filtros' }));
    expect(await screen.findByText('Gómez, Lucía')).toBeInTheDocument();
    expect(screen.getByRole('searchbox')).toHaveValue('');
  });
});

describe('pagination (mock backend)', () => {
  it('pages through the results keeping the page in the address', async () => {
    seedMany(25);
    await openAthletes();
    await screen.findAllByRole('listitem');
    expect(screen.getAllByRole('listitem')).toHaveLength(20);
    expect(screen.getByRole('navigation', { name: 'Paginación' })).toHaveTextContent('Página 1 de 2 · 25 en total');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Siguiente' }));
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(5));
    expect(lastList().get('page')).toBe('1');
    expect(location()).toBe('/admin/deportistas?pagina=2');
  });

  it('keeps the status filter and the page when coming back from a detail', async () => {
    seedMany(25);
    await openAthletes('/admin/deportistas?estado=TODOS&pagina=2');
    const rows = await screen.findAllByRole('listitem');
    expect(rows).toHaveLength(5);
    const user = userEvent.setup();
    await user.click(within(rows[0]!).getByRole('link'));
    const back = await screen.findByRole('link', { name: 'Volver al listado' });
    expect(back).toHaveAttribute('href', '/admin/deportistas?estado=TODOS&pagina=2');
    await user.click(back);
    expect(await screen.findByRole('heading', { name: 'Deportistas' })).toBeInTheDocument();
    expect(location()).toBe('/admin/deportistas?estado=TODOS&pagina=2');
    expect(screen.getByLabelText('Estado')).toHaveValue('TODOS');
    expect(await screen.findAllByRole('listitem')).toHaveLength(5);
  });

  it('never carries the search term back in the address: only the filter and the page do', async () => {
    seed('Zeta', 'Inactivo Raro', { activo: false });
    await openAthletes('/admin/deportistas?estado=INACTIVOS');
    const user = userEvent.setup();
    await user.type(await screen.findByRole('searchbox'), 'zeta');
    await waitFor(() => expect(lastList().get('busqueda')).toBe('zeta'));
    await user.click(await screen.findByRole('link', { name: 'Inactivo Raro, Zeta' }));
    const back = await screen.findByRole('link', { name: 'Volver al listado' });
    expect(back).toHaveAttribute('href', '/admin/deportistas?estado=INACTIVOS');
  });

  it('goes back to the first page when the requested page no longer exists', async () => {
    seedMany(3);
    await openAthletes('/admin/deportistas?pagina=9');
    expect(await screen.findByText('Esa página ya no existe.')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Volver a la primera página' }));
    expect(await screen.findAllByRole('listitem')).toHaveLength(3);
    expect(location()).toBe('/admin/deportistas');
  });

  it('ignores a malformed page in the address', async () => {
    seedMany(2);
    await openAthletes('/admin/deportistas?pagina=abc');
    expect(await screen.findAllByRole('listitem')).toHaveLength(2);
    expect(lastList().get('page')).toBe('0');
  });
});

describe('privacy of the list (mock backend)', () => {
  it('keeps names, DNIs and the search term out of the address, storage and console', async () => {
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    try {
      seed('Marina', 'Privada', { dni: '27123456' });
      await openAthletes();
      await screen.findByText('Privada, Marina');
      await userEvent.setup().type(screen.getByRole('searchbox'), 'marina');
      await waitFor(() => expect(lastList().get('busqueda')).toBe('marina'));
      expect(location()).not.toMatch(/Marina|Privada|27123456|marina/);
      expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toMatch(/Marina|Privada|27123456|marina/);
      expect(spies.flatMap((spy) => spy.mock.calls).flat().join(' ')).not.toMatch(/Marina|Privada|27123456|marina/);
    } finally {
      spies.forEach((spy) => spy.mockRestore());
    }
  });
});

describe('identity change in the list (mock backend)', () => {
  const OTHER = 'otra.admin@example.com';
  const SEARCHED = '/admin/deportistas?estado=ACTIVOS&page=0&size=20&busqueda=marina';
  const UNFILTERED = '/admin/deportistas?estado=ACTIVOS&page=0&size=20';

  beforeEach(() => {
    backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: OTHER, mfaEnrolado: true });
    seed('Marina', 'Privada', { dni: '27123456' });
    seed('Otro', 'Visible', { dni: '28123456' });
  });

  async function switchIdentity(queryClient: Awaited<ReturnType<typeof openAthletes>>['queryClient']) {
    backend.signIn(OTHER);
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    });
  }

  it('clears a COMPLETED search: the term, the filtered rows and the page of the previous administrator', async () => {
    const { queryClient } = await openAthletes('/admin/deportistas?estado=TODOS');
    const user = userEvent.setup();
    await user.type(screen.getByRole('searchbox'), 'marina');
    await waitFor(() => expect(screen.queryByText('Visible, Otro')).not.toBeInTheDocument());
    expect(screen.getByText('Privada, Marina')).toBeInTheDocument();
    const held = backend.hold('get', '/admin/deportistas?estado=TODOS&page=0&size=20');
    await switchIdentity(queryClient);
    expect(await screen.findByText('Cargando deportistas…')).toBeInTheDocument(); // no old rows while the new query resolves
    expect(screen.queryByText('Privada, Marina')).not.toBeInTheDocument();
    expect(screen.getByRole('searchbox')).toHaveValue('');
    held.release();
    expect(await screen.findByText('Visible, Otro')).toBeInTheDocument(); // the new identity's own, unfiltered query
    expect(screen.getByText('Privada, Marina')).toBeInTheDocument();
    expect(screen.getByRole('searchbox')).toHaveValue('');
    expect(listRequests().at(-1)!.url).not.toContain('busqueda');
    expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toMatch(/marina|Marina|Privada/);
    expect(location()).not.toMatch(/marina|Privada/i);
  });

  it('discards the response of a PENDING search of the previous administrator: it never populates the new screen', async () => {
    const { queryClient } = await openAthletes();
    await screen.findByText('Visible, Otro');
    const stale = backend.hold('get', SEARCHED);
    await userEvent.setup().type(screen.getByRole('searchbox'), 'marina');
    await waitFor(() => expect(listRequests().some((r) => r.url === SEARCHED)).toBe(true)); // the search is in flight
    const fresh = backend.hold('get', UNFILTERED);
    await switchIdentity(queryClient);
    expect(await screen.findByText('Cargando deportistas…')).toBeInTheDocument();
    expect(screen.getByRole('searchbox')).toHaveValue('');
    stale.release(); // the old answer (only «Marina») arrives while the new identity's query is still pending
    await act(async () => new Promise((resolve) => setTimeout(resolve, 150)));
    expect(screen.getByText('Cargando deportistas…')).toBeInTheDocument();
    expect(screen.queryByText('Privada, Marina')).not.toBeInTheDocument();
    fresh.release();
    expect(await screen.findByText('Visible, Otro')).toBeInTheDocument();
    expect(screen.getByText('2 deportistas')).toBeInTheDocument(); // the full list of the new identity, not the filtered one
    expect(screen.getByRole('searchbox')).toHaveValue('');
  });
});
