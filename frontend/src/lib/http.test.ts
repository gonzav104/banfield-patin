import type { InternalAxiosRequestConfig } from 'axios';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './api-error';
import { apiGet, apiGetBlob, apiWrite, BLOB_TIMEOUT_MS, http, setSessionEventListener } from './http';
import { createBackend } from '@/test/render';

afterEach(() => setSessionEventListener(null));

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

  it('sends extra headers on a write, but never lets them replace the CSRF header', async () => {
    const backend = createBackend();
    await apiWrite('post', '/auth/login', { email: 'lucia@example.com', password: 'correct-horse' }, undefined, {
      headers: { 'Idempotency-Key': '7d0c8a2e-1111-4222-8333-444455556666', 'X-XSRF-TOKEN': 'forged' },
    });

    const [write] = backend.writes();
    expect(write?.idempotencyKey).toBe('7d0c8a2e-1111-4222-8333-444455556666');
    expect(write?.csrfHeader).toMatch(/^csrf-/);
  });

  it('keeps the previous behaviour of a write without options (no extra headers)', async () => {
    const backend = createBackend();
    await apiWrite('post', '/auth/login', { email: 'lucia@example.com', password: 'correct-horse' });

    const [write] = backend.writes();
    expect(write?.idempotencyKey).toBeNull();
    expect(write?.body).toEqual({ email: 'lucia@example.com', password: 'correct-horse' });
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

  it('reports "lost" on 401 NO_AUTENTICADO but nothing on wrong credentials', async () => {
    const backend = createBackend();
    const listener = vi.fn();
    setSessionEventListener(listener);
    backend.override('get', '/admin/invitaciones', { status: 401, body: { codigo: 'NO_AUTENTICADO', mensaje: 'x' } });
    await expect(http.get('/admin/invitaciones')).rejects.toBeInstanceOf(ApiError);
    expect(listener).toHaveBeenCalledWith('lost');

    listener.mockClear();
    backend.override('post', '/auth/login', { status: 401, body: { codigo: 'CREDENCIALES_INVALIDAS', mensaje: 'x' } });
    await expect(apiWrite('post', '/auth/login', {})).rejects.toBeInstanceOf(ApiError);
    expect(listener).not.toHaveBeenCalled();
  });

  it('reports "suspect" (re-check, do not drop) on a 401 without a code from an authenticated endpoint', async () => {
    const backend = createBackend();
    const listener = vi.fn();
    setSessionEventListener(listener);
    backend.override('get', '/admin/invitaciones?page=0', { status: 401, body: '<html>proxy</html>' });
    await expect(http.get('/admin/invitaciones?page=0')).rejects.toMatchObject({ kind: 'unauthenticated', code: null });
    expect(listener).toHaveBeenCalledWith('suspect');
  });

  it.each(['/auth/me', '/auth/login', '/auth/admin/login', '/auth/invitaciones/validar', '/auth/registro/invitacion'])(
    'does not raise a session event for a 401 without a code from %s',
    async (url) => {
      const backend = createBackend();
      const listener = vi.fn();
      setSessionEventListener(listener);
      backend.override(url === '/auth/me' ? 'get' : 'post', url, { status: 401 });
      await expect(url === '/auth/me' ? http.get(url) : apiWrite('post', url, {})).rejects.toBeInstanceOf(ApiError);
      expect(listener).not.toHaveBeenCalled();
    },
  );

  it.each([503, 'network'] as const)('does not raise a session event for %s', async (failure) => {
    const backend = createBackend();
    const listener = vi.fn();
    setSessionEventListener(listener);
    backend.override('get', '/admin/invitaciones', failure === 'network' ? 'network' : { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } });
    await expect(http.get('/admin/invitaciones')).rejects.toBeInstanceOf(ApiError);
    expect(listener).not.toHaveBeenCalled();
  });
});

/**
 * Differential check of the additive `apiGetBlob` (U16): the request config that apiGet and apiWrite hand to the adapter is pinned
 * to the values they produced before it existed (observed on the base commit).
 */
