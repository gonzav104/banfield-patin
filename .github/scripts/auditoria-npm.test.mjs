import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdtempSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import * as gate from './auditoria-npm.mjs';

const { evaluar } = gate;

const GHSA = 'GHSA-fc5q-39xc-vjpf';
const OTRO = 'GHSA-aaaa-bbbb-cccc';
const HOY = new Date('2026-11-17T20:00:00Z');

const aviso = (ghsa, nombre, severidad, rango, fuente = 1) => ({
  source: fuente,
  name: nombre,
  title: `aviso de ${nombre}`,
  url: `https://github.com/advisories/${ghsa}`,
  severity: severidad,
  range: rango,
});
/** The `metadata` npm prints: the number of vulnerabilities per severity and the total. */
const metadatos = (vulnerabilidades) => {
  const cuenta = { info: 0, low: 0, moderate: 0, high: 0, critical: 0 };
  for (const v of Object.values(vulnerabilidades)) if (v?.severity in cuenta) cuenta[v.severity] += 1;
  return { vulnerabilities: { ...cuenta, total: Object.keys(vulnerabilidades).length }, dependencies: {} };
};
/** A report shaped like `npm audit --json` (auditReportVersion 2) around the given vulnerabilities, with coherent metadata. */
const armar = (vulnerabilidades) => ({ auditReportVersion: 2, vulnerabilities: vulnerabilidades, metadata: metadatos(vulnerabilidades) });
/** The advisory sits in the package that owns it; the packages that depend on it only point to it and carry its severity. */
const reporte = (...avisos) =>
  armar(
    Object.fromEntries([
      ...avisos.map((a) => [a.name, { name: a.name, severity: a.severity, via: [a], range: '*' }]),
      ...(avisos.length ? [['dependiente', { name: 'dependiente', severity: avisos[0].severity, via: [avisos[0].name], range: '*' }]] : []),
    ]),
  );
const excepcion = (extra = {}) => ({
  ghsa: GHSA,
  paquete: 'picomatch',
  rango: '<=4.0.7',
  vence: '2026-12-17',
  motivo: 'ReDoS solo con patrones controlados por el atacante; el proyecto no los recibe y la dependencia es de desarrollo.',
  ...extra,
});
const picomatch = (extra) => aviso(GHSA, 'picomatch', 'high', '<=4.0.7', extra);

test('without advisories the gate passes', () => {
  const r = evaluar(armar({}), [], HOY);
  assert.equal(r.ok, true);
  assert.deepEqual(r.problemas, []);
});

test('an advisory without an exception fails and names it', () => {
  const r = evaluar(reporte(picomatch()), [], HOY);
  assert.equal(r.ok, false);
  assert.match(r.problemas.join('\n'), new RegExp(GHSA));
});

test('the excepted advisory passes while it is valid, and the packages that only depend on it do not count', () => {
  const r = evaluar(reporte(picomatch()), [excepcion()], HOY);
  assert.equal(r.ok, true);
  assert.deepEqual(r.aceptados.map((a) => a.ghsa), [GHSA]);
});

test('a SECOND advisory next to the excepted one still fails, and only that one is reported', () => {
  const r = evaluar(reporte(picomatch(1), aviso(OTRO, 'lodash', 'moderate', '<4.17.21', 2)), [excepcion()], HOY);
  assert.equal(r.ok, false);
  assert.equal(r.problemas.length, 1);
  assert.match(r.problemas[0], new RegExp(OTRO));
});

test('the exception expires: valid through the whole expiry day, failing the next day', () => {
  const ultimoInstante = new Date('2026-12-17T23:59:59Z');
  assert.equal(evaluar(reporte(picomatch()), [excepcion()], ultimoInstante).ok, true);
  const alDiaSiguiente = evaluar(reporte(picomatch()), [excepcion()], new Date('2026-12-18T00:00:00Z'));
  assert.equal(alDiaSiguiente.ok, false);
  assert.match(alDiaSiguiente.problemas.join('\n'), /venci/i);
});

test('if the affected range changes (a fixed version was published) the exception no longer applies', () => {
  const r = evaluar(reporte(aviso(GHSA, 'picomatch', 'high', '<4.0.8')), [excepcion()], HOY);
  assert.equal(r.ok, false);
  assert.match(r.problemas.join('\n'), /rango/i);
});

test('an exception for another package does not cover the advisory', () => {
  const r = evaluar(reporte(picomatch()), [excepcion({ paquete: 'otro' })], HOY);
  assert.equal(r.ok, false);
});

