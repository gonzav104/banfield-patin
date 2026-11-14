import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';

// MOCK backend (see src/test/fakeBackend.ts). Real-backend evidence lives in e2e/familias-listado.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const listingRequests = () => backend.requests.filter((r) => r.method === 'get' && r.url.startsWith('/admin/familias/listado'));
const lastListing = () => new URLSearchParams(listingRequests().at(-1)!.url.split('?')[1] ?? '');
const location = () => screen.getByTestId('location').textContent ?? '';

async function openFamilies(path = '/admin/familias') {
  backend.signIn('marcos@example.com');
  renderApp(path);
  return screen.findByRole('heading', { name: 'Familias' });
}

function seedMany(count: number) {
  for (let n = 1; n <= count; n += 1) backend.addFamily(`Familia ${String(n).padStart(3, '0')}`);
}

describe('admin access to the family list (mock backend)', () => {
  it('sends anonymous visitors to the admin login', async () => {
    renderApp('/admin/familias');
    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
  });

  it('keeps a family user out and never requests the list', async () => {
    backend.signIn('lucia@example.com');
    renderApp('/admin/familias');
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(listingRequests()).toHaveLength(0);
  });

  it('keeps a password-only admin session out and never requests the list', async () => {
    backend.signIn('marcos@example.com', true);
    renderApp('/admin/familias');
    expect(await screen.findByRole('heading', { name: 'Verificación en dos pasos' })).toBeInTheDocument();
    expect(listingRequests()).toHaveLength(0);
  });

  it('is reachable from the admin navigation', async () => {
    backend.signIn('marcos@example.com');
    renderApp('/admin');
    await userEvent.setup().click(await screen.findByRole('link', { name: 'Familias' }));
    expect(await screen.findByRole('heading', { name: 'Familias' })).toBeInTheDocument();
    expect(location()).toBe('/admin/familias');
  });

  it('shows the permission error when the API answers 403', async () => {
    backend.signIn('marcos@example.com');
    backend.override('get', '/admin/familias/listado?page=0&size=20', { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } });
    renderApp('/admin/familias');
    expect(await screen.findByRole('alert')).toHaveTextContent('No tenés permiso para ver las familias.');
  });
});

describe('family list (mock backend)', () => {
  it('requests the first page of 20 with no filters and shows each family with its status and counts', async () => {
    backend.addFamily('Zapata', { activa: true, cantidadTutores: 2, cantidadDeportistasActivos: 1 });
    backend.addFamily('álvarez', { activa: false, cantidadTutores: 0, cantidadDeportistasActivos: 0 });
    await openFamilies();

    const rows = await screen.findAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(listingRequests()[0]!.url).toBe('/admin/familias/listado?page=0&size=20');

    expect(rows[0]).toHaveTextContent('álvarez');
    expect(within(rows[0]!).getByText('Inactiva')).toBeInTheDocument();
    expect(rows[0]).toHaveTextContent(/Tutores:\s*0/);
    expect(rows[1]).toHaveTextContent('Zapata');
    expect(within(rows[1]!).getByText('Activa')).toBeInTheDocument();
    expect(rows[1]).toHaveTextContent(/Tutores:\s*2/);
    expect(rows[1]).toHaveTextContent(/Deportistas activos:\s*1/);
  });

  it('announces how many families match', async () => {
    seedMany(3);
    await openFamilies();
    await screen.findAllByRole('listitem');
    expect(screen.getByText('3 familias')).toHaveAttribute('role', 'status');
  });

  it('uses the singular for a single family', async () => {
    seedMany(1);
    await openFamilies();
    await screen.findAllByRole('listitem');
    expect(screen.getByText('1 familia')).toHaveAttribute('role', 'status');
    expect(screen.queryByText('1 familias')).not.toBeInTheDocument();
  });

  it('shows a loading state while the list is on its way', async () => {
    seedMany(1);
    const held = backend.hold('get', '/admin/familias/listado?page=0&size=20');
    backend.signIn('marcos@example.com');
    renderApp('/admin/familias');
    expect(await screen.findByText('Cargando familias…')).toBeInTheDocument();
    held.release();
    expect(await screen.findByText('Familia 001')).toBeInTheDocument();
    expect(screen.queryByText('Cargando familias…')).not.toBeInTheDocument();
  });

  it('shows an empty state when the school has no families', async () => {
    await openFamilies();
    expect(await screen.findByText('Todavía no hay familias registradas.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Limpiar filtros' })).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Paginación' })).not.toBeInTheDocument();
  });

  it('shows a safe error with a working retry', async () => {
    seedMany(1);
    backend.override('get', '/admin/familias/listado?page=0&size=20', { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'detalle interno' } });
    await openFamilies();
    expect(await screen.findByRole('alert')).toHaveTextContent('Ocurrió un problema en el servidor');
    expect(screen.getByRole('alert')).not.toHaveTextContent('detalle interno');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByText('Familia 001')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('explains a network failure', async () => {
    backend.override('get', '/admin/familias/listado?page=0&size=20', 'network');
    await openFamilies();
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos conectar con el servidor');
  });
});

