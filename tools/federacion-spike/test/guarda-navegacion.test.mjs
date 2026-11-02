import test from 'node:test';
import assert from 'node:assert/strict';
import { decidirAvance, seleccionesDe } from '../src/vivo/guarda-navegacion.mjs';
import { contrato } from './ayudas.mjs';

const ctl = (...preguntas) => ({ preguntas: preguntas.map((p) => ({ titulo: p[0], textos: [], fechas: [], radios: p[1] ?? [], listas: p[2] ?? [], archivo: false, alertas: [] })), botones: [] });
const decidir = (form, etiquetaBoton, controles, botones = []) => decidirAvance({ etiquetaBoton, botones, contrato: contrato(form), selecciones: seleccionesDe(controles, contrato(form)) });

test('apto: con "No" el botón sigue diciendo "Siguiente" pero la guarda NO permite avanzar (el salto es "enviar")', () => {
  const r = decidir('apto', 'Siguiente', ctl(['Atleta empadronado?', ['No']]));
  assert.equal(r.permitido, false); assert.match(r.motivo, /salta a ENVIAR/);
});
test('apto: con "Sí" sí se puede avanzar', () => {
  assert.equal(decidir('apto', 'Siguiente', ctl(['Atleta empadronado?', ['Sí']], ['Club', [], ['Club Social y Deportivo Banfield San Pedro']])).permitido, true);
});
test('padrón: "¿Es menor?" = No salta a enviar y se rechaza; = Sí se permite', () => {
  assert.equal(decidir('padron', 'Siguiente', ctl(['Es menor?', ['No']])).permitido, false);
  assert.equal(decidir('padron', 'Siguiente', ctl(['Es menor?', ['Sí']])).permitido, true);
});
test('padrón: los saltos a otras secciones (certificado médico, WADA) no son envío', () => {
  assert.equal(decidir('padron', 'Siguiente', ctl(['TIENE CERTIFICADO MEDICO?', ['No']], ['TIENE CERTIFICADO WADA?', ['No']])).permitido, true);
});
test('se rechaza si el botón no es exactamente "Siguiente" o si hay un "Enviar" en pantalla', () => {
  assert.equal(decidir('apto', 'Enviar', ctl()).permitido, false);
  assert.equal(decidir('apto', 'Siguiente', ctl(), ['Atrás', 'Enviar']).permitido, false);
  assert.equal(decidir('apto', 'siguiente', ctl()).permitido, false);
});
test('falla cerrado: una selección en una pregunta u opción desconocida se rechaza', () => {
  assert.match(decidir('apto', 'Siguiente', ctl(['Pregunta inventada', ['Sí']])).motivo, /desconocida/);
  assert.match(decidir('apto', 'Siguiente', ctl(['Atleta empadronado?', ['Quizás']])).motivo, /opción desconocida/);
});
test('ninguna de las opciones que saltan a enviar queda fuera de la guarda (contrato real de ambos formularios)', () => {
  for (const form of ['padron', 'apto']) {
    for (const q of Object.values(contrato(form).preguntas)) for (const o of q.opciones.filter((x) => x.salto === 'enviar')) {
      const r = decidir(form, 'Siguiente', ctl([q.titulo, [o.rotulo]]));
      assert.equal(r.permitido, false, `${form}: "${q.titulo}" = "${o.rotulo}"`);
    }
  }
});
