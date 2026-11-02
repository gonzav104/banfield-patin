// Compara lo que se esperaba precargar con lo que muestran los controles reales (resultado de LEER_CONTROLES).
import { limpiarTexto } from '../esquema/normalizar.mjs';

export const ESTADO = Object.freeze({
  OK: 'ok', DISTINTO: 'distinto', VACIO: 'vacio', NO_VISIBLE: 'no-visible', ARCHIVO: 'archivo', SIN_DATO: 'sin-dato',
});

function esperado(pregunta, valor) {
  switch (pregunta.formato) {
    case 'texto': return String(valor).trim();
    case 'digitos': return String(valor).replace(/\D/g, '');
    case 'opcion': return pregunta.opciones[valor];
    case 'fecha': return String(valor);
    default: return undefined;
  }
}

function actual(control, tipo) {
  if (tipo === 'texto' || tipo === 'parrafo') return control.textos[0] ?? '';
  if (tipo === 'fecha') return control.fechas[0] ?? '';
  if (tipo === 'lista') return control.listas[0] ?? '';
  if (tipo === 'opcion') return control.radios[0] ?? '';
  return '';
}

export function verificarPrecarga({ controles, perfil, valores, contrato }) {
  const porTitulo = new Map(controles.preguntas.map((c) => [limpiarTexto(c.titulo), c]));
  const filas = [];
  for (const p of perfil.preguntas) {
    const q = contrato.preguntas[String(p.itemId)];
    const fila = { clave: p.clave, titulo: q.titulo, tipo: q.tipo, sensibilidad: p.sensibilidad };
    if (p.formato === 'archivo') { filas.push({ ...fila, estado: ESTADO.ARCHIVO }); continue; }
    const valor = valores[p.clave];
    if (valor === undefined || valor === null || valor === '') { filas.push({ ...fila, estado: ESTADO.SIN_DATO }); continue; }
    const control = porTitulo.get(q.titulo);
    if (!control) { filas.push({ ...fila, estado: ESTADO.NO_VISIBLE }); continue; }
    const esp = esperado(p, valor); const act = actual(control, q.tipo);
    filas.push({ ...fila, esperado: esp, actual: act, estado: act === '' ? ESTADO.VACIO : act === esp ? ESTADO.OK : ESTADO.DISTINTO });
  }
  return filas;
}

export function resumir(filas) {
  const cuenta = (e) => filas.filter((f) => f.estado === e).length;
  const verificables = filas.filter((f) => [ESTADO.OK, ESTADO.DISTINTO, ESTADO.VACIO].includes(f.estado));
  return {
    ok: cuenta(ESTADO.OK), distinto: cuenta(ESTADO.DISTINTO), vacio: cuenta(ESTADO.VACIO), noVisible: cuenta(ESTADO.NO_VISIBLE), archivo: cuenta(ESTADO.ARCHIVO), sinDato: cuenta(ESTADO.SIN_DATO),
    verificables: verificables.length, porcentajeSobreVerificables: verificables.length ? Math.round((cuenta(ESTADO.OK) / verificables.length) * 1000) / 10 : null,
  };
}

// El resultado de browser_evaluate llega como "### Result\n<json>\n### Ran ...": se toma lo que hay entre ambos encabezados.
export function extraerResultado(texto) {
  const m = /### Result\s*\n([\s\S]*?)(?:\n### |$)/.exec(texto);
  if (!m) throw new Error('La respuesta no contiene la sección "### Result"');
  return JSON.parse(m[1]);
}