describe('status filter (mock backend)', () => {
  beforeEach(() => {
    backend.addFamily('Activa Uno', { activa: true });
    backend.addFamily('Inactiva Uno', { activa: false });
  });

  it('asks the backend for the chosen status and keeps it in the address', async () => {
    await openFamilies();
    await screen.findByText('Activa Uno');
    await userEvent.setup().selectOptions(screen.getByLabelText('Estado'), 'INACTIVOS');
    await waitFor(() => expect(screen.queryByText('Activa Uno')).not.toBeInTheDocument());
    expect(screen.getByText('Inactiva Uno')).toBeInTheDocument();
    expect(lastListing().get('estado')).toBe('INACTIVOS');
    expect(location()).toContain('estado=INACTIVOS');
  });

  it('omits the status parameter for «Todas» and clears it from the address', async () => {
    await openFamilies('/admin/familias?estado=ACTIVOS');
    await screen.findByText('Activa Uno');
    expect(screen.queryByText('Inactiva Uno')).not.toBeInTheDocument();
    await userEvent.setup().selectOptions(screen.getByLabelText('Estado'), 'TODOS');
    expect(await screen.findByText('Inactiva Uno')).toBeInTheDocument();
    expect(lastListing().has('estado')).toBe(false);
    expect(location()).toBe('/admin/familias');
  });

  it('starts from the status in the address', async () => {
    await openFamilies('/admin/familias?estado=INACTIVOS');
    expect(await screen.findByText('Inactiva Uno')).toBeInTheDocument();
    expect(screen.getByLabelText('Estado')).toHaveValue('INACTIVOS');
  });

  it('ignores an unknown status in the address instead of sending it to the backend', async () => {
    await openFamilies('/admin/familias?estado=CUALQUIERA');
    expect(await screen.findByText('Activa Uno')).toBeInTheDocument();
    expect(screen.getByLabelText('Estado')).toHaveValue('TODOS');
    expect(listingRequests().every((r) => !r.url.includes('CUALQUIERA'))).toBe(true);
  });
});

describe('search (mock backend)', () => {
  beforeEach(() => {
    backend.addFamily('Gómez');
    backend.addFamily('Pereira');
    backend.addFamily('Pereyra');
  });

  it('searches by name after a pause, sending a single request and keeping the term out of the address and storage', async () => {
    await openFamilies();
    await screen.findByText('Gómez');
    const before = listingRequests().length;
    await userEvent.setup().type(screen.getByRole('searchbox', { name: 'Buscar por nombre' }), 'pere');
    await waitFor(() => expect(screen.queryByText('Gómez')).not.toBeInTheDocument());
    expect(screen.getByText('Pereira')).toBeInTheDocument();
    expect(screen.getByText('Pereyra')).toBeInTheDocument();

    expect(lastListing().get('busqueda')).toBe('pere');
    const sent = listingRequests().slice(before).map((r) => new URLSearchParams(r.url.split('?')[1]).get('busqueda'));
    expect(sent).toEqual(['pere']); // no request per keystroke
    expect(location()).toBe('/admin/familias');
    expect(JSON.stringify({ ...localStorage })).not.toContain('pere');
    expect(JSON.stringify({ ...sessionStorage })).not.toContain('pere');
  });

  it('trims the term and limits the field to the 100 characters the backend accepts', async () => {
    await openFamilies();
    await screen.findByText('Gómez');
    const box = screen.getByRole('searchbox', { name: 'Buscar por nombre' });
    expect(box).toHaveAttribute('maxlength', '100');
    await userEvent.setup().type(box, '  pereira  ');
    await waitFor(() => expect(screen.queryByText('Pereyra')).not.toBeInTheDocument());
    expect(lastListing().get('busqueda')).toBe('pereira');
  });

  it('shows a «no matches» state with a way to clear every filter', async () => {
    const user = userEvent.setup();
    await openFamilies('/admin/familias?estado=ACTIVOS');
    await screen.findByText('Gómez');
    await user.type(screen.getByRole('searchbox', { name: 'Buscar por nombre' }), 'zzz');
    expect(await screen.findByText('No encontramos familias con esos criterios.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Limpiar filtros' }));
    expect(await screen.findByText('Gómez')).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Buscar por nombre' })).toHaveValue('');
    expect(screen.getByLabelText('Estado')).toHaveValue('TODOS');
    expect(location()).toBe('/admin/familias');
  });
});