describe('http client: request config of apiGet and apiWrite is unchanged', () => {
  function captureConfigs(): InternalAxiosRequestConfig[] {
    createBackend(); // clears the CSRF cookie
    const configs: InternalAxiosRequestConfig[] = [];
    http.defaults.adapter = (config) => {
      configs.push(config);
      if (config.url === '/auth/csrf') document.cookie = 'XSRF-TOKEN=token-1; path=/';
      return Promise.resolve({ data: config.url === '/auth/csrf' ? { headerName: 'X-XSRF-TOKEN' } : { ok: true }, status: 200, statusText: 'OK', headers: {}, config, request: {} });
    };
    return configs;
  }
  const shape = (config: InternalAxiosRequestConfig | undefined) => ({
    method: config?.method,
    url: config?.url,
    timeout: config?.timeout,
    responseType: config?.responseType,
    defaultValidateStatus: config?.validateStatus === http.defaults.validateStatus,
    withCredentials: config?.withCredentials,
    headers: Object.fromEntries(Object.entries(config?.headers.toJSON() ?? {}).sort(([a], [b]) => a.localeCompare(b))),
    data: config?.data as unknown,
    hasUploadProgress: typeof config?.onUploadProgress === 'function',
  });

  it('apiGet', async () => {
    const configs = captureConfigs();
    await expect(apiGet('/admin/deportistas?page=0')).resolves.toEqual({ ok: true });
    expect(configs.map(shape)).toEqual([
      {
        method: 'get',
        url: '/admin/deportistas?page=0',
        timeout: 15_000,
        responseType: undefined,
        defaultValidateStatus: true,
        withCredentials: true,
        headers: { Accept: 'application/json' },
        data: undefined,
        hasUploadProgress: false,
      },
    ]);
  });

  it('apiWrite without options', async () => {
    const configs = captureConfigs();
    await apiWrite('post', '/auth/logout', { a: 1 });
    expect(configs.map(shape)).toEqual([
      { method: 'get', url: '/auth/csrf', timeout: 15_000, responseType: undefined, defaultValidateStatus: true, withCredentials: true, headers: { Accept: 'application/json' }, data: undefined, hasUploadProgress: false },
      {
        method: 'post',
        url: '/auth/logout',
        timeout: 15_000,
        responseType: undefined,
        defaultValidateStatus: true,
        withCredentials: true,
        headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'X-XSRF-TOKEN': 'token-1' },
        data: '{"a":1}',
        hasUploadProgress: false,
      },
    ]);
  });

  it('apiWrite with options', async () => {
    const configs = captureConfigs();
    await apiWrite('put', '/admin/deportistas/dep-1', { b: 2 }, undefined, { headers: { 'Idempotency-Key': 'k-1', 'x-xsrf-token': 'forged' }, timeout: 90_000, onUploadProgress: () => undefined });
    expect(shape(configs[1])).toEqual({
      method: 'put',
      url: '/admin/deportistas/dep-1',
      timeout: 90_000,
      responseType: undefined,
      defaultValidateStatus: true,
      withCredentials: true,
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'Idempotency-Key': 'k-1', 'X-XSRF-TOKEN': 'token-1' },
      data: '{"b":2}',
      hasUploadProgress: true,
    });
  });
});

/** jsdom's Blob has neither text() nor arrayBuffer(). */
function readText(blob: Blob): Promise<string> {
  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '');
    reader.readAsText(blob);
  });
}

