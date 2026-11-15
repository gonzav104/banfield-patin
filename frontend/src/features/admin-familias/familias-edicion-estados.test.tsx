import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { QueryClient } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { sessionKey } from '@/features/auth/session';
import type { FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';

// MOCK backend (see src/test/fakeBackend.ts, which mirrors the real contract of FE-05/U1). Real-backend evidence lives in
// e2e/familias-edicion-estados.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const location = () => screen.getByTestId('location').textContent ?? '';
const puts = () => backend.requests.filter((r) => r.method === 'put');
const statusPosts = () => backend.requests.filter((r) => r.method === 'post' && /\/(activar|desactivar)$/.test(r.url));
const detailGets = () => backend.requests.filter((r) => r.method === 'get' && /^\/admin\/familias\/[^/?]+$/.test(r.url));
const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function openDetail(name = 'Gómez', extra: Parameters<FakeBackend['addFamily']>[1] = {}) {
  const family = backend.addFamily(name, extra);
  backend.signIn('marcos@example.com');
  const { queryClient } = renderApp(`/admin/familias/${family.id}`);
  await screen.findByRole('heading', { level: 1, name });
  return { family, queryClient };
}

async function startEditing(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Editar nombre' }));
  return screen.getByLabelText(/Nombre de referencia/);
}

async function typeName(user: ReturnType<typeof userEvent.setup>, value: string) {
  const box = screen.getByLabelText(/Nombre de referencia/);
  await user.clear(box);
  await user.type(box, value);
}

describe('edit the name (mock backend)', () => {
  it('offers the editor only from the detail, with the current name and nothing sent yet', async () => {
    await openDetail('Gómez');
    const user = userEvent.setup();
    const box = await startEditing(user);
    expect(box).toHaveValue('Gómez');
    expect(box).toHaveAttribute('maxlength', '150');
    expect(puts()).toHaveLength(0);
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled(); // nothing changed yet
  });

  it('saves with the revision of the current detail, trimmed, with CSRF, and reports the result', async () => {
    const { family } = await openDetail('Gómez', { version: 4 });
    const user = userEvent.setup();
    await startEditing(user);
    await typeName(user, '  Gómez Rodríguez  ');
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Gómez Rodríguez' })).toBeInTheDocument();
    expect(puts()).toHaveLength(1);
    expect(puts()[0]!.url).toBe(`/admin/familias/${family.id}`);
    expect(puts()[0]!.body).toEqual({ nombreReferencia: 'Gómez Rodríguez', versionEsperada: '4' });
    expect(puts()[0]!.csrfHeader).toBeTruthy();
    expect(screen.getByText('Nombre actualizado.')).toBeInTheDocument();
    expect(screen.queryByLabelText(/Nombre de referencia/)).not.toBeInTheDocument(); // the editor is closed
    expect(backend.familyVersion(family.id)).toBe('5');
  });

  it('is allowed on an inactive family', async () => {
    await openDetail('Pérez', { activa: false });
    const user = userEvent.setup();
    await startEditing(user);
    await typeName(user, 'Pérez Gómez');
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Pérez Gómez' })).toBeInTheDocument();
    expect(screen.getByText('Inactiva')).toBeInTheDocument();
  });

  it('shows the new name in the list afterwards', async () => {
    await openDetail('Gómez');
    const user = userEvent.setup();
    await startEditing(user);
    await typeName(user, 'Gómez Nuevo');
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    await screen.findByRole('heading', { level: 1, name: 'Gómez Nuevo' });
    await user.click(screen.getByRole('link', { name: 'Volver al listado' }));
    expect(await screen.findByRole('link', { name: 'Gómez Nuevo' })).toBeInTheDocument();
  });

  it.each(['', '   '])('does not send an empty name («%s»)', async (value) => {
    await openDetail();
    const user = userEvent.setup();
    const box = await startEditing(user);
    await user.clear(box);
    if (value) await user.type(box, value);
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    expect(await screen.findByText('Ingresá un nombre de referencia.')).toBeInTheDocument();
    expect(box).toHaveAttribute('aria-invalid', 'true');
    expect(puts()).toHaveLength(0);
  });

  it('rejects a name longer than 150 characters without sending it', async () => {
    await openDetail();
    const user = userEvent.setup();
    const box = await startEditing(user);
    box.removeAttribute('maxlength');
    await user.clear(box);
    await user.click(box);
    await user.paste('x'.repeat(151));
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    expect(await screen.findByText('Máximo 150 caracteres.')).toBeInTheDocument();
    expect(puts()).toHaveLength(0);
  });

  it('disables the button while saving so a double click or Enter cannot write twice', async () => {
    const { family } = await openDetail();
    const user = userEvent.setup();
    await startEditing(user);
    await typeName(user, 'Una sola vez');
    const held = backend.hold('put', `/admin/familias/${family.id}`);
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    const saving = await screen.findByRole('button', { name: 'Guardando…' });
    expect(saving).toBeDisabled();
    await user.click(saving);
    await user.keyboard('{Enter}');
    held.release();
    expect(await screen.findByText('Nombre actualizado.')).toBeInTheDocument();
    expect(puts()).toHaveLength(1);
  });

  it('shows a fixed message for a validation error from the backend, without echoing it, and keeps the draft', async () => {
    const { family } = await openDetail();
    backend.override('put', `/admin/familias/${family.id}`, { status: 400, body: { codigo: 'VALIDACION', mensaje: 'texto del servidor', detalles: [{ campo: 'nombreReferencia', mensaje: 'detalle del servidor' }] } });
    const user = userEvent.setup();
    await startEditing(user);
    await typeName(user, 'Borrador');
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Revisá el nombre de referencia');
    expect(alert).not.toHaveTextContent('servidor');
    expect(screen.getByLabelText(/Nombre de referencia/)).toHaveValue('Borrador');
  });

  it('explains a permission error, a missing family, a lock contention and an invalid CSRF token', async () => {
    const { family } = await openDetail();
    const url = `/admin/familias/${family.id}`;
    const user = userEvent.setup();
    await startEditing(user);
    await typeName(user, 'Borrador');
    const cases: [Parameters<FakeBackend['override']>[2], string][] = [
      [{ status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } }, 'No tenés permiso para modificar familias.'],
      [{ status: 404, body: { codigo: 'FAMILIA_NO_ENCONTRADA', mensaje: 'x' } }, 'La familia ya no existe.'],
      [{ status: 409, body: { codigo: 'CONFLICTO_CONCURRENCIA', mensaje: 'x' } }, 'Otra operación está en curso sobre esta familia'],
      [{ status: 403, body: { codigo: 'CSRF_INVALIDO', mensaje: 'x' } }, 'No pudimos validar la solicitud'],
    ];
    for (const [failure, text] of cases) {
      backend.override('put', url, failure);
      await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
      await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(text));
      expect(screen.getByLabelText(/Nombre de referencia/)).toHaveValue('Borrador');
    }
    expect(puts()).toHaveLength(cases.length); // exactly one request per manual attempt, never more
  });
});