test('the threshold stays at moderate: a moderate advisory fails, a low one does not', () => {
  assert.equal(evaluar(reporte(aviso(OTRO, 'x', 'moderate', '*')), [], HOY).ok, false);
  assert.equal(evaluar(reporte(aviso(OTRO, 'x', 'low', '*')), [], HOY).ok, true);
});

test('an exception that no advisory uses any more is only a warning (it can be removed)', () => {
  const r = evaluar(armar({}), [excepcion()], HOY);
  assert.equal(r.ok, true);
  assert.match(r.avisos.join('\n'), new RegExp(GHSA));
});

test('an audit that could not run fails instead of passing by default', () => {
  assert.equal(evaluar({ error: { code: 'ENOTFOUND', summary: 'sin red' } }, [], HOY).ok, false);
  assert.equal(evaluar({}, [], HOY).ok, false);
  assert.equal(evaluar(null, [], HOY).ok, false);
});

test('a malformed exception (missing reason, bad date, empty range) is rejected and covers nothing', () => {
  for (const mala of [excepcion({ motivo: '' }), excepcion({ vence: '17/12/2026' }), excepcion({ rango: '' }), excepcion({ ghsa: 'picomatch' })]) {
    const r = evaluar(reporte(picomatch()), [mala], HOY);
    assert.equal(r.ok, false, JSON.stringify(mala));
  }
});

test('the same advisory object listed twice is counted once; two advisories of one package with different ids are two', () => {
  // The very same advisory object listed twice in the entry of the package it is for (an advisory of another package under it would be rejected).
  const repetido = armar({ picomatch: { name: 'picomatch', severity: 'high', via: [picomatch(7), picomatch(7)], range: '*' } });
  const r = evaluar(repetido, [excepcion()], HOY);
  assert.equal(r.ok, true);
  assert.equal(r.aceptados.length, 1);

  const dosDelMismoPaquete = reporte(picomatch(1), aviso(OTRO, 'picomatch', 'high', '<=4.0.7', 2));
  dosDelMismoPaquete.vulnerabilities.picomatch.via.push(aviso(OTRO, 'picomatch', 'high', '<=4.0.7', 2));
  const dos = evaluar(dosDelMismoPaquete, [excepcion()], HOY);
  assert.equal(dos.ok, false, 'an exception for ONE advisory of a package never covers another advisory of the same package');
  assert.match(dos.problemas.join('\n'), new RegExp(OTRO));
});

test('same source and package but advisories that differ by id are not merged; without a source they are still told apart', () => {
  const r = evaluar(
    armar({ picomatch: { name: 'picomatch', severity: 'high', via: [{ ...picomatch(), source: undefined }, { ...aviso(OTRO, 'picomatch', 'high', '<=4.0.7'), source: undefined }], range: '*' } }),
    [excepcion()],
    HOY,
  );
  assert.equal(r.ok, false);
  assert.match(r.problemas.join('\n'), new RegExp(OTRO));
});

test('FAIL CLOSED: an invalid current date, an unknown or missing severity and a non-object vulnerabilities list never pass', () => {
  assert.equal(evaluar(reporte(picomatch()), [excepcion()], new Date('no es una fecha')).ok, false);
  for (const severidad of ['critical ', 'High', undefined, null, 'urgent']) {
    const r = evaluar(reporte(aviso(OTRO, 'x', severidad, '*')), [], HOY);
    assert.equal(r.ok, false, `severity ${JSON.stringify(severidad)}`);
    assert.match(r.problemas.join('\n'), /severidad/i);
  }
  assert.equal(evaluar({ auditReportVersion: 2, vulnerabilities: [], metadata: {} }, [], HOY).ok, false);
});

test('an exception cannot be written for more than a month ahead: a far expiry date fails the gate', () => {
  const r = evaluar(reporte(picomatch()), [excepcion({ vence: '9999-12-31' })], HOY);
  assert.equal(r.ok, false);
  assert.match(r.problemas.join('\n'), /31 días|plazo|demasiado/i);
  assert.equal(evaluar(reporte(picomatch()), [excepcion({ vence: '2026-12-18' })], HOY).ok, true, '31 days ahead is the limit');
  assert.equal(evaluar(reporte(picomatch()), [excepcion({ vence: '2026-12-19' })], HOY).ok, false);
});

// --- the real thing: the versioned exception against the real report of 2026-11-17, through the command line

