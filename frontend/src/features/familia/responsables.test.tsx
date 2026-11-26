import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it } from 'vitest';
import { createQueryClient } from '@/app/providers';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, family, renderApp } from '@/test/render';
import { fetchAthleteResponsables } from './api';
import { familyKeys, useAthleteResponsables } from './hooks';
import type { FamilyIdentity } from './identity';
import { familyResponsablesSchema } from './schemas';

// MOCK backend (src/test/fakeBackend.ts mirrors GET /api/familia/deportistas/{id}/responsables).
// Real-backend evidence lives in e2e/responsables-portal.real.spec.ts and in FamiliaPortalResponsablesDbTest.
let backend: FakeBackend;
const ghost = '00000000-0000-4000-8000-0000000000ff';
const responsablesUrl = (id: string) => `/familia/deportistas/${id}/responsables`;
const calls = () => backend.requests.filter((r) => r.url.endsWith('/responsables')).map((r) => r.url);

function own() {
  const athlete = backend.addAthlete({ nombre: 'Ana', apellido: 'Paz', edad: 9, activo: true, vigente: null });
  backend.linkAthlete('familia-1', athlete.id);
  return athlete;
}

beforeEach(() => {
  backend = createBackend();
  backend.signIn(family.email);
});

describe('responsables of an own athlete: contract (mock backend)', () => {
  it('parses exactly four fields per responsable and keeps a null type apart from SIN_DETERMINAR', async () => {
    const athlete = own();
    backend.addResponsable(athlete.id, 'familia-1', { nombre: 'Ana', apellido: 'Alvarez', tipo: 'MADRE', tutorId: 't-1' });
    backend.addResponsable(athlete.id, 'familia-1', { nombre: 'Beto', apellido: 'Benitez', tipo: null, tutorId: 't-2' });
    backend.addResponsable(athlete.id, 'familia-1', { nombre: 'Carla', apellido: 'Campos', tipo: 'SIN_DETERMINAR', tutorId: 't-3' });

    const rows = await fetchAthleteResponsables(athlete.id);

    expect(rows).toEqual([
      { tutorId: 't-1', nombre: 'Ana', apellido: 'Alvarez', tipo: 'MADRE' },
      { tutorId: 't-2', nombre: 'Beto', apellido: 'Benitez', tipo: null },
      { tutorId: 't-3', nombre: 'Carla', apellido: 'Campos', tipo: 'SIN_DETERMINAR' },
    ]);
    rows.forEach((row) => expect(Object.keys(row).sort()).toEqual(['apellido', 'nombre', 'tipo', 'tutorId']));
  });

  it('drops everything the server may add and rejects an unknown type or a malformed answer', () => {
    const parsed = familyResponsablesSchema.parse({
      responsables: [{ tutorId: 't', nombre: 'A', apellido: 'B', tipo: 'MADRE', dni: '30111222', telefono: '11', version: '3', familiaId: 'f' }],
    });
    expect(parsed.responsables[0]).toEqual({ tutorId: 't', nombre: 'A', apellido: 'B', tipo: 'MADRE' });
    expect(familyResponsablesSchema.safeParse({ responsables: [{ tutorId: 't', nombre: 'A', apellido: 'B', tipo: 'ABUELO' }] }).success).toBe(false);
    expect(familyResponsablesSchema.safeParse({ responsables: [{ tutorId: 't', nombre: 'A', apellido: 'B' }] }).success).toBe(false);
    expect(familyResponsablesSchema.safeParse([]).success).toBe(false);
  });

  it('refuses an id that is not a UUID without any request', async () => {
    await expect(fetchAthleteResponsables('../../admin')).rejects.toMatchObject({ status: 400 });
    expect(calls()).toEqual([]);
  });

  it('gives one 404 for a foreign, revoked, inactive-family or unknown athlete', async () => {
    const foreign = backend.addAthlete({ nombre: 'Otro', apellido: 'Ajeno', edad: 8, activo: true, vigente: null });
    backend.addPortalFamily('familia-2', 'Familia Ajena');
    backend.linkAthlete('familia-2', foreign.id);
    const revoked = own();
    backend.revokeLink('familia-1', revoked.id);
    for (const id of [foreign.id, revoked.id, ghost]) {
      await expect(fetchAthleteResponsables(id)).rejects.toMatchObject({ status: 404, code: 'DEPORTISTA_NO_ENCONTRADO' });
    }
  });

  it('shows only the own family when two families share an athlete, and never the others by count or name', async () => {
    const athlete = own();
    backend.addPortalFamily('familia-2', 'Familia Ajena');
    backend.linkAthlete('familia-2', athlete.id);
    backend.addResponsable(athlete.id, 'familia-1', { nombre: 'Ana', apellido: 'Alvarez', tipo: 'MADRE', tutorId: 't-own' });
    backend.addResponsable(athlete.id, 'familia-2', { nombre: 'Intruso', apellido: 'Ajeno', tipo: 'PADRE', tutorId: 't-foreign-1' });
    backend.addResponsable(athlete.id, 'familia-2', { nombre: 'Otra', apellido: 'Ajena', tipo: 'TUTOR_LEGAL', tutorId: 't-foreign-2' });

    const rows = await fetchAthleteResponsables(athlete.id);

    expect(rows.map((r) => r.tutorId)).toEqual(['t-own']);
    expect(JSON.stringify(rows)).not.toMatch(/Intruso|Ajen/);
  });

  it('omits closed responsabilities: a closed one never comes back, even if the tutor is active again', async () => {
    const athlete = own();
    const closed = backend.addResponsable(athlete.id, 'familia-1', { nombre: 'Ana', apellido: 'Alvarez', tipo: 'MADRE', tutorId: 't-1' });
    backend.addResponsable(athlete.id, 'familia-1', { nombre: 'Beto', apellido: 'Benitez', tipo: 'PADRE', tutorId: 't-2' });
    backend.closeResponsableBehindTheScenes(closed.id, 'TUTOR_INACTIVO');
    expect((await fetchAthleteResponsables(athlete.id)).map((r) => r.tutorId)).toEqual(['t-2']);
  });
});

