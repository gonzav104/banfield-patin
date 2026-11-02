import test from 'node:test';
import assert from 'node:assert/strict';
import { construirEnlace, ValorInvalidoError } from '../src/precompletado/enlace.mjs';
import { valoresDe, PERFILES } from '../src/datos/ficticios.mjs';
import { perfil, clonar } from './ayudas.mjs';

const parametros = (url) => Object.fromEntries(new URL(url).searchParams);
const clave = (form, c) => perfil(form).preguntas.find((p) => p.clave === c);
const enlace = (form, nombre, extra = {}) => construirEnlace({ perfil: perfil(form), valores: valoresDe(nombre), ...extra });

test('arma el enlace con el formulario del perfil y sólo el club autorizado', () => {
  const r = enlace('apto', 'menor'); const u = new URL(r.url); const q = parametros(r.url);
  assert.equal(u.origin + u.pathname, `https://docs.google.com/forms/d/e/${perfil('apto').formId}/viewform`);
  assert.equal(q.usp, 'pp_url');
  assert.equal(Object.keys(q).length, 2);
  assert.equal(q[`entry.${clave('apto', 'escuela.club').entry}`], 'Club Social y Deportivo Banfield San Pedro');
});

test('es determinista: el mismo perfil y los mismos valores dan siempre la misma URL', () => {
  assert.equal(enlace('padron', 'menor').url, enlace('padron', 'menor').url);
});

test('no incluye identificadores, contacto ni matrícula en la URL', () => {
  const q = parametros(enlace('padron', 'menor').url);
  for (const c of ['deportista.dni', 'deportista.cuil', 'deportista.telefono', 'apto.medicoMatricula']) assert.equal(q[`entry.${clave('padron', c).entry}`], undefined, c);
});

test('el formato de fecha no altera la política: ninguna fecha se incluye', () => {
  const p = clonar(perfil('apto')); p.fechaFormato = 'partes';
  const q = parametros(construirEnlace({ perfil: p, valores: valoresDe('menor') }).url); const e = clave('apto', 'apto.fechaEmision').entry;
  assert.deepEqual([q[`entry.${e}_year`], q[`entry.${e}_month`], q[`entry.${e}_day`], q[`entry.${e}`]], [undefined, undefined, undefined, undefined]);
});

test('los nombres, domicilio y correo con caracteres especiales quedan fuera', () => {
  const q = parametros(enlace('padron', 'condicional').url);
  for (const c of ['deportista.apellidos', 'deportista.nombres', 'deportista.domicilio', 'deportista.otrosDatosDomicilio', 'deportista.email']) assert.equal(q[`entry.${clave('padron', c).entry}`], undefined, c);
});

test('un valor público inválido falla sin revelar el valor en el error', () => {
  const secreto = 'VALOR_FICTICIO_PRIVADO';
  assert.throws(() => construirEnlace({ perfil: perfil('padron'), valores: { ...valoresDe('menor'), 'escuela.club': secreto } }), (e) => e instanceof ValorInvalidoError && /escuela\.club/.test(e.message) && !e.message.includes(secreto));
});

test('nunca precarga archivos y los informa como omitidos', () => {
  const r = enlace('padron', 'menor'); const q = parametros(r.url);
  for (const p of perfil('padron').preguntas.filter((x) => x.formato === 'archivo')) {
    assert.equal(q[`entry.${p.entry}`], undefined);
    assert.ok(r.omitidos.some((o) => o.clave === p.clave && /archivo/.test(o.motivo)));
  }
});

test('los datos faltantes o vacíos se omiten con su motivo (no se envía un parámetro vacío)', () => {
  const r = construirEnlace({ perfil: perfil('padron'), valores: { ...valoresDe('mayor'), 'escuela.club': '' } });
  assert.ok(r.omitidos.some((o) => o.clave === 'escuela.club' && o.motivo === 'sin dato'));
  assert.ok(!Object.keys(parametros(r.url)).some((k) => parametros(r.url)[k] === ''));
});

test('la sensibilidad máxima sólo restringe; no autoriza datos personales', () => {
  const todo = enlace('padron', 'menor'); const baja = enlace('padron', 'menor', { sensibilidadMaxima: 'baja' }); const media = enlace('padron', 'menor', { sensibilidadMaxima: 'media' });
  assert.equal(baja.incluidos.length, 1); assert.equal(media.incluidos.length, 1); assert.equal(todo.incluidos.length, 1);
  const q = parametros(baja.url);
  for (const c of ['deportista.dni', 'deportista.cuil', 'deportista.domicilio', 'deportista.email', 'deportista.telefono', 'deportista.fechaNacimiento', 'tutor.madre.dni']) assert.equal(q[`entry.${clave('padron', c).entry}`], undefined, c);
  assert.throws(() => enlace('padron', 'menor', { sensibilidadMaxima: 'extrema' }), /sensibilidadMaxima/);
});

test('los radios con saltos quedan fuera: L2 no demostró su comportamiento de precarga', () => {
  const incluidas = enlace('padron', 'menor').incluidos.map((i) => i.clave);
  for (const c of ['apto.tiene', 'wada.tiene', 'deportista.esMenor']) assert.ok(!incluidas.includes(c));
});

test('los tres perfiles ficticios no contienen datos reales: DNI 9xxxxxxx y correos .invalid', () => {
  for (const [nombre, v] of Object.entries(PERFILES)) {
    assert.match(v['deportista.dni'], /^9\d{7}$/, nombre); assert.match(v['deportista.email'], /@example\.invalid$/, nombre);
  }
});
