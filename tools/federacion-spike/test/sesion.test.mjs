import test from 'node:test';
import assert from 'node:assert/strict';
import { SesionFormulario } from '../src/vivo/sesion.mjs';
import { contrato } from './ayudas.mjs';

const SIN = '- heading "AG_AptosFisicos2026"';
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
test('si el diálogo no se resuelve, falla en vez de leer datos de un borrador ajeno', async () => {
  await assert.rejects(() => new SesionFormulario(falso([CON]), contrato('apto')).abrir('https://x'), /no se pudo resolver/);
});
