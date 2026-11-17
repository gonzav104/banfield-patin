import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdtempSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { evaluar } from './auditoria-npm.mjs';

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
/** A report shaped like `npm audit --json` (auditReportVersion 2): the advisory sits in the package that owns it; the packages that depend on it only point to it. */
const reporte = (...avisos) => ({
  auditReportVersion: 2,
  vulnerabilities: Object.fromEntries([
    ...avisos.map((a) => [a.name, { name: a.name, severity: a.severity, via: [a], range: '*' }]),
    ...(avisos.length ? [['dependiente', { name: 'dependiente', severity: 'high', via: [avisos[0].name], range: '*' }]] : []),
  ]),
  metadata: {},
});
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
  const r = evaluar({ auditReportVersion: 2, vulnerabilities: {}, metadata: {} }, [], HOY);
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
  const r = evaluar({ auditReportVersion: 2, vulnerabilities: {}, metadata: {} }, [excepcion()], HOY);
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
  const repetido = reporte(picomatch(7));
  repetido.vulnerabilities.copia = { name: 'copia', severity: 'high', via: [picomatch(7)], range: '*' };
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
  const sinFuente = reporte({ ...picomatch(), source: undefined }, { ...aviso(OTRO, 'picomatch', 'high', '<=4.0.7'), source: undefined });
  sinFuente.vulnerabilities.picomatch.via.push(sinFuente.vulnerabilities.picomatch.via[0]);
  const r = evaluar(
    { ...sinFuente, vulnerabilities: { picomatch: { name: 'picomatch', severity: 'high', via: [{ ...picomatch(), source: undefined }, { ...aviso(OTRO, 'picomatch', 'high', '<=4.0.7'), source: undefined }], range: '*' } } },
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
const correr = (...args) => spawnSync(process.execPath, [join(AQUI, 'auditoria-npm.mjs'), ...args], { encoding: 'utf8' });

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
    writeFileSync(limpio, JSON.stringify({ auditReportVersion: 2, vulnerabilities: {}, metadata: {} }));
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
    const r = spawnSync(process.execPath, [enlace, '--reporte', REAL, '--excepciones', vacio], { encoding: 'utf8' });
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