const AQUI = new URL('.', import.meta.url).pathname;
const REAL = join(AQUI, 'fixtures', 'npm-audit-picomatch-2026-11-17.json');
const EXCEPCIONES = join(AQUI, '..', 'auditoria-excepciones.json');
const REAL_PRODUCCION = join(AQUI, 'fixtures', 'npm-audit-produccion-2026-11-17.json');
/** Runs the command line. Unless the test passes its own `--reporte-produccion`, the real (clean) production report of 2026-11-17 is used, so no test reaches the registry. */
const correr = (...args) => {
  const conProduccion = args.includes('--reporte-produccion') ? args : [...args, '--reporte-produccion', REAL_PRODUCCION];
  return spawnSync(process.execPath, [join(AQUI, 'auditoria-npm.mjs'), ...conProduccion], { encoding: 'utf8' });
};

test('the versioned exceptions file covers the real report of 2026-11-17: only picomatch, exit code 0', () => {
  const r = correr('--reporte', REAL, '--excepciones', EXCEPCIONES, '--ahora', '2026-11-17T20:00:00Z');
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, new RegExp(GHSA));
});

test('the same real report fails without the exception (the gate still bites) and after its expiry', () => {
  const dir = mkdtempSync(join(tmpdir(), 'auditoria-'));
  try {
    const vacio = join(dir, 'vacio.json');
    writeFileSync(vacio, JSON.stringify({ excepciones: [] }));
    const sin = correr('--reporte', REAL, '--excepciones', vacio, '--ahora', '2026-11-17T20:00:00Z');
    assert.equal(sin.status, 1);
    assert.match(sin.stderr, new RegExp(GHSA));
    const vencida = correr('--reporte', REAL, '--excepciones', EXCEPCIONES, '--ahora', '2027-01-01T00:00:00Z');
    assert.equal(vencida.status, 1);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('the command line fails on an unreadable report and on a missing exceptions file', () => {
  assert.equal(correr('--reporte', join(AQUI, 'no-existe.json'), '--excepciones', EXCEPCIONES).status, 1);
  assert.equal(correr('--reporte', REAL, '--excepciones', join(AQUI, 'no-existe.json')).status, 1);
});

test('a missing or malformed exceptions file fails even when the report is clean (it is never read as «no exceptions»)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'auditoria-'));
  try {
    const limpio = join(dir, 'limpio.json');
    writeFileSync(limpio, JSON.stringify(armar({})));
    assert.equal(correr('--reporte', limpio, '--excepciones', join(dir, 'no-existe.json')).status, 1);
    const sinLista = join(dir, 'sin-lista.json');
    writeFileSync(sinLista, JSON.stringify({ otra: [] }));
    assert.equal(correr('--reporte', limpio, '--excepciones', sinLista).status, 1);
    const roto = join(dir, 'roto.json');
    writeFileSync(roto, '{ no es json');
    assert.equal(correr('--reporte', limpio, '--excepciones', roto).status, 1);
    assert.equal(correr('--reporte', limpio, '--excepciones', EXCEPCIONES).status, 0); // the control: a clean report with a valid file passes
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('FAIL CLOSED through a symlink: running the script by a link still runs the gate (it never exits 0 without running)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'auditoria-'));
  try {
    const enlace = join(dir, 'enlace.mjs');
    symlinkSync(join(AQUI, 'auditoria-npm.mjs'), enlace);
    const vacio = join(dir, 'vacio.json');
    writeFileSync(vacio, JSON.stringify({ excepciones: [] }));
    const r = spawnSync(process.execPath, [enlace, '--reporte', REAL, '--reporte-produccion', REAL_PRODUCCION, '--excepciones', vacio], { encoding: 'utf8' });
    assert.equal(r.status, 1, r.stdout + r.stderr);
    assert.match(r.stderr, new RegExp(GHSA));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('an invalid --ahora fails the command line instead of skipping the expiry check', () => {
  assert.equal(correr('--reporte', REAL, '--excepciones', EXCEPCIONES, '--ahora', 'garbage').status, 1);
});

test('the versioned exceptions file has the fields the gate needs and a bounded life', () => {
  const { excepciones } = JSON.parse(readFileSync(EXCEPCIONES, 'utf8'));
  assert.equal(excepciones.length, 1);
  const [e] = excepciones;
  assert.equal(e.ghsa, GHSA);
  assert.equal(e.paquete, 'picomatch');
  assert.equal(e.rango, '<=4.0.7');
  const dias = (Date.parse(`${e.vence}T00:00:00Z`) - Date.parse('2026-11-17T00:00:00Z')) / 86_400_000;
  assert.ok(dias > 0 && dias <= 30, `la excepción debe vivir como máximo 30 días (vive ${dias})`);
});

// --- Hallazgo 1: every vulnerability of moderate severity or more must be fully explained by advisories the gate can verify

const REPORTE_REAL = JSON.parse(readFileSync(REAL, 'utf8'));
const EXCEPCIONES_REALES = JSON.parse(readFileSync(EXCEPCIONES, 'utf8')).excepciones;
const AHORA_REAL = new Date('2026-11-17T20:00:00Z');
/** The real report of 2026-11-17 (its picomatch advisory is excepted) plus extra vulnerabilities, with coherent metadata. */
const realCon = (extra) => armar({ ...structuredClone(REPORTE_REAL.vulnerabilities), ...extra });
const evaluarReal = (reporteConExtra) => evaluar(reporteConExtra, EXCEPCIONES_REALES, AHORA_REAL);

test('control: the real report with the versioned exception still passes, every chain explained down to the excepted advisory', () => {
  const r = evaluarReal(REPORTE_REAL);
  assert.equal(r.ok, true, r.problemas.join('\n'));
  assert.deepEqual(r.aceptados.map((a) => a.ghsa), [GHSA]);
});

const INEXPLICADAS = {
  'an empty via': { name: 'intruso', severity: 'high', via: [], range: '*' },
  'a via that is not a list': { name: 'intruso', severity: 'high', via: 'picomatch', range: '*' },
  'no via at all': { name: 'intruso', severity: 'critical', range: '*' },
  'a reference to a vulnerability that is not in the report': { name: 'intruso', severity: 'high', via: ['no-existe'], range: '*' },
  'invalid via items (null, a number, an array)': { name: 'intruso', severity: 'critical', via: [null, 42, []], range: '*' },
  'an advisory object without url': { name: 'intruso', severity: 'high', via: [{ source: 9, name: 'intruso', severity: 'high', range: '<1' }], range: '*' },
  'an advisory object without range': { name: 'intruso', severity: 'high', via: [{ source: 9, name: 'intruso', severity: 'high', url: `https://github.com/advisories/${OTRO}` }], range: '*' },
  'an advisory object that belongs to another package': { name: 'intruso', severity: 'high', via: [{ ...picomatch(), name: 'otro-paquete' }], range: '*' },
  'a severity higher than any advisory it reaches': { name: 'intruso', severity: 'critical', via: ['picomatch'], range: '*' },
  'a reference only to an entry that is not an object': { name: 'intruso', severity: 'high', via: ['roto'], range: '*' },
  'a name that is not the key it is listed under': { name: 'otra-cosa', severity: 'high', via: ['picomatch'], range: '*' },
};
for (const [descripcion, entrada] of Object.entries(INEXPLICADAS)) {
  test(`an unexplained vulnerability fails even next to the excepted advisory: ${descripcion}`, () => {
    const r = evaluarReal(realCon({ intruso: entrada, ...(descripcion.includes('not an object') ? { roto: 'texto' } : {}) }));
    assert.equal(r.ok, false);
    assert.match(r.problemas.join('\n'), /intruso|roto/, 'the problem names the vulnerability that could not be explained');
  });
}

test('a vulnerability without a severity, or with one the gate does not know, fails instead of being skipped', () => {
  for (const severidad of [undefined, null, 'urgent', 'High']) {
    const r = evaluarReal(realCon({ intruso: { name: 'intruso', severity: severidad, via: ['picomatch'], range: '*' } }));
    assert.equal(r.ok, false, `severity ${JSON.stringify(severidad)}`);
    assert.match(r.problemas.join('\n'), /severidad/i);
  }
});

test('a cycle with no advisory at its origin fails, and a cycle that reaches an excepted advisory is fine', () => {
  const sinOrigen = evaluarReal(realCon({ a: { name: 'a', severity: 'high', via: ['b'], range: '*' }, b: { name: 'b', severity: 'high', via: ['a'], range: '*' } }));
  assert.equal(sinOrigen.ok, false);
  assert.match(sinOrigen.problemas.join('\n'), /ninguna cadena de «via» llega a un aviso de origen/);
  const conOrigen = evaluarReal(realCon({ a: { name: 'a', severity: 'high', via: ['b', 'picomatch'], range: '*' }, b: { name: 'b', severity: 'high', via: ['a'], range: '*' } }));
  assert.equal(conOrigen.ok, true, conOrigen.problemas.join('\n'));
});

test('a long transitive chain is explained by following it, and a chain that ends in an advisory with NO exception fails and names it', () => {
  const cadena = evaluarReal(realCon({ n1: { name: 'n1', severity: 'high', via: ['n2'], range: '*' }, n2: { name: 'n2', severity: 'high', via: ['n3'], range: '*' }, n3: { name: 'n3', severity: 'high', via: ['vite'], range: '*' } }));
  assert.equal(cadena.ok, true, cadena.problemas.join('\n'));
  const otro = aviso(OTRO, 'lodash', 'moderate', '<4.17.21', 5);
  const sinExcepcion = evaluarReal(realCon({ lodash: { name: 'lodash', severity: 'moderate', via: [otro], range: '*' }, uso: { name: 'uso', severity: 'moderate', via: ['lodash'], range: '*' } }));
  assert.equal(sinExcepcion.ok, false);
  assert.match(sinExcepcion.problemas.join('\n'), new RegExp(OTRO));
});

test('below the threshold nothing is demanded: a low vulnerability with no via does not fail the gate', () => {
  const r = evaluarReal(realCon({ menor: { name: 'menor', severity: 'low', via: [], range: '*' } }));
  assert.equal(r.ok, true, r.problemas.join('\n'));
});

test('a vulnerability that depends only on advisories below the threshold, but claims moderate or more, fails', () => {
  const baja = aviso(OTRO, 'x', 'low', '*', 6);
  const r = evaluar(armar({ x: { name: 'x', severity: 'low', via: [baja], range: '*' }, dep: { name: 'dep', severity: 'moderate', via: ['x'], range: '*' } }), [], HOY);
  assert.equal(r.ok, false);
  assert.match(r.problemas.join('\n'), /dep/);
});

// --- an incomplete or inconsistent report is not trusted (both audits)

const COMPLETITUD = {
  'without metadata': (r) => { delete r.metadata; },
  'without the vulnerability counts': (r) => { r.metadata = { dependencies: {} }; },
  'with a total that does not match the entries': (r) => { r.metadata.vulnerabilities.total += 1; },
  'with a severity count that does not match the entries': (r) => { r.metadata.vulnerabilities.high -= 1; },
  'with a non numeric count': (r) => { r.metadata.vulnerabilities.high = '11'; },
  'of another report version': (r) => { r.auditReportVersion = 1; },
  'without a report version': (r) => { delete r.auditReportVersion; },
};
for (const [descripcion, estropear] of Object.entries(COMPLETITUD)) {
  test(`a report ${descripcion} is rejected by the main audit and by the production audit`, () => {
    const principal = structuredClone(REPORTE_REAL);
    estropear(principal);
    assert.equal(evaluarReal(principal).ok, false);
    const produccion = armar({});
    estropear(produccion);
    assert.equal(gate.evaluarProduccion(produccion, AHORA_REAL).ok, false);
  });
}

// --- Hallazgo 2: the production dependencies are audited on their own, and an exception never applies to them

const PRODUCCION_REAL = JSON.parse(readFileSync(REAL_PRODUCCION, 'utf8'));

test('the real production report of 2026-11-17 is clean and passes', () => {
  assert.equal(PRODUCCION_REAL.metadata.vulnerabilities.total, 0);
  const r = gate.evaluarProduccion(PRODUCCION_REAL, AHORA_REAL);
  assert.equal(r.ok, true, r.problemas.join('\n'));
});

test('ANY advisory of moderate severity or more in production fails, EVEN with the excepted id', () => {
  const conElExceptuado = gate.evaluarProduccion(reporte(picomatch()), AHORA_REAL);
  assert.equal(conElExceptuado.ok, false);
  assert.match(conElExceptuado.problemas.join('\n'), new RegExp(GHSA));
  assert.match(conElExceptuado.problemas.join('\n'), /producci/i);
  assert.equal(gate.evaluarProduccion(REPORTE_REAL, AHORA_REAL).ok, false, 'the whole real report, as if it were the production one');
  assert.equal(gate.evaluarProduccion(reporte(aviso(OTRO, 'x', 'moderate', '*')), AHORA_REAL).ok, false);
});

test('a low advisory in production does not fail (the threshold is the same as everywhere)', () => {
  assert.equal(gate.evaluarProduccion(reporte(aviso(OTRO, 'x', 'low', '*')), AHORA_REAL).ok, true);
});

test('a production audit that did not run, or that is invalid or incomplete, fails', () => {
  for (const malo of [{ error: { code: 'ENOTFOUND', summary: 'sin red' } }, {}, null, [], 'texto', { auditReportVersion: 2 }, { auditReportVersion: 2, vulnerabilities: [], metadata: {} }]) {
    assert.equal(gate.evaluarProduccion(malo, AHORA_REAL).ok, false, JSON.stringify(malo));
  }
});

test('nothing can excuse a production advisory: it fails the same before the exception expires, on its last day and after it', () => {
  for (const fecha of ['2026-11-17T20:00:00Z', '2026-12-17T23:59:59Z', '2027-06-01T00:00:00Z']) {
    const r = gate.evaluarProduccion(reporte(picomatch()), new Date(fecha));
    assert.equal(r.ok, false, fecha);
    assert.match(r.problemas.join('\n'), new RegExp(GHSA), fecha);
  }
});

test('CLI: a production report that carries the excepted advisory fails the gate, and the output says it is about production', () => {
  const r = correr('--reporte', REAL, '--excepciones', EXCEPCIONES, '--ahora', '2026-11-17T20:00:00Z', '--reporte-produccion', REAL);
  assert.equal(r.status, 1, r.stdout + r.stderr);
  assert.match(r.stderr, new RegExp(GHSA));
  assert.match(r.stderr, /producci/i);
});

test('CLI: the clean real production report lets the real main report pass, and the output says both audits ran', () => {
  const r = correr('--reporte', REAL, '--excepciones', EXCEPCIONES, '--ahora', '2026-11-17T20:00:00Z');
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /producci/i);
});

test('CLI: an unreadable, non JSON, empty or error production report fails even if the main audit passes', () => {
  const dir = mkdtempSync(join(tmpdir(), 'auditoria-'));
  try {
    const casos = { 'no existe': join(dir, 'no-existe.json'), 'vacio': join(dir, 'vacio.json'), 'roto': join(dir, 'roto.json'), 'error': join(dir, 'error.json'), 'incompleto': join(dir, 'incompleto.json') };
    writeFileSync(casos.vacio, '');
    writeFileSync(casos.roto, '{ truncado');
    writeFileSync(casos.error, JSON.stringify({ error: { code: 'EAUDITNOLOCK', summary: 'sin lockfile' } }));
    writeFileSync(casos.incompleto, JSON.stringify({ auditReportVersion: 2, vulnerabilities: {} }));
    for (const [nombre, ruta] of Object.entries(casos)) {
      const r = correr('--reporte', REAL, '--excepciones', EXCEPCIONES, '--ahora', '2026-11-17T20:00:00Z', '--reporte-produccion', ruta);
      assert.equal(r.status, 1, `${nombre}: ${r.stdout}${r.stderr}`);
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('CLI: the main audit failing and the production audit failing are BOTH reported', () => {
  const dir = mkdtempSync(join(tmpdir(), 'auditoria-'));
  try {
    const vacio = join(dir, 'vacio.json');
    writeFileSync(vacio, JSON.stringify({ excepciones: [] }));
    const r = correr('--reporte', REAL, '--excepciones', vacio, '--ahora', '2026-11-17T20:00:00Z', '--reporte-produccion', REAL);
    assert.equal(r.status, 1);
    assert.match(r.stderr, /Aviso sin excepci/);
    assert.match(r.stderr, /producci/i);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('the threshold, the excepted id and the expiry are the ones that were published', () => {
  const [e] = EXCEPCIONES_REALES;
  assert.deepEqual([e.ghsa, e.paquete, e.rango, e.vence], [GHSA, 'picomatch', '<=4.0.7', '2026-12-17']);
  assert.equal(evaluar(reporte(aviso(OTRO, 'x', 'moderate', '*')), [], HOY).ok, false);
  assert.equal(evaluar(reporte(aviso(OTRO, 'x', 'low', '*')), [], HOY).ok, true);
});

// --- the commands the gate really runs, checked with a fake `npm` first in the PATH (no registry, no network)

/** A temp dir with an `npm` that logs its arguments and prints the given JSON for the full audit and for the production one (exit code 1, like npm audit when it finds advisories). */
function conNpmFalso(salidaCompleta, salidaProduccion, verificar) {
  const dir = mkdtempSync(join(tmpdir(), 'auditoria-npm-'));
  try {
    const registro = join(dir, 'llamadas.txt');
    writeFileSync(join(dir, 'completa.out'), salidaCompleta);
    writeFileSync(join(dir, 'produccion.out'), salidaProduccion);
    const falso = join(dir, 'npm');
    writeFileSync(falso, `#!/bin/sh\necho "$*" >> '${registro}'\ncase "$*" in\n  *--omit=dev*) cat '${join(dir, 'produccion.out')}' ;;\n  *) cat '${join(dir, 'completa.out')}' ;;\nesac\nexit 1\n`, { mode: 0o755 });
    const r = spawnSync(process.execPath, [join(AQUI, 'auditoria-npm.mjs'), '--excepciones', EXCEPCIONES, '--ahora', '2026-11-17T20:00:00Z'], {
      encoding: 'utf8',
      env: { ...process.env, PATH: `${dir}:${process.env.PATH}` },
    });
    let llamadas = [];
    try {
      llamadas = readFileSync(registro, 'utf8').trim().split('\n');
    } catch {
      /* npm never ran */
    }
    verificar(r, llamadas);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}
const texto = (objeto) => JSON.stringify(objeto);

test('the gate runs EXACTLY two audits: `npm audit --json` and `npm audit --omit=dev --json`, and judges the JSON even though npm exits with 1', () => {
  conNpmFalso(readFileSync(REAL, 'utf8'), readFileSync(REAL_PRODUCCION, 'utf8'), (r, llamadas) => {
    assert.deepEqual([...llamadas].sort(), ['audit --json', 'audit --omit=dev --json']);
    assert.equal(r.status, 0, r.stdout + r.stderr);
  });
});

test('through the real commands, a production audit with the excepted advisory fails, and so does one that prints garbage or nothing', () => {
  conNpmFalso(readFileSync(REAL, 'utf8'), readFileSync(REAL, 'utf8'), (r) => {
    assert.equal(r.status, 1);
    assert.match(r.stderr, /producci/i);
  });
  for (const basura of ['', 'npm WARN algo\n{ truncado', texto({ error: { code: 'ENOTFOUND', summary: 'sin red' } })]) {
    conNpmFalso(readFileSync(REAL, 'utf8'), basura, (r) => assert.equal(r.status, 1, `production output ${JSON.stringify(basura)}: ${r.stdout}${r.stderr}`));
    conNpmFalso(basura, readFileSync(REAL_PRODUCCION, 'utf8'), (r) => assert.equal(r.status, 1, `main output ${JSON.stringify(basura)}: ${r.stdout}${r.stderr}`));
  }
});

test('without `npm` in the PATH the gate fails instead of passing (nothing was audited)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'auditoria-sin-npm-'));
  try {
    const r = spawnSync(process.execPath, [join(AQUI, 'auditoria-npm.mjs'), '--excepciones', EXCEPCIONES], { encoding: 'utf8', env: { ...process.env, PATH: dir } });
    assert.equal(r.status, 1, r.stdout + r.stderr);
    assert.match(r.stderr, /ENOENT/, 'it says npm could not be run, not just that the answer was not a report');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});


test('an unresolvable branch fails even when the same vulnerability also reaches the excepted advisory by another route', () => {
  const MEZCLADAS = {
    'a reference that does not exist': { name: 'mezcla', severity: 'high', via: ['picomatch', 'no-existe'], range: '*' },
    'an invalid via item (null)': { name: 'mezcla', severity: 'high', via: ['picomatch', null], range: '*' },
    'an invalid via item (an incomplete advisory)': { name: 'mezcla', severity: 'high', via: ['picomatch', { name: 'mezcla', severity: 'high' }], range: '*' },
    'a reference to a low vulnerability with an empty via': { name: 'mezcla', severity: 'high', via: ['picomatch', 'rota'], range: '*' },
    'a reference to a low vulnerability that points nowhere': { name: 'mezcla', severity: 'high', via: ['picomatch', 'huerfana'], range: '*' },
    'a reference to a low vulnerability whose via is not a list': { name: 'mezcla', severity: 'high', via: ['picomatch', 'sin-lista'], range: '*' },
  };
  const BAJAS = {
    rota: { name: 'rota', severity: 'low', via: [], range: '*' },
    huerfana: { name: 'huerfana', severity: 'low', via: ['fantasma'], range: '*' },
    'sin-lista': { name: 'sin-lista', severity: 'low', via: 'texto', range: '*' },
  };
  for (const [descripcion, entrada] of Object.entries(MEZCLADAS)) {
    const r = evaluarReal(realCon({ mezcla: entrada, ...BAJAS }));
    assert.equal(r.ok, false, descripcion);
    assert.match(r.problemas.join('\n'), /mezcla/, descripcion);
  }
  // control: the same vulnerability with ONLY the valid route passes
  assert.equal(evaluarReal(realCon({ mezcla: { name: 'mezcla', severity: 'high', via: ['picomatch'], range: '*' }, ...BAJAS })).ok, true);
});


// --- found by the independent verification of the hardened gate

test('a severity named like an Object.prototype member is unknown, not a valid level (toString, constructor, __proto__, hasOwnProperty)', () => {
  for (const severidad of ['toString', 'constructor', '__proto__', 'hasOwnProperty', 'valueOf']) {
    const enEntrada = evaluarReal(realCon({ intruso: { name: 'intruso', severity: severidad, via: ['picomatch'], range: '*' } }));
    assert.equal(enEntrada.ok, false, `entry severity ${severidad}`);
    assert.match(enEntrada.problemas.join('\n'), /severidad/i);
    const enAviso = evaluar(reporte(aviso(OTRO, 'x', severidad, '*')), [], HOY);
    assert.equal(enAviso.ok, false, `advisory severity ${severidad}`);
  }
});

test('an advisory object that cannot be evaluated fails whatever the severity of the entry that holds it (a low entry cannot hide it)', () => {
  const PEOR = {
    'a blank range': { source: 9, name: 'baja', severity: 'critical', url: `https://github.com/advisories/${OTRO}`, range: ' ' },
    'no url': { source: 9, name: 'baja', severity: 'critical', range: '<2' },
    'no name': { source: 9, severity: 'high', url: `https://github.com/advisories/${OTRO}`, range: '<2' },
    'an unknown severity': { source: 9, name: 'baja', severity: 'urgent', range: ' ' },
  };
  for (const [descripcion, malo] of Object.entries(PEOR)) {
    const r = evaluarReal(realCon({ baja: { name: 'baja', severity: 'low', via: [malo], range: '*' } }));
    assert.equal(r.ok, false, descripcion);
    assert.match(r.problemas.join('\n'), /baja/, descripcion);
  }
  // control: a malformed advisory that is itself below the threshold is not demanded
  assert.equal(evaluarReal(realCon({ baja: { name: 'baja', severity: 'low', via: [{ source: 9, name: 'baja', severity: 'low', range: ' ' }], range: '*' } })).ok, true);
});

test('the severity of a vulnerability is backed by the HIGHEST advisory it reaches, not the lowest', () => {
  const baja = aviso(OTRO, 'menor', 'low', '<2', 8);
  const mezclado = evaluarReal(realCon({ menor: { name: 'menor', severity: 'low', via: [baja], range: '*' }, mixta: { name: 'mixta', severity: 'high', via: ['picomatch', 'menor'], range: '*' } }));
  assert.equal(mezclado.ok, true, mezclado.problemas.join('\n'));
  const demasiado = evaluarReal(realCon({ menor: { name: 'menor', severity: 'low', via: [baja], range: '*' }, mixta: { name: 'mixta', severity: 'critical', via: ['picomatch', 'menor'], range: '*' } }));
  assert.equal(demasiado.ok, false);
  assert.match(demasiado.problemas.join('\n'), /mixta/);
});

test('the metadata counts of EVERY level are checked, and a report version above 2 is not accepted either', () => {
  for (const nivel of ['info', 'low', 'moderate', 'critical']) {
    const r = structuredClone(REPORTE_REAL);
    r.metadata.vulnerabilities[nivel] += 1;
    assert.equal(evaluarReal(r).ok, false, nivel);
  }
  for (const version of [3, '2', 2.5]) {
    const r = structuredClone(REPORTE_REAL);
    r.auditReportVersion = version;
    assert.equal(evaluarReal(r).ok, false, String(version));
  }
});

test('a used exception raises no «unused» warning, and a report that carries BOTH an error and vulnerabilities is not trusted', () => {
  assert.deepEqual(evaluarReal(REPORTE_REAL).avisos, []);
  const conError = structuredClone(REPORTE_REAL);
  conError.error = { code: 'EAUDIT', summary: 'algo salió mal' };
  assert.equal(evaluarReal(conError).ok, false);
});
