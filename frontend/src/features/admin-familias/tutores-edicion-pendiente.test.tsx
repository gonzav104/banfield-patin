import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { sessionKey } from '@/features/auth/session';
import { http } from '@/lib/http';
import type { FakeAdminTutor, FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';

// Maintenance: the tutor edit form while its PUT is pending (mock backend, see src/test/fakeBackend.ts).
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: 'otra.admin@example.com', mfaEnrolado: true });
});

const DNI = '30111222';
const PUT = '/admin/tutores/t-rosa';
const tutorPuts = () => backend.requests.filter((r) => r.method === 'put' && r.url === '/admin/tutores/t-rosa');
const detailKey = ['admin', 'tutor-detail', 't-rosa'];

/**
 * Holds the answer of every PUT of the tutor, including the ones the mock answers with an override (the mock's own `hold` only
 * delays the non-overridden ones), and gives it back on `release()`. Restored after each test.
 */
const originalAdapter = http.defaults.adapter;
afterEach(() => {
  http.defaults.adapter = originalAdapter;
});
function deferPut() {
  const inner = http.defaults.adapter as (config: never) => Promise<unknown>;
  const pending: (() => void)[] = [];
  http.defaults.adapter = ((config: { method?: string; url?: string }) => {
    const answer = inner(config as never);
    if ((config.method ?? '').toLowerCase() === 'put' && config.url === PUT) {
      return new Promise((resolve, reject) => {
        pending.push(() => void answer.then(resolve, reject));
        answer.catch(() => undefined);
      });
    }
    return answer;
  }) as never;
  return { release: () => pending.splice(0).forEach((deliver) => deliver()) };
}
const LABELS = { nombre: /^Nombre/, apellido: /^Apellido/, telefono: /^Teléfono/, parentesco: /^Parentesco/ };
const field = (key: keyof typeof LABELS) => screen.getByLabelText(LABELS[key]);
const save = (user: ReturnType<typeof userEvent.setup>) => user.click(screen.getByRole('button', { name: 'Guardar cambios' }));

function tutor(over: Partial<FakeAdminTutor> = {}): FakeAdminTutor {
  return { id: 't-rosa', nombre: 'Rosa', apellido: 'Sintética', dni: DNI, dniEnmascarado: '••••••222', telefono: '11 5555-0000', email: 'rosa@example.test', parentesco: 'Madre', activo: true, version: 3, ...over };
}

async function openEdit() {
  const family = backend.addFamily('Gómez', { version: 5, tutores: [tutor()] });
  backend.signIn('marcos@example.com');
  const { queryClient } = renderApp(`/admin/familias/${family.id}/tutores/t-rosa/editar`);
  await screen.findByRole('heading', { level: 1, name: 'Editar tutor' });
  await waitFor(() => expect(field('nombre')).toHaveValue('Rosa'));
  return { family, queryClient, user: userEvent.setup() };
}

async function change(user: ReturnType<typeof userEvent.setup>, key: keyof typeof LABELS, value: string) {
  await user.clear(field(key));
  await user.type(field(key), value);
}

