import test from 'node:test';
import assert from 'node:assert/strict';
import { construirEnlace } from '../src/precompletado/enlace.mjs';
import { valoresDe, PERFILES } from '../src/datos/ficticios.mjs';
import { perfil } from './ayudas.mjs';

test('los perfiles ficticios sólo precargan el dato público autorizado del club', () => {
  for (const form of ['padron', 'apto']) {
    const p = perfil(form);
    const club = p.preguntas.find((q) => q.clave === 'escuela.club');
    for (const nombre of Object.keys(PERFILES)) {
      const r = construirEnlace({ perfil: p, valores: valoresDe(nombre) });
      const params = new URL(r.url).searchParams;
      assert.equal(params.size, 2, `${form}/${nombre}: cantidad de parámetros`);
      assert.deepEqual([...params.keys()].sort(), [`entry.${club.entry}`, 'usp'].sort());
      assert.equal(params.get(`entry.${club.entry}`), club.opciones.BANFIELD_SAN_PEDRO);
      assert.deepEqual(r.incluidos.map((q) => q.clave), ['escuela.club']);
    }
  }
});

test('elevar sensibilidad máxima nunca incorpora datos personales al enlace', () => {
  const p = perfil('padron');
  const valores = valoresDe('condicional');
  const r = construirEnlace({ perfil: p, valores, sensibilidadMaxima: 'alta' });
  const params = new URL(r.url).searchParams;
  assert.equal(params.size, 2);
  for (const q of p.preguntas.filter((x) => x.clave !== 'escuela.club')) {
    if (q.entry) assert.equal(params.has(`entry.${q.entry}`), false, q.clave);
  }
});

test('un perfil manipulado no puede convertir el campo público en transporte de datos personales', () => {
  const p = structuredClone(perfil('padron'));
  const club = p.preguntas.find((q) => q.clave === 'escuela.club');
  club.opciones.BANFIELD_SAN_PEDRO = 'PERSONA_FICTICIA_PRIVADA';
  assert.throws(() => construirEnlace({ perfil: p, valores: valoresDe('menor'), sensibilidadMaxima: 'alta' }), (e) => !e.message.includes('PERSONA_FICTICIA_PRIVADA'));
});

test('identificadores de formulario y entry no admiten inyección de parámetros', () => {
  const secreto = 'PERSONA_FICTICIA_PRIVADA';
  const p = perfil('padron');
  assert.throws(() => construirEnlace({ perfil: p, valores: valoresDe('menor'), formId: `id?dato=${secreto}` }), (e) => !e.message.includes(secreto));
  const alterado = structuredClone(p);
  alterado.preguntas.find((q) => q.clave === 'escuela.club').entry = `123&dato=${secreto}`;
  assert.throws(() => construirEnlace({ perfil: alterado, valores: valoresDe('menor') }), (e) => !e.message.includes(secreto));
});
