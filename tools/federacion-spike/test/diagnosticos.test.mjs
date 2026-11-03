import test from 'node:test';
import assert from 'node:assert/strict';
import { sanitizarBitacora, sanitizarInforme } from '../src/vivo/diagnosticos.mjs';

test('bitácora: conserva códigos y contadores, nunca texto libre, valores ni URLs', () => {
  const marca = 'PERSONA_FICTICIA_PRIVADA';
  const entrada = [{ accion: 'limpiar', ok: false, intentos: 2, motivo: `${marca} https://example.invalid/?entry.1=12345678`, vista: [marca], clics: [marca] }];
  const salida = sanitizarBitacora(entrada);
  assert.deepEqual(salida, [{ accion: 'limpiar', ok: false, intentos: 2 }]);
  assert.equal(JSON.stringify(salida).includes(marca), false);
});

test('reporte: persiste sólo estados y conteos de campos conocidos', () => {
  const marca = 'PERSONA_FICTICIA_PRIVADA';
  const informe = { fecha: '2026-11-02T00:00:00.000Z', url: `https://example.invalid/?x=${marca}`, formularios: {
    padron: { esquema: { compatible: true, diferencias: 0, huella: 'abc', crudo: marca }, perfiles: {
      menor: { enlace: { parametros: 1, largo: 100, url: marca }, final: { ok: 1, distinto: 0, vacio: 0, verificables: 1, porcentajeSobreVerificables: 100 }, filas: [{ clave: 'deportista.dni', estado: 'ok', esperado: marca, actual: marca }], alertas: [marca], bitacora: [{ accion: 'abrir', vista: marca }] },
    }, bitacora: [{ accion: 'limpiar', motivo: marca }] },
  } };
  const salida = sanitizarInforme(informe);
  assert.equal(JSON.stringify(salida).includes(marca), false);
  assert.equal(JSON.stringify(salida).includes('https://'), false);
  assert.equal(salida.formularios.padron.perfiles.menor.final.ok, 1);
  assert.equal(salida.formularios.padron.perfiles.menor.filas[0].estado, 'ok');
  assert.equal(salida.formularios.padron.perfiles.menor.filas[0].esperado, undefined);
});