describe('responsables in the athlete sheet (mock backend)', () => {
  it('draws name and plain-language type, tells "Sin declarar" from "Sin determinar" and explains what the type is not', async () => {
    const athlete = own();
    backend.addResponsable(athlete.id, 'familia-1', { nombre: 'Ana', apellido: 'Alvarez', tipo: 'MADRE', tutorId: 't-1' });
    backend.addResponsable(athlete.id, 'familia-1', { nombre: 'Beto', apellido: 'Benitez', tipo: null, tutorId: 't-2' });
    backend.addResponsable(athlete.id, 'familia-1', { nombre: 'Carla', apellido: 'Campos', tipo: 'SIN_DETERMINAR', tutorId: 't-3' });
    renderApp(`/familia/deportistas/${athlete.id}`);

    const section = await screen.findByRole('region', { name: 'Responsables' });
    const items = await within(section).findAllByRole('listitem');
    expect(items.map((i) => i.textContent)).toEqual(['Ana AlvarezMadre', 'Beto BenitezSin declarar', 'Carla CamposSin determinar']);
    expect(within(section).getByText(/No acredita representación legal/)).toBeInTheDocument();
    expect(section).not.toHaveTextContent(/historial|DNI|teléfono|versión/i);
    expect(within(section).queryByRole('button')).not.toBeInTheDocument();
    expect(within(section).queryByRole('link')).not.toBeInTheDocument();
  });

  it('says there are none yet, without alarm, when the athlete is own but has no open responsabilities', async () => {
    const athlete = own();
    renderApp(`/familia/deportistas/${athlete.id}`);
    const section = await screen.findByRole('region', { name: 'Responsables' });
    expect(await within(section).findByText(/Todavía no hay responsables registrados/)).toBeInTheDocument();
    expect(within(section).queryByRole('list')).not.toBeInTheDocument();
  });

  it('shows a retryable error that keeps the rest of the sheet, and recovers on retry', async () => {
    const athlete = own();
    backend.addResponsable(athlete.id, 'familia-1', { nombre: 'Ana', apellido: 'Alvarez', tipo: 'MADRE', tutorId: 't-1' });
    backend.override('get', responsablesUrl(athlete.id), { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } }, { once: true });
    renderApp(`/familia/deportistas/${athlete.id}`);
    const section = await screen.findByRole('region', { name: 'Responsables' });
    expect(await within(section).findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Ana Paz' })).toBeInTheDocument();
    await userEvent.click(within(section).getByRole('button', { name: 'Reintentar' }));
    expect(await within(section).findByText('Ana Alvarez')).toBeInTheDocument();
  });
});

describe('responsables in the athlete sheet: the link is revoked while it is open (mock backend)', () => {
  it('replaces the list with the error after a refresh answers 404, so nothing of the revoked athlete stays on screen', async () => {
    const athlete = own();
    backend.addResponsable(athlete.id, 'familia-1', { nombre: 'Ana', apellido: 'Alvarez', tipo: 'MADRE', tutorId: 't-1' });
    const { queryClient } = renderApp(`/familia/deportistas/${athlete.id}`);
    const section = await screen.findByRole('region', { name: 'Responsables' });
    expect(await within(section).findByText('Ana Alvarez')).toBeInTheDocument();

    backend.revokeLink('familia-1', athlete.id);
    await queryClient.refetchQueries({ queryKey: familyKeys.responsables({ userId: 'u-family', familyId: 'familia-1' }, athlete.id) });
    await waitFor(() => expect(within(section).getByRole('alert')).toBeInTheDocument());
    expect(section).not.toHaveTextContent('Ana Alvarez');
  });
});

describe('responsables: queries respect the authenticated identity', () => {
  const identityA: FamilyIdentity = { userId: 'u-a', familyId: 'familia-1' };
  const identityB: FamilyIdentity = { userId: 'u-b', familyId: 'familia-2' };

  it('keys by user, family and athlete so another identity or athlete never shares an entry', () => {
    const keyA = familyKeys.responsables(identityA, 'dep-1');
    expect(familyKeys.responsables(identityB, 'dep-1')).not.toEqual(keyA);
    expect(familyKeys.responsables(identityA, 'dep-2')).not.toEqual(keyA);
    expect(familyKeys.responsables(null, 'dep-1')).not.toEqual(keyA);
    expect(familyKeys.responsables(identityA, 'dep-1')).toEqual(keyA);
  });

  it('never requests anything without a session, without a family or with an id that is not a UUID', async () => {
    const athlete = own();
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={createQueryClient()}>{children}</QueryClientProvider>;
    renderHook(() => useAthleteResponsables(null, athlete.id), { wrapper });
    renderHook(() => useAthleteResponsables({ userId: 'u', familyId: null }, athlete.id), { wrapper });
    renderHook(() => useAthleteResponsables(identityA, 'no-es-un-uuid'), { wrapper });
    renderHook(() => useAthleteResponsables(identityA, undefined), { wrapper });
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(calls()).toEqual([]);
  });
});
