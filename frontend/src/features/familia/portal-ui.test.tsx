import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { sessionKey } from '@/features/auth/session';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, family, renderApp } from '@/test/render';

// MOCK backend (src/test/fakeBackend.ts). Real-backend evidence lives in e2e/portal.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  backend = createBackend();
  backend.signIn(family.email);
});

afterEach(() => backend.clearOverrides());

const portalUrls = () => backend.requests.filter((r) => r.url.startsWith('/familia/')).map((r) => r.url);

function addAthletes(count: number, familyId = 'familia-1') {
  for (let i = 1; i <= count; i += 1) {
    const athlete = backend.addAthlete({
      nombre: `Atleta ${String(i).padStart(2, '0')}`,
      apellido: 'Prueba',
      fechaNacimiento: '2015-06-15',
      activo: true,
    });
    backend.linkAthlete(familyId, athlete.id);
  }
}

describe('family dashboard (/familia)', () => {
  it('shows the family, tutors (name and relationship only), the real total and a short preview', async () => {
    backend.addPortalFamily('familia-1', 'Familia Gómez', [
      { id: 't1', nombre: 'Marta', apellido: 'Gómez', parentesco: 'Madre', telefono: '11-5555-0000', email: 'marta@example.com' },
      { id: 't2', nombre: 'Juan', apellido: 'Gómez', parentesco: null, telefono: null, email: null },
    ]);
    addAthletes(5);
    renderApp('/familia');

    expect(await screen.findByRole('heading', { name: 'Hola, Lucía' })).toBeInTheDocument();
    expect(await screen.findByText('Familia Gómez')).toBeInTheDocument();
    expect(screen.getByText('Marta Gómez')).toBeInTheDocument();
    expect(screen.getByText(/· Madre/)).toBeInTheDocument();
    expect(screen.getByText('Juan Gómez')).toBeInTheDocument();
    expect(await screen.findByText('5 vinculados')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem').filter((li) => /Atleta/.test(li.textContent ?? ''))).toHaveLength(3);
    expect(screen.getByRole('link', { name: 'Ver todos los deportistas (5)' })).toHaveAttribute('href', '/familia/deportistas');
    expect(document.body.textContent).not.toMatch(/5555|marta@/);
  });

  it('downloads only what it shows: the family and a page of 3 athletes', async () => {
    addAthletes(5);
    renderApp('/familia');
    await screen.findByText('5 vinculados');

    expect([...new Set(portalUrls())].sort()).toEqual(['/familia/deportistas?page=0&size=3', '/familia/mi-familia']);
  });

  it('tells an account without a family apart, and requests nothing from /api/familia', async () => {
    backend.addUser({ ...family, id: 'u-nofam', email: 'sinfamilia@example.com', familiaId: null });
    backend.signIn('sinfamilia@example.com');
    renderApp('/familia');

    expect(await screen.findByText('Tu cuenta todavía no está asociada a una familia')).toBeInTheDocument();
    expect(screen.getByText('Consultá con la escuela para que la vinculen.')).toBeInTheDocument();
    expect(screen.queryByText(/Todavía no hay deportistas/)).not.toBeInTheDocument();
    expect(portalUrls()).toEqual([]);
  });

  it('tells a family that is not available apart (404 FAMILIA_NO_ENCONTRADA)', async () => {
    backend.setPortalFamilyActive('familia-1', false);
    renderApp('/familia');

    expect(await screen.findByText('No pudimos encontrar tu familia')).toBeInTheDocument();
    expect(screen.queryByText('Tu cuenta todavía no está asociada a una familia')).not.toBeInTheDocument();
    expect(screen.queryByText(/Todavía no hay deportistas/)).not.toBeInTheDocument();
  });

  it('tells a family without athletes apart', async () => {
    renderApp('/familia');

    expect(await screen.findByText('Todavía no hay deportistas vinculados a tu familia')).toBeInTheDocument();
    expect(screen.queryByText('Tu cuenta todavía no está asociada a una familia')).not.toBeInTheDocument();
    expect(screen.queryByText('No pudimos encontrar tu familia')).not.toBeInTheDocument();
  });

  it('shows a retryable error without closing the session when the server is down (503)', async () => {
    backend.override('get', '/familia/mi-familia', { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } }, { once: true });
    addAthletes(1);
    renderApp('/familia');

    expect(await screen.findByText(/El servicio no está disponible/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Hola, Lucía' })).toBeInTheDocument(); // still signed in
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByText('Familia Gómez')).toBeInTheDocument();
  });

  it('shows a retryable error on a network failure without closing the session', async () => {
    backend.override('get', '/familia/deportistas?page=0&size=3', 'network', { once: true });
    renderApp('/familia');

    expect(await screen.findByText(/No pudimos conectar con el servidor/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Hola, Lucía' })).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByText('Todavía no hay deportistas vinculados a tu familia')).toBeInTheDocument();
  });

  it('explains a permission error (403)', async () => {
    backend.override('get', '/familia/mi-familia', { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } });
    renderApp('/familia');
    expect(await screen.findByText('No tenés permiso para ver esta información.')).toBeInTheDocument();
  });

  it('goes to the login screen when the session expires', async () => {
    const { queryClient } = renderApp('/familia');
    await screen.findByText('Familia Gómez');

    backend.expireSession();
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    });

    expect(await screen.findByRole('heading', { name: 'Ingresá a tu portal' })).toBeInTheDocument();
    expect(screen.queryByText('Familia Gómez')).not.toBeInTheDocument();
  });
});