describe('edit conflict: 409 EDICION_DESACTUALIZADA (mock backend)', () => {
  async function conflict() {
    const { family, queryClient } = await openDetail('Gómez', { version: 2 });
    const user = userEvent.setup();
    await startEditing(user);
    await typeName(user, 'Mi borrador');
    backend.changeFamilyBehindTheScenes(family.id, { nombreReferencia: 'Gómez del otro admin' }); // version 3 now
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    await screen.findByText(/Otro administrador modificó esta familia/);
    return { family, queryClient, user };
  }

  it('says that another administrator changed the record, keeps the draft and sends nothing else', async () => {
    await conflict();
    expect(screen.getByRole('alert')).toHaveTextContent('Tu borrador sigue acá, pero todavía no se guardó');
    expect(screen.getByLabelText(/Nombre de referencia/)).toHaveValue('Mi borrador');
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled(); // cannot save again over the stale revision
    expect(puts()).toHaveLength(1);
    expect(puts()[0]!.body).toEqual({ nombreReferencia: 'Mi borrador', versionEsperada: '2' });
    await wait(400);
    expect(puts()).toHaveLength(1); // nothing retried by itself
  });

  it('does not reload or change anything silently: the current data are shown only when asked for', async () => {
    const { family } = await conflict();
    const before = detailGets().length;
    expect(screen.getByRole('heading', { level: 1, name: 'Gómez' })).toBeInTheDocument(); // still the stale header
    await wait(300);
    expect(detailGets().length).toBe(before);
    expect(backend.familyVersion(family.id)).toBe('3');
  });

  it('shows the current data next to the draft when asked, still without writing', async () => {
    const { user } = await conflict();
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    const panel = await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(panel).toHaveTextContent(/Nombre vigente:\s*Gómez del otro admin/);
    expect(panel).toHaveTextContent(/Tu borrador:\s*Mi borrador/);
    expect(puts()).toHaveLength(1);
  });

  it('continues only by an explicit choice, and then sends the current revision, not before', async () => {
    const { family, user } = await conflict();
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(puts()).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: 'Seguir editando mi borrador' }));
    expect(screen.getByLabelText(/Nombre de referencia/)).toHaveValue('Mi borrador');
    expect(puts()).toHaveLength(1); // choosing to continue is not saving
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Mi borrador' })).toBeInTheDocument();
    expect(puts()).toHaveLength(2);
    expect(puts()[1]!.body).toEqual({ nombreReferencia: 'Mi borrador', versionEsperada: '3' });
    expect(backend.familyVersion(family.id)).toBe('4');
  });

  it('conflicts again if the record changes once more after the administrator chose to continue', async () => {
    const { family, user } = await conflict(); // the other administrator already moved it to revision 3
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    await screen.findByRole('group', { name: 'Datos vigentes' });
    await user.click(screen.getByRole('button', { name: 'Seguir editando mi borrador' }));
    backend.changeFamilyBehindTheScenes(family.id, { nombreReferencia: 'Otro cambio más' }); // revision 4 behind the back again
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Otro administrador modificó esta familia');
    expect(puts()).toHaveLength(2);
    expect(puts()[1]!.body).toEqual({ nombreReferencia: 'Mi borrador', versionEsperada: '3' }); // the revision the user had chosen to build on
    expect(screen.getByLabelText(/Nombre de referencia/)).toHaveValue('Mi borrador');
    expect(backend.familyVersion(family.id)).toBe('4');
    await wait(400);
    expect(puts()).toHaveLength(2); // still nothing retried or saved by itself
  });

  it('can discard the draft and keep the current data', async () => {
    const { user } = await conflict();
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    await screen.findByRole('group', { name: 'Datos vigentes' });
    await user.click(screen.getByRole('button', { name: 'Descartar mi borrador' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Gómez del otro admin' })).toBeInTheDocument();
    expect(screen.queryByLabelText(/Nombre de referencia/)).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('Mi borrador');
    expect(puts()).toHaveLength(1);
  });

  it('is stale even when the other administrator wrote the very same name', async () => {
    const { family } = await openDetail('Gómez', { version: 0 });
    const user = userEvent.setup();
    await startEditing(user);
    await typeName(user, 'Mismo nombre');
    backend.changeFamilyBehindTheScenes(family.id, { nombreReferencia: 'Mismo nombre' });
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    expect(await screen.findByText(/Otro administrador modificó esta familia/)).toBeInTheDocument();
  });

  it('keeps the draft only in memory', async () => {
    await conflict();
    expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toContain('Mi borrador');
    expect(location()).not.toContain('borrador');
  });

  it('offers a retry if the current data could not be loaded, keeping the draft', async () => {
    const { family, user } = await conflict();
    backend.override('get', `/admin/familias/${family.id}`, 'network');
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    expect(await screen.findByText(/No pudimos conectar con el servidor/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Nombre de referencia/)).toHaveValue('Mi borrador');
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    expect(await screen.findByRole('group', { name: 'Datos vigentes' })).toBeInTheDocument();
  });
});

describe('uncertain result of the edit (mock backend)', () => {
  it.each([
    ['a network failure or timeout', 'network' as const],
    ['a 500', { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'x' } }],
    ['a 503', { status: 503, body: { codigo: 'NO_DISPONIBLE', mensaje: 'x' } }],
  ])('does not claim it failed after %s, never retries by itself and keeps the draft', async (_label, failure) => {
    const { family } = await openDetail();
    backend.override('put', `/admin/familias/${family.id}`, failure);
    const user = userEvent.setup();
    await startEditing(user);
    await typeName(user, 'Incierto');
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos confirmar si el cambio se guardó');
    await wait(400);
    expect(puts()).toHaveLength(1);
    expect(screen.getByLabelText(/Nombre de referencia/)).toHaveValue('Incierto');
    expect(screen.getByRole('button', { name: 'Verificar datos vigentes' })).toBeEnabled();
  });

  it('recognises, when verifying, that the change had actually been saved', async () => {
    const { family } = await openDetail();
    backend.override('put', `/admin/familias/${family.id}`, 'network');
    const user = userEvent.setup();
    await startEditing(user);
    await typeName(user, 'Se guardó igual');
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    await screen.findByText(/No pudimos confirmar si el cambio se guardó/);
    backend.changeFamilyBehindTheScenes(family.id, { nombreReferencia: 'Se guardó igual' }); // the write did reach the server
    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));
    expect(await screen.findByText('El cambio ya figura guardado.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cerrar' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Se guardó igual' })).toBeInTheDocument();
    expect(puts()).toHaveLength(1);
  });

  it('shows the real state when verifying finds that it was not saved, and lets the user decide', async () => {
    const { family } = await openDetail();
    backend.override('put', `/admin/familias/${family.id}`, 'network');
    const user = userEvent.setup();
    await startEditing(user);
    await typeName(user, 'No llegó');
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    await screen.findByText(/No pudimos confirmar si el cambio se guardó/);
    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));
    const panel = await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(panel).toHaveTextContent(/Nombre vigente:\s*Gómez/);
    expect(panel).toHaveTextContent(/Tu borrador:\s*No llegó/);
    expect(puts()).toHaveLength(1);
  });
});

