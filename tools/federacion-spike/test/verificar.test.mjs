import test from 'node:test';
import assert from 'node:assert/strict';
import { verificarPrecarga, resumir, extraerResultado, ESTADO } from '../src/vivo/verificar.mjs';
import { valoresDe } from '../src/datos/ficticios.mjs';
import { perfil, contrato } from './ayudas.mjs';

const control = (titulo, extra = {}) => ({ titulo, textos: [], fechas: [], radios: [], listas: [], archivo: false, alertas: [], ...extra });
const controles = (...cs) => ({ preguntas: cs, botones: ['Siguiente'] });
const filaDe = (filas, clave) => filas.find((f) => f.clave === clave);

test('distingue ok, distinto, vacío y no visible, y deja aparte archivos y datos faltantes', () => {
  const c = controles(control('DNI', { textos: ['90000001'] }), control('APELLIDOS', { textos: ['Otro'] }), control('NOMBRES'), control('Club', { listas: ['Club Social y Deportivo Banfield San Pedro'] }));
  const filas = verificarPrecarga({ controles: c, perfil: perfil('apto'), valores: valoresDe('menor'), contrato: contrato('apto') });
  assert.equal(filaDe(filas, 'deportista.dni').estado, ESTADO.OK);
  assert.equal(filaDe(filas, 'deportista.apellidos').estado, ESTADO.DISTINTO);
  assert.equal(filaDe(filas, 'deportista.nombres').estado, ESTADO.VACIO);
  assert.equal(filaDe(filas, 'escuela.club').estado, ESTADO.OK);
  assert.equal(filaDe(filas, 'apto.medicoNombre').estado, ESTADO.NO_VISIBLE);
  assert.equal(filaDe(filas, 'documento.APTO_FISICO').estado, ESTADO.ARCHIVO);
  const r = resumir(filas);
  assert.deepEqual([r.ok, r.distinto, r.vacio, r.noVisible, r.archivo], [2, 1, 1, 4, 1]);
  assert.equal(r.verificables, 4); assert.equal(r.porcentajeSobreVerificables, 50);
});

test('compara las fechas en ISO y los radios por su valor', () => {
  const c = controles(control('FECHA EMISION CERTIFICADO MEDICO', { fechas: ['2026-03-10'] }), control('Atleta empadronado?', { radios: ['Sí'] }));
  const filas = verificarPrecarga({ controles: c, perfil: perfil('apto'), valores: valoresDe('menor'), contrato: contrato('apto') });
  assert.equal(filaDe(filas, 'apto.fechaEmision').estado, ESTADO.OK); assert.equal(filaDe(filas, 'deportista.empadronado').estado, ESTADO.OK);
});

test('sin datos verificables el porcentaje es nulo y no se inventa un 100 %', () => {
  assert.equal(resumir([{ estado: ESTADO.ARCHIVO }]).porcentajeSobreVerificables, null);
});

test('extrae el JSON de la respuesta de browser_evaluate y falla si no hay resultado', () => {
  assert.deepEqual(extraerResultado('### Result\n{"a": {"b": [1, 2]}}\n### Ran Playwright code\n```js\nawait x\n```'), { a: { b: [1, 2] } });
  assert.throws(() => extraerResultado('### Error\nTimeout'), /### Result/);
});