describe('athletes list (/familia/deportistas)', () => {
  it('uses the real pagination: 10 per page, Anterior/Siguiente and the total from the server', async () => {
    addAthletes(12);
    const user = userEvent.setup();
    renderApp('/familia/deportistas');

    expect(await screen.findByText('Página 1 de 2 · 12 en total')).toBeInTheDocument();
    expect(screen.getAllByText(/^Atleta \d\d Prueba$/)).toHaveLength(10);
    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Siguiente' })).toBeEnabled();

    await user.click(screen.getByRole('button', { name: 'Siguiente' }));
    expect(await screen.findByText('Página 2 de 2 · 12 en total')).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByText(/^Atleta \d\d Prueba$/)).toHaveLength(2));
    expect(screen.getByText('Atleta 11 Prueba')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Siguiente' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Anterior' })).toBeEnabled();
    expect(portalUrls()).toContain('/familia/deportistas?page=1&size=10');

    await user.click(screen.getByRole('button', { name: 'Anterior' }));
    expect(await screen.findByText('Página 1 de 2 · 12 en total')).toBeInTheDocument();
  });

  it('moves focus to the list heading after changing page', async () => {
    addAthletes(12);
    const user = userEvent.setup();
    renderApp('/familia/deportistas');
    await screen.findByText('Página 1 de 2 · 12 en total');
    await user.click(screen.getByRole('button', { name: 'Siguiente' }));
    await screen.findByText('Página 2 de 2 · 12 en total');

    expect(screen.getByRole('heading', { name: 'Deportistas vinculados' })).toHaveFocus();
  });

  it('shows name, age and status in the backend order, with the status as text', async () => {
    const mk = (nombre: string, apellido: string, fechaNacimiento: string | null, activo: boolean) =>
      backend.linkAthlete('familia-1', backend.addAthlete({ nombre, apellido, fechaNacimiento, activo }).id);
    const year = new Date().getFullYear();
    mk('Bruno', 'Zárate', `${year - 9}-01-01`, true);
    mk('Ana', 'Álvarez', null, false);
    renderApp('/familia/deportistas');

    await screen.findByText('Bruno Zárate');
    const rows = screen.getAllByRole('listitem').filter((li) => /Álvarez|Zárate/.test(li.textContent ?? ''));
    expect(rows).toHaveLength(2);
    expect(within(rows[0]!).getByText('Ana Álvarez')).toBeInTheDocument();
    expect(within(rows[0]!).getByText('Edad no informada')).toBeInTheDocument();
    expect(within(rows[0]!).getByText('Inactivo')).toBeInTheDocument();
    expect(within(rows[1]!).getByText('Bruno Zárate')).toBeInTheDocument();
    expect(within(rows[1]!).getByText('9 años')).toBeInTheDocument();
    expect(within(rows[1]!).getByText('Activo')).toBeInTheDocument();
  });

  it('explains that Activo/Inactivo is the school status and never suggests an enrolment', async () => {
    addAthletes(1);
    backend.linkAthlete('familia-1', backend.addAthlete({ nombre: 'Iván', apellido: 'Medina', fechaNacimiento: null, activo: false }).id);
    renderApp('/familia/deportistas');
    await screen.findByText('Iván Medina');

    expect(screen.getByText(/estado del deportista en la escuela/)).toBeInTheDocument();
    expect(screen.getByText(/No indica su inscripción/)).toBeInTheDocument();
    for (const forbidden of [/habilitad/i, /federad[oa]s?\b(?!\.)/i]) {
      expect(screen.queryByText(forbidden, { selector: 'li *' })).not.toBeInTheDocument();
    }
    expect(screen.queryByText(/inscripto/i)).not.toBeInTheDocument();
  });

  it('never renders DNI, address, school or family identifiers, even if the server over-shares', async () => {
    const athlete = backend.addAthlete({
      nombre: 'Ana',
      apellido: 'Paz',
      fechaNacimiento: '2016-05-01',
      activo: true,
      extra: { dni: '98765432', cuil: '27-98765432-1', domicilio: 'Calle Falsa 123', escuelaId: 'escuela-1', familiaId: 'familia-1', telefono: '11-4444-0000' },
    });
    backend.linkAthlete('familia-1', athlete.id);
    renderApp('/familia/deportistas');
    await screen.findByText('Ana Paz');

    expect(document.body.innerHTML).not.toMatch(/98765432|27-98765432|Calle Falsa|4444|escuela-1|familia-1/);
  });

  it('distinguishes the three empty situations on the list too', async () => {
    renderApp('/familia/deportistas');
    expect(await screen.findByText('Todavía no hay deportistas vinculados a tu familia')).toBeInTheDocument();
  });

  it('offers to go back to the first page when the current page is empty but the total is not', async () => {
    addAthletes(12);
    const user = userEvent.setup();
    renderApp('/familia/deportistas');
    await screen.findByText('Página 1 de 2 · 12 en total');
    backend.override('get', '/familia/deportistas?page=1&size=10', { status: 200, body: { contenido: [], pagina: 1, tamanio: 10, totalElementos: 12 } });
    await user.click(screen.getByRole('button', { name: 'Siguiente' }));

    expect(await screen.findByText(/ya no tiene deportistas/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Volver a la primera página' }));
    expect(await screen.findByText('Página 1 de 2 · 12 en total')).toBeInTheDocument();
  });

  it('shows a retryable error if the list fails (503) and keeps the session', async () => {
    backend.override('get', '/familia/deportistas?page=0&size=10', { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } }, { once: true });
    addAthletes(1);
    renderApp('/familia/deportistas');

    expect(await screen.findByText(/El servicio no está disponible/)).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByText('Atleta 01 Prueba')).toBeInTheDocument();
  });

  it('shows the no-family notice without requesting anything', async () => {
    backend.addUser({ ...family, id: 'u-nofam', email: 'sinfamilia@example.com', familiaId: null });
    backend.signIn('sinfamilia@example.com');
    renderApp('/familia/deportistas');

    expect(await screen.findByText('Tu cuenta todavía no está asociada a una familia')).toBeInTheDocument();
    expect(portalUrls()).toEqual([]);
  });
});

