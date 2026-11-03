// Guarda de seguridad: decide si es lícito pulsar "Siguiente". El texto del botón NO alcanza: en el apto, con "No" elegido,
// el botón sigue diciendo "Siguiente" aunque el formulario salta a "enviar" (se verificó en vivo). Por eso se mira también
// qué opciones están seleccionadas y adónde saltan según el contrato. Ante cualquier duda, se rechaza (falla cerrado).
import { limpiarTexto } from '../esquema/normalizar.mjs';

export function seleccionesDe(controles, contrato) {
  const porTitulo = new Map(Object.values(contrato.preguntas).map((q) => [q.titulo, q]));
  const selecciones = [];
  for (const c of controles.preguntas) {
    const valores = [...c.radios, ...c.listas];
    if (!valores.length) continue;
    const q = porTitulo.get(limpiarTexto(c.titulo));
    for (const rotulo of valores) selecciones.push({ itemId: q?.itemId ?? null, titulo: c.titulo, rotulo });
  }
  return selecciones;
}

export function decidirAvance({ etiquetaBoton, botones = [], contrato, selecciones }) {
  if (botones.includes('Enviar')) return { permitido: false, motivo: 'hay un botón "Enviar" en la pantalla' };
  if (etiquetaBoton !== 'Siguiente') return { permitido: false, motivo: `el botón dice "${etiquetaBoton}", no "Siguiente"` };
  for (const s of selecciones) {
    const q = s.itemId === null ? null : contrato.preguntas[String(s.itemId)];
    if (!q) return { permitido: false, motivo: `selección en una pregunta desconocida: "${s.titulo}"` };
    const opcion = q.opciones.find((o) => o.rotulo === s.rotulo);
    if (!opcion) return { permitido: false, motivo: `opción desconocida "${s.rotulo}" en "${q.titulo}"` };
    if (opcion.salto === 'enviar') return { permitido: false, motivo: `la opción "${s.rotulo}" de "${q.titulo}" salta a ENVIAR el formulario` };
  }
  return { permitido: true, motivo: null };
}
