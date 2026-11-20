import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { sessionKey } from '@/features/auth/session';
import type { FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';

let backend: FakeBackend;
beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  // jsdom has no top layer; real modal focus, Escape and keyboard behavior are checked in Playwright.
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', ''); } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open'); } });
});
afterEach(() => {
  cleanup();
  Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal');
  Reflect.deleteProperty(HTMLDialogElement.prototype, 'close');
});

const LIST_URL = '/admin/temporadas?page=0&size=100';
const writes = () => backend.requests.filter((r) => r.method === 'post' && r.url.startsWith('/admin/temporadas'));
const activations = () => writes().filter((r) => r.url.endsWith('/activar'));
const creations = () => writes().filter((r) => r.url === '/admin/temporadas');
const listReads = () => backend.requests.filter((r) => r.method === 'get' && r.url === LIST_URL).length;
const dialog = () => within(screen.getByRole('dialog'));
const rowOf = (year: number) => screen.getByText(String(year), { selector: 'span' }).closest('li')!;
const activateButton = (year: number) => screen.getByRole('button', { name: `Activar la temporada ${year}` });
function open(path = '/admin/temporadas') {
  backend.signIn('marcos@example.com');
  return { ...renderApp(path), user: userEvent.setup() };
}
async function ready(year: number) {
  await screen.findByText(String(year), { selector: 'span' });
}
async function openActivation(user: ReturnType<typeof userEvent.setup>, year: number) {
  const button = activateButton(year);
  await waitFor(() => expect(button).toBeEnabled());
  await user.click(button);
  return button;
}
const confirmButton = () => dialog().getByRole('button', { name: 'Confirmar activación' });

describe('seasons administration: reading', () => {
  it('is reachable from the existing ADMIN navigation and shows the title', async () => {
    const { user } = open('/admin');
    await user.click(await screen.findByRole('link', { name: 'Temporadas' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Temporadas' })).toBeInTheDocument();
  });

  it('shows an empty state with the creation form and no activation actions when the school has no seasons', async () => {
    open();
    expect(await screen.findByText('Todavía no hay temporadas')).toBeInTheDocument();
    expect(screen.getByLabelText('Año de la temporada')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Activar la temporada/ })).not.toBeInTheDocument();
  });

  it('shows the active season apart from the others, each with its year and state as text', async () => {
    backend.addSeason(2025);
    backend.addSeason(2026, true);
    backend.addSeason(2027);
    open();
    await ready(2026);
    const activeList = screen.getByRole('list', { name: 'Temporada activa' });
    expect(within(activeList).getByText('2026')).toBeInTheDocument();
    expect(within(activeList).getByText(/Activa/)).toBeInTheDocument();
    expect(within(activeList).queryByRole('button')).not.toBeInTheDocument();
    const others = within(screen.getByRole('list', { name: 'Otras temporadas' }));
    expect(others.getAllByRole('listitem').map((li) => li.textContent)).toEqual([expect.stringContaining('2027'), expect.stringContaining('2025')]);
    expect(others.getAllByText(/Inactiva/)).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'Activar la temporada 2027' })).toBeInTheDocument();
  });

  it('explains that no season is active and offers to activate any of them', async () => {
    backend.addSeason(2026);
    open();
    await ready(2026);
    expect(screen.getByText(/Ninguna temporada está activa/)).toBeInTheDocument();
    expect(activateButton(2026)).toBeEnabled();
  });

  it('shows a loading state while the seasons are being read', async () => {
    backend.addSeason(2026);
    const held = backend.hold('get', LIST_URL);
    open();
    expect(await screen.findByText('Cargando temporadas…')).toBeInTheDocument();
    held.release();
    await ready(2026);
    expect(screen.queryByText('Cargando temporadas…')).not.toBeInTheDocument();
  });

  it('shows a read error with an explicit retry that does not write', async () => {
    backend.addSeason(2026);
    backend.override('get', LIST_URL, 'network');
    const { user } = open();
    expect(await screen.findByRole('alert')).toHaveTextContent(/No pudimos conectar/);
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    await ready(2026);
    expect(writes()).toHaveLength(0);
  });

  it('keeps the seasons on screen when a refresh fails and blocks the actions until they are consulted again', async () => {
    backend.addSeason(2026, true);
    backend.addSeason(2027);
    const { user, queryClient } = open();
    await ready(2027);
    backend.override('get', LIST_URL, 'network', { once: true });
    await act(async () => { await queryClient.invalidateQueries({ queryKey: ['admin', 'seasons'] }).catch(() => undefined); });
    expect(await screen.findByText(/No pudimos actualizar las temporadas/)).toBeInTheDocument();
    expect(screen.getByText('2027')).toBeInTheDocument();
    expect(activateButton(2027)).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Consultar temporadas vigentes' }));
    await waitFor(() => expect(activateButton(2027)).toBeEnabled());
  });

  it('treats a listing with two active seasons as a malformed answer, never drawn', async () => {
    backend.addSeason(2026, true);
    backend.addSeason(2027, true);
    open();
    expect(await screen.findByRole('alert')).toHaveTextContent(/problema en el servidor/);
    expect(screen.queryByText('2027')).not.toBeInTheDocument();
  });
});

