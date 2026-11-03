import test from 'node:test';
import assert from 'node:assert/strict';
import { refDe, refRadio, botonesDe, dialogoBorrador, refCasillaCorreo, resultadoDe, refConfirmarBorrado } from '../src/vivo/instantanea.mjs';

const SNAP = `
        - radiogroup "Atleta empadronado? Pregunta obligatoria" [ref=f1e143]:
          - generic [ref=f1e147]:
            - generic [ref=f1e148]:
              - radio "Sí"
            - generic [ref=f1e149]: Sí
          - generic [ref=f1e154]:
            - generic [ref=f1e155]:
              - radio "No" [checked]
            - generic [ref=f1e156]: "No"
        - checkbox "Registrar <cuenta> como el correo electrónico que se incluirá en mi respuesta" [ref=f1e37] [cursor=pointer]
        - button "Siguiente" [ref=f1e162] [cursor=pointer]
        - button "Borrar formulario" [ref=f1e167] [cursor=pointer]`;
const BORRADOR = `- alertdialog [ref=f2e197]:
    - heading "¿Quieres continuar con el borrador actual?" [level=1] [ref=f2e199]
    - button "Usar borrador anterior" [active] [ref=f2e202]
    - link "Continuar" [ref=f2e206] [cursor=pointer]:`;

test('la referencia de un radio es la del contenedor, también si ya está marcado', () => {
  assert.equal(refRadio(SNAP, 'Sí'), 'f1e148'); assert.equal(refRadio(SNAP, 'No'), 'f1e155'); assert.equal(refRadio(SNAP, 'Quizás'), null);
});
test('detecta los botones y la casilla del correo', () => {
  assert.deepEqual(botonesDe(SNAP), ['Siguiente', 'Borrar formulario']); assert.equal(refCasillaCorreo(SNAP), 'f1e37'); assert.equal(refDe(SNAP, /button "Siguiente"/), 'f1e162');
  assert.deepEqual(botonesDe('button "Atrás"\nbutton "Enviar"'), ['Atrás', 'Enviar']);
});
test('detecta el diálogo de borrador y la referencia de "Continuar", y nada si no hay diálogo', () => {
  assert.deepEqual(dialogoBorrador(BORRADOR), { titulo: '¿Quieres continuar con el borrador actual?', refContinuar: 'f2e206' });
  assert.equal(dialogoBorrador(SNAP), null);
});
test('extrae el resultado JSON de browser_evaluate', () => {
  assert.deepEqual(resultadoDe('### Result\n{"a":[1,2]}\n### Ran Playwright code\n```js\n```'), { a: [1, 2] }); assert.equal(resultadoDe('### Error\nx'), null);
});

test('la confirmación del borrado es el botón "Borrar" del diálogo, no "Cancelar"', () => {
  assert.equal(refConfirmarBorrado('- button "Borrar formulario" [ref=b2]\n- alertdialog [ref=a1]:\n  - button "Cancelar" [ref=a2]\n  - button "Borrar formulario" [ref=a3]'), 'a3');
  assert.equal(refConfirmarBorrado('- button "Borrar formulario" [ref=b2]'), null);
});

test('la confirmación del borrado funciona con la estructura real: dialog "¿Quieres borrar el formulario?"', () => {
  const real = '- button "Siguiente" [ref=f1e439]\n- button "Borrar formulario" [ref=f1e440] [cursor=pointer]\n- dialog [ref=f1e558]:\n  - heading "¿Quieres borrar el formulario?" [level=1] [ref=f1e560]\n  - button "Cancelar" [ref=f1e563] [cursor=pointer]\n  - button "Borrar formulario" [ref=f1e567] [cursor=pointer]';
  assert.equal(refConfirmarBorrado(real), 'f1e567');
  assert.equal(dialogoBorrador(real), null, 'el diálogo de borrado no es el de borrador');
});
