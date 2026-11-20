import { MemoryRouter, useNavigate } from 'react-router-dom';
import { App } from '@/app/App';
import { AppProviders, createQueryClient } from '@/app/providers';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { sessionKey } from '@/features/auth/session';
import { getAthleteLinks } from './api';
import { athleteLinksKey, familyAthletesKey } from './hooks';
import type { FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';
let backend: FakeBackend;
beforeEach(() => {
  backend = createBackend(); backend.signIn(admin.email);
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', ''); } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open'); } });
});
afterEach(() => { cleanup(); Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal'); Reflect.deleteProperty(HTMLDialogElement.prototype, 'close'); });
const posts = () => backend.requests.filter((r) => r.method === 'post' && /^\/admin\/familias\/[^/]+\/deportistas$/.test(r.url));
const modal = () => within(screen.getByRole('dialog'));
function seed(count = 3) {
  const family = backend.addFamily('Familia Selección');
  const athletes = Array.from({ length: count }, (_, i) => backend.addAdminAthlete({ nombre: `Persona ${i}`, apellido: 'Prueba', dni: String(35123450 + i), cuil: '20351234501' }));
  return { family, athletes, endpoint: `/admin/familias/${family.id}/deportistas` };
}
async function start(count = 3) {
  const data = seed(count); const app = renderApp(`/admin/familias/${data.family.id}`); const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Vincular deportistas' }));
  await screen.findByRole('checkbox', { name: /Persona 0/ });
  return { ...data, ...app, user };
}
async function select(user: ReturnType<typeof userEvent.setup>, name = 'Persona 0') { await user.click(screen.getByRole('checkbox', { name: new RegExp(name) })); }
async function prepare(user: ReturnType<typeof userEvent.setup>) { await user.click(screen.getByRole('button', { name: 'Revisar selección' })); await screen.findByRole('dialog'); }
async function confirm(user: ReturnType<typeof userEvent.setup>) { await user.click(modal().getByRole('button', { name: 'Confirmar vinculación' })); }

describe('family athlete selection and linking', () => {
  it('requires accessible confirmation, cancel preserves selection and no POST is sent', async () => {
    const { user } = await start(); await select(user); await prepare(user);
    const dialog = screen.getByRole('dialog', { name: 'Confirmar vinculación de deportistas' });
    expect(dialog).toHaveAttribute('aria-describedby');
    expect(modal().getByRole('button', { name: 'Cancelar' })).toHaveFocus();
    expect(modal().getByText(/obtendrá acceso.*no transfiere.*principal.*backend/i)).toBeInTheDocument();
    await user.click(modal().getByRole('button', { name: 'Cancelar' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Revisar selección' })).toHaveFocus();
    expect(screen.getByRole('checkbox', { name: /Persona 0/ })).toBeChecked(); expect(posts()).toHaveLength(0);
  });
  it('creates multiple links only after confirmation and refreshes affected caches', async () => {
    const { user, family, athletes, queryClient } = await start();
    queryClient.setQueryData(athleteLinksKey(athletes[0]!.id), { observed: true });
    await select(user); await select(user, 'Persona 1'); await prepare(user); await confirm(user);
    expect(await screen.findByText('Vinculación confirmada: 2 creados, 0 reactivados, 0 sin cambios.')).toBeInTheDocument();
    expect(posts()).toHaveLength(1); expect(backend.adminLinkCount()).toBe(2);
    expect(queryClient.getQueryState(athleteLinksKey(athletes[0]!.id))?.isInvalidated).toBe(true);
    expect(queryClient.getQueryData(familyAthletesKey(family.id))).toEqual(expect.arrayContaining([expect.objectContaining({ deportistaId: athletes[0]!.id })]));
  });
  it('distinguishes absent, active and revoked, reactivates the same link without transferring principal', async () => {
    const { family, athletes } = seed(); const other = backend.addFamily('Otra familia');
    backend.addAdminLink(other.id, athletes[0]!.id, { esPrincipal: true });
    const revoked = backend.addAdminLink(family.id, athletes[0]!.id, { estado: 'REVOCADO' });
    backend.addAdminLink(family.id, athletes[1]!.id);
    renderApp(`/admin/familias/${family.id}`); const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Vincular deportistas' }));
    expect(await screen.findByText('Revocado: se reactivará')).toBeInTheDocument(); expect(screen.getByText('Activo: sin cambios')).toBeInTheDocument(); expect(screen.getByText('Sin vínculo: se creará')).toBeInTheDocument();
    await select(user); await select(user, 'Persona 1'); await prepare(user); await confirm(user);
    await screen.findByText('Vinculación confirmada: 0 creados, 1 reactivados, 1 sin cambios.');
    expect(backend.adminLinkCount()).toBe(3);
    const observed = await getAthleteLinks(athletes[0]!.id);
    expect(observed.vinculos.find((r) => r.vinculoId === revoked.id)).toMatchObject({ estado: 'ACTIVO', esPrincipal: false });
    expect(observed.vinculos.find((r) => r.familiaId === other.id)).toMatchObject({ estado: 'ACTIVO', esPrincipal: true });
  });
  it('retains selection across pages and search without exposing full DNI/CUIL or URL search', async () => {
    const { user, queryClient } = await start(22); await select(user);
    await user.click(screen.getByRole('button', { name: 'Siguiente' }));
    await screen.findByText(/Página 2 de 2/); expect(screen.getByText('1 seleccionados de 50')).toBeInTheDocument();
    await user.type(screen.getByRole('textbox', { name: 'Buscar deportistas' }), 'Persona 1');
    await waitFor(() => expect(backend.requests.some((r) => r.url.includes('busqueda=Persona'))).toBe(true));
    expect(screen.getByTestId('location').textContent).not.toContain('Persona');
    expect(document.body.textContent).not.toContain('35123450'); expect(JSON.stringify(queryClient.getQueryCache().getAll().map((q) => q.state.data))).not.toContain('20351234501');
    expect(backend.requests.some((r) => /^\/admin\/deportistas\/[^/]+$/.test(r.url))).toBe(false);
  });
  it('enforces the fifty-athlete selection cap across pagination', async () => {
    const { user } = await start(51);
    for (let page = 0; page < 3; page++) {
      for (const box of screen.getAllByRole('checkbox').slice(0, page === 2 ? 10 : 20)) await user.click(box);
      if (page < 2) { await user.click(screen.getByRole('button', { name: 'Siguiente' })); await screen.findByText(new RegExp(`Página ${page + 2} de 3`)); }
    }
    expect(screen.getByText('50 seleccionados de 50')).toBeInTheDocument();
    expect(screen.getAllByRole('checkbox').filter((b) => !(b as HTMLInputElement).checked)[0]).toBeDisabled();
  });
  it('does not offer linking for inactive families or select inactive/unsupported athletes', async () => {
    const { family, athletes } = seed(); family.activa = false;
    renderApp(`/admin/familias/${family.id}`);
    expect(await screen.findByRole('button', { name: 'Vincular deportistas' })).toBeDisabled(); cleanup(); family.activa = true;
    athletes[0]!.activo = false; backend.addAdminLink(family.id, athletes[1]!.id, { estado: 'PENDIENTE' });
    renderApp(`/admin/familias/${family.id}`); const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: 'Vincular deportistas' }));
    await screen.findByRole('checkbox', { name: /Persona 1/ }); expect(screen.queryByRole('checkbox', { name: /Persona 0/ })).not.toBeInTheDocument(); expect(screen.getByRole('checkbox', { name: /Persona 1/ })).toBeDisabled();
  });
  it('keeps captured revisions despite background refresh and demands new confirmation after stale', async () => {
    const { user, family, athletes, queryClient } = await start(); await select(user); await prepare(user);
    const concurrent = backend.addAdminLink(family.id, athletes[0]!.id); backend.changeAdminLinkBehindTheScenes(concurrent.id, { estado: 'REVOCADO' });
    await act(async () => { await queryClient.invalidateQueries({ queryKey: familyAthletesKey(family.id) }); });
    await confirm(user); await modal().findByText(/Los vínculos cambiaron/);
    expect(posts()[0]!.body).toMatchObject({ deportistas: [{ versionEsperada: '0' }] });
    expect(modal().queryByRole('button', { name: 'Confirmar vinculación' })).not.toBeInTheDocument();
    await user.click(modal().getByRole('button', { name: 'Consultar datos vigentes' }));
    await user.click(await modal().findByRole('button', { name: 'Revisar nueva confirmación' }));
    expect(posts()).toHaveLength(1); expect(modal().getByText(/Reactivar/)).toBeInTheDocument(); await confirm(user);
    await screen.findByText('Vinculación confirmada: 0 creados, 1 reactivados, 0 sin cambios.'); expect(posts()[1]!.body).toMatchObject({ deportistas: [{ versionEsperada: '1' }] });
  });
  it.each(['network', '500', 'malformed'])('treats %s as uncertain, never auto retries, and GET verification is not success', async (kind) => {
    const { user, endpoint } = await start(); await select(user); await prepare(user);
    if (kind === 'network') backend.override('post', endpoint, 'network'); else backend.override('post', endpoint, { status: kind === '500' ? 500 : 200, body: {} });
    await confirm(user); await modal().findByText(/No sabemos si la vinculación se aplicó/);
    expect(posts()).toHaveLength(1); expect(screen.queryByText(/Vinculación confirmada:/)).not.toBeInTheDocument();
    await user.click(modal().getByRole('button', { name: 'Consultar datos vigentes' }));
    await modal().findByRole('button', { name: 'Revisar nueva confirmación' }); expect(posts()).toHaveLength(1); expect(screen.queryByText(/Vinculación confirmada:/)).not.toBeInTheDocument();
  });
  it.each([
    ['CONFLICTO_CONCURRENCIA', /Otra operación está en curso/], ['FAMILIA_INACTIVA', /familia está inactiva/i],
    ['DEPORTISTA_INACTIVO', /deportista está inactivo/i], ['VINCULO_PRINCIPAL_EN_CONFLICTO', /vínculo principal cambió/i], ['VINCULO_NO_ACTIVO', /vínculo ya no está activo/i],
  ])('explains %s without claiming success', async (code, message) => {
    const { user, endpoint } = await start(); await select(user); await prepare(user);
    backend.override('post', endpoint, { status: 409, body: { codigo: code, detalles: [{ campo: 'deportistas[0]', mensaje: 'DO NOT ECHO THIS RAW DETAIL' }] } });
    await confirm(user); await modal().findByText(message); expect(screen.queryByText(/Vinculación confirmada:/)).not.toBeInTheDocument(); expect(screen.queryByText(/DO NOT ECHO/)).not.toBeInTheDocument();
  });
  it('blocks double clicks and a second request while the first is pending', async () => {
    const { user, endpoint } = await start(); await select(user); await prepare(user);
    const held = backend.hold('post', endpoint); const button = modal().getByRole('button', { name: 'Confirmar vinculación' });
    fireEvent.click(button); fireEvent.click(button); await waitFor(() => expect(posts()).toHaveLength(1)); expect(button).toBeDisabled(); held.release(); await screen.findByText(/Vinculación confirmada:/);
  });
  it('keeps the pending lock across route unmount/remount', async () => {
    const data = seed(); const client = createQueryClient();
    function Navigator() { const navigate = useNavigate(); return <><button onClick={() => { void navigate('/admin/familias'); }}>Salir prueba</button><button onClick={() => { void navigate(`/admin/familias/${data.family.id}`); }}>Volver prueba</button><App /></>; }
    render(<AppProviders client={client}><MemoryRouter initialEntries={[`/admin/familias/${data.family.id}`]}><Navigator /></MemoryRouter></AppProviders>);
    const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: 'Vincular deportistas' })); await screen.findByRole('checkbox', { name: /Persona 0/ }); await select(user); await prepare(user);
    const held = backend.hold('post', data.endpoint); await confirm(user); await waitFor(() => expect(posts()).toHaveLength(1));
    await user.click(screen.getByRole('button', { name: 'Salir prueba' })); await user.click(screen.getByRole('button', { name: 'Volver prueba' }));
    expect(await screen.findByRole('button', { name: 'Vincular deportistas' })).toBeDisabled(); held.release(); await waitFor(() => expect(screen.getByRole('button', { name: 'Vincular deportistas' })).toBeEnabled()); expect(posts()).toHaveLength(1);
  });
  it('discards late success and invalidations after the same id changes school identity', async () => {
    const { user, endpoint, queryClient, athletes } = await start(); await select(user); await prepare(user);
    const held = backend.hold('post', endpoint); await confirm(user); await waitFor(() => expect(posts()).toHaveLength(1));
    act(() => { queryClient.setQueryData(sessionKey, { ...admin, escuelaId: 'different-school', mfaPendiente: false }); });
    queryClient.setQueryData(athleteLinksKey(athletes[0]!.id), { newIdentity: true }); held.release();
    await waitFor(() => expect(queryClient.isMutating()).toBe(0)); expect(screen.queryByText(/Vinculación confirmada:/)).not.toBeInTheDocument(); expect(queryClient.getQueryState(athleteLinksKey(athletes[0]!.id))?.isInvalidated).toBe(false);
  });
  it('stops preparation on a late read after changing family resource', async () => {
    const data = seed(); const next = backend.addFamily('Familia destino'); const client = createQueryClient();
    function Navigator() { const navigate = useNavigate(); return <><button onClick={() => { void navigate(`/admin/familias/${next.id}`); }}>Otra familia prueba</button><App /></>; }
    render(<AppProviders client={client}><MemoryRouter initialEntries={[`/admin/familias/${data.family.id}`]}><Navigator /></MemoryRouter></AppProviders>);
    const user = userEvent.setup(); await user.click(await screen.findByRole('button', { name: 'Vincular deportistas' })); await screen.findByRole('checkbox', { name: /Persona 0/ }); await select(user); await select(user, 'Persona 1');
    const held = backend.hold('get', `/admin/deportistas/${data.athletes[0]!.id}/vinculos`);
    await user.click(screen.getByRole('button', { name: 'Revisar selección' }));
    await waitFor(() => expect(backend.requests.some((r) => r.url.includes(`${data.athletes[0]!.id}/vinculos`))).toBe(true));
    await user.click(screen.getByRole('button', { name: 'Otra familia prueba' })); await screen.findByRole('heading', { level: 1, name: 'Familia destino' }); held.release();
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(backend.requests.some((r) => r.url.includes(`${data.athletes[1]!.id}/vinculos`))).toBe(false); expect(posts()).toHaveLength(0);
  });
  it('does not dispatch a batch if identity changes while CSRF is pending', async () => {
    const { user, queryClient } = await start(); await select(user); await prepare(user);
    const held = backend.hold('get', '/auth/csrf'); await confirm(user);
    await waitFor(() => expect(backend.requests.some((r) => r.url === '/auth/csrf')).toBe(true));
    act(() => { queryClient.setQueryData(sessionKey, { ...admin, id: 'another-admin', mfaPendiente: false }); }); held.release();
    await waitFor(() => expect(queryClient.isMutating()).toBe(0)); expect(posts()).toHaveLength(0); expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
  it('failed read verification preserves intent without enabling another POST', async () => {
    const { user, endpoint, athletes } = await start(); await select(user); await prepare(user);
    backend.override('post', endpoint, 'network'); await confirm(user); await modal().findByText(/No sabemos si/);
    backend.override('get', `/admin/deportistas/${athletes[0]!.id}/vinculos`, 'network');
    await user.click(modal().getByRole('button', { name: 'Consultar datos vigentes' })); await modal().findByText(/No pudimos consultar los datos necesarios/);
    expect(modal().queryByRole('button', { name: 'Confirmar vinculación' })).not.toBeInTheDocument(); expect(posts()).toHaveLength(1);
    await user.click(modal().getByRole('button', { name: 'Cancelar' })); expect(screen.getByRole('checkbox', { name: /Persona 0/ })).toBeChecked(); expect(screen.getByRole('button', { name: 'Consultar y revisar selección' })).toBeEnabled();
  });

  it('refreshes affected reads after a committed but lost response without retrying or claiming success', async () => {
    const { user, family, athletes, endpoint, queryClient } = await start();
    const other = backend.addFamily('Familia principal previa');
    backend.addAdminLink(other.id, athletes[0]!.id, { esPrincipal: true });
    queryClient.setQueryData(athleteLinksKey(athletes[0]!.id), await getAthleteLinks(athletes[0]!.id));
    queryClient.setQueryData(familyAthletesKey(other.id), []);
    await select(user); await prepare(user);
    // The server committed, but its POST response never arrived at the client.
    const committed = backend.addAdminLink(family.id, athletes[0]!.id);
    backend.changeAdminLinkBehindTheScenes(committed.id, { estado: 'ACTIVO' });
    backend.override('post', endpoint, 'network'); await confirm(user); await modal().findByText(/No sabemos si/);
    await user.click(modal().getByRole('button', { name: 'Consultar datos vigentes' }));
    await modal().findByRole('button', { name: 'Revisar nueva confirmación' });
    await user.click(modal().getByRole('button', { name: 'Cancelar' }));
    expect(await screen.findByRole('link', { name: 'Persona 0 Prueba' })).toBeInTheDocument();
    expect(queryClient.getQueryData(familyAthletesKey(family.id))).toEqual(expect.arrayContaining([expect.objectContaining({ vinculoId: committed.id, estado: 'ACTIVO' })]));
    expect(queryClient.getQueryState(athleteLinksKey(athletes[0]!.id))?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(familyAthletesKey(other.id))?.isInvalidated).toBe(true);
    expect(posts()).toHaveLength(1); expect(screen.queryByText(/Vinculación confirmada:/)).not.toBeInTheDocument();
  });
  it('does not invalidate a different identity when a recovery read arrives late', async () => {
    const { user, athletes, endpoint, queryClient } = await start(); await select(user); await prepare(user);
    backend.override('post', endpoint, 'network'); await confirm(user); await modal().findByText(/No sabemos si/);
    const url = `/admin/deportistas/${athletes[0]!.id}/vinculos`; const before = backend.requests.filter((r) => r.url === url).length;
    const held = backend.hold('get', url); await user.click(modal().getByRole('button', { name: 'Consultar datos vigentes' }));
    await waitFor(() => expect(backend.requests.filter((r) => r.url === url)).toHaveLength(before + 1));
    act(() => { queryClient.setQueryData(sessionKey, { ...admin, escuelaId: 'another-school', mfaPendiente: false }); });
    queryClient.setQueryData(athleteLinksKey(athletes[0]!.id), { newIdentity: true }); held.release();
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(queryClient.getQueryState(athleteLinksKey(athletes[0]!.id))?.isInvalidated).toBe(false); expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(posts()).toHaveLength(1);
  });

});