describe('seasons administration: creation', () => {
  it('creates an INACTIVE season with only the year, refreshes the list and the sports-data caches', async () => {
    backend.addSeason(2026, true);
    const { user, queryClient } = open();
    await ready(2026);
    queryClient.setQueryData(['admin', 'seasons', 'count'], 1);
    await user.type(screen.getByLabelText('Año de la temporada'), ' 2027 ');
    await user.click(screen.getByRole('button', { name: 'Crear temporada' }));
    expect(await screen.findByText('Temporada 2027 creada. Queda inactiva hasta que la actives.')).toBeInTheDocument();
    expect(creations()).toHaveLength(1);
    expect(creations()[0]!.body).toEqual({ anio: 2027 });
    expect(creations()[0]!.csrfHeader).toBeTruthy();
    expect(backend.seasonsOnServer().find((s) => s.anio === 2027)).toMatchObject({ activa: false });
    await waitFor(() => expect(within(rowOf(2027)).getByText(/Inactiva/)).toBeInTheDocument());
    expect(within(rowOf(2026)).getByText(/Activa/)).toBeInTheDocument();
    expect(queryClient.getQueryState(['admin', 'seasons', 'count'])?.isInvalidated).toBe(true);
    expect(screen.getByLabelText('Año de la temporada')).toHaveValue('');
  });

  it.each([['', 'Ingresá el año de la temporada.'], ['2019', 'El año debe estar entre 2020 y 2100.'], ['2101', 'El año debe estar entre 2020 y 2100.'],
    ['2027.5', 'Ingresá un año entero, solo con números.'], ['2027.0', 'Ingresá un año entero, solo con números.'], ['1e3', 'Ingresá un año entero, solo con números.'], ['abc', 'Ingresá un año entero, solo con números.']])(
    'rejects %j in the field without sending anything', async (typed, message) => {
      const { user } = open();
      await screen.findByText('Todavía no hay temporadas');
      if (typed) await user.type(screen.getByLabelText('Año de la temporada'), typed);
      await user.click(screen.getByRole('button', { name: 'Crear temporada' }));
      expect(await screen.findByText(message)).toBeInTheDocument();
      expect(screen.getByLabelText('Año de la temporada')).toHaveAttribute('aria-invalid', 'true');
      expect(creations()).toHaveLength(0);
    });

  it('tells a duplicated year in the field and keeps the typed value', async () => {
    backend.addSeason(2027);
    const { user } = open();
    await ready(2027);
    await user.type(screen.getByLabelText('Año de la temporada'), '2027');
    await user.click(screen.getByRole('button', { name: 'Crear temporada' }));
    expect(await screen.findByText('Ya existe una temporada de ese año.')).toBeInTheDocument();
    expect(screen.getByLabelText('Año de la temporada')).toHaveValue('2027');
    expect(creations()).toHaveLength(1);
    expect(backend.seasonsOnServer()).toHaveLength(1);
  });

  it('tells a validation error from the backend without echoing its message', async () => {
    const { user } = open();
    await screen.findByText('Todavía no hay temporadas');
    backend.override('post', '/admin/temporadas', { status: 400, body: { codigo: 'VALIDACION', mensaje: 'detalle interno del servidor' } });
    await user.type(screen.getByLabelText('Año de la temporada'), '2027');
    await user.click(screen.getByRole('button', { name: 'Crear temporada' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('El año no es válido');
    expect(screen.queryByText(/detalle interno/)).not.toBeInTheDocument();
  });

  it('does not infer the creation from a lost answer: it asks to consult, blocks a new send and never retries', async () => {
    const { user } = open();
    await screen.findByText('Todavía no hay temporadas');
    backend.addSeason(2027); // what really happened on the server although the client never saw the answer
    backend.override('post', '/admin/temporadas', 'network');
    await user.type(screen.getByLabelText('Año de la temporada'), '2027');
    await user.click(screen.getByRole('button', { name: 'Crear temporada' }));
    expect(await screen.findByText(/No podemos confirmar si la temporada se creó/)).toBeInTheDocument();
    expect(screen.queryByText(/creada/)).not.toBeInTheDocument();
    expect(screen.queryByText('2027', { selector: 'span' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Crear temporada' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Consultar temporadas vigentes' }));
    expect(await screen.findByText(/no confirma el resultado de la petición anterior/)).toBeInTheDocument();
    expect(await screen.findByText('2027', { selector: 'span' })).toBeInTheDocument();
    expect(screen.queryByText(/creada/)).not.toBeInTheDocument();
    expect(creations()).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Crear temporada' })).toBeEnabled();
  });

  it('sends a double submit once', async () => {
    const { user } = open();
    await screen.findByText('Todavía no hay temporadas');
    const held = backend.hold('post', '/admin/temporadas');
    await user.type(screen.getByLabelText('Año de la temporada'), '2027');
    const form = screen.getByRole('button', { name: /Crear temporada|Creando/ }).closest('form')!;
    fireEvent.submit(form);
    fireEvent.submit(form);
    await user.click(screen.getByRole('button', { name: /Crear temporada|Creando/ }));
    await waitFor(() => expect(creations()).toHaveLength(1));
    held.release();
    expect(await screen.findByText(/Temporada 2027 creada/)).toBeInTheDocument();
    expect(creations()).toHaveLength(1);
  });
});

describe('seasons administration: safe activation', () => {
  it('activates from zero: names the season, says that nothing is active and that history is kept, and cancel sends nothing', async () => {
    backend.addSeason(2026);
    backend.addSeason(2027);
    const { user } = open();
    await ready(2027);
    const trigger = await openActivation(user, 2027);
    const modal = screen.getByRole('dialog', { name: 'Activar la temporada 2027' });
    expect(modal).toHaveAttribute('aria-describedby');
    expect(dialog().getByText('Se activará la temporada 2027.')).toBeInTheDocument();
    expect(dialog().getByText('Ahora ninguna temporada está activa.')).toBeInTheDocument();
    expect(dialog().getByText(/datos históricos de las otras temporadas no se eliminan ni se trasladan/)).toBeInTheDocument();
    expect(dialog().getByRole('button', { name: 'Cancelar' })).toHaveFocus();
    await user.click(dialog().getByRole('button', { name: 'Cancelar' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    expect(activations()).toHaveLength(0);
    await openActivation(user, 2027);
    await user.click(confirmButton());
    expect(await screen.findByText('La temporada 2027 es la activa.')).toBeInTheDocument();
    expect(activations()).toHaveLength(1);
    expect(activations()[0]!.body).toEqual({ activaEsperadaId: null });
    expect(activations()[0]!.csrfHeader).toBeTruthy();
    expect(backend.seasonsOnServer().filter((s) => s.activa).map((s) => s.anio)).toEqual([2027]);
    await waitFor(() => expect(within(screen.getByRole('list', { name: 'Temporada activa' })).getByText('2027')).toBeInTheDocument());
  });

  it('replaces the active season: says which one stops being active and sends the observed one as the intent', async () => {
    const old = backend.addSeason(2026, true);
    backend.addSeason(2027);
    const { user, queryClient } = open();
    await ready(2027);
    queryClient.setQueryData(['admin', 'seasons', 'count'], 2);
    await openActivation(user, 2027);
    expect(dialog().getByText('Dejará de estar activa la temporada 2026.')).toBeInTheDocument();
    await user.click(confirmButton());
    expect(await screen.findByText('La temporada 2027 es la activa.')).toBeInTheDocument();
    expect(activations()[0]!.body).toEqual({ activaEsperadaId: old.id });
    expect(backend.seasonsOnServer().filter((s) => s.activa).map((s) => s.anio)).toEqual([2027]);
    expect(queryClient.getQueryState(['admin', 'seasons', 'count'])?.isInvalidated).toBe(true);
    await waitFor(() => expect(within(screen.getByRole('list', { name: 'Otras temporadas' })).getByText('2026')).toBeInTheDocument());
    expect(screen.getByRole('heading', { level: 1, name: 'Temporadas' })).toBeInTheDocument();
  });

  it('is idempotent when the season became the active one meanwhile: the answer is a success and nothing else changes', async () => {
    backend.addSeason(2026, true);
    const target = backend.addSeason(2027);
    const { user } = open();
    await ready(2027);
    backend.activateSeasonBehindTheScenes(target.id);
    await openActivation(user, 2027);
    await user.click(confirmButton());
    expect(await screen.findByText('La temporada 2027 es la activa.')).toBeInTheDocument();
    expect(activations()).toHaveLength(1);
    expect(backend.seasonsOnServer().filter((s) => s.activa).map((s) => s.anio)).toEqual([2027]);
  });

  it('never replaces the intent while the dialog is open, even if a background refresh sees another active season', async () => {
    const seen = backend.addSeason(2026, true);
    const other = backend.addSeason(2027);
    backend.addSeason(2028);
    const { user, queryClient } = open();
    await ready(2028);
    await openActivation(user, 2028);
    backend.activateSeasonBehindTheScenes(other.id);
    await act(async () => { await queryClient.invalidateQueries({ queryKey: ['admin', 'seasons'] }); });
    await waitFor(() => expect(listReads()).toBeGreaterThan(1));
    expect(dialog().getByText('Dejará de estar activa la temporada 2026.')).toBeInTheDocument();
    await user.click(confirmButton());
    expect(await screen.findByText(/La temporada activa cambió mientras decidías/)).toBeInTheDocument();
    expect(activations()).toHaveLength(1);
    expect(activations()[0]!.body).toEqual({ activaEsperadaId: seen.id });
    expect(backend.seasonsOnServer().filter((s) => s.activa).map((s) => s.anio)).toEqual([2027]);
  });

  it('on EDICION_DESACTUALIZADA keeps nothing applied, consults the current state and requires a NEW confirmation with the new intent', async () => {
    const seen = backend.addSeason(2026, true);
    const other = backend.addSeason(2027);
    backend.addSeason(2028);
    const { user } = open();
    await ready(2028);
    backend.activateSeasonBehindTheScenes(other.id);
    await openActivation(user, 2028);
    await user.click(confirmButton());
    expect(await screen.findByText(/La temporada activa cambió mientras decidías/)).toBeInTheDocument();
    expect(dialog().queryByRole('button', { name: 'Confirmar activación' })).not.toBeInTheDocument();
    expect(activations()).toHaveLength(1);
    expect(activations()[0]!.body).toEqual({ activaEsperadaId: seen.id });
    await user.click(dialog().getByRole('button', { name: 'Consultar temporadas vigentes' }));
    expect(await dialog().findByText(/no confirma el resultado de la petición anterior/)).toBeInTheDocument();
    expect(activations()).toHaveLength(1); // the consultation never sends the write again
    // The outdated consequences are not repeated after the consultation: the NEW confirmation shows the current ones.
    expect(dialog().queryByText(/Dejará de estar activa|Ahora ninguna temporada está activa/)).not.toBeInTheDocument();
    expect(dialog().queryByText(/no se eliminan ni se trasladan/)).not.toBeInTheDocument();
    await user.click(dialog().getByRole('button', { name: 'Revisar nueva confirmación' }));
    expect(dialog().getByText('Dejará de estar activa la temporada 2027.')).toBeInTheDocument();
    await user.click(confirmButton());
    expect(await screen.findByText('La temporada 2028 es la activa.')).toBeInTheDocument();
    expect(activations()).toHaveLength(2);
    expect(activations()[1]!.body).toEqual({ activaEsperadaId: other.id });
    expect(backend.seasonsOnServer().filter((s) => s.activa).map((s) => s.anio)).toEqual([2028]);
  });

  it('offers no new confirmation when the consultation shows that the season already is the active one', async () => {
    backend.addSeason(2026, true);
    const target = backend.addSeason(2027);
    const { user } = open();
    await ready(2027);
    backend.activateSeasonBehindTheScenes(target.id);
    await openActivation(user, 2027);
    backend.override('post', `/admin/temporadas/${target.id}/activar`, { status: 409, body: { codigo: 'EDICION_DESACTUALIZADA' } });
    await user.click(confirmButton());
    await screen.findByText(/La temporada activa cambió mientras decidías/);
    await user.click(dialog().getByRole('button', { name: 'Consultar temporadas vigentes' }));
    expect(await dialog().findByText('Esa temporada ya es la activa. No hace falta confirmar nada.')).toBeInTheDocument();
    expect(dialog().queryByRole('button', { name: 'Revisar nueva confirmación' })).not.toBeInTheDocument();
    expect(activations()).toHaveLength(1);
  });

  it('tells a concurrency conflict apart from an uncertain result, and from a stale intent', async () => {
    backend.addSeason(2026, true);
    const target = backend.addSeason(2027);
    const { user } = open();
    await ready(2027);
    backend.override('post', `/admin/temporadas/${target.id}/activar`, { status: 409, body: { codigo: 'CONFLICTO_CONCURRENCIA', mensaje: 'detalle interno' } });
    await openActivation(user, 2027);
    await user.click(confirmButton());
    expect(await screen.findByText(/Otra activación está en curso/)).toBeInTheDocument();
    expect(screen.queryByText(/No podemos confirmar el resultado/)).not.toBeInTheDocument();
    expect(screen.queryByText(/cambió mientras decidías/)).not.toBeInTheDocument();
    expect(screen.queryByText(/detalle interno/)).not.toBeInTheDocument();
    expect(activations()).toHaveLength(1);
  });

  it('does not claim success or failure after a lost answer, never retries and blocks new activations until consulted', async () => {
    backend.addSeason(2026, true);
    const target = backend.addSeason(2027);
    backend.addSeason(2028);
    const { user } = open();
    await ready(2028);
    backend.override('post', `/admin/temporadas/${target.id}/activar`, 'network');
    await openActivation(user, 2027);
    await user.click(confirmButton());
    expect(await screen.findByText(/No podemos confirmar el resultado de la activación/)).toBeInTheDocument();
    expect(screen.queryByText(/es la activa\./)).not.toBeInTheDocument();
    await user.click(dialog().getByRole('button', { name: 'Cancelar' }));
    expect(activateButton(2027)).toBeDisabled();
    expect(activateButton(2028)).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Consultar temporadas vigentes' })).toBeEnabled();
    await act(async () => { await new Promise((r) => setTimeout(r, 30)); });
    expect(activations()).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: 'Consultar temporadas vigentes' }));
    await waitFor(() => expect(activateButton(2028)).toBeEnabled());
    expect(screen.getByText(/no confirma el resultado de la petición anterior/)).toBeInTheDocument();
  });

  it('sends a double confirmation once', async () => {
    backend.addSeason(2026, true);
    const target = backend.addSeason(2027);
    const { user } = open();
    await ready(2027);
    const held = backend.hold('post', `/admin/temporadas/${target.id}/activar`);
    await openActivation(user, 2027);
    const button = confirmButton();
    fireEvent.click(button);
    fireEvent.click(button);
    await waitFor(() => expect(activations()).toHaveLength(1));
    expect(dialog().getByText('Esperando respuesta…')).toBeInTheDocument();
    expect(dialog().getByRole('button', { name: 'Cancelar' })).toBeDisabled();
    held.release();
    expect(await screen.findByText('La temporada 2027 es la activa.')).toBeInTheDocument();
    expect(activations()).toHaveLength(1);
  });

  it('blocks a creation while an activation is pending (one write lane for the school)', async () => {
    backend.addSeason(2026, true);
    const target = backend.addSeason(2027);
    const { user } = open();
    await ready(2027);
    const held = backend.hold('post', `/admin/temporadas/${target.id}/activar`);
    await openActivation(user, 2027);
    await user.click(confirmButton());
    await waitFor(() => expect(activations()).toHaveLength(1));
    expect(screen.getAllByRole('button', { name: 'Crear temporada' }).at(-1)).toBeDisabled();
    held.release();
    await screen.findByText('La temporada 2027 es la activa.');
  });

  it('drops a late answer after the identity changed, without notices or refreshes for the new identity', async () => {
    backend.addSeason(2026, true);
    const target = backend.addSeason(2027);
    const { user, queryClient } = open();
    backend.addUser({ ...admin, id: 'u-other', email: 'other@example.com' });
    await ready(2027);
    const held = backend.hold('post', `/admin/temporadas/${target.id}/activar`);
    await openActivation(user, 2027);
    await user.click(confirmButton());
    await waitFor(() => expect(activations()).toHaveLength(1));
    backend.signIn('other@example.com');
    backend.override('get', LIST_URL, { status: 403, body: { codigo: 'ACCESO_DENEGADO' } }, { once: false });
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await act(async () => { await new Promise((r) => setTimeout(r, 30)); });
    const before = backend.requests.length;
    held.release();
    await act(async () => { await new Promise((r) => setTimeout(r, 50)); });
    expect(screen.queryByText('La temporada 2027 es la activa.')).not.toBeInTheDocument();
    expect(backend.requests.slice(before).filter((r) => r.url.startsWith('/admin/'))).toHaveLength(0);
  });

  it('refuses to send a write when the identity changed before the request left', async () => {
    backend.addSeason(2026, true);
    const target = backend.addSeason(2027);
    const { user, queryClient } = open();
    backend.addUser({ ...admin, id: 'u-other', email: 'other@example.com' });
    await ready(2027);
    await openActivation(user, 2027);
    const before = activations().length;
    queryClient.setQueryData(sessionKey, { ...(queryClient.getQueryData(sessionKey) as object), id: 'u-other' });
    await act(async () => { await user.click(confirmButton()).catch(() => undefined); });
    await act(async () => { await new Promise((r) => setTimeout(r, 30)); });
    expect(activations().length).toBe(before);
    expect(backend.seasonsOnServer().find((s) => s.id === target.id)?.activa).toBe(false);
  });
});

describe('seasons administration: access', () => {
  it('shows the forbidden screen to a FAMILIA account and requests nothing', async () => {
    backend.signIn('lucia@example.com');
    renderApp('/admin/temporadas');
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(backend.requests.filter((r) => r.url.startsWith('/admin/temporadas'))).toHaveLength(0);
  });
});