describe('navigation', () => {
  it('has one "Principal" landmark with Inicio and Deportistas, marks the current page and navigates', async () => {
    const user = userEvent.setup();
    renderApp('/familia');
    const nav = await screen.findByRole('navigation', { name: 'Principal' });

    const home = within(nav).getByRole('link', { name: 'Inicio' });
    const athletes = within(nav).getByRole('link', { name: 'Deportistas' });
    expect(home).toHaveAttribute('aria-current', 'page');
    expect(athletes).not.toHaveAttribute('aria-current');

    await user.click(athletes);
    expect(await screen.findByRole('heading', { name: 'Deportistas', level: 1 })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/familia/deportistas');
    expect(within(screen.getByRole('navigation', { name: 'Principal' })).getByRole('link', { name: 'Deportistas' })).toHaveAttribute('aria-current', 'page');
  });

  it('is reachable by keyboard and keeps the logout button available', async () => {
    const user = userEvent.setup();
    renderApp('/familia');
    await screen.findByRole('navigation', { name: 'Principal' });

    await user.tab();
    expect(screen.getByRole('link', { name: 'Inicio' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('link', { name: 'Deportistas' })).toHaveFocus();
    expect(screen.getByRole('button', { name: 'Cerrar sesión' })).toBeInTheDocument();
  });

  it('sends unknown /familia/* paths back to the dashboard', async () => {
    renderApp('/familia/otra-cosa');
    expect(await screen.findByRole('heading', { name: 'Hola, Lucía' })).toBeInTheDocument();
  });
});

describe('isolation between families in the same browser', () => {
  it('never shows the previous family data after the identity changes', async () => {
    backend.addUser({ ...family, id: 'u-b', nombre: 'Rosa', email: 'otra@example.com', familiaId: 'familia-2' });
    backend.addPortalFamily('familia-2', 'Familia López');
    backend.linkAthlete('familia-1', backend.addAthlete({ nombre: 'Propia', apellido: 'Gómez', fechaNacimiento: null, activo: true }).id);
    backend.linkAthlete('familia-2', backend.addAthlete({ nombre: 'Ajena', apellido: 'López', fechaNacimiento: null, activo: true }).id);
    const { queryClient } = renderApp('/familia/deportistas');
    await screen.findByText('Propia Gómez');

    backend.signIn('otra@example.com');
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    });

    // The keys carry the family id, so the screen can never read the previous family's entry.
    expect(await screen.findByText('Ajena López')).toBeInTheDocument();
    expect(screen.queryByText('Propia Gómez')).not.toBeInTheDocument();
  });

  it('leaves no family data in the cache after a real logout', async () => {
    backend.linkAthlete('familia-1', backend.addAthlete({ nombre: 'Propia', apellido: 'Gómez', fechaNacimiento: null, activo: true }).id);
    const { queryClient } = renderApp('/familia/deportistas');
    await screen.findByText('Propia Gómez');
    expect(queryClient.getQueryCache().getAll().some((q) => q.queryKey[0] === 'familia')).toBe(true);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Cerrar sesión' }));

    expect(await screen.findByRole('heading', { name: 'Ingresá a tu portal' })).toBeInTheDocument();
    expect(queryClient.getQueryCache().getAll().some((q) => q.queryKey[0] === 'familia')).toBe(false);
    expect(document.body.textContent).not.toMatch(/Propia/);
  });
});
