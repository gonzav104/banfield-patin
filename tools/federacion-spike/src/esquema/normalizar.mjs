// Convierte el esquema crudo de un Google Form (FB_PUBLIC_LOAD_DATA_) en un "contrato" estable y comparable.
import { createHash } from 'node:crypto';

const TIPOS = { 0: 'texto', 1: 'parrafo', 2: 'opcion', 3: 'lista', 4: 'casillas', 5: 'escala', 7: 'cuadricula', 9: 'fecha', 10: 'hora', 13: 'archivo', 18: 'calificacion' };
const TIPO_SECCION = 8;

export function limpiarTexto(valor) {
  return String(valor ?? '')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ').trim();
}

// -2: "ir a la siguiente sección"; -3: "enviar el formulario"; >0: id de la sección destino.
function destino(valor) {
  if (valor === null || valor === undefined) return null;
  if (valor === -2 || valor === -1) return 'siguiente';
  if (valor === -3) return 'enviar';
  return `seccion:${valor}`;
}

export function normalizar(crudo) {
  if (!crudo || !Array.isArray(crudo.items)) throw new Error('El esquema no tiene la lista de ítems (items)');
  const secciones = [];
  const preguntas = {};
  let actual = { id: null, titulo: '(inicio)', preguntas: [] };
  for (const item of crudo.items) {
    const [id, titulo, ayuda, tipoCodigo, definiciones] = item;
    if (tipoCodigo === TIPO_SECCION) {
      secciones.push(actual);
      actual = { id, titulo: limpiarTexto(titulo), preguntas: [] };
      continue;
    }
    const q = Array.isArray(definiciones) ? definiciones[0] : null;
    if (!q) continue; // textos, imágenes y videos: no reciben respuesta
    const tipo = TIPOS[tipoCodigo] ?? `desconocido:${tipoCodigo}`;
    const opciones = Array.isArray(q[1]) ? q[1].map((o) => ({ rotulo: limpiarTexto(o[0]), salto: destino(o[2]), otro: Boolean(o[4]) })) : [];
    preguntas[String(id)] = {
      itemId: id,
      entry: q[0],
      titulo: limpiarTexto(titulo),
      ayuda: limpiarTexto(ayuda),
      tipo,
      obligatoria: q[2] === 1,
      opciones,
      validacion: q[4] ?? null,
      fecha: tipo === 'fecha' ? (q[7] ?? null) : null,
    };
    actual.preguntas.push(id);
  }
  secciones.push(actual);
  return { titulo: limpiarTexto(crudo.titulo), configuracion: crudo.config ?? null, secciones, preguntas };
}

function ordenar(valor) {
  if (Array.isArray(valor)) return valor.map(ordenar);
  if (valor && typeof valor === 'object') return Object.fromEntries(Object.keys(valor).sort().map((k) => [k, ordenar(valor[k])]));
  return valor;
}
export const canonico = (valor) => JSON.stringify(ordenar(valor));

// Parte del contrato que importa para rellenar: ignora títulos, textos de ayuda y el orden de preguntas y opciones.
export function contratoDuro(contrato) {
  return {
    configuracion: contrato.configuracion ?? null,
    secciones: contrato.secciones.map((s) => ({ id: s.id, preguntas: [...s.preguntas].sort((a, b) => a - b) })),
    preguntas: Object.fromEntries(Object.entries(contrato.preguntas).map(([id, q]) => [id, {
      entry: q.entry, tipo: q.tipo, obligatoria: q.obligatoria, validacion: q.validacion, fecha: q.fecha,
      opciones: q.opciones.map((o) => ({ rotulo: o.rotulo, salto: o.salto, otro: o.otro })).sort((a, b) => a.rotulo.localeCompare(b.rotulo)),
    }])),
  };
}

export const huella = (contrato) => createHash('sha256').update(canonico(contratoDuro(contrato))).digest('hex');
