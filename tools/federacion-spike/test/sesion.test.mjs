import test from 'node:test';
import assert from 'node:assert/strict';
import { SesionFormulario } from '../src/vivo/sesion.mjs';
import { contrato } from './ayudas.mjs';

const SIN = '- heading "AG_AptosFisicos2026"\n- button "Siguiente" [ref=b1]';
const CON = '- alertdialog [ref=d1]:\n  - heading "¿Quieres continuar con el borrador actual?"\n  - link "Continuar" [ref=d9] [cursor=pointer]:';
// Cliente simulado: devuelve las instantáneas en el orden dado y registra cada llamada.
const falso = (instantaneas) => { const llamadas = []; let i = 0; return { llamadas, async herramienta(nombre, args) { llamadas.push([nombre, args?.target ?? args?.url ?? null]); return nombre === 'browser_snapshot' ? instantaneas[Math.min(i++, instantaneas.length - 1)] : ''; } }; };

test('sin borrador: abre, espera y no hace clic en nada', async () => {
  const c = falso([SIN]); const r = await new SesionFormulario(c, contrato('apto')).abrir('https://x');
  assert.deepEqual(r, { dialogoBorrador: null }); assert.equal(c.llamadas.filter(([n]) => n === 'browser_click').length, 0);
});
test('con un diálogo tardío: elige "Continuar" y exige que desaparezca antes de devolver el control', async () => {
  const c = falso([CON, SIN]); const r = await new SesionFormulario(c, contrato('apto')).abrir('https://x');
  assert.equal(r.dialogoBorrador, '¿Quieres continuar con el borrador actual?');
  assert.deepEqual(c.llamadas.filter(([n]) => n === 'browser_click'), [['browser_click', 'd9']]);
});
test('si el diálogo no se resuelve o la página no queda lista, falla en vez de leer datos de un borrador ajeno', async () => {
  await assert.rejects(() => new SesionFormulario(falso([CON]), contrato('apto')).abrir('https://x'), /no quedó listo/);
  await assert.rejects(() => new SesionFormulario(falso(['- heading "cargando"']), contrato('apto')).abrir('https://x'), /no quedó listo/);
});
test('espera a que aparezcan los botones antes de devolver el control (la primera lectura puede llegar antes de que cargue)', async () => {
  const c = falso(['- heading "cargando"', '- heading "cargando"', SIN]); await new SesionFormulario(c, contrato('apto')).abrir('https://x');
  assert.equal(c.llamadas.filter(([n]) => n === 'browser_snapshot').length, 3);
});
test('limpiar el borrador: pulsa "Borrar formulario" y su confirmación, nunca un botón de envío', async () => {
  const BORRAR = '- button "Siguiente" [ref=b1]\n- button "Borrar formulario" [ref=b2]';
  const CONFIRMA = '- dialog [ref=a1]:\n  - heading "¿Quieres borrar el formulario?"\n  - button "Cancelar" [ref=a2]\n  - button "Borrar formulario" [ref=a3]';
  const c = falso([BORRAR, BORRAR, CONFIRMA, BORRAR]); assert.equal(await new SesionFormulario(c, contrato('apto')).limpiarBorrador('https://x'), true);
  assert.deepEqual(c.llamadas.filter(([n]) => n === 'browser_click').map(([, t]) => t), ['b2', 'a3']);
});
const respuesta = (obj) => `### Result\n${JSON.stringify(obj)}\n### Ran Playwright code`;
const vacio = { preguntas: [{ titulo: 'DNI', textos: [''], fechas: [], radios: [], listas: [], archivo: false, alertas: [] }], botones: [] };
const lleno = { preguntas: [{ titulo: 'DNI', textos: ['90000001'], fechas: [], radios: [], listas: [], archivo: false, alertas: [] }], botones: [] };
const conControles = (instantaneas, controles) => { const c = falso(instantaneas); const base = c.herramienta; c.herramienta = async (n, a) => (n === 'browser_evaluate' ? respuesta(controles) : base(n, a)); return c; };

test('sin diálogo de confirmación y con el formulario vacío, está limpio (no había nada que borrar)', async () => {
  const BORRAR = '- button "Siguiente" [ref=b1]\n- button "Borrar formulario" [ref=b2]';
  assert.equal(await new SesionFormulario(conControles([BORRAR], vacio), contrato('apto')).limpiarBorrador('https://x'), true);
});
test('sin diálogo de confirmación pero con respuestas cargadas, NO da por limpio el borrador', async () => {
  const BORRAR = '- button "Siguiente" [ref=b1]\n- button "Borrar formulario" [ref=b2]';
  const c = conControles([BORRAR], lleno); const ses = new SesionFormulario(c, contrato('apto'));
  assert.equal(await ses.limpiarBorrador('https://x'), false); assert.deepEqual(ses.bitacora.at(-1), { accion: 'limpiar', ok: false });
});