describe('edit a tutor: the form while the PUT is pending', () => {
  it('locks every field while the request is in flight, and what is typed after the click does not exist (it would be lost behind a «saved»)', async () => {
    const { family, user } = await openEdit();
    const held = backend.hold('put', PUT);
    await change(user, 'nombre', 'Rosa María');
    await save(user);
    await waitFor(() => expect(tutorPuts()).toHaveLength(1));
    for (const key of Object.keys(LABELS) as (keyof typeof LABELS)[]) expect(field(key)).toBeDisabled();
    expect(screen.getByLabelText(/^DNI/)).toBeDisabled();
    expect(screen.getByLabelText(/^Correo/)).toBeDisabled();
    await user.type(field('telefono'), '999'); // ignored: the field is locked
    expect(field('telefono')).toHaveValue('11 5555-0000');
    held.release();
    await screen.findByText('Cambios guardados.');
    // The draft that was sent is the draft that was saved: nothing typed after the click was saved or shown as saved.
    expect(tutorPuts()[0]!.body).toMatchObject({ nombre: 'Rosa María', telefono: '11 5555-0000' });
    expect(backend.tutorVersion('t-rosa')).toBe('4');
    expect(family.id).toBeTruthy();
  });

  it('sends ONE PUT when the form is submitted three times in the same task (before React shows the pending state)', async () => {
    const { user } = await openEdit();
    await change(user, 'nombre', 'Rosa María');
    const button = screen.getByRole('button', { name: 'Guardar cambios' });
    act(() => {
      button.click();
      button.click();
      button.click();
    });
    await screen.findByText('Cambios guardados.');
    expect(tutorPuts()).toHaveLength(1);
    expect(backend.tutorVersion('t-rosa')).toBe('4'); // moved exactly once
  });

  it('does not let a form submit event slip through while the first request is pending', async () => {
    const { user } = await openEdit();
    const held = backend.hold('put', PUT);
    await change(user, 'nombre', 'Rosa María');
    await save(user);
    await waitFor(() => expect(tutorPuts()).toHaveLength(1));
    fireEvent.submit(screen.getByRole('form', { name: 'Editar tutor' }));
    fireEvent.submit(screen.getByRole('form', { name: 'Editar tutor' }));
    held.release();
    await screen.findByText('Cambios guardados.');
    expect(tutorPuts()).toHaveLength(1);
  });

  it('a PUT answered AFTER the session changed to an identity that cannot see the tutor leaves nothing of it in the cache or on screen', async () => {
    const { family, queryClient, user } = await openEdit();
    const held = backend.hold('put', PUT);
    await change(user, 'nombre', 'Rosa María');
    await save(user);
    await waitFor(() => expect(tutorPuts()).toHaveLength(1));
    backend.signIn('otra.admin@example.com');
    backend.override('get', PUT, { status: 404, body: { codigo: 'TUTOR_NO_ENCONTRADO', mensaje: 'x' } }, { once: false });
    backend.override('get', `/admin/familias/${family.id}`, { status: 404, body: { codigo: 'FAMILIA_NO_ENCONTRADA', mensaje: 'x' } }, { once: false });
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    });
    await screen.findByRole('heading', { name: 'No encontramos este tutor' });
    held.release();
    await act(async () => new Promise((resolve) => setTimeout(resolve, 400)));
    expect(queryClient.getQueryData(detailKey)).toBeUndefined(); // the previous identity's FULL detail (DNI) must not come back
    expect(document.body).not.toHaveTextContent(DNI);
    expect(screen.queryByText('Cambios guardados.')).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^DNI/)).not.toBeInTheDocument();
  });

  it('a LATE ERROR (409 stale) of the previous identity paints no alert, no «Ver datos vigentes» and blocks nothing on the new identity', async () => {
    const { queryClient, user } = await openEdit();
    backend.override('put', PUT, { status: 409, body: { codigo: 'EDICION_DESACTUALIZADA', mensaje: 'x', detalles: [] } }, { once: true });
    const late = deferPut();
    await change(user, 'nombre', 'Rosa María');
    await save(user);
    await waitFor(() => expect(tutorPuts()).toHaveLength(1));
    backend.signIn('otra.admin@example.com');
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    });
    await waitFor(() => expect(field('nombre')).toHaveValue('Rosa')); // the new identity starts from the stored data
    late.release();
    await act(async () => new Promise((resolve) => setTimeout(resolve, 400)));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Ver datos vigentes' })).not.toBeInTheDocument();
    expect(field('nombre')).toBeEnabled();
    await change(user, 'nombre', 'Otra Rosa');
    await save(user); // the new identity's own form works
    await waitFor(() => expect(tutorPuts()).toHaveLength(2));
    late.release();
    await screen.findByText('Cambios guardados.');
  });

  it('after a definite error the draft is intact and the form is usable again; it is never left locked', async () => {
    const { user } = await openEdit();
    backend.override('put', PUT, { status: 400, body: { codigo: 'VALIDACION', mensaje: 'detalle interno', detalles: [] } }, { once: true });
    await change(user, 'nombre', 'Rosa María');
    await change(user, 'parentesco', 'Tutora legal');
    await save(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('Revisá los datos del tutor');
    expect(field('nombre')).toHaveValue('Rosa María');
    expect(field('parentesco')).toHaveValue('Tutora legal');
    for (const key of Object.keys(LABELS) as (keyof typeof LABELS)[]) expect(field(key)).toBeEnabled();
    await user.type(field('telefono'), '1'); // the administrator can keep editing
    expect(field('telefono')).toHaveValue('11 5555-00001');
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(tutorPuts()).toHaveLength(2);
    expect(tutorPuts()[1]!.body).toMatchObject({ nombre: 'Rosa María', parentesco: 'Tutora legal', telefono: '11 5555-00001' });
  });

  it('keeps the existing uncertain-result flow: the draft stays, the resend stays blocked until verified, and the fields are not left locked by the pending state', async () => {
    const { user } = await openEdit();
    backend.override('put', PUT, 'network', { once: true });
    await change(user, 'nombre', 'Rosa María');
    await save(user);
    await screen.findByText(/No pudimos confirmar si el cambio se guardó/);
    expect(tutorPuts()).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled(); // resend blocked until verified (U5-B)
    expect(field('nombre')).toHaveValue('Rosa María');
    expect(field('nombre')).toBeEnabled(); // U5-B behavior preserved: not locked once nothing is pending
    expect(screen.getByRole('button', { name: 'Verificar datos vigentes' })).toBeEnabled();
  });
});
