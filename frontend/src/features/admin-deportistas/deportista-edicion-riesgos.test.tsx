import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { sessionKey } from '@/features/auth/session';
import type { FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';

let backend: FakeBackend;
beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: 'otra.admin@example.com', mfaEnrolado: true });
});
const puts = () => backend.requests.filter((r) => r.method === 'put' && /^\/admin\/deportistas\/[^/]+$/.test(r.url));
const seed = () =>
  backend.addAdminAthlete({ nombre: 'Lucía', apellido: 'Sintética', dni: '30111322', cuil: '20301113227', domicilio: 'Calle Falsa 123', localidad: 'Banfield' });
const detailKey = (id: string) => ['admin', 'athlete-detail', id];

describe('edit athlete: what a slow save and a changed identity must not do', () => {
  it('does not let the administrator type while the PUT is in flight (that text would be lost and «Cambios guardados.» shown)', async () => {
    const a = seed();
    backend.signIn('marcos@example.com');
    renderApp(`/admin/deportistas/${a.id}/editar`);
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Lucía'));
    await user.clear(screen.getByLabelText(/^Nombre/));
    await user.type(screen.getByLabelText(/^Nombre/), 'Lucía M');
    const held = backend.hold('put', `/admin/deportistas/${a.id}`);
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(screen.getByLabelText(/^Localidad/)).toBeDisabled(); // OBSERVED: enabled
    held.release();
  });

  it('a PUT answered AFTER the session changed to an identity that cannot see the athlete leaves nothing of it on screen or in the cache', async () => {
    const a = seed();
    backend.signIn('marcos@example.com');
    const { queryClient } = renderApp(`/admin/deportistas/${a.id}/editar`);
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Lucía'));
    await user.clear(screen.getByLabelText(/^Nombre/));
    await user.type(screen.getByLabelText(/^Nombre/), 'Lucía M');
    const held = backend.hold('put', `/admin/deportistas/${a.id}`);
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    await waitFor(() => expect(puts()).toHaveLength(1));
    backend.signIn('otra.admin@example.com');
    backend.override('get', `/admin/deportistas/${a.id}`, { status: 404, body: { codigo: 'DEPORTISTA_NO_ENCONTRADO', mensaje: 'x' } }, { once: false });
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await screen.findByRole('heading', { name: 'No encontramos este deportista' });
    held.release();
    await act(async () => { await new Promise((r) => setTimeout(r, 400)); });
    expect(queryClient.getQueryData(detailKey(a.id))).toBeUndefined(); // OBSERVED: the previous identity's full detail (DNI, CUIL)
    expect(screen.queryByLabelText(/^DNI/)).not.toBeInTheDocument(); // OBSERVED: the edit form opens with the previous identity's data
  });

});

describe('edit athlete: conflicts with an empty side (pass today, keep as regression)', () => {
  it('decides opposite ways, null on either side, and sends the explicit nulls with the current revision as a string', async () => {
    const a = seed();
    backend.signIn('marcos@example.com');
    renderApp(`/admin/deportistas/${a.id}/editar`);
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Lucía'));
    await user.clear(screen.getByLabelText(/^Localidad/)); await user.type(screen.getByLabelText(/^Localidad/), 'Mi Loc'); // other CLEARS it
    await user.clear(screen.getByLabelText(/^Domicilio \(opcional\)/)); // other changes it
    backend.changeAdminAthleteBehindTheScenes(a.id, { localidad: null, domicilio: 'Su Calle 9' });
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    await user.click(await screen.findByRole('button', { name: 'Ver datos vigentes' }));
    const g = await screen.findByRole('group', { name: 'Datos vigentes' });
    await user.click(within(within(g).getByRole('group', { name: 'Localidad' })).getByRole('radio', { name: /Usar el valor vigente/ }));
    await user.click(within(within(g).getByRole('group', { name: 'Domicilio' })).getByRole('radio', { name: /Mantener mi valor/ }));
    await user.click(within(g).getByRole('button', { name: 'Seguir editando con los datos vigentes' }));
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    await screen.findByText('Cambios guardados.');
    expect(puts()[1]!.body).toMatchObject({ localidad: null, domicilio: null, versionEsperada: '1' });
    expect(backend.adminAthlete(a.id)).toMatchObject({ localidad: null, domicilio: null });
  });
});

describe('same-task repeated clicks (the in-flight guard must not depend on React Query having notified yet)', () => {
  it('sends ONE PUT when «Guardar cambios» is clicked three times in the same task', async () => {
    const a = seed();
    backend.signIn('marcos@example.com');
    renderApp(`/admin/deportistas/${a.id}/editar`);
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Lucía'));
    await user.clear(screen.getByLabelText(/^Nombre/));
    await user.type(screen.getByLabelText(/^Nombre/), 'Lucía M');
    const button = screen.getByRole('button', { name: 'Guardar cambios' });
    act(() => {
      button.click();
      button.click();
      button.click();
    });
    await screen.findByText('Cambios guardados.');
    expect(puts()).toHaveLength(1);
    expect(backend.adminAthleteVersion(a.id)).toBe('1');
  });

});