describe('http client: apiGetBlob (mock backend)', () => {
  function seedFile(backend: ReturnType<typeof createBackend>, file: Blob, disposition?: string | null) {
    backend.signIn('marcos@example.com');
    const athlete = backend.addAdminAthlete({ nombre: 'Ana', apellido: 'Prueba', dni: '30111222' });
    const document = backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE' });
    if (disposition === undefined) backend.attachDocumentFile(document.id, file);
    else backend.attachDocumentFile(document.id, file, disposition);
    return `/admin/deportistas/${athlete.id}/documentos/${document.id}/archivo`;
  }

  it('asks for a fresh CSRF token and sends it on the GET, returning the bytes with their type and disposition', async () => {
    const backend = createBackend();
    const url = seedFile(backend, new Blob(['%PDF-1.7'], { type: 'application/pdf' }), 'attachment; filename="documento.pdf"');

    const result = await apiGetBlob(url);

    expect(backend.requests.map((r) => `${r.method} ${r.url}`)).toEqual(['get /auth/csrf', `get ${url}`]);
    expect(backend.requests[1]?.csrfHeader).toMatch(/^csrf-/);
    expect(result.contentType).toBe('application/pdf');
    expect(result.contentDisposition).toBe('attachment; filename="documento.pdf"');
    expect(await readText(result.blob)).toBe('%PDF-1.7');
  });

  it('never lets an extra header replace the CSRF header', async () => {
    const backend = createBackend();
    const url = seedFile(backend, new Blob(['x'], { type: 'image/png' }));
    await apiGetBlob(url, { headers: { 'X-XSRF-TOKEN': 'forged', 'x-xsrf-token': 'forged-too' } });
    expect(backend.requests[1]?.csrfHeader).toMatch(/^csrf-/);
  });

  it('uses a longer timeout than the JSON requests, and accepts another one', async () => {
    createBackend();
    const timeouts: (number | undefined)[] = [];
    http.defaults.adapter = (config) => {
      timeouts.push(config.timeout);
      if (config.url === '/auth/csrf') document.cookie = 'XSRF-TOKEN=token-1; path=/';
      return Promise.resolve({ data: config.url === '/auth/csrf' ? {} : new Blob(['x'], { type: 'image/png' }), status: 200, statusText: 'OK', headers: { 'content-type': 'image/png' }, config, request: {} });
    };
    await apiGetBlob('/file');
    await apiGetBlob('/file', { timeout: 5_000 });
    expect(BLOB_TIMEOUT_MS).toBe(60_000);
    expect(timeouts).toEqual([15_000, 60_000, 15_000, 5_000]);
  });

  it('does not send the request when the CSRF cookie is unavailable', async () => {
    const backend = createBackend();
    const url = seedFile(backend, new Blob(['x'], { type: 'image/png' }));
    backend.override('get', '/auth/csrf', { status: 200, body: { headerName: 'X-XSRF-TOKEN' } });
    await expect(apiGetBlob(url)).rejects.toMatchObject({ kind: 'csrf' });
    expect(backend.requests.filter((r) => r.url === url)).toHaveLength(0);
  });

  it.each([
    [502, 'INTEGRIDAD_FALLIDA', 'unavailable'],
    [409, 'INTEGRIDAD_NO_VERIFICABLE', 'conflict'],
    [429, 'DESCARGA_OCUPADA', 'unknown'],
    [503, 'DESCARGA_NO_DISPONIBLE', 'unavailable'],
    [404, 'DOCUMENTO_NO_ENCONTRADO', 'unknown'],
    [403, 'ACCESO_DENEGADO', 'forbidden'],
    [403, 'CSRF_INVALIDO', 'csrf'],
  ])('reads an error body that arrives as a Blob: HTTP %i %s -> "%s" with its code', async (status, codigo, kind) => {
    const backend = createBackend();
    const url = seedFile(backend, new Blob(['x'], { type: 'image/png' }));
    backend.override('get', url, { status, body: { codigo, mensaje: 'texto del servidor', detalles: [] } });
    const error = await apiGetBlob(url).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ kind, status, code: codigo });
    expect(backend.requests.filter((r) => r.url === url)).toHaveLength(1); // never retried
  });

  it('really receives the error body as a Blob (like a browser) and still reads its code', async () => {
    const backend = createBackend();
    const url = seedFile(backend, new Blob(['x'], { type: 'image/png' }));
    const bodies: unknown[] = [];
    const original = http.defaults.adapter as (config: InternalAxiosRequestConfig) => Promise<unknown>;
    http.defaults.adapter = (config) =>
      original(config).then((response) => {
        bodies.push((response as { data: unknown }).data);
        return response as never;
      });
    backend.override('get', url, { status: 502, body: { codigo: 'INTEGRIDAD_FALLIDA', mensaje: 'x' } });
    await expect(apiGetBlob(url)).rejects.toMatchObject({ code: 'INTEGRIDAD_FALLIDA' });
    expect(bodies.at(-1)).toBeInstanceOf(Blob);
  });

  it('keeps the status and no code for an error body that is not JSON', async () => {
    const backend = createBackend();
    const url = seedFile(backend, new Blob(['x'], { type: 'image/png' }));
    backend.override('get', url, { status: 502, body: '<html>proxy</html>' });
    await expect(apiGetBlob(url)).rejects.toMatchObject({ kind: 'unavailable', status: 502, code: null });
  });

  it('raises "lost" (not "suspect") on a 401 NO_AUTENTICADO read from a Blob body', async () => {
    const backend = createBackend();
    const url = seedFile(backend, new Blob(['x'], { type: 'image/png' }));
    const listener = vi.fn();
    setSessionEventListener(listener);
    backend.expireSession();
    await expect(apiGetBlob(url)).rejects.toMatchObject({ kind: 'unauthenticated', code: 'NO_AUTENTICADO' });
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith('lost');
  });

  it('maps a missing response to the "network" kind', async () => {
    const backend = createBackend();
    const url = seedFile(backend, new Blob(['x'], { type: 'image/png' }));
    backend.override('get', url, 'network');
    await expect(apiGetBlob(url)).rejects.toMatchObject({ kind: 'network', status: null });
  });

  it('normalises the Content-Type (parameters dropped, lower case) and reports a missing one as null', async () => {
    const backend = createBackend();
    const url = seedFile(backend, new Blob(['x'], { type: 'image/PNG; charset=binary' }), null);
    const result = await apiGetBlob(url);
    expect(result.contentType).toBe('image/png');
    expect(result.contentDisposition).toBeNull();
  });
});
