import test from 'node:test';
import assert from 'node:assert/strict';
import { edad, esMenor, requiereWada } from '../src/datos/derivados.mjs';
import { valoresDe } from '../src/datos/ficticios.mjs';

test('la edad respeta el cumpleaños, incluido el 29 de febrero', () => {
  assert.equal(edad('2000-02-29', '2026-02-28'), 25); assert.equal(edad('2000-02-29', '2026-03-01'), 26);
  assert.equal(edad('2014-05-20', '2026-05-19'), 11); assert.equal(edad('2014-05-20', '2026-05-20'), 12);
});
test('"menor" es hasta los 17 años; WADA desde los 17 (mayores de 16)', () => {
  assert.equal(esMenor('2008-11-03', '2026-11-02'), true);  // 17 años y 364 días
  assert.equal(esMenor('2008-11-02', '2026-11-02'), false); // 18 recién cumplidos
  assert.equal(requiereWada('2010-11-03', '2026-11-02'), false); // 15
  assert.equal(requiereWada('2010-11-02', '2026-11-02'), false); // 16 recién cumplidos: "mayores de 16" no lo incluye
  assert.equal(requiereWada('2009-11-02', '2026-11-02'), true);  // 17
});
test('los perfiles ficticios ejercitan las ramas previstas', () => {
  assert.equal(valoresDe('menor')['deportista.esMenor'], 'SI'); assert.equal(valoresDe('menor')['wada.tiene'], 'NO');
  assert.equal(valoresDe('mayor')['deportista.esMenor'], 'NO'); assert.equal(valoresDe('mayor')['wada.tiene'], 'SI');
  const c = valoresDe('condicional'); assert.equal(c['deportista.esMenor'], 'SI'); assert.equal(c['apto.tiene'], 'NO'); assert.equal(c['wada.tiene'], 'SI'); assert.equal(c['deportista.empadronado'], 'NO');
});
