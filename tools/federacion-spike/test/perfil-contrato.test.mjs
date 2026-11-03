import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizar, canonico } from '../src/esquema/normalizar.mjs';
import { validarPerfil, PerfilInvalidoError } from '../src/perfil/validar.mjs';
import { crudo, contrato, perfil, clonar, item, def } from './ayudas.mjs';

for (const form of ['padron', 'apto']) {
  test(`${form}: el contrato base versionado es exactamente lo que sale de normalizar el esquema real`, () => {
    assert.equal(canonico(normalizar(crudo(form))), canonico(contrato(form)));
  });
  test(`${form}: el perfil coincide con el contrato y mapea todas las preguntas obligatorias`, () => {
    assert.equal(validarPerfil(perfil(form), contrato(form)), true);
  });
}

test('el padrón tiene 37 preguntas y 6 secciones; el apto 9 y 2 (contra el esquema real de la Fase A)', () => {
  assert.equal(Object.keys(contrato('padron').preguntas).length, 37); assert.equal(contrato('padron').secciones.length, 6);
  assert.equal(Object.keys(contrato('apto').preguntas).length, 9); assert.equal(contrato('apto').secciones.length, 2);
});

test('un perfil con un entry viejo, un tipo erróneo o una opción inexistente se rechaza', () => {
  const p = clonar(perfil('padron')); p.preguntas.find((x) => x.clave === 'deportista.dni').entry = 1;
  assert.throws(() => validarPerfil(p, contrato('padron')), (e) => e instanceof PerfilInvalidoError && /deportista\.dni: entry/.test(e.message));
  const t = clonar(perfil('padron')); t.preguntas.find((x) => x.clave === 'deportista.cuil').tipo = 'fecha';
  assert.throws(() => validarPerfil(t, contrato('padron')), /deportista\.cuil: tipo/);
  const o = clonar(perfil('padron')); o.preguntas.find((x) => x.clave === 'federado.funcion').opciones.ATLETA = 'Deportista';
  assert.throws(() => validarPerfil(o, contrato('padron')), /ATLETA → "Deportista" no existe/);
});

test('una pregunta obligatoria que el perfil no mapea se rechaza (el relleno quedaría incompleto)', () => {
  const p = clonar(perfil('apto')); p.preguntas = p.preguntas.filter((x) => x.clave !== 'apto.medicoMatricula');
  assert.throws(() => validarPerfil(p, contrato('apto')), /pregunta obligatoria sin mapear: "NUMERO DE MATRICULA DEL MEDICO"/);
});

test('un formulario nuevo con una pregunta obligatoria no mapeada se detecta aunque el contrato anterior sea compatible', () => {
  const r = clonar(crudo('apto')); r.items.push([999000333, 'NUEVO DATO', null, 0, [[777000333, null, 1]]]);
  assert.throws(() => validarPerfil(perfil('apto'), normalizar(r)), /pregunta obligatoria sin mapear: "NUEVO DATO"/);
});

test('las claves del perfil no se repiten y los archivos no llevan opciones', () => {
  for (const form of ['padron', 'apto']) {
    const claves = perfil(form).preguntas.map((p) => p.clave);
    assert.equal(new Set(claves).size, claves.length);
    for (const p of perfil(form).preguntas.filter((x) => x.formato === 'archivo')) assert.equal(p.opciones, undefined);
  }
});
