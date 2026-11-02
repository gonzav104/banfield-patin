import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizar, huella } from '../src/esquema/normalizar.mjs';
import { comparar, exigirCompatible, EsquemaIncompatibleError, SEVERIDAD } from '../src/esquema/comparar.mjs';
import { crudo, clonar, item, def } from './ayudas.mjs';

const base = () => normalizar(crudo('padron'));
const cambiar = (fn, form = 'padron') => { const r = clonar(crudo(form)); fn(r); return normalizar(r); };
const codigos = (res, sev) => res.diferencias.filter((d) => !sev || d.severidad === sev).map((d) => d.codigo).sort();

test('un esquema idéntico es compatible, sin diferencias y con la misma huella', () => {
  const r = comparar(base(), base());
  assert.equal(r.compatible, true); assert.deepEqual(r.diferencias, []); assert.equal(r.huellaBase, r.huellaNueva);
});

test('son irrelevantes: reordenar opciones, espacios en el título y marcado HTML o espacios en la ayuda', () => {
  const nuevo = cambiar((r) => {
    def(r, 'FUNCION')[1].reverse();
    item(r, 'APELLIDOS')[1] = '  APELLIDOS   ';
    item(r, 'DOMICILIO')[2] = '<b>Calle</b>   y   Altura';
  });
  const r = comparar(base(), nuevo);
  assert.equal(r.compatible, true);
  assert.equal(r.huellaBase, r.huellaNueva, 'la huella solo mira el contrato duro');
  assert.deepEqual(codigos(r, SEVERIDAD.INCOMPATIBLE), []); assert.deepEqual(codigos(r, SEVERIDAD.AVISO), []);
  assert.deepEqual(codigos(r), [], 'ni siquiera se informan: el contenido es el mismo una vez limpio');
});

test('cambiar el texto de ayuda es informativo y no bloquea', () => {
  const r = comparar(base(), cambiar((x) => { item(x, 'DOMICILIO')[2] = 'Otra indicación'; }));
  assert.equal(r.compatible, true); assert.deepEqual(codigos(r), ['ayuda-cambiada']); assert.equal(r.diferencias[0].severidad, SEVERIDAD.INFORMATIVO);
  assert.equal(r.huellaBase, r.huellaNueva, 'un texto de ayuda distinto no cambia el contrato duro');
});

test('cambiar el título de una pregunta es un aviso, no bloquea', () => {
  const r = comparar(base(), cambiar((x) => { item(x, 'NACIONALIDAD')[1] = 'PAÍS DE NACIONALIDAD'; }));
  assert.equal(r.compatible, true); assert.deepEqual(codigos(r), ['titulo-cambiado']); assert.equal(r.diferencias[0].severidad, SEVERIDAD.AVISO);
  assert.equal(r.huellaBase, r.huellaNueva, 'un título distinto no cambia el contrato duro');
});

const incompatibles = [
  ['una pregunta eliminada', (r) => { r.items.splice(r.items.indexOf(item(r, 'CUIL')), 1); }, 'pregunta-eliminada'],
  ['una pregunta obligatoria nueva', (r) => { r.items.push([999000111, 'NUEVA OBLIGATORIA', null, 0, [[777000111, null, 1]]]); }, 'pregunta-obligatoria-nueva'],
  ['un entry cambiado', (r) => { def(r, 'DNI')[0] = 1; }, 'entry-cambiado'],
  ['un tipo cambiado (texto → párrafo)', (r) => { item(r, 'NOMBRES')[3] = 1; }, 'tipo-cambiado'],
  ['una pregunta que pasa de opcional a obligatoria', (r) => { def(r, 'OTROS DATOS DEL DOMICILIO')[2] = 1; }, 'ahora-obligatoria'],
  ['una opción renombrada', (r) => { def(r, 'FUNCION')[1][0][0] = 'Deportista'; }, 'opcion-eliminada'],
  ['una opción eliminada', (r) => { def(r, 'DEPORTE')[1].pop(); }, 'opcion-eliminada'],
  ['un salto condicional cambiado', (r) => { def(r, 'Es menor?')[1][1][2] = 1951630528; }, 'salto-cambiado'],
  ['un salto de "enviar" a "siguiente"', (r) => { def(r, 'Es menor?')[1][1][2] = -2; }, 'salto-cambiado'],
  ['una validación cambiada', (r) => { def(r, 'DNI')[4] = [[1, 10]]; }, 'validacion-cambiada'],
  ['la configuración de fecha cambiada (con hora)', (r) => { def(r, 'FECHA DE NACIMIENTO')[7] = [1, 1]; }, 'fecha-cambiada'],
];
for (const [nombre, mutar, codigo] of incompatibles) {
  test(`detecta como incompatible: ${nombre}`, () => {
    const r = comparar(base(), cambiar(mutar));
    assert.equal(r.compatible, false); assert.ok(codigos(r, SEVERIDAD.INCOMPATIBLE).includes(codigo), `esperaba ${codigo}; hubo ${codigos(r)}`);
    assert.notEqual(r.huellaBase, r.huellaNueva);
  });
}

