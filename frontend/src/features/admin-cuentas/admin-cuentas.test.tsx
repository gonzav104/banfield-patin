import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';

// MOCK backend (src/test/fakeBackend.ts mirrors CuentasFamiliaAdminController). Real-backend evidence: e2e/cuentas-familia.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  // jsdom has no native <dialog> modal API.
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', ''); } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open'); } });
});
afterEach(() => {
  cleanup();
  Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal');
  Reflect.deleteProperty(HTMLDialogElement.prototype, 'close');
});

const accountPosts = () => backend.requests.filter((r) => r.method === 'post' && /\/cuentas\/[^/]+\/(desactivar|reactivar)$/.test(r.url));

async function openFamily(options: { enabled?: boolean; extraPending?: number } = {}) {
  const family = backend.addFamily('Gómez');
  backend.accountsManagement.enabled = options.enabled ?? true;
  backend.addUser({ id: 'u-madre', nombre: 'Laura', apellido: 'Gómez', email: 'laura@example.test', password: 'x', rol: 'FAMILIA', familiaId: family.id, ultimoAccesoEn: '2026-11-20T10:00:00Z' });
  backend.addUser({ id: 'u-padre', nombre: 'Martín', apellido: 'Gómez', email: 'martin@example.test', password: 'x', rol: 'FAMILIA', familiaId: family.id, activo: false });
  backend.addUser({ id: 'u-ajena', nombre: 'Otra', apellido: 'Persona', email: 'otra@example.test', password: 'x', rol: 'FAMILIA', familiaId: 'familia-1' });
  backend.seedInvitation({ status: 'PENDIENTE', family: 'Gómez', email: 'abuela@example.test' });
  backend.seedInvitation({ status: 'USADA', family: 'Gómez', email: 'usada@example.test' });
  for (let i = 0; i < (options.extraPending ?? 0); i += 1) backend.seedInvitation({ status: 'PENDIENTE', family: 'Gómez', email: `extra${i}@example.test` });
  backend.signIn('marcos@example.com');
  renderApp(`/admin/familias/${family.id}`);
  const list = await screen.findByRole('list', { name: 'Cuentas de acceso' });
  return { family, list };
}

describe('family access accounts (mock backend)', () => {
  it('shows only the accounts of this family with plain-language status and the unused invitations', async () => {
    const { list } = await openFamily();
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(within(items[0]!).getByText('Laura Gómez')).toBeInTheDocument();
    expect(within(items[0]!).getByText('Puede ingresar')).toBeInTheDocument();
    expect(within(items[0]!).getByText(/Último ingreso:/)).toBeInTheDocument();
    expect(within(items[1]!).getByText('Sin acceso')).toBeInTheDocument();
    expect(within(items[1]!).getByText('Todavía no ingresó.')).toBeInTheDocument();
    expect(screen.queryByText('Otra Persona')).not.toBeInTheDocument();
    const invitations = screen.getByRole('list', { name: 'Invitaciones sin usar' });
    expect(within(invitations).getByText('abuela@example.test')).toBeInTheDocument();
    expect(within(invitations).queryByText('usada@example.test')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Invitar a una persona' })).toHaveAttribute('href', '/admin/invitaciones');
  });

  it('says when the family has more unused invitations than the ones shown', async () => {
    await openFamily({ extraPending: 20 });
    expect(within(screen.getByRole('list', { name: 'Invitaciones sin usar' })).getAllByRole('listitem')).toHaveLength(20);
    expect(screen.getByText(/Se muestran las 20 más recientes de 21/)).toBeInTheDocument();
  });

  it('removes access after an explicit confirmation that explains the effect, with CSRF, and reports it', async () => {
    const { list } = await openFamily();
    const user = userEvent.setup();
    await user.click(within(list).getByRole('button', { name: /Quitar acceso de Laura Gómez/ }));
    const dialog = screen.getByRole('dialog', { name: 'Quitar el acceso a Laura Gómez' });
    expect(within(dialog).getByText(/no va a poder ingresar más/)).toBeInTheDocument();
    expect(within(dialog).getByText(/No se borra ningún dato de la familia/)).toBeInTheDocument();
    expect(accountPosts()).toHaveLength(0);

    await user.click(within(dialog).getByRole('button', { name: 'Quitar acceso' }));

    expect(await screen.findByText('Listo: Laura Gómez ya no puede ingresar.')).toBeInTheDocument();
    expect(accountPosts()).toHaveLength(1);
    expect(accountPosts()[0]!.url).toMatch(/\/cuentas\/u-madre\/desactivar$/);
    expect(accountPosts()[0]!.csrfHeader).not.toBeNull();
    await waitFor(() => expect(within(screen.getByRole('list', { name: 'Cuentas de acceso' })).getAllByText('Sin acceso')).toHaveLength(2));
  });

  it('gives access back to a deactivated account', async () => {
    const { list } = await openFamily();
    const user = userEvent.setup();
    await user.click(within(list).getByRole('button', { name: /Devolver acceso de Martín Gómez/ }));
    const dialog = screen.getByRole('dialog', { name: 'Devolver el acceso a Martín Gómez' });
    expect(within(dialog).getByText(/va a tener que ingresar de nuevo/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Devolver acceso' }));
    expect(await screen.findByText('Listo: Martín Gómez puede volver a ingresar.')).toBeInTheDocument();
    expect(accountPosts()[0]!.url).toMatch(/\/cuentas\/u-padre\/reactivar$/);
  });

  it('cancelling the confirmation sends nothing', async () => {
    const { list } = await openFamily();
    const user = userEvent.setup();
    await user.click(within(list).getByRole('button', { name: /Quitar acceso de Laura Gómez/ }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancelar' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(accountPosts()).toHaveLength(0);
  });

  it('while management is off in this environment it says so and offers no action', async () => {
    const { list } = await openFamily({ enabled: false });
    expect(screen.getByText(/todavía no está habilitado en este entorno/)).toBeInTheDocument();
    expect(within(list).queryByRole('button')).not.toBeInTheDocument();
  });

  it('an account that left the family is reported without retry and the list is refreshed', async () => {
    const { list } = await openFamily();
    const user = userEvent.setup();
    await user.click(within(list).getByRole('button', { name: /Quitar acceso de Laura Gómez/ }));
    backend.addUser({ id: 'u-madre', nombre: 'Laura', apellido: 'Gómez', email: 'laura@example.test', password: 'x', rol: 'FAMILIA', familiaId: 'familia-1' });
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Quitar acceso' }));
    expect(await screen.findByText(/Esa cuenta ya no está en esta familia/)).toBeInTheDocument();
    expect(within(screen.getByRole('dialog')).queryByRole('button', { name: /Quitar acceso|Volver a intentar/ })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('Laura Gómez', { selector: 'p' })).not.toBeInTheDocument());
  });
});