describe('unsaved changes (mock backend)', () => {
  it('warns before leaving through an in-app link and lets the user stay', async () => {
    await openDetail();
    const user = userEvent.setup();
    await startEditing(user);
    await typeName(user, 'Sin guardar');
    await user.click(screen.getByRole('link', { name: 'Volver al listado' }));

    const panel = await screen.findByRole('group', { name: 'Cambios sin guardar' });
    expect(panel).toHaveTextContent('Tenés cambios sin guardar.');
    expect(location()).toMatch(/^\/admin\/familias\/.+/);
    await user.click(within(panel).getByRole('button', { name: 'Seguir editando' }));
    expect(screen.queryByRole('group', { name: 'Cambios sin guardar' })).not.toBeInTheDocument();
    expect(screen.getByLabelText(/Nombre de referencia/)).toHaveValue('Sin guardar');
  });

  it('discards and leaves when the user says so', async () => {
    await openDetail();
    const user = userEvent.setup();
    await startEditing(user);
    await typeName(user, 'Sin guardar');
    await user.click(screen.getByRole('link', { name: 'Volver al listado' }));
    await user.click(within(await screen.findByRole('group', { name: 'Cambios sin guardar' })).getByRole('button', { name: 'Descartar y salir' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Familias' })).toBeInTheDocument();
    expect(location()).toBe('/admin/familias');
    expect(puts()).toHaveLength(0);
  });

  it('also guards the navigation menu', async () => {
    await openDetail();
    const user = userEvent.setup();
    await startEditing(user);
    await typeName(user, 'Sin guardar');
    await user.click(screen.getByRole('link', { name: 'Invitaciones' }));
    expect(await screen.findByRole('group', { name: 'Cambios sin guardar' })).toBeInTheDocument();
    expect(location()).not.toBe('/admin/invitaciones');
  });

  it('asks before cancelling an edit that has changes, and cancels silently when there are none', async () => {
    await openDetail();
    const user = userEvent.setup();
    await startEditing(user);
    await user.click(screen.getByRole('button', { name: 'Cancelar' }));
    expect(screen.queryByLabelText(/Nombre de referencia/)).not.toBeInTheDocument(); // nothing to lose

    await startEditing(user);
    await typeName(user, 'Sin guardar');
    await user.click(screen.getByRole('button', { name: 'Cancelar' }));
    const panel = await screen.findByRole('group', { name: 'Cambios sin guardar' });
    await user.click(within(panel).getByRole('button', { name: 'Descartar cambios' }));
    expect(screen.queryByLabelText(/Nombre de referencia/)).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('Sin guardar');
  });

  it('does not interfere with navigation when there is nothing to lose', async () => {
    await openDetail();
    const user = userEvent.setup();
    await startEditing(user);
    await user.click(screen.getByRole('link', { name: 'Volver al listado' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Familias' })).toBeInTheDocument();
  });

  it('asks the browser to confirm closing or reloading the tab only while there are unsaved changes', async () => {
    await openDetail();
    const user = userEvent.setup();
    const probe = () => {
      const event = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(event);
      return event.defaultPrevented;
    };
    expect(probe()).toBe(false);
    await startEditing(user);
    expect(probe()).toBe(false); // opened but unchanged
    await typeName(user, 'Sin guardar');
    expect(probe()).toBe(true);
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    await screen.findByText('Nombre actualizado.');
    expect(probe()).toBe(false);
  });
});

describe('activate and deactivate (mock backend)', () => {
  it('explains the consequences of deactivating before doing anything, and cancelling sends nothing', async () => {
    await openDetail('Gómez', { version: 6 });
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Desactivar familia' }));
    const panel = await screen.findByRole('group', { name: 'Confirmar desactivación' });
    expect(panel).toHaveTextContent('dejarán de poder ingresar al portal');
    expect(panel).toHaveTextContent('No se elimina su historial');
    expect(panel).toHaveTextContent('no se desactiva a sus deportistas');
    expect(panel).toHaveTextContent('tutores ni vínculos');
    expect(panel).toHaveTextContent('podés reactivarla');
    expect(statusPosts()).toHaveLength(0);
    await user.click(within(panel).getByRole('button', { name: 'Cancelar' }));
    expect(screen.queryByRole('group', { name: 'Confirmar desactivación' })).not.toBeInTheDocument();
    expect(statusPosts()).toHaveLength(0);
  });

  it('deactivates with the revision of the current detail and reports it', async () => {
    const { family } = await openDetail('Gómez', { version: 6 });
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Desactivar familia' }));
    await user.click(await screen.findByRole('button', { name: 'Sí, desactivar' }));

    expect(await screen.findByText('Inactiva')).toBeInTheDocument();
    expect(statusPosts()).toHaveLength(1);
    expect(statusPosts()[0]!.url).toBe(`/admin/familias/${family.id}/desactivar`);
    expect(statusPosts()[0]!.body).toEqual({ versionEsperada: '6' });
    expect(statusPosts()[0]!.csrfHeader).toBeTruthy();
    expect(screen.getByText('La familia quedó desactivada.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Activar familia' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Desactivar familia' })).not.toBeInTheDocument();
    expect(backend.familyVersion(family.id)).toBe('7');
  });

  it('activates after explaining what changes and what does not', async () => {
    const { family } = await openDetail('Pérez', { activa: false, version: 1 });
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Activar familia' }));
    const panel = await screen.findByRole('group', { name: 'Confirmar activación' });
    expect(panel).toHaveTextContent('podrán volver a ingresar al portal');
    expect(panel).toHaveTextContent('no cambian');
    await user.click(within(panel).getByRole('button', { name: 'Sí, activar' }));
    expect(await screen.findByText('Activa')).toBeInTheDocument();
    expect(statusPosts()[0]!.url).toBe(`/admin/familias/${family.id}/activar`);
    expect(statusPosts()[0]!.body).toEqual({ versionEsperada: '1' });
    expect(screen.getByText('La familia quedó activa.')).toBeInTheDocument();
  });

  it('writes once even if the confirmation is clicked twice', async () => {
    const { family } = await openDetail();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Desactivar familia' }));
    const held = backend.hold('post', `/admin/familias/${family.id}/desactivar`);
    await user.click(await screen.findByRole('button', { name: 'Sí, desactivar' }));
    const busy = await screen.findByRole('button', { name: 'Desactivando…' });
    expect(busy).toBeDisabled();
    await user.click(busy);
    held.release();
    expect(await screen.findByText('La familia quedó desactivada.')).toBeInTheDocument();
    expect(statusPosts()).toHaveLength(1);
  });

  it('uses the revision it had when the confirmation was opened: a stale one is rejected, nothing is retried', async () => {
    const { family } = await openDetail('Gómez', { version: 2 });
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Desactivar familia' }));
    backend.changeFamilyBehindTheScenes(family.id, { activa: false }); // the other administrator deactivated it first
    await user.click(await screen.findByRole('button', { name: 'Sí, desactivar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Otro administrador modificó esta familia');
    expect(statusPosts()).toHaveLength(1);
    expect(statusPosts()[0]!.body).toEqual({ versionEsperada: '2' });
    await wait(400);
    expect(statusPosts()).toHaveLength(1);
    expect(screen.getByText('Activa')).toBeInTheDocument(); // the screen still shows what the user decided on

    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    expect(await screen.findByText('Inactiva')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Activar familia' })).toBeInTheDocument();
    expect(statusPosts()).toHaveLength(1); // reloading is not writing
  });

  it.each([
    ['a network failure or timeout', 'network' as const],
    ['a 500', { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'x' } }],
  ])('does not claim it failed after %s and never retries by itself', async (_label, failure) => {
    const { family } = await openDetail();
    backend.override('post', `/admin/familias/${family.id}/desactivar`, failure);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Desactivar familia' }));
    await user.click(await screen.findByRole('button', { name: 'Sí, desactivar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos confirmar si el cambio se aplicó');
    await wait(400);
    expect(statusPosts()).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Verificar datos vigentes' })).toBeEnabled();
  });

  it('explains a permission error and a lock contention', async () => {
    const { family } = await openDetail();
    const url = `/admin/familias/${family.id}/desactivar`;
    const user = userEvent.setup();
    backend.override('post', url, { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } });
    await user.click(screen.getByRole('button', { name: 'Desactivar familia' }));
    await user.click(await screen.findByRole('button', { name: 'Sí, desactivar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No tenés permiso para modificar familias.');
    expect(statusPosts()).toHaveLength(1);
  });

  it('blocks the status controls while the name is being edited, so two writes cannot compete', async () => {
    await openDetail();
    const user = userEvent.setup();
    await startEditing(user);
    expect(screen.getByRole('button', { name: 'Desactivar familia' })).toBeDisabled();
    expect(statusPosts()).toHaveLength(0);
  });
});

describe('permissions and privacy (mock backend)', () => {
  it('keeps a family user out and never writes', async () => {
    const family = backend.addFamily('Gómez');
    backend.signIn('lucia@example.com');
    renderApp(`/admin/familias/${family.id}`);
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Editar nombre|desactivar|activar/i })).not.toBeInTheDocument();
    expect(backend.requests.filter((r) => r.method !== 'get' && r.url.startsWith('/admin/familias'))).toHaveLength(0);
  });

  it('keeps a password-only admin session out and never writes', async () => {
    const family = backend.addFamily('Gómez');
    backend.signIn('marcos@example.com', true);
    renderApp(`/admin/familias/${family.id}`);
    expect(await screen.findByRole('heading', { name: 'Verificación en dos pasos' })).toBeInTheDocument();
    expect(backend.requests.filter((r) => r.method !== 'get' && r.url.startsWith('/admin/familias'))).toHaveLength(0);
  });

  it('keeps the draft and the family name out of the address, storage and console', async () => {
    const logged: unknown[][] = [];
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    try {
      await openDetail('Reservada');
      const user = userEvent.setup();
      await startEditing(user);
      await typeName(user, 'Borrador Reservado');
      expect(location()).not.toMatch(/Reservad|Borrador/);
      expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toMatch(/Reservad|Borrador/);
    } finally {
      logged.push(...spies.flatMap((spy) => spy.mock.calls));
      spies.forEach((spy) => spy.mockRestore());
    }
    expect(logged.flat().join(' ')).not.toMatch(/Reservad|Borrador/);
  });

  describe('identity change', () => {
    async function switchIdentity(queryClient: QueryClient, email: string) {
      backend.signIn(email);
      await act(async () => {
        await queryClient.invalidateQueries({ queryKey: sessionKey });
      });
    }

    it('drops the draft and the open confirmation when the session now belongs to another administrator', async () => {
      backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: 'otra.admin@example.com', mfaEnrolado: true });
      const { queryClient } = await openDetail('Gómez');
      const user = userEvent.setup();
      await startEditing(user);
      await typeName(user, 'Borrador del primero');
      expect(screen.getByLabelText(/Nombre de referencia/)).toHaveValue('Borrador del primero');

      await switchIdentity(queryClient, 'otra.admin@example.com');
      await screen.findByRole('heading', { level: 1, name: 'Gómez' });
      await waitFor(() => expect(screen.queryByLabelText(/Nombre de referencia/)).not.toBeInTheDocument());
      expect(document.body.textContent).not.toContain('Borrador del primero');
      expect(screen.getByRole('button', { name: 'Editar nombre' })).toBeInTheDocument();
      expect(puts()).toHaveLength(0);
    });

    it('does not warn about unsaved changes of someone who is no longer the user', async () => {
      backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: 'otra.admin@example.com', mfaEnrolado: true });
      const { queryClient } = await openDetail('Gómez');
      const user = userEvent.setup();
      await startEditing(user);
      await typeName(user, 'Borrador del primero');
      await switchIdentity(queryClient, 'otra.admin@example.com');
      await screen.findByRole('button', { name: 'Editar nombre' });
      const event = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
    });

    it('sends the user to the login when the session expires while editing, and the draft is gone', async () => {
      await openDetail('Gómez');
      const user = userEvent.setup();
      await startEditing(user);
      await typeName(user, 'Borrador vencido');
      backend.expireSession();
      await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
      expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
      expect(document.body.textContent).not.toContain('Borrador vencido');
    });
  });
});
