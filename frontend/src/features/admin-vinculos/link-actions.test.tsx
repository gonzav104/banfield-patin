import { MemoryRouter, useNavigate } from 'react-router-dom';
import { App } from '@/app/App';
import { AppProviders, createQueryClient } from '@/app/providers';
import { http } from '@/lib/http';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { sessionKey } from '@/features/auth/session';
import { athleteLinksKey, familyAthletesKey } from './hooks';
import type { FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';
import { REVISION, seedMixedLinks } from './links-fixtures';

let backend: FakeBackend;
beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  // jsdom has no top layer; real modal focus, Escape and keyboard behavior are checked in Playwright.
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) {
    this.removeAttribute('open');
  } });
});
afterEach(() => {
  cleanup();
  Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal');
  Reflect.deleteProperty(HTMLDialogElement.prototype, 'close');
});

const posts = () => backend.requests.filter((r) => r.method === 'post' && /\/(revocar|principal)$/.test(r.url));
const endpoint = (family: string, athlete: string, action = 'revocar') => `/admin/familias/${family}/deportistas/${athlete}/${action}`;
const item = (name: string) => screen.getByRole('link', { name }).closest('li')!;
const dialog = () => within(screen.getByRole('dialog'));
function open() {
  const data = seedMixedLinks(backend);
  backend.signIn('marcos@example.com');
  const app = renderApp(`/admin/deportistas/${data.athlete.id}/vinculos`);
  return { ...data, ...app, user: userEvent.setup() };
}
async function choose(user: ReturnType<typeof userEvent.setup>, family: string, action = 'Revocar vínculo') {
  const button = within(item(family)).getByRole('button', { name: action });
  await waitFor(() => expect(button).toBeEnabled());
  await user.click(button);
  return button;
}

