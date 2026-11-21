import { describe, expect, it } from 'vitest';
import { ApiError } from '@/lib/api-error';
import { uploadFailure } from './upload-outcome';

const error = (kind: ConstructorParameters<typeof ApiError>[0], status: number | null, code: string | null) =>
  new ApiError(kind, 'server text that must never be shown', { status, code });

describe('uploadFailure: what to do after a failed upload', () => {
  it.each([
    ['a lost response (network)', error('network', null, null)],
    ['an uncertain result', error('unavailable', 503, 'RESULTADO_CARGA_INCIERTO')],
    ['the upload being unavailable', error('unavailable', 503, 'CARGA_NO_DISPONIBLE')],
    ['no capacity', error('unknown', 429, 'CARGA_OCUPADA')],
    ['a gateway timeout', error('unavailable', 504, null)],
    ['an internal error', error('server', 500, 'ERROR_INTERNO')],
    ['a stale CSRF token', error('csrf', 403, 'CSRF_INVALIDO')],
  ])('keeps the SAME key and bytes after %s (retrying is safe: the server never stores twice)', (_name, failure) => {
    expect(uploadFailure(failure).action).toBe('retry-same');
  });

  it('tells the family the result is unknown and that retrying will not duplicate the document', () => {
    for (const failure of [error('network', null, null), error('unavailable', 503, 'RESULTADO_CARGA_INCIERTO')]) {
      const outcome = uploadFailure(failure);
      expect(outcome.message).toMatch(/no pudimos confirmar/i);
      expect(outcome.message).toMatch(/no se va a duplicar/i);
      expect(outcome.message).not.toMatch(/falló|se subió|se guardó correctamente/i);
    }
  });

  it.each([
    [413, 'ARCHIVO_DEMASIADO_GRANDE', /tamaño máximo/i],
    [422, 'ARCHIVO_INVALIDO', /no pudimos aceptar este archivo/i],
    [422, 'ARCHIVO_PELIGROSO', /control de seguridad/i],
    [415, 'TIPO_NO_SOPORTADO', /JPG o PNG, o un PDF/i],
    [400, 'SOLICITUD_INVALIDA', /JPG o PNG, o un PDF/i],
  ])('a %i %s asks for another file and drops the attempt', (status, code, text) => {
    const outcome = uploadFailure(error(status === 400 ? 'validation' : 'unknown', status, code));
    expect(outcome.action).toBe('choose-other-file');
    expect(outcome.message).toMatch(text);
  });

  it('a 409 OPERACION_EN_CURSO keeps the key and asks for a manual retry, without saying it failed', () => {
    const outcome = uploadFailure(error('conflict', 409, 'OPERACION_EN_CURSO'));
    expect(outcome.action).toBe('retry-same');
    expect(outcome.message).toMatch(/todavía se está procesando/i);
    expect(outcome.message).toMatch(/no se va a duplicar/i);
  });

  it('an unknown 409 code keeps today\'s behavior: it drops the attempt and refreshes', () => {
    expect(uploadFailure(error('conflict', 409, 'CODIGO_FUTURO'))).toMatchObject({ action: 'refresh' });
    expect(uploadFailure(error('conflict', 409, null))).toMatchObject({ action: 'refresh' });
  });

  it('a 409 drops the attempt and refreshes the documents: the operation cannot be repeated with that key', () => {
    const outcome = uploadFailure(error('conflict', 409, 'OPERACION_NO_REPETIBLE'));
    expect(outcome.action).toBe('refresh');
    expect(outcome.message).toMatch(/actualizamos la información/i);
  });

  it('while replacing, a 409 says the document was already replaced or changed and refreshes', () => {
    const outcome = uploadFailure(error('conflict', 409, 'OPERACION_NO_REPETIBLE'), null, true);
    expect(outcome.action).toBe('refresh');
    expect(outcome.message).toMatch(/ya fue reemplazado o cambió de estado/i);
    expect(uploadFailure(error('conflict', 409, 'OPERACION_EN_CURSO'), null, true).action).toBe('retry-same');
  });

  it('a 404 DOCUMENTO_NO_ENCONTRADO refreshes with its own message; the athlete 404 keeps its message', () => {
    expect(uploadFailure(error('unknown', 404, 'DOCUMENTO_NO_ENCONTRADO'), null, true)).toMatchObject({
      action: 'refresh',
      message: expect.stringMatching(/ya no está disponible/i) as string,
    });
    expect(uploadFailure(error('unknown', 404, 'DEPORTISTA_NO_ENCONTRADO'))).toMatchObject({ message: expect.stringMatching(/deportista/i) as string });
    // The original upload is unchanged: without `replacing`, the same code keeps the generic 404 message.
    expect(uploadFailure(error('unknown', 404, 'DOCUMENTO_NO_ENCONTRADO'))).toMatchObject({ action: 'refresh', message: expect.stringMatching(/deportista/i) as string });
  });

  it('a 404 is the uniform "athlete not found" answer', () => {
    expect(uploadFailure(error('unknown', 404, 'DEPORTISTA_NO_ENCONTRADO'))).toMatchObject({ action: 'refresh' });
  });

  it('a 401 is a lost session and a 403 is a missing permission', () => {
    expect(uploadFailure(error('unauthenticated', 401, 'NO_AUTENTICADO')).action).toBe('session-expired');
    expect(uploadFailure(error('forbidden', 403, 'ACCESO_DENEGADO'))).toMatchObject({ action: 'stop' });
  });

  it('never shows the raw server message', () => {
    const failures = [
      error('network', null, null),
      error('unknown', 413, 'ARCHIVO_DEMASIADO_GRANDE'),
      error('conflict', 409, 'OPERACION_NO_REPETIBLE'),
      error('unknown', 418, 'ALGO_NUEVO'),
    ];
    for (const failure of failures) expect(uploadFailure(failure).message).not.toContain('server text');
  });
});
