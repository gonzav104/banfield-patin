#!/usr/bin/env node
// Gate de `npm audit` del job «Auditoría de dependencias».
//
// Hace DOS auditorías independientes, las dos con el umbral en `moderate`:
//
//   1. Auditoría completa (`npm audit --json`): no acepta ningún aviso que no esté listado, con motivo y fecha de vencimiento,
//      en .github/auditoria-excepciones.json. Además, TODA vulnerabilidad de severidad moderate o superior del reporte debe quedar
//      completamente explicada por avisos verificables: se siguen las referencias transitivas (`via`) hasta los avisos de origen
//      y se rechaza lo desconocido, lo incompleto, los ciclos sin origen y cualquier caso ambiguo. Encontrar el aviso permitido
//      en alguna parte del reporte no alcanza.
//   2. Auditoría de producción (`npm audit --omit=dev --json`): NINGUNA excepción aplica. Cualquier aviso moderate o superior en
//      las dependencias de producción falla, aunque su GHSA esté exceptuado: la excepción se justificó porque el paquete es solo
//      de desarrollo, y esa condición se comprueba en cada ejecución en lugar de darse por cierta.
//
// Un reporte que no se puede interpretar (sin versión 2, sin `metadata` coherente con las entradas, sin JSON, con error) no cuenta
// como aprobado. No instala nada: usa solo package-lock.json (como `npm audit`).
//
// Una excepción deja de valer (y el job falla) cuando:
//   - vence (la fecha `vence` es el último día en que vale, hasta las 23:59:59 UTC);
//   - su vencimiento queda a más de 31 días de hoy;
//   - cambia el rango afectado que informa npm (señal de que se publicó una versión corregida: hay que actualizar);
//   - el aviso es de otro paquete, o el registro de la excepción está incompleto.
// Una excepción que ningún aviso usa ya es solo una advertencia: se puede borrar.
import { readFileSync, realpathSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Sin prototipo: `ORDEN['toString']` no puede pasar por un nivel de severidad válido.
const ORDEN = Object.freeze(Object.assign(Object.create(null), { info: 0, low: 1, moderate: 2, high: 3, critical: 4 }));
const UMBRAL = ORDEN.moderate;
/** Una excepción no puede vencer más allá de este plazo desde hoy: una fecha lejana la convertiría en permanente. */
const PLAZO_MAXIMO_DIAS = 31;
const DIA_MS = 86_400_000;
const GHSA = /^GHSA(-[2-9cfghjmpqrvwx]{4}){3}$/;
const FECHA = /^\d{4}-\d{2}-\d{2}$/;

const idDelAviso = (url) => String(url ?? '').split('/').pop();
const esObjeto = (x) => x !== null && typeof x === 'object' && !Array.isArray(x);
const hayTexto = (x) => typeof x === 'string' && x.trim() !== '';
const tiene = (objeto, clave) => Object.hasOwn(objeto, clave);
/** Un aviso (objeto de `via`) que se puede evaluar: sabe de qué paquete es, qué rango afecta y dónde está su ficha. La severidad se juzga aparte. */
const esAviso = (via) => esObjeto(via) && hayTexto(via.url) && hayTexto(via.name) && hayTexto(via.range);
const claveDelAviso = (via) => JSON.stringify([via.source ?? null, via.name ?? null, via.url ?? null, via.range ?? null]);
const describir = (aviso) => `${aviso.ghsa} (${aviso.paquete} ${aviso.rango}, ${aviso.severidad}): ${aviso.titulo}`;

/** Los avisos de un reporte: los objetos de `via` (los paquetes que dependen de ellos apuntan por nombre). Un mismo aviso repetido cuenta una vez; dos avisos distintos nunca se funden. */
function avisosDe(reporte) {
  const distintos = new Map();
  for (const vulnerabilidad of Object.values(reporte.vulnerabilities)) {
    if (!esObjeto(vulnerabilidad) || !Array.isArray(vulnerabilidad.via)) continue;
    for (const via of vulnerabilidad.via) {
      if (!esAviso(via)) continue;
      const clave = claveDelAviso(via);
      if (!distintos.has(clave)) distintos.set(clave, via);
    }
  }
  return [...distintos.values()].map((via) => ({
    ghsa: idDelAviso(via.url),
    paquete: via.name,
    severidad: via.severity,
    rango: via.range,
    titulo: via.title ?? '',
  }));
}

/** El reporte tiene que ser un reporte completo de `npm audit` (versión 2) y su `metadata` tiene que coincidir con las entradas. */
function problemasDeCompletitud(reporte) {
  const problemas = [];
  if (reporte.auditReportVersion !== 2) {
    problemas.push(`El reporte es de una versión que no se sabe interpretar (auditReportVersion ${JSON.stringify(reporte.auditReportVersion)}; se espera 2).`);
  }
  const cuentas = reporte.metadata?.vulnerabilities;
  if (!esObjeto(cuentas)) {
    problemas.push('El reporte está incompleto: falta metadata.vulnerabilities, así que no se puede comprobar que estén todas las vulnerabilidades.');
    return problemas;
  }
  const entradas = Object.values(reporte.vulnerabilities);
  for (const severidad of Object.keys(ORDEN)) {
    const hay = entradas.filter((entrada) => esObjeto(entrada) && entrada.severity === severidad).length;
    if (!Number.isInteger(cuentas[severidad]) || cuentas[severidad] < 0) {
      problemas.push(`El reporte está incompleto: metadata.vulnerabilities.${severidad} no es un número válido.`);
    } else if (cuentas[severidad] !== hay) {
      problemas.push(`El reporte es incoherente: metadata dice ${cuentas[severidad]} de severidad ${severidad} y hay ${hay} entradas.`);
    }
  }
  if (!Number.isInteger(cuentas.total) || cuentas.total !== entradas.length) {
    problemas.push(`El reporte es incoherente: metadata dice ${JSON.stringify(cuentas.total)} vulnerabilidades en total y hay ${entradas.length} entradas.`);
  }
  return problemas;
}

/**
 * Sigue las referencias de `via` de una vulnerabilidad hasta los avisos de origen. Devuelve los avisos alcanzados y los problemas
 * que impiden darla por explicada: referencias a entradas que no existen, entradas incompletas, vías vacías, avisos inválidos o
 * de otro paquete. Los ciclos se recorren una sola vez; un ciclo sin ningún aviso de origen deja la lista de avisos vacía.
 */
function seguirReferencias(nombre, entradas) {
  const problemas = [];
  const avisos = new Map();
  const visitadas = new Set([nombre]);
  const pendientes = [nombre];
  while (pendientes.length) {
    const actual = pendientes.pop();
    const entrada = entradas[actual];
    const donde = actual === nombre ? `la vulnerabilidad ${nombre}` : `${actual} (alcanzada desde ${nombre})`;
    if (!esObjeto(entrada) || !Array.isArray(entrada.via)) {
      problemas.push(`${donde} no tiene un «via» válido.`);
      continue;
    }
    if (entrada.via.length === 0) {
      problemas.push(`${donde} tiene el «via» vacío: no apunta a ningún aviso ni a ninguna otra vulnerabilidad.`);
      continue;
    }
    for (const via of entrada.via) {
      if (typeof via === 'string') {
        if (!tiene(entradas, via)) problemas.push(`${donde} apunta a «${via}», que no existe en el reporte.`);
        else if (!visitadas.has(via)) {
          visitadas.add(via);
          pendientes.push(via);
        }
      } else if (!esAviso(via)) {
        problemas.push(`${donde} contiene un elemento de «via» que no es una referencia ni un aviso completo.`);
      } else if (via.name !== actual) {
        problemas.push(`${donde} contiene un aviso que es de otro paquete (${JSON.stringify(via.name)}).`);
      } else {
        avisos.set(claveDelAviso(via), via);
      }
    }
  }
  return { problemas, avisos: [...avisos.values()] };
}

/** Cada vulnerabilidad de severidad moderate o superior (o de severidad desconocida) tiene que quedar completamente explicada por avisos verificables. */
function problemasDeExplicacion(reporte) {
  const problemas = [];
  const entradas = reporte.vulnerabilities;
  for (const [nombre, entrada] of Object.entries(entradas)) {
    const severidad = esObjeto(entrada) ? entrada.severity : undefined;
    const nivel = ORDEN[severidad];
    if (nivel !== undefined && nivel < UMBRAL) {
      // Por debajo del umbral no se exige que la entrada se explique, pero una entrada baja no puede esconder un aviso que no se puede evaluar
      // y que declara una severidad desconocida o de moderate para arriba.
      for (const via of Array.isArray(entrada.via) ? entrada.via : []) {
        if (esObjeto(via) && !esAviso(via) && (ORDEN[via.severity] === undefined || ORDEN[via.severity] >= UMBRAL)) {
          problemas.push(`La entrada ${nombre} (${severidad}) contiene un aviso que no se puede evaluar (falta su ficha, su paquete o su rango) y declara severidad ${JSON.stringify(via.severity)}.`);
        }
      }
      continue;
    }
    if (!esObjeto(entrada)) {
      problemas.push(`La entrada «${nombre}» del reporte no es un objeto: no se puede saber qué vulnerabilidad representa.`);
      continue;
    }
    if (nivel === undefined) {
      problemas.push(`Vulnerabilidad ${nombre} con severidad desconocida (${JSON.stringify(severidad)}): no se puede comparar con el umbral.`);
      continue;
    }
    if (entrada.name !== nombre) {
      problemas.push(`La vulnerabilidad ${nombre} se llama ${JSON.stringify(entrada.name)} dentro del reporte: la entrada es ambigua.`);
      continue;
    }
    const { problemas: detalles, avisos } = seguirReferencias(nombre, entradas);
    if (detalles.length) {
      problemas.push(`Vulnerabilidad ${nombre} (${severidad}) sin explicar: ${detalles.join(' ')}`);
      continue;
    }
    if (avisos.length === 0) {
      problemas.push(`Vulnerabilidad ${nombre} (${severidad}) sin explicar: ninguna cadena de «via» llega a un aviso de origen (¿un ciclo sin origen?).`);
      continue;
    }
    const nivelesDeLosAvisos = avisos.map((aviso) => ORDEN[aviso.severity]).filter((valor) => valor !== undefined);
    if (nivelesDeLosAvisos.length === 0 || Math.max(...nivelesDeLosAvisos) < nivel) {
      problemas.push(`Vulnerabilidad ${nombre} declara severidad ${severidad}, mayor que la de los avisos que alcanza: la severidad no está respaldada por ningún aviso.`);
    }
  }
  return problemas;
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
  if (!esObjeto(reporte) || reporte.error || !esObjeto(reporte.vulnerabilities)) {
    const detalle = reporte?.error ? `${reporte.error.code ?? 'error'}: ${reporte.error.summary ?? ''}` : 'la respuesta no es un reporte de auditoría';
    resultado.problemas.push(`No se pudo auditar (${detalle}). Una auditoría que no corre no cuenta como aprobada.`);
    return resultado;
  }
  const incompleto = problemasDeCompletitud(reporte);
  if (incompleto.length) {
    resultado.problemas.push(...incompleto);
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
    const excepcion = validas.find((e) => e.ghsa === aviso.ghsa);
    if (!excepcion) {
      resultado.problemas.push(`Aviso sin excepción: ${describir(aviso)}`);
      continue;
    }
    usadas.add(excepcion.ghsa);
    if (excepcion.paquete !== aviso.paquete) {
      resultado.problemas.push(`La excepción de ${excepcion.ghsa} es para el paquete ${excepcion.paquete}, no para ${aviso.paquete}.`);
    } else if (excepcion.rango !== aviso.rango) {
      resultado.problemas.push(`El rango afectado de ${excepcion.ghsa} cambió (excepción: ${excepcion.rango}; npm: ${aviso.rango}): ¿hay una versión corregida? Revisar y actualizar.`);
    } else if (ahora.getTime() >= Date.parse(`${excepcion.vence}T00:00:00Z`) + DIA_MS) {
      resultado.problemas.push(`La excepción de ${excepcion.ghsa} venció el ${excepcion.vence}: ${describir(aviso)}`);
    } else if (Date.parse(`${excepcion.vence}T00:00:00Z`) > ahora.getTime() + PLAZO_MAXIMO_DIAS * DIA_MS) {
      resultado.problemas.push(`La excepción de ${excepcion.ghsa} vence el ${excepcion.vence}, demasiado lejos: el plazo máximo es de ${PLAZO_MAXIMO_DIAS} días desde hoy.`);
    } else {
      resultado.aceptados.push({ ...aviso, vence: excepcion.vence, motivo: excepcion.motivo });
    }
  }

  // Los avisos permitidos no bastan: cada vulnerabilidad del reporte tiene que estar explicada por ellos.
  resultado.problemas.push(...problemasDeExplicacion(reporte));

  for (const e of validas) {
    if (!usadas.has(e.ghsa)) resultado.avisos.push(`La excepción ${e.ghsa} ya no la usa ningún aviso: se puede borrar de auditoria-excepciones.json.`);
  }
  resultado.ok = resultado.problemas.length === 0;
  return resultado;
}

/**
 * Auditoría de las dependencias de producción (`npm audit --omit=dev --json`). NINGUNA excepción aplica: cualquier aviso
 * moderate o superior falla, aunque su GHSA esté exceptuado, porque la excepción se justifica por ser solo de desarrollo.
 */
export function evaluarProduccion(reporte, ahora = new Date()) {
  const { ok, problemas } = evaluar(reporte, [], ahora);
  return { ok, problemas: problemas.map((problema) => `En producción (ninguna excepción aplica): ${problema}`) };
}

function argumento(nombre) {
  const i = process.argv.indexOf(`--${nombre}`);
  return i === -1 ? undefined : process.argv[i + 1];
}

/** Lee el reporte de un archivo (pruebas y diagnóstico) o lo pide a `npm`. `npm audit` sale con código 1 cuando encuentra avisos: lo que cuenta es el JSON. */
function obtenerReporte(ruta, argumentosDeNpm) {
  if (ruta) return JSON.parse(readFileSync(ruta, 'utf8'));
  const corrida = spawnSync('npm', argumentosDeNpm, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  if (corrida.error) throw corrida.error;
  return JSON.parse(corrida.stdout);
}

function main() {
  const rutaExcepciones = argumento('excepciones') ?? fileURLToPath(new URL('../auditoria-excepciones.json', import.meta.url));
  const ahora = argumento('ahora') ? new Date(argumento('ahora')) : new Date();

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
    reporte = obtenerReporte(argumento('reporte'), ['audit', '--json']);
  } catch (error) {
    console.error(`No se pudo obtener el reporte de auditoría: ${error.message}`);
    return 1;
  }
  let produccion;
  try {
    produccion = obtenerReporte(argumento('reporte-produccion'), ['audit', '--omit=dev', '--json']);
  } catch (error) {
    console.error(`No se pudo obtener el reporte de auditoría de producción: ${error.message}`);
    return 1;
  }

  const completa = evaluar(reporte, excepciones, ahora);
  const deProduccion = evaluarProduccion(produccion, ahora);
  for (const a of completa.aceptados) {
    console.log(`Aviso aceptado hasta el ${a.vence}: ${a.ghsa} (${a.paquete} ${a.rango}, ${a.severidad}). ${a.motivo}`);
  }
  for (const advertencia of completa.avisos) console.log(`Advertencia: ${advertencia}`);
  const problemas = [...completa.problemas, ...deProduccion.problemas];
  for (const problema of problemas) console.error(`::error::${problema}`);
  if (problemas.length) {
    console.error(`Auditoría de dependencias: ${problemas.length} problema(s).`);
    return 1;
  }
  console.log('Auditoría completa: sin avisos moderate o superiores fuera de las excepciones vigentes y con todas las vulnerabilidades explicadas.');
  console.log('Auditoría de producción: sin avisos moderate o superiores (ninguna excepción aplica a producción).');
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
