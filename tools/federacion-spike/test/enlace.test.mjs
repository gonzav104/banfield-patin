import test from 'node:test';
import assert from 'node:assert/strict';
import { construirEnlace, ValorInvalidoError } from '../src/precompletado/enlace.mjs';
import { valoresDe, PERFILES } from '../src/datos/ficticios.mjs';
import { perfil, clonar } from './ayudas.mjs';

const parametros = (url) => Object.fromEntries(new URL(url).searchParams);
const clave = (form, c) => perfil(form).preguntas.find((p) => p.clave === c);
const enlace = (form, nombre, extra = {}) => construirEnlace({ perfil: perfil(form), valores: valoresDe(nombre), ...extra });

test('arma el enlace con el formulario del perfil, usp=pp_url y los entry de cada pregunta', () => {
  const r = enlace('apto', 'menor'); const u = new URL(r.url); const q = parametros(r.url);
  assert.equal(u.origin + u.pathname, `https://docs.google.com/forms/d/e/${perfil('apto').formId}/viewform`);
  assert.equal(q.usp, 'pp_url');
  assert.equal(q[`entry.${clave('apto', 'deportista.dni').entry}`], '90000001');
  assert.equal(q[`entry.${clave('apto', 'escuela.club').entry}`], 'Club Social y Deportivo Banfield San Pedro');
});

test('es determinista: el mismo perfil y los mismos valores dan siempre la misma URL', () => {
  assert.equal(enlace('padron', 'menor').url, enlace('padron', 'menor').url);
});

test('normaliza los datos que el formulario valida como número (DNI, CUIL, teléfono, matrícula)', () => {
  const q = parametros(enlace('padron', 'menor').url);
  assert.equal(q[`entry.${clave('padron', 'deportista.telefono').entry}`], '1155550001');
  assert.equal(q[`entry.${clave('padron', 'apto.medicoMatricula').entry}`], '100001');
  assert.equal(q[`entry.${clave('padron', 'deportista.cuil').entry}`], '27900000014');
});

test('las fechas van como AAAA-MM-DD, incluido el 29 de febrero; una fecha imposible falla', () => {
  assert.equal(parametros(enlace('padron', 'mayor').url)[`entry.${clave('padron', 'deportista.fechaNacimiento').entry}`], '2000-02-29');
  const v = { ...valoresDe('mayor'), 'deportista.fechaNacimiento': '2026-02-30' };
  assert.throws(() => construirEnlace({ perfil: perfil('padron'), valores: v }), (e) => e instanceof ValorInvalidoError && /fecha inválida/.test(e.message));
  assert.throws(() => construirEnlace({ perfil: perfil('padron'), valores: { ...v, 'deportista.fechaNacimiento': '20/02/2026' } }), ValorInvalidoError);
});

test('el formato "partes" separa año, mes y día (alternativa si el formulario no acepta ISO)', () => {
  const p = clonar(perfil('apto')); p.fechaFormato = 'partes';
  const q = parametros(construirEnlace({ perfil: p, valores: valoresDe('menor') }).url); const e = clave('apto', 'apto.fechaEmision').entry;
  assert.deepEqual([q[`entry.${e}_year`], q[`entry.${e}_month`], q[`entry.${e}_day`], q[`entry.${e}`]], ['2026', '3', '10', undefined]);
});

test('los caracteres especiales sobreviven a la codificación (ñ, tildes, &, #, +, comillas, comas, punto y coma)', () => {
  const q = parametros(enlace('padron', 'condicional').url);
  assert.equal(q[`entry.${clave('padron', 'deportista.apellidos').entry}`], "Núñez-Prueba D'Ficticio");
  assert.equal(q[`entry.${clave('padron', 'deportista.domicilio').entry}`], 'Calle & Inventada #7, 1° "A"');
  assert.equal(q[`entry.${clave('padron', 'deportista.otrosDatosDomicilio').entry}`], 'Barrio Ñuñoa; km 5,5 + fondo');
  assert.equal(q[`entry.${clave('padron', 'deportista.email').entry}`], 'ñandu+prueba@example.invalid');
});

test('un valor interno que no está en el mapa de opciones falla en vez de inventar un rótulo', () => {
  assert.throws(() => construirEnlace({ perfil: perfil('padron'), valores: { ...valoresDe('menor'), 'licencia.tipo': 'OTRA' } }), (e) => e instanceof ValorInvalidoError && /licencia\.tipo/.test(e.message));
});

test('nunca precarga archivos y los informa como omitidos', () => {
  const r = enlace('padron', 'menor'); const q = parametros(r.url);
  for (const p of perfil('padron').preguntas.filter((x) => x.formato === 'archivo')) {
    assert.equal(q[`entry.${p.entry}`], undefined);
    assert.ok(r.omitidos.some((o) => o.clave === p.clave && /archivo/.test(o.motivo)));
  }
});

test('los datos faltantes o vacíos se omiten con su motivo (no se envía un parámetro vacío)', () => {
  const r = enlace('padron', 'mayor');
  assert.ok(r.omitidos.some((o) => o.clave === 'deportista.otrosDatosDomicilio' && o.motivo === 'sin dato'));
  assert.ok(!Object.keys(parametros(r.url)).some((k) => parametros(r.url)[k] === ''));
});

test('la sensibilidad máxima excluye los datos más delicados de la URL', () => {
  const todo = enlace('padron', 'menor'); const baja = enlace('padron', 'menor', { sensibilidadMaxima: 'baja' }); const media = enlace('padron', 'menor', { sensibilidadMaxima: 'media' });
  assert.ok(baja.incluidos.length < media.incluidos.length && media.incluidos.length < todo.incluidos.length);
  const q = parametros(baja.url);
  for (const c of ['deportista.dni', 'deportista.cuil', 'deportista.domicilio', 'deportista.email', 'deportista.telefono', 'deportista.fechaNacimiento', 'tutor.madre.dni']) assert.equal(q[`entry.${clave('padron', c).entry}`], undefined, c);
  assert.throws(() => enlace('padron', 'menor', { sensibilidadMaxima: 'extrema' }), /sensibilidadMaxima/);
});

test('el padrón no puede precargar las preguntas que disparan saltos de sección sin avisar: se incluyen y se verifican en vivo', () => {
  // La prueba real (ver el plan ODD) mostró que Google ignora la precarga de los radios con saltos; el enlace igual los incluye
  // porque no daña, y la verificación en vivo es la que informa la limitación.
  const incluidas = enlace('padron', 'menor').incluidos.map((i) => i.clave);
  for (const c of ['apto.tiene', 'wada.tiene', 'deportista.esMenor']) assert.ok(incluidas.includes(c));
});

test('los tres perfiles ficticios no contienen datos reales: DNI 9xxxxxxx y correos .invalid', () => {
  for (const [nombre, v] of Object.entries(PERFILES)) {
    assert.match(v['deportista.dni'], /^9\d{7}$/, nombre); assert.match(v['deportista.email'], /@example\.invalid$/, nombre);
  }
});
