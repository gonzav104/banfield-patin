import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import type { FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';

// MOCK backend. Real-backend evidence: e2e/mis-datos.real.spec.ts (invitation to a concrete person).
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  backend.signIn(admin.email);
});

describe('invite a person who already figures in the family (mock backend)', () => {
  it('offers the active people of the chosen family and sends the chosen one with the invitation', async () => {
    const family = backend.addFamily('Gómez', {
      tutores: [
        { id: 'tut-1', nombre: 'Laura', apellido: 'Gómez', dniEnmascarado: null, telefono: null, email: null, parentesco: 'Madre', activo: true },
        { id: 'tut-2', nombre: 'Inactivo', apellido: 'Gómez', dniEnmascarado: null, telefono: null, email: null, parentesco: null, activo: false },
      ],
    });
    renderApp('/admin/invitaciones');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /Nueva invitación/ }));
    await user.click(await screen.findByLabelText('Familia existente'));
    await user.type(await screen.findByLabelText('Buscar familia por nombre'), 'Gómez');
    await user.click(await screen.findByRole('radio', { name: /Gómez/ }));

    const select = await screen.findByLabelText(/¿Es para alguien que ya figura en la familia\?/);
    expect(screen.getByRole('option', { name: 'Laura Gómez (Madre)' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /Inactivo/ })).not.toBeInTheDocument();
    await user.selectOptions(select, 'tut-1');
    await user.click(screen.getByRole('button', { name: /Crear invitación/ }));

    await screen.findByText(/registro/i);
    const write = backend.requests.find((r) => r.method === 'post' && r.url === '/admin/invitaciones');
    expect(write?.body).toMatchObject({ familiaId: family.id, tutorId: 'tut-1' });
  });

  it('a new family never offers a person to invite', async () => {
    renderApp('/admin/invitaciones');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /Nueva invitación/ }));
    expect(screen.queryByLabelText(/¿Es para alguien que ya figura en la familia\?/)).not.toBeInTheDocument();
  });
});