test('una pregunta opcional nueva es un aviso, no bloquea', () => {
  const r = comparar(base(), cambiar((x) => { x.items.push([999000222, 'DATO EXTRA', null, 0, [[777000222, null, 0]]]); }));
  assert.equal(r.compatible, true); assert.deepEqual(codigos(r), ['pregunta-opcional-nueva']);
});

test('agregar una opción es informativo y quitar la obligatoriedad también', () => {
  const r = comparar(base(), cambiar((x) => { def(x, 'FUNCION')[1].push(['Árbitro', null, null, null, 0]); def(x, 'CUIL')[2] = 0; }));
  assert.equal(r.compatible, true); assert.deepEqual(codigos(r), ['ahora-opcional', 'opcion-agregada']);
});

test('la estructura de secciones: eliminar una sección o mover una pregunta existente a otra sección es incompatible', () => {
  const sinSeccion = comparar(base(), cambiar((x) => { x.items.splice(x.items.indexOf(item(x, 'Menores de Edad')), 1); }));
  assert.equal(sinSeccion.compatible, false); assert.ok(codigos(sinSeccion).includes('secciones-cambiadas'));
  const movida = comparar(base(), cambiar((x) => { const i = item(x, 'CUIL'); x.items.splice(x.items.indexOf(i), 1); x.items.splice(x.items.indexOf(item(x, 'Certificado WADA')) + 1, 0, i); }));
  assert.equal(movida.compatible, false); assert.deepEqual(codigos(movida), ['pregunta-cambio-de-seccion']);
});

test('renombrar una sección es un aviso', () => {
  const r = comparar(base(), cambiar((x) => { item(x, 'Menores de Edad')[1] = 'Datos de menores'; }));
  assert.equal(r.compatible, true); assert.deepEqual(codigos(r), ['seccion-renombrada']);
});

test('el apto también se compara (otro formulario, mismas reglas)', () => {
  const b = normalizar(crudo('apto'));
  const r = comparar(b, cambiar((x) => { def(x, 'Atleta empadronado?')[1][1][2] = -2; }, 'apto'));
  assert.equal(r.compatible, false); assert.deepEqual(codigos(r, SEVERIDAD.INCOMPATIBLE), ['salto-cambiado']);
});

test('exigirCompatible detiene con el detalle de cada cambio incompatible y deja pasar lo compatible', () => {
  const malo = comparar(base(), cambiar((x) => { def(x, 'DNI')[0] = 1; item(x, 'CUIL')[3] = 1; }));
  assert.throws(() => exigirCompatible(malo), (e) => e instanceof EsquemaIncompatibleError && e.diferencias.length === 2 && /entry-cambiado/.test(e.message) && /tipo-cambiado/.test(e.message));
  const bueno = comparar(base(), cambiar((x) => { item(x, 'NACIONALIDAD')[1] = 'PAÍS'; }));
  assert.equal(exigirCompatible(bueno), bueno);
});

test('la huella es determinista y distingue los dos formularios', () => {
  assert.equal(huella(base()), huella(base()));
  assert.notEqual(huella(base()), huella(normalizar(crudo('apto'))));
});