describe('pagination (mock backend)', () => {
  it('does not show pagination controls when everything fits in one page', async () => {
    seedMany(20);
    await openFamilies();
    await screen.findAllByRole('listitem');
    expect(screen.queryByRole('navigation', { name: 'Paginación' })).not.toBeInTheDocument();
  });

  it('walks through the pages, keeping the page number (not the search) in the address', async () => {
    seedMany(45);
    const user = userEvent.setup();
    await openFamilies();
    expect(await screen.findByText('Familia 001')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(20);
    expect(screen.getByText(/Página 1 de 3/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Siguiente' }));
    expect(await screen.findByText('Familia 021')).toBeInTheDocument();
    expect(screen.queryByText('Familia 001')).not.toBeInTheDocument();
    expect(lastListing().get('page')).toBe('1');
    expect(location()).toContain('pagina=2');

    await user.click(screen.getByRole('button', { name: 'Siguiente' }));
    expect(await screen.findByText('Familia 045')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(5);
    expect(screen.getByRole('button', { name: 'Siguiente' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Anterior' }));
    expect(await screen.findByText('Familia 021')).toBeInTheDocument();
  });

  it('opens the page given in the address (counted from 1)', async () => {
    seedMany(45);
    await openFamilies('/admin/familias?pagina=3');
    expect(await screen.findByText('Familia 041')).toBeInTheDocument();
    expect(lastListing().get('page')).toBe('2');
  });

  it.each(['0', '-1', 'abc', '1.5', ''])('falls back to the first page for the invalid page «%s»', async (value) => {
    seedMany(3);
    await openFamilies(`/admin/familias?pagina=${value}`);
    expect(await screen.findByText('Familia 001')).toBeInTheDocument();
    expect(lastListing().get('page')).toBe('0');
  });

  it('goes back to the first page when the filter changes', async () => {
    seedMany(45);
    await openFamilies('/admin/familias?pagina=2');
    await screen.findByText('Familia 021');
    await userEvent.setup().selectOptions(screen.getByLabelText('Estado'), 'ACTIVOS');
    expect(await screen.findByText('Familia 001')).toBeInTheDocument();
    expect(lastListing().get('page')).toBe('0');
    expect(location()).not.toContain('pagina');
  });

  it('explains a page that no longer exists and offers the first page', async () => {
    seedMany(3);
    await openFamilies('/admin/familias?pagina=9');
    expect(await screen.findByText('Esa página ya no existe.')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Volver a la primera página' }));
    expect(await screen.findByText('Familia 001')).toBeInTheDocument();
    expect(location()).toBe('/admin/familias');
  });
});

describe('data minimisation (mock backend)', () => {
  it('shows only what the summary carries and logs nothing to the console', async () => {
    const logged: unknown[][] = [];
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    try {
      backend.addFamily('Sintética', { cantidadTutores: 1 });
      await openFamilies();
      const user = userEvent.setup();
      await screen.findByText('Sintética');
      await user.type(screen.getByRole('searchbox', { name: 'Buscar por nombre' }), 'sint');
      await waitFor(() => expect(lastListing().get('busqueda')).toBe('sint'));
      expect(document.body.textContent).not.toMatch(/\d{7,8}/); // no DNI-like numbers anywhere in the list
    } finally {
      logged.push(...spies.flatMap((spy) => spy.mock.calls));
      spies.forEach((spy) => spy.mockRestore());
    }
    expect(logged.flat().join(' ')).not.toContain('sint');
  });
});
