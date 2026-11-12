import type { QueryClient } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { App } from '@/app/App';
import { AppProviders, createQueryClient } from '@/app/providers';
import { FakeBackend, type FakeUser } from './fakeBackend';

export const family: FakeUser = {
  id: 'u-family',
  nombre: 'Lucía',
  apellido: 'Gómez',
  email: 'lucia@example.com',
  password: 'correct-horse',
  rol: 'FAMILIA',
};

export const admin: FakeUser = {
  id: 'u-admin',
  nombre: 'Marcos',
  apellido: 'Ruiz',
  email: 'marcos@example.com',
  password: 'admin-pass-123',
  rol: 'ADMIN',
  mfaEnrolado: true,
};

export const newAdmin: FakeUser = { ...admin, id: 'u-admin-2', email: 'nuevo@example.com', mfaEnrolado: false };

export function createBackend(): FakeBackend {
  const backend = new FakeBackend();
  backend.addUser({ ...family }).addUser({ ...admin }).addUser({ ...newAdmin });
  backend.install();
  return backend;
}

/** Exposes the current path to assertions (MemoryRouter has no real address bar). */
function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{`${location.pathname}${location.search}${location.hash}`}</output>;
}

/** Renders the whole app (real providers, router and guards) at `path`. */
export function renderApp(path: string): { queryClient: QueryClient } {
  const queryClient = createQueryClient();
  render(
    <AppProviders client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <App />
        <LocationProbe />
      </MemoryRouter>
    </AppProviders>,
  );
  return { queryClient };
}
