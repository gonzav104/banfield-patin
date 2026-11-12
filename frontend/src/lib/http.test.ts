import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './api-error';
import { apiWrite, http, setUnauthenticatedListener } from './http';
import { createBackend } from '@/test/render';

afterEach(() => setUnauthenticatedListener(null));

describe('http client (mock backend)', () => {
  it('requests a CSRF token and sends it in the header before every write', async () => {
    const backend = createBackend();
    await apiWrite('post', '/auth/login', { email: 'lucia@example.com', password: 'correct-horse' });

    const urls = backend.requests.map((r) => `${r.method} ${r.url}`);
    expect(urls).toEqual(['get /auth/csrf', 'post /auth/login']);
    expect(backend.writes()[0]?.csrfHeader).toMatch(/^csrf-/);
  });

  it('asks for a fresh CSRF token on each write instead of caching it across session changes', async () => {
    const backend = createBackend();
    await apiWrite('post', '/auth/login', { email: 'lucia@example.com', password: 'correct-horse' });
    await apiWrite('post', '/auth/logout');

    const csrfCalls = backend.requests.filter((r) => r.url === '/auth/csrf');
    expect(csrfCalls).toHaveLength(2);
    const [login, logout] = backend.writes();
    expect(login?.csrfHeader).not.toBeNull();
    expect(logout?.csrfHeader).not.toBeNull();
    expect(logout?.csrfHeader).not.toBe(login?.csrfHeader);
  });

  it('does not send the write when the CSRF cookie is unavailable', async () => {
    const backend = createBackend();
    // Backend that answers /csrf but never exposes the cookie.
    backend.override('get', '/auth/csrf', { status: 200, body: { headerName: 'X-XSRF-TOKEN', token: 'x' } });

    await expect(apiWrite('post', '/auth/logout')).rejects.toMatchObject({ kind: 'csrf' });
    expect(backend.writes()).toHaveLength(0);
  });

  it('never retries a failed write', async () => {
    const backend = createBackend();
    backend.override('post', '/auth/logout', { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } });

    await expect(apiWrite('post', '/auth/logout')).rejects.toMatchObject({ kind: 'unavailable' });
    expect(backend.writes()).toHaveLength(1);
  });

  it.each([
    [401, 'NO_AUTENTICADO', 'unauthenticated'],
    [403, 'CSRF_INVALIDO', 'csrf'],
    [403, 'ACCESO_DENEGADO', 'forbidden'],
    [400, 'SOLICITUD_INVALIDA', 'validation'],
    [409, 'MFA_ESTADO_INVALIDO', 'conflict'],
    [503, 'SERVICIO_NO_DISPONIBLE', 'unavailable'],
    [500, 'ERROR_INTERNO', 'server'],
  ])('maps HTTP %i %s to the "%s" kind', async (status, codigo, kind) => {
    const backend = createBackend();
    backend.override('get', '/auth/me', { status, body: { codigo, mensaje: 'msg' } });

    const error = await http.get('/auth/me').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ kind, status, code: codigo });
  });

  it('maps a missing response to the "network" kind', async () => {
    const backend = createBackend();
    backend.override('get', '/auth/me', 'network');

    await expect(http.get('/auth/me')).rejects.toMatchObject({ kind: 'network', status: null });
  });

  it('notifies the listener on 401 NO_AUTENTICADO but not on wrong credentials', async () => {
    const backend = createBackend();
    const listener = vi.fn();
    setUnauthenticatedListener(listener);

    await expect(http.get('/auth/me')).rejects.toBeInstanceOf(ApiError); // no session
    expect(listener).toHaveBeenCalledTimes(1);

    listener.mockClear();
    backend.override('post', '/auth/login', { status: 401, body: { codigo: 'CREDENCIALES_INVALIDAS', mensaje: 'x' } });
    await expect(apiWrite('post', '/auth/login', {})).rejects.toBeInstanceOf(ApiError);
    expect(listener).not.toHaveBeenCalled();
  });
});
