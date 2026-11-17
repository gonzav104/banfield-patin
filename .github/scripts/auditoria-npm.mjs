#!/usr/bin/env node
// Gate de `npm audit` del job «Auditoría de dependencias».
//
// Mantiene el umbral en `moderate` y no acepta ningún aviso que no esté listado, con motivo y fecha de vencimiento,
// en .github/auditoria-excepciones.json. No instala nada: usa solo package-lock.json (como `npm audit`).
//
// Una excepción deja de aplicar (y el job falla) cuando:
//   - vence (la fecha `vence` es el último día en que vale, hasta las 23:59:59 UTC);
//   - cambia el rango afectado que informa npm (señal de que se publicó una versión corregida: hay que actualizar);
//   - el aviso es de otro paquete, o el registro de la excepción está incompleto.
// Una excepción que ningún aviso usa ya es solo una advertencia: se puede borrar.
import { readFileSync, realpathSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ORDEN = { info: 0, low: 1, moderate: 2, high: 3, critical: 4 };
const UMBRAL = ORDEN.moderate;
/** Una excepción no puede vencer más allá de este plazo desde hoy: una fecha lejana la convertiría en permanente. */
const PLAZO_MAXIMO_DIAS = 31;
const DIA_MS = 86_400_000;
const GHSA = /^GHSA(-[2-9cfghjmpqrvwx]{4}){3}$/;
const FECHA = /^\d{4}-\d{2}-\d{2}$/;

const idDelAviso = (url) => String(url ?? '').split('/').pop();

/** Los avisos de un reporte de `npm audit --json`: solo los objetos de `via` (los paquetes que dependen de ellos apuntan por nombre). */
function avisosDe(reporte) {
  // Un mismo aviso puede repetirse en varias entradas; dos avisos distintos nunca se funden (clave: fuente, paquete, id y rango).
  const distintos = new Map();
  for (const vulnerabilidad of Object.values(reporte.vulnerabilities)) {
    for (const via of vulnerabilidad.via ?? []) {
      if (!via || typeof via !== 'object') continue;
      const clave = JSON.stringify([via.source ?? null, via.name ?? null, via.url ?? null, via.range ?? null]);
      if (!distintos.has(clave)) distintos.set(clave, via);
    }
  }
  return [...distintos.values()].map((via) => ({
    ghsa: idDelAviso(via.url),
    paquete: via.name,
    severidad: via.severity,
    rango: via.range,
    titulo: via.title,
  }));
}

function problemasDeLaExcepcion(e) {
  const faltas = [];
  if (!GHSA.test(String(e?.ghsa ?? ''))) faltas.push('ghsa');
  for (const campo of ['paquete', 'rango', 'motivo']) if (typeof e?.[campo] !== 'string' || !e[campo].trim()) faltas.push(campo);
  if (!FECHA.test(String(e?.vence ?? '')) || Number.isNaN(Date.parse(`${e.vence}T00:00:00Z`))) faltas.push('vence');
  return faltas;
}

/**
 * @param reporte salida parseada de `npm audit --json`
 * @param excepciones lista de excepciones versionadas
 * @param ahora instante contra el que se mide el vencimiento (inyectable para las pruebas)
 */
export function evaluar(reporte, excepciones, ahora = new Date()) {
  const resultado = { ok: false, problemas: [], avisos: [], aceptados: [] };
  if (Number.isNaN(ahora?.getTime?.())) {
    resultado.problemas.push('La fecha de referencia no es válida: sin ella no se puede medir el vencimiento de las excepciones.');
    return resultado;
  }
  if (!reporte || typeof reporte !== 'object' || reporte.error || !reporte.vulnerabilities || typeof reporte.vulnerabilities !== 'object' || Array.isArray(reporte.vulnerabilities)) {
    const detalle = reporte?.error ? `${reporte.error.code ?? 'error'}: ${reporte.error.summary ?? ''}` : 'la respuesta no es un reporte de auditoría';
    resultado.problemas.push(`No se pudo auditar (${detalle}). Una auditoría que no corre no cuenta como aprobada.`);
    return resultado;
  }

  const validas = [];
  for (const e of excepciones ?? []) {
    const faltas = problemasDeLaExcepcion(e);
    if (faltas.length) resultado.problemas.push(`Excepción mal formada (${e?.ghsa ?? 'sin ghsa'}): falta o es inválido ${faltas.join(', ')}.`);
    else validas.push(e);
  }

  const usadas = new Set();
  for (const aviso of avisosDe(reporte)) {
    if (ORDEN[aviso.severidad] === undefined) {
      // Un nivel que no conocemos no se puede comparar con el umbral: se falla en lugar de ignorarlo.
      resultado.problemas.push(`Aviso con severidad desconocida (${JSON.stringify(aviso.severidad)}): ${aviso.ghsa} (${aviso.paquete} ${aviso.rango}): ${aviso.titulo}`);
      continue;
    }
    if (ORDEN[aviso.severidad] < UMBRAL) continue;
    const etiqueta = `${aviso.ghsa} (${aviso.paquete} ${aviso.rango}, ${aviso.severidad}): ${aviso.titulo}`;
    const excepcion = validas.find((e) => e.ghsa === aviso.ghsa);
    if (!excepcion) {
      resultado.problemas.push(`Aviso sin excepción: ${etiqueta}`);
      continue;
    }
    usadas.add(excepcion.ghsa);
    if (excepcion.paquete !== aviso.paquete) {
      resultado.problemas.push(`La excepción de ${excepcion.ghsa} es para el paquete ${excepcion.paquete}, no para ${aviso.paquete}.`);
    } else if (excepcion.rango !== aviso.rango) {
      resultado.problemas.push(`El rango afectado de ${excepcion.ghsa} cambió (excepción: ${excepcion.rango}; npm: ${aviso.rango}): ¿hay una versión corregida? Revisar y actualizar.`);
    } else if (ahora.getTime() >= Date.parse(`${excepcion.vence}T00:00:00Z`) + DIA_MS) {
      resultado.problemas.push(`La excepción de ${excepcion.ghsa} venció el ${excepcion.vence}: ${etiqueta}`);
    } else if (Date.parse(`${excepcion.vence}T00:00:00Z`) > ahora.getTime() + PLAZO_MAXIMO_DIAS * DIA_MS) {
      resultado.problemas.push(`La excepción de ${excepcion.ghsa} vence el ${excepcion.vence}, demasiado lejos: el plazo máximo es de ${PLAZO_MAXIMO_DIAS} días desde hoy.`);
    } else {
      resultado.aceptados.push({ ...aviso, vence: excepcion.vence, motivo: excepcion.motivo });
    }
  }

  for (const e of validas) {
    if (!usadas.has(e.ghsa)) resultado.avisos.push(`La excepción ${e.ghsa} ya no la usa ningún aviso: se puede borrar de auditoria-excepciones.json.`);
  }
  resultado.ok = resultado.problemas.length === 0;
  return resultado;
}

function argumento(nombre) {
  const i = process.argv.indexOf(`--${nombre}`);
  return i === -1 ? undefined : process.argv[i + 1];
}

function main() {
  const rutaExcepciones = argumento('excepciones') ?? fileURLToPath(new URL('../auditoria-excepciones.json', import.meta.url));
  const ahora = argumento('ahora') ? new Date(argumento('ahora')) : new Date();
  const rutaReporte = argumento('reporte');

  let excepciones;
  try {
    excepciones = JSON.parse(readFileSync(rutaExcepciones, 'utf8')).excepciones;
    if (!Array.isArray(excepciones)) throw new Error('falta la lista «excepciones»');
  } catch (error) {
    console.error(`No se pudo leer el archivo de excepciones: ${error.message}`);
    return 1;
  }

  let reporte;
  try {
    if (rutaReporte) {
      reporte = JSON.parse(readFileSync(rutaReporte, 'utf8'));
    } else {
      // `npm audit` sale con código 1 cuando encuentra avisos: lo que cuenta es el JSON.
      const corrida = spawnSync('npm', ['audit', '--json'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
      reporte = JSON.parse(corrida.stdout);
    }
  } catch (error) {
    console.error(`No se pudo obtener el reporte de auditoría: ${error.message}`);
    return 1;
  }

  const resultado = evaluar(reporte, excepciones, ahora);
  for (const a of resultado.aceptados) {
    console.log(`Aviso aceptado hasta el ${a.vence}: ${a.ghsa} (${a.paquete} ${a.rango}, ${a.severidad}). ${a.motivo}`);
  }
  for (const advertencia of resultado.avisos) console.log(`Advertencia: ${advertencia}`);
  for (const problema of resultado.problemas) console.error(`::error::${problema}`);
  if (!resultado.ok) {
    console.error(`Auditoría de dependencias: ${resultado.problemas.length} problema(s).`);
    return 1;
  }
  console.log('Auditoría de dependencias: sin avisos moderate o superiores fuera de las excepciones vigentes.');
  return 0;
}

// Se compara por ruta real: por un enlace simbólico `import.meta.url` ya viene resuelto y `argv[1]` no, y un `main()` que no corre sale con 0.
const esElPrograma = (() => {
  try {
    return realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url));
  } catch {
    return false;
  }
})();
if (esElPrograma) process.exitCode = main();
