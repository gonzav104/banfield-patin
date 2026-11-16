import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { sessionKey } from '@/features/auth/session';
import type { FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';

// Maintenance: the athlete creation form while its POST is pending (mock backend, see src/test/fakeBackend.ts).
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: 'otra.admin@example.com', mfaEnrolado: true });
});

const DNI = '30111322';
const posts = () => backend.requests.filter((r) => r.method === 'post' && r.url === '/admin/deportistas');
const LABELS = { dni: /^DNI/, nombre: /^Nombre/, apellido: /^Apellido/, cuil: /^CUIL/, localidad: /^Localidad/, telefono: /^Teléfono/ };
const field = (key: keyof typeof LABELS) => screen.getByLabelText(LABELS[key]);
type User = ReturnType<typeof userEvent.setup>;
const submit = (user: User) => user.click(screen.getByRole('button', { name: 'Crear deportista' }));

async function openForm() {
  backend.signIn('marcos@example.com');
  const { queryClient } = renderApp('/admin/deportistas/nuevo');
  await screen.findByRole('heading', { name: 'Nuevo deportista' });
  return { queryClient, user: userEvent.setup() };
}

async function fillRequired(user: User) {
  await user.type(field('dni'), DNI);
  await user.type(field('nombre'), 'Lucía');
  await user.type(field('apellido'), 'Sintética');
}

describe('new athlete: the form while the POST is pending', () => {
  it('locks every field while the request is in flight, and what is typed after the click does not exist', async () => {
    const { user } = await openForm();
    const held = backend.hold('post', '/admin/deportistas');
    await fillRequired(user);
    await submit(user);
    await waitFor(() => expect(posts()).toHaveLength(1));
    for (const key of Object.keys(LABELS) as (keyof typeof LABELS)[]) expect(field(key)).toBeDisabled();
    await user.type(field('localidad'), 'Banfield'); // ignored: the field is locked
    expect(field('localidad')).toHaveValue('');
    held.release();
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(posts()[0]!.body).toEqual({ dni: DNI, nombre: 'Lucía', apellido: 'Sintética' }); // what was sent is what was created
    expect(screen.getByText('Deportista creado. Todavía no tiene familia asignada.')).toBeInTheDocument();
    expect(backend.adminAthleteCount()).toBe(1);
  });

  it('sends ONE POST when the form is submitted three times in the same task (before React shows the pending state)', async () => {
    const { user } = await openForm();
    await fillRequired(user);
    const button = screen.getByRole('button', { name: 'Crear deportista' });
    act(() => {
      button.click();
      button.click();
      button.click();
    });
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(posts()).toHaveLength(1);
    expect(backend.adminAthleteCount()).toBe(1); // never two athletes
  });

  it('does not let a form submit event slip through while the first request is pending', async () => {
    const { user } = await openForm();
    const held = backend.hold('post', '/admin/deportistas');
    await fillRequired(user);
    await submit(user);
    await waitFor(() => expect(posts()).toHaveLength(1));
    fireEvent.submit(screen.getByRole('form', { name: 'Nuevo deportista' }));
    fireEvent.submit(screen.getByRole('form', { name: 'Nuevo deportista' }));
    held.release();
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(posts()).toHaveLength(1);
  });

  it('a POST answered AFTER the session changed leaves the new identity on a clean form: no navigation, no «created» notice, nothing of the previous draft', async () => {
    const { queryClient, user } = await openForm();
    const held = backend.hold('post', '/admin/deportistas');
    await fillRequired(user);
    await submit(user);
    await waitFor(() => expect(posts()).toHaveLength(1));
    backend.signIn('otra.admin@example.com');
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    });
    await waitFor(() => expect(field('nombre')).toHaveValue('')); // the new identity starts clean
    held.release();
    await act(async () => new Promise((resolve) => setTimeout(resolve, 400)));
    expect(screen.getByRole('heading', { name: 'Nuevo deportista' })).toBeInTheDocument(); // it did not navigate to the previous identity's athlete
    expect(screen.getByTestId('location')).toHaveTextContent(/\/admin\/deportistas\/nuevo$/);
    expect(screen.queryByText(/Deportista creado/)).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain(DNI);
    expect(field('dni')).toHaveValue('');
    expect(field('dni')).toBeEnabled(); // and the form of the new identity is usable
  });

  it('after a definite error the draft is intact and the form is usable again; it is never left locked', async () => {
    backend.addAdminAthlete({ nombre: 'Ya', apellido: 'Existente', dni: DNI });
    const { user } = await openForm();
    await fillRequired(user);
    await user.type(field('localidad'), 'Banfield');
    await submit(user);
    expect(await screen.findByText(/Ya existe un deportista activo con ese DNI/)).toBeInTheDocument();
    expect(field('nombre')).toHaveValue('Lucía');
    expect(field('localidad')).toHaveValue('Banfield');
    for (const key of Object.keys(LABELS) as (keyof typeof LABELS)[]) expect(field(key)).toBeEnabled();
    await user.clear(field('dni'));
    await user.type(field('dni'), '30111399');
    await submit(user);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(posts()).toHaveLength(2);
    expect(posts()[1]!.body).toMatchObject({ dni: '30111399', localidad: 'Banfield' });
  });

  it('keeps the uncertain-result flow: locked and blocked until the administrator decides, then usable again', async () => {
    const { user } = await openForm();
    backend.override('post', '/admin/deportistas', 'network', { once: true });
    await fillRequired(user);
    await submit(user);
    await screen.findByText(/No pudimos confirmar si el deportista se creó/);
    expect(posts()).toHaveLength(1);
    expect(field('nombre')).toBeDisabled(); // U7 behavior preserved: locked while the result is unknown
    expect(screen.getByRole('button', { name: 'Crear deportista' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Verificar en el listado' }));
    await user.click(await screen.findByRole('button', { name: 'Volver a intentar' }));
    expect(field('nombre')).toBeEnabled(); // recovered by an explicit decision only
    expect(field('nombre')).toHaveValue('Lucía');
    await submit(user);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(posts()).toHaveLength(2);
    expect(backend.adminAthleteCount()).toBe(1);
  });
});
