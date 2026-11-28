import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, family, renderApp } from '@/test/render';

// MOCK backend (src/test/fakeBackend.ts mirrors PerfilPropioController). Real-backend evidence: e2e/mis-datos.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  backend.signIn(family.email);
  backend.profileSelfService.enabled = true;
});

const writes = () => backend.requests.filter((r) => r.url.startsWith('/familia/mi-perfil') && r.method !== 'get');

async function open() {
  renderApp('/familia/mis-datos');
  await screen.findByRole('heading', { level: 1, name: 'Mis datos' });
}

describe('Mis datos (mock backend)', () => {
  it('is reachable from the family dashboard with a plain-words card', async () => {
    renderApp('/familia');
    const link = await screen.findByRole('link', { name: 'Ver mis datos' });
    expect(link).toHaveAttribute('href', '/familia/mis-datos');
  });

  it('with nobody to claim it goes straight to a short form and saves with CSRF, trimmed and with the DNI never shown back', async () => {
    await open();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Nombre/), '  Laura ');
    await user.type(screen.getByLabelText(/^Apellido/), 'Gómez');
    await user.type(screen.getByLabelText(/^DNI/), '30.111.222');
    await user.type(screen.getByLabelText(/^Correo/), 'Laura@Example.Test');
    await user.click(screen.getByRole('button', { name: 'Guardar mis datos' }));

    expect(await screen.findByText(/Listo: tus datos quedaron cargados/)).toBeInTheDocument();
    expect(writes()).toHaveLength(1);
    expect(writes()[0]!.csrfHeader).not.toBeNull();
    expect(writes()[0]!.body).toMatchObject({ nombre: 'Laura', apellido: 'Gómez', dni: '30.111.222', email: 'Laura@Example.Test' });
    // The form now shows the saved profile: the DNI only as a masked hint, and «Guardar cambios» waits for a change.
    expect(await screen.findByRole('heading', { name: 'Tus datos' })).toBeInTheDocument();
    expect(screen.getByText(/Ahora figura ••••222/)).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('30.111.222');
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
  });

  it('validates before sending, with fixed messages and nothing sent', async () => {
    await open();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^DNI/), '12');
    await user.click(screen.getByRole('button', { name: 'Guardar mis datos' }));
    expect(await screen.findByText('Ingresá el nombre.')).toBeInTheDocument();
    expect(screen.getByText('Ingresá el apellido.')).toBeInTheDocument();
    expect(screen.getByText('El DNI debe tener entre 7 y 9 dígitos.')).toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });

  it('offers «¿Sos alguna de estas personas?» and «Soy yo» links the person without asking for the data again', async () => {
    backend.addPersonWithoutAccount('familia-1', 'Laura', 'Gómez', 'Madre');
    backend.addPersonWithoutAccount('familia-1', 'Martín', 'Gómez', 'Padre');
    backend.addPersonWithoutAccount('otra-familia', 'Ajena', 'Persona');
    await open();
    const list = await screen.findByRole('list', { name: 'Personas de tu familia' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(2);
    expect(screen.queryByText(/Ajena/)).not.toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(within(list).getByRole('button', { name: 'Soy Laura Gómez' }));

    expect(await screen.findByRole('heading', { name: 'Tus datos' })).toBeInTheDocument();
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Laura');
    expect(writes()).toHaveLength(1);
    expect(writes()[0]!.url).toBe('/familia/mi-perfil/reclamar-tutor');
    expect(writes()[0]!.body).toMatchObject({ versionEsperada: '0' });
    // Claiming is a declaration: the data are the club's, but the club has NOT confirmed that this account is that person.
    expect(screen.getByText(/Todavía tiene que confirmar que esta cuenta es tuya/)).toBeInTheDocument();
    expect(screen.queryByText(/El club confirmó que esta cuenta es tuya/)).not.toBeInTheDocument();
    // The identity came from the club: it is read-only for the family.
    expect(screen.getByLabelText(/^Nombre/)).toBeDisabled();
    expect(screen.getByText(/El club ya verificó tu nombre y tu DNI/)).toBeInTheDocument();
  });

  it('«No soy ninguna de ellas» opens the form', async () => {
    backend.addPersonWithoutAccount('familia-1', 'Laura', 'Gómez');
    await open();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'No soy ninguna de ellas' }));
    expect(await screen.findByRole('heading', { name: 'Cargá tus datos' })).toBeInTheDocument();
  });

  it('a person someone else just claimed is a neutral message and the list is refreshed, without exposing why', async () => {
    const person = backend.addPersonWithoutAccount('familia-1', 'Laura', 'Gómez');
    await open();
    const list = await screen.findByRole('list', { name: 'Personas de tu familia' });
    person.claimedBy = 'otra-cuenta';
    const user = userEvent.setup();
    await user.click(within(list).getByRole('button', { name: 'Soy Laura Gómez' }));
    expect(await screen.findByText(/No pudimos asociarte con esa persona/)).toBeInTheDocument();
    // «Actualizamos la lista»: it is really refreshed, so the person that is not free anymore is not offered again.
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Soy Laura Gómez' })).not.toBeInTheDocument());
  });

  it('a stale save is refused with a plain explanation and the screen shows the current data', async () => {
    await open();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Nombre/), 'Laura');
    await user.type(screen.getByLabelText(/^Apellido/), 'Gómez');
    await user.click(screen.getByRole('button', { name: 'Guardar mis datos' }));
    await screen.findByRole('heading', { name: 'Tus datos' });

    backend.touchProfile(family.id);
    await user.type(screen.getByLabelText(/^Teléfono/), '11 5555-0000');
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    expect(await screen.findByText(/Tus datos cambiaron mientras los editabas/)).toBeInTheDocument();
  });

  it('once the club verified the identity name and DNI are locked and only contact can change', async () => {
    await open();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Nombre/), 'Laura');
    await user.type(screen.getByLabelText(/^Apellido/), 'Gómez');
    await user.type(screen.getByLabelText(/^DNI/), '30111222');
    await user.click(screen.getByRole('button', { name: 'Guardar mis datos' }));
    await screen.findByRole('heading', { name: 'Tus datos' });
    backend.verifyProfileIdentity(family.id);
    // A new visit loads the verified state.
    cleanup();
    renderApp('/familia/mis-datos');
    expect(await screen.findByLabelText(/^Nombre/)).toBeDisabled();
    expect(screen.getByLabelText(/^Apellido/)).toBeDisabled();
    expect(screen.getByText(/DNI: ••••222/)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Teléfono/)).toBeEnabled();
    expect(screen.queryByLabelText(/^Cambiar el DNI/)).not.toBeInTheDocument();
  });

  it('with the feature off it explains it and offers no form or action', async () => {
    backend.profileSelfService.enabled = false;
    backend.addPersonWithoutAccount('familia-1', 'Laura', 'Gómez');
    await open();
    expect(await screen.findByText(/todavía no está disponible/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Soy |Guardar/ })).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });

  it('an administrator never reaches it', () => {
    backend = createBackend();
    backend.signIn('marcos@example.com');
    renderApp('/familia/mis-datos');
    expect(screen.queryByRole('heading', { name: 'Mis datos' })).not.toBeInTheDocument();
  });
});