describe('administrative link actions (mock backend)', () => {
  it.each(['Revocar vínculo', 'Cambiar principal'])('requires an accessible explicit confirmation and cancel sends nothing: %s', async (action) => {
    const { user } = open();
    await screen.findByRole('link', { name: 'Familia Pérez' });
    const trigger = await choose(user, 'Familia Pérez', action);
    const modal = screen.getByRole('dialog', { name: action });
    expect(modal).toHaveAttribute('aria-describedby');
    expect(dialog().getByRole('button', { name: 'Cancelar' })).toHaveFocus();
    expect(posts()).toHaveLength(0);
    await user.click(dialog().getByRole('button', { name: 'Cancelar' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    expect(posts()).toHaveLength(0);
  });

  it('revokes the principal, preserves history and does not promote the other active family', async () => {
    const { user, athlete, gomez } = open();
    await screen.findByRole('link', { name: 'Familia Gómez' });
    await choose(user, 'Familia Gómez');
    expect(dialog().getByText(/retira el acceso.*conserva el historial.*no.*automáticamente/i)).toBeInTheDocument();
    await user.click(dialog().getByRole('button', { name: 'Confirmar revocación' }));
    await screen.findByText('Vínculo revocado.');
    expect(within(item('Familia Gómez')).getByText('Vínculo revocado')).toBeInTheDocument();
    expect(screen.queryByText('Familia principal')).not.toBeInTheDocument();
    expect(screen.getByText(/Ninguna de las familias activas/)).toBeInTheDocument();
    expect(posts()).toHaveLength(1);
    expect(posts()[0]).toMatchObject({ url: endpoint(gomez.id, athlete.id), body: { versionEsperada: String(REVISION) } });
    expect(posts()[0]!.csrfHeader).toBeTruthy();
    expect(document.body.innerHTML).not.toContain(String(REVISION));
  });

  it('changes principal between active families without revoking either', async () => {
    const { user, athlete, perez } = open();
    await screen.findByRole('link', { name: 'Familia Pérez' });
    await choose(user, 'Familia Pérez', 'Cambiar principal');
    expect(dialog().getByText(/no revoca.*ni transfiere automáticamente/i)).toBeInTheDocument();
    await user.click(dialog().getByRole('button', { name: 'Confirmar cambio de principal' }));
    await screen.findByText('Familia principal actualizada.');
    expect(within(item('Familia Pérez')).getByText('Familia principal')).toBeInTheDocument();
    expect(within(item('Familia Gómez')).queryByText('Familia principal')).not.toBeInTheDocument();
    expect(screen.getAllByText('Vínculo activo')).toHaveLength(2);
    expect(posts()[0]).toMatchObject({ url: endpoint(perez.id, athlete.id, 'principal'), body: { versionEsperada: String(REVISION) } });
  });

  it('does not offer actions for previous links or principal selection for disabled families', async () => {
    const { perez, queryClient } = open();
    perez.activa = false;
    await act(async () => { await queryClient.invalidateQueries({ queryKey: ['admin', 'families'] }); });
    await screen.findByRole('link', { name: 'Familia Ruiz' });
    expect(within(item('Familia Ruiz')).queryByRole('button')).not.toBeInTheDocument();
    await waitFor(() => expect(within(item('Familia Pérez')).getByRole('button', { name: 'Cambiar principal' })).toBeDisabled());
    expect(within(item('Familia Pérez')).getByRole('button', { name: 'Revocar vínculo' })).toBeEnabled();
    expect(within(item('Familia Gómez')).queryByRole('button', { name: 'Cambiar principal' })).not.toBeInTheDocument();
  });

  it('pins the revision at dialog opening despite a background refresh; stale requires a new confirmation', async () => {
    const { user, athlete, perezLink, queryClient } = open();
    await screen.findByRole('link', { name: 'Familia Pérez' });
    await choose(user, 'Familia Pérez');
    backend.changeAdminLinkBehindTheScenes(perezLink.id, {});
    await act(async () => { await queryClient.refetchQueries({ queryKey: athleteLinksKey(athlete.id) }); });
    await user.click(dialog().getByRole('button', { name: 'Confirmar revocación' }));
    await screen.findByText(/Los vínculos cambiaron/);
    expect(posts()[0]!.body).toEqual({ versionEsperada: String(REVISION) });
    expect(dialog().queryByRole('button', { name: 'Confirmar revocación' })).not.toBeInTheDocument();
    await user.click(dialog().getByRole('button', { name: 'Consultar vínculos vigentes' }));
    await user.click(await screen.findByRole('button', { name: 'Revisar nueva confirmación' }));
    expect(posts()).toHaveLength(1);
    await user.click(dialog().getByRole('button', { name: 'Confirmar revocación' }));
    await screen.findByText('Vínculo revocado.');
    expect(posts()[1]!.body).toEqual({ versionEsperada: String(REVISION + 1) });
  });

  it.each([
    ['CONFLICTO_CONCURRENCIA', /Otra operación está en curso/],
    ['VINCULO_NO_ACTIVO', /El vínculo ya no está activo/],
    ['FAMILIA_INACTIVA', /La familia está inactiva/],
  ])('shows a fixed specific message, no success or automatic retries for %s', async (code, message) => {
    const { user, perez, athlete } = open();
    backend.override('post', endpoint(perez.id, athlete.id, 'principal'), { status: 409, body: { codigo: code, mensaje: 'SENSITIVE BACKEND TEXT' } });
    await screen.findByRole('link', { name: 'Familia Pérez' });
    await choose(user, 'Familia Pérez', 'Cambiar principal');
    await user.click(dialog().getByRole('button', { name: 'Confirmar cambio de principal' }));
    await screen.findByText(message);
    expect(posts()).toHaveLength(1);
    expect(screen.queryByText('Familia principal actualizada.')).not.toBeInTheDocument();
    expect(document.body.innerHTML).not.toContain('SENSITIVE BACKEND TEXT');
  });

  it.each(['network', 500, 503] as const)('an uncertain %s result is not retried and a later read does not claim success', async (failure) => {
    const { user, perez, athlete } = open();
    backend.override('post', endpoint(perez.id, athlete.id), failure === 'network' ? 'network' : { status: failure, body: {} });
    await screen.findByRole('link', { name: 'Familia Pérez' });
    await choose(user, 'Familia Pérez');
    await user.click(dialog().getByRole('button', { name: 'Confirmar revocación' }));
    await screen.findByText(/No podemos confirmar el resultado/);
    expect(posts()).toHaveLength(1);
    await user.click(dialog().getByRole('button', { name: 'Consultar vínculos vigentes' }));
    await screen.findByText(/La consulta no confirma el resultado de la petición anterior/);
    expect(posts()).toHaveLength(1);
    expect(screen.queryByText('Vínculo revocado.')).not.toBeInTheDocument();
    expect(within(item('Familia Pérez')).getByText('Vínculo activo')).toBeInTheDocument();
  });

  it('keeps uncertainty after a read shows the operation applied', async () => {
    const { user, athlete, perez, perezLink } = open();
    backend.override('post', endpoint(perez.id, athlete.id), 'network');
    await screen.findByRole('link', { name: 'Familia Pérez' });
    await choose(user, 'Familia Pérez');
    await user.click(dialog().getByRole('button', { name: 'Confirmar revocación' }));
    await screen.findByText(/No podemos confirmar el resultado/);
    backend.changeAdminLinkBehindTheScenes(perezLink.id, { estado: 'REVOCADO' });
    await user.click(dialog().getByRole('button', { name: 'Consultar vínculos vigentes' }));
    await screen.findByText(/La consulta no confirma el resultado de la petición anterior/);
    expect(within(item('Familia Pérez')).getByText('Vínculo revocado')).toBeInTheDocument();
    expect(dialog().queryByRole('button', { name: 'Revisar nueva confirmación' })).not.toBeInTheDocument();
    expect(screen.queryByText('Vínculo revocado.')).not.toBeInTheDocument();
  });

  it('does not reopen confirmation if consulting current links fails', async () => {
    const { user, athlete, perez } = open();
    backend.override('post', endpoint(perez.id, athlete.id), 'network');
    await screen.findByRole('link', { name: 'Familia Pérez' });
    await choose(user, 'Familia Pérez');
    await user.click(dialog().getByRole('button', { name: 'Confirmar revocación' }));
    await screen.findByText(/No podemos confirmar el resultado/);
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, { status: 503, body: {} });
    await user.click(dialog().getByRole('button', { name: 'Consultar vínculos vigentes' }));
    await screen.findByText(/No pudimos consultar los vínculos vigentes/);
    expect(dialog().queryByRole('button', { name: 'Revisar nueva confirmación' })).not.toBeInTheDocument();
    expect(posts()).toHaveLength(1);
  });

  it('blocks double-clicks and other row actions while a POST is pending', async () => {
    const { user, athlete, perez } = open();
    const held = backend.hold('post', endpoint(perez.id, athlete.id));
    await screen.findByRole('link', { name: 'Familia Pérez' });
    await choose(user, 'Familia Pérez');
    const confirm = dialog().getByRole('button', { name: 'Confirmar revocación' });
    fireEvent.click(confirm); fireEvent.click(confirm);
    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(within(item('Familia Gómez')).getByRole('button', { name: 'Revocar vínculo' })).toBeDisabled();
    expect(dialog().getByRole('button', { name: 'Cancelar' })).toBeDisabled();
    held.release();
    await screen.findByText('Vínculo revocado.');
    expect(posts()).toHaveLength(1);
  });

  it('refreshes affected family queries and counters after a confirmed principal change', async () => {
    const { user, athlete, perez, gomez, queryClient } = open();
    queryClient.setQueryData(familyAthletesKey(gomez.id), ['old']);
    queryClient.setQueryData(familyAthletesKey(perez.id), ['old']);
    queryClient.setQueryData(['admin', 'families', 'list', {}], ['old']);
    await screen.findByRole('link', { name: 'Familia Pérez' });
    await choose(user, 'Familia Pérez', 'Cambiar principal');
    await user.click(dialog().getByRole('button', { name: 'Confirmar cambio de principal' }));
    await screen.findByText('Familia principal actualizada.');
    expect(queryClient.getQueryState(familyAthletesKey(gomez.id))?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(familyAthletesKey(perez.id))?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(['admin', 'families', 'list', {}])?.isInvalidated).toBe(true);
    expect(queryClient.getQueryData(athleteLinksKey(athlete.id))).toMatchObject({ versionVinculos: String(REVISION + 1) });
  });

  it('drops a late successful write after identity changes, without repopulating private caches', async () => {
    const { user, athlete, perez, queryClient } = open();
    backend.addUser({ ...admin, id: 'u-other', email: 'other@example.com' });
    const held = backend.hold('post', endpoint(perez.id, athlete.id));
    await screen.findByRole('link', { name: 'Familia Pérez' });
    await choose(user, 'Familia Pérez');
    await user.click(dialog().getByRole('button', { name: 'Confirmar revocación' }));
    await waitFor(() => expect(posts()).toHaveLength(1));
    backend.signIn('other@example.com');
    backend.override('get', `/admin/deportistas/${athlete.id}`, { status: 404, body: {} }, { once: false });
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, { status: 404, body: {} }, { once: false });
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await screen.findByRole('heading', { name: 'No encontramos este deportista' });
    const before = backend.requests.length;
    held.release();
    await act(async () => { await new Promise((r) => setTimeout(r, 50)); });
    expect(screen.queryByText('Vínculo revocado.')).not.toBeInTheDocument();
    expect(queryClient.getQueryData(athleteLinksKey(athlete.id))).toBeUndefined();
    expect(backend.requests.slice(before).filter((r) => r.url.startsWith('/admin/'))).toHaveLength(0);
  });
  it('closing an uncertain dialog retains the verification requirement before any new write', async () => {
    const { user, athlete, perez } = open();
    backend.override('post', endpoint(perez.id, athlete.id), 'network');
    await screen.findByRole('link', { name: 'Familia Pérez' });
    await choose(user, 'Familia Pérez');
    await user.click(dialog().getByRole('button', { name: 'Confirmar revocación' }));
    await screen.findByText(/No podemos confirmar el resultado/);
    await user.click(dialog().getByRole('button', { name: 'Cancelar' }));
    expect(within(item('Familia Pérez')).getByRole('button', { name: 'Revocar vínculo' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Consultar vínculos vigentes' }));
    await waitFor(() => expect(within(item('Familia Pérez')).getByRole('button', { name: 'Revocar vínculo' })).toBeEnabled());
    expect(posts()).toHaveLength(1);
  });

  it.each([
    { familiaId: 'different-family' },
    { deportistaId: 'different-athlete' },
    { estado: 'ACTIVO' },
    { versionVinculos: 123 },
  ])('a malformed or misdirected POST answer cannot announce success: %j', async (wrong) => {
    const { user, athlete, perez, perezLink } = open();
    const body = { vinculoId: perezLink.id, familiaId: perez.id, familiaNombre: perez.nombreReferencia,
      deportistaId: athlete.id, deportistaNombre: athlete.nombre, deportistaApellido: athlete.apellido,
      deportistaActivo: true, estado: 'REVOCADO', esPrincipal: false, versionVinculos: String(REVISION + 1), ...wrong };
    backend.override('post', endpoint(perez.id, athlete.id), { status: 200, body });
    await screen.findByRole('link', { name: 'Familia Pérez' });
    await choose(user, 'Familia Pérez');
    await user.click(dialog().getByRole('button', { name: 'Confirmar revocación' }));
    await screen.findByText(/No podemos confirmar el resultado/);
    expect(screen.queryByText('Vínculo revocado.')).not.toBeInTheDocument();
    expect(posts()).toHaveLength(1);
  });

  it('personal edits do not change the separate aggregate link revision', async () => {
    const { user, athlete, queryClient } = open();
    await screen.findByRole('link', { name: 'Familia Pérez' });
    athlete.version += 1;
    await act(async () => { await queryClient.refetchQueries({ queryKey: athleteLinksKey(athlete.id) }); });
    await choose(user, 'Familia Pérez');
    await user.click(dialog().getByRole('button', { name: 'Confirmar revocación' }));
    await screen.findByText('Vínculo revocado.');
    expect(posts()[0]!.body).toEqual({ versionEsperada: String(REVISION) });
  });

  it('cycles Tab and Shift+Tab inside the confirmation', async () => {
    const { user } = open();
    await screen.findByRole('link', { name: 'Familia Pérez' });
    await choose(user, 'Familia Pérez');
    const cancel = dialog().getByRole('button', { name: 'Cancelar' });
    const confirm = dialog().getByRole('button', { name: 'Confirmar revocación' });
    fireEvent.keyDown(cancel, { key: 'Tab' });
    expect(confirm).toHaveFocus();
    fireEvent.keyDown(confirm, { key: 'Tab', shiftKey: true });
    expect(cancel).toHaveFocus();
  });

  it.each(['Revocar vínculo', 'Cambiar principal'])('keeps the pending operation locked after leaving and returning: %s', async (action) => {
    const data = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    const queryClient = createQueryClient();
    let go: (path: string) => void = () => undefined;
    function NavigationProbe() {
      const navigate = useNavigate();
      go = (path: string) => { void navigate(path); };
      return null;
    }
    const path = `/admin/deportistas/${data.athlete.id}/vinculos`;
    render(<AppProviders client={queryClient}><MemoryRouter initialEntries={[path]}><App /><NavigationProbe /></MemoryRouter></AppProviders>);
    const user = userEvent.setup();
    const adapter = http.defaults.adapter;
    if (typeof adapter !== 'function') throw new Error('Expected the installed fake adapter');
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    let attempts = 0;
    // Delay BEFORE the fake handles the POST: hold() only delays delivery, after it already committed.
    http.defaults.adapter = async (config) => {
      if (config.method === 'post' && /\/(revocar|principal)$/.test(config.url ?? '')) {
        attempts += 1;
        if (attempts === 1) await gate;
      }
      return adapter(config);
    };
    try {
      await screen.findByRole('link', { name: 'Familia Pérez' });
      await choose(user, 'Familia Pérez', action);
      await user.click(dialog().getByRole('button', { name: /^Confirmar/ }));
      await waitFor(() => expect(attempts).toBe(1));
      expect(posts()).toHaveLength(0); // The first request has not reached the server yet.
      act(() => { go('/admin/deportistas'); });
      await screen.findByRole('heading', { name: 'Deportistas' });
      act(() => { go(path); });
      await screen.findByRole('link', { name: 'Familia Pérez' });
      await act(async () => {
        await queryClient.refetchQueries({ queryKey: ['admin', 'families', 'link-eligibility'] });
        await new Promise((resolve) => setTimeout(resolve, 0));
      });
      const retry = within(item('Familia Pérez')).getByRole('button', { name: action });
      if (!retry.hasAttribute('disabled')) {
        await user.click(retry);
        await user.click(dialog().getByRole('button', { name: /^Confirmar/ }));
      }
      expect(attempts).toBe(1);
      expect(retry).toBeDisabled();
    } finally {
      release();
      await waitFor(() => expect(queryClient.isMutating()).toBe(0));
      http.defaults.adapter = adapter;
    }
    expect(posts()).toHaveLength(1);
    expect(within(item('Familia Pérez')).getByText(action === 'Revocar vínculo' ? 'Vínculo revocado' : 'Familia principal')).toBeInTheDocument();
    expect(screen.queryByText(action === 'Revocar vínculo' ? 'Vínculo revocado.' : 'Familia principal actualizada.')).not.toBeInTheDocument();
  });

});
