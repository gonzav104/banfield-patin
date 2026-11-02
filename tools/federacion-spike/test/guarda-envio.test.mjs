import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { RAIZ } from './ayudas.mjs';
import { generarElegirYAvanzar } from '../src/vivo/navegar.mjs';
import { LEER_CONTROLES } from '../src/vivo/leer-controles.mjs';

const archivos = (dir) => fs.readdirSync(dir, { withFileTypes: true }).flatMap((d) => (d.isDirectory() ? archivos(path.join(dir, d.name)) : [path.join(dir, d.name)]));

test('ningún archivo del spike contiene una ruta de envío (submit, formResponse, clic en "Enviar")', () => {
  const prohibidos = [/\.submit\s*\(/, /formResponse/, /Enviar['"`]\s*\)?\s*[,.]?\s*click/i, /requestSubmit/];
  for (const f of [...archivos(path.join(RAIZ, 'src')), ...archivos(path.join(RAIZ, 'perfiles'))]) {
    const texto = fs.readFileSync(f, 'utf8');
    for (const re of prohibidos) assert.doesNotMatch(texto, re, `${path.relative(RAIZ, f)} contiene ${re}`);
  }
});

test('la navegación solo hace clic en un botón cuyo texto es exactamente "Siguiente"', () => {
  const js = generarElegirYAvanzar({ preguntaRegex: 'x', valorRadio: 'Sí', avanzar: true });
  assert.match(js, /etiqueta === 'Siguiente'/);
  assert.match(js, /NO se hizo clic: el botón dice/);
  assert.equal((js.match(/\.click\(\)/g) ?? []).length, 2); // el radio y el botón "Siguiente"
  assert.doesNotMatch(js, /Enviar['"`]\)?\.click/);
});

test('con avanzar=false no se hace clic en ningún botón', () => {
  const js = generarElegirYAvanzar({ preguntaRegex: 'x', valorRadio: 'Sí', avanzar: false });
  assert.match(js, /if \(false\)/);
});

test('la lectura de controles no escribe ni hace clic', () => {
  assert.doesNotMatch(LEER_CONTROLES, /\.click\(|\.value\s*=[^=]|dispatchEvent|\.submit|setAttribute|fetch\(|XMLHttpRequest/);
});
