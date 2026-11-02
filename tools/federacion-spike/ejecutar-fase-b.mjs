// Fase B (sin envío, sin archivos, datos ficticios) sobre el Chrome ya autenticado de la persona.
// Uso: node ejecutar-fase-b.mjs  → escribe salida/fase-b.json. Requiere aprobar la conexión de la extensión de Playwright.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ClienteMcp } from './src/vivo/cliente-mcp.mjs';
import { SesionFormulario } from './src/vivo/sesion.mjs';
import { verificarPrecarga, resumir, ESTADO } from './src/vivo/verificar.mjs';
import { construirEnlace } from './src/precompletado/enlace.mjs';
import { normalizar } from './src/esquema/normalizar.mjs';
import { comparar, exigirCompatible } from './src/esquema/comparar.mjs';
import { validarPerfil } from './src/perfil/validar.mjs';
import { valoresDe, PERFILES } from './src/datos/ficticios.mjs';

const RAIZ = path.dirname(fileURLToPath(import.meta.url));
const leer = (...p) => JSON.parse(fs.readFileSync(path.join(RAIZ, ...p), 'utf8'));
const RADIO_DE_CAMINO = { padron: 'apto.tiene', apto: 'deportista.empadronado' }; // la pregunta con salto que hay que elegir a mano

// Une las filas de varias secciones: una pregunta cuenta como "ok" si en alguna pantalla se vio correcta.
const PESO = { [ESTADO.OK]: 5, [ESTADO.DISTINTO]: 4, [ESTADO.VACIO]: 3, [ESTADO.ARCHIVO]: 2, [ESTADO.SIN_DATO]: 2, [ESTADO.NO_VISIBLE]: 1 };
const unir = (...listas) => [...new Map(listas.flat().sort((a, b) => PESO[a.estado] - PESO[b.estado]).map((f) => [f.clave, f])).values()];

const cliente = await ClienteMcp.iniciar();
console.log('Esperando que apruebes la conexión de la extensión de Playwright en Chrome (hasta 15 minutos)…');
await cliente.herramienta('browser_tabs', { action: 'list' }, { timeoutMs: 15 * 60 * 1000 });
console.log('Conexión aprobada.');
const informe = { fecha: new Date().toISOString(), formularios: {} };
try {
  for (const form of ['padron', 'apto']) {
    const perfil = leer('perfiles', `${form}-2026.json`); const base = leer('contratos', `${form}-2026.contrato.json`);
    const ses = new SesionFormulario(cliente, base);
    const r = { esquema: null, perfiles: {} }; informe.formularios[form] = r;

    // Fase D como compuerta: si el formulario cambió de forma incompatible, no se rellena nada.
    await ses.abrir(`https://docs.google.com/forms/d/e/${perfil.formId}/viewform`);
    const vivo = normalizar(await ses.esquemaVivo());
    const dif = comparar(base, vivo); exigirCompatible(dif); validarPerfil(perfil, vivo);
    r.esquema = { compatible: dif.compatible, diferencias: dif.diferencias.length, huella: dif.huellaNueva };

    for (const nombre of Object.keys(PERFILES)) {
      const valores = valoresDe(nombre); const p = { enlace: null, seccion1: null, avance: null, seccion2: null, final: null };
      p.limpio = await ses.limpiarBorrador(`https://docs.google.com/forms/d/e/${perfil.formId}/viewform`);
      if (!p.limpio) throw new Error('No se pudo limpiar el borrador: los resultados no serían confiables');
      const enlace = construirEnlace({ perfil, valores }); p.enlace = { parametros: enlace.incluidos.length, largo: enlace.url.length, omitidos: enlace.omitidos.length };
      p.apertura = await ses.abrir(enlace.url);
      const filas1 = verificarPrecarga({ controles: await ses.controles(), perfil, valores, contrato: base });
      p.seccion1 = resumir(filas1);
      p.correoMarcado = await ses.marcarCorreo();
      const radio = perfil.preguntas.find((q) => q.clave === RADIO_DE_CAMINO[form]);
      p.radioElegido = await ses.elegir(radio.opciones[valores[radio.clave]]);
      const av = await ses.avanzar(); p.avance = { avanzo: av.avanzo, motivo: av.motivo ?? null, botones: av.botones };
      let filas2 = [];
      if (av.avanzo) { const c2 = await ses.controles(); filas2 = verificarPrecarga({ controles: c2, perfil, valores, contrato: base }); p.seccion2 = resumir(filas2); p.botonesSeccion2 = c2.botones; }
      else if (!av.motivo) p.alertas = await ses.alertas();
      if (av.avanzo === true && (p.avance.botones ?? []).length === 0) p.avance.nota = 'sin botones';
      if (!av.avanzo && !av.motivo) p.bloqueo = await ses.alertas();
      if (av.avanzo && p.botonesSeccion2?.includes('Enviar')) p.nota = 'la última pantalla muestra "Enviar": no se pulsó';
      p.final = resumir(unir(filas1, filas2));
      p.filas = unir(filas1, filas2).filter((f) => f.estado !== ESTADO.ARCHIVO).map((f) => ({ clave: f.clave, estado: f.estado, sensibilidad: f.sensibilidad }));
      r.perfiles[nombre] = p;
      console.log(`${form}/${nombre}: avanzó=${av.avanzo} ${av.motivo ? `(${av.motivo})` : ''} final=${JSON.stringify(p.final)}`);
    }
    r.bitacora = ses.bitacora;
  }
} finally { cliente.cerrar(); }
fs.mkdirSync(path.join(RAIZ, 'salida'), { recursive: true });
fs.writeFileSync(path.join(RAIZ, 'salida', 'fase-b.json'), JSON.stringify(informe, null, 2));
console.log('listo: salida/fase-b.json');
