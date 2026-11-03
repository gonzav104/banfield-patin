// Compara dos contratos y clasifica cada diferencia: incompatible (detiene), aviso (revisar) o informativo.
import { huella } from './normalizar.mjs';

export const SEVERIDAD = Object.freeze({ INCOMPATIBLE: 'incompatible', AVISO: 'aviso', INFORMATIVO: 'informativo' });
const mismo = (a, b) => JSON.stringify(a) === JSON.stringify(b);

export class EsquemaIncompatibleError extends Error {
  constructor(diferencias) {
    super(`El formulario cambió de forma incompatible; hay que revisar el mapeo antes de rellenar:\n${diferencias.map((d) => `- [${d.codigo}] ${d.titulo ?? d.itemId ?? ''}: ${d.detalle}`).join('\n')}`);
    this.name = 'EsquemaIncompatibleError';
    this.diferencias = diferencias;
  }
}

export function comparar(base, nuevo) {
  const diferencias = [];
  const reportar = (severidad, codigo, q, detalle) => diferencias.push({ severidad, codigo, itemId: q?.itemId ?? null, titulo: q?.titulo ?? null, detalle });

  if (base.titulo !== nuevo.titulo) reportar(SEVERIDAD.AVISO, 'titulo-del-formulario', null, `"${base.titulo}" → "${nuevo.titulo}"`);

  if (!mismo(base.configuracion ?? null, nuevo.configuracion ?? null)) reportar(SEVERIDAD.INCOMPATIBLE, 'configuracion-cambiada', null, `configuración del formulario (p. ej. correo verificado obligatorio): ${JSON.stringify(base.configuracion)} → ${JSON.stringify(nuevo.configuracion)}`);

  for (const [id, antes] of Object.entries(base.preguntas)) {
    const despues = nuevo.preguntas[id];
    if (!despues) { reportar(SEVERIDAD.INCOMPATIBLE, 'pregunta-eliminada', antes, 'la pregunta ya no existe'); continue; }
    if (antes.entry !== despues.entry) reportar(SEVERIDAD.INCOMPATIBLE, 'entry-cambiado', antes, `${antes.entry} → ${despues.entry}`);
    if (antes.tipo !== despues.tipo) reportar(SEVERIDAD.INCOMPATIBLE, 'tipo-cambiado', antes, `${antes.tipo} → ${despues.tipo}`);
    if (!antes.obligatoria && despues.obligatoria) reportar(SEVERIDAD.INCOMPATIBLE, 'ahora-obligatoria', antes, 'era opcional y ahora es obligatoria');
    if (antes.obligatoria && !despues.obligatoria) reportar(SEVERIDAD.INFORMATIVO, 'ahora-opcional', antes, 'era obligatoria y ahora es opcional');
    if (!mismo(antes.validacion, despues.validacion)) reportar(SEVERIDAD.INCOMPATIBLE, 'validacion-cambiada', antes, `${JSON.stringify(antes.validacion)} → ${JSON.stringify(despues.validacion)}`);
    if (!mismo(antes.fecha, despues.fecha)) reportar(SEVERIDAD.INCOMPATIBLE, 'fecha-cambiada', antes, `${JSON.stringify(antes.fecha)} → ${JSON.stringify(despues.fecha)}`);
    const rotulosAntes = new Map(antes.opciones.map((o) => [o.rotulo, o]));
    const rotulosDespues = new Map(despues.opciones.map((o) => [o.rotulo, o]));
    for (const [rotulo, o] of rotulosAntes) {
      const otra = rotulosDespues.get(rotulo);
      if (!otra) reportar(SEVERIDAD.INCOMPATIBLE, 'opcion-eliminada', antes, `la opción "${rotulo}" ya no existe`);
      else if (o.salto !== otra.salto) reportar(SEVERIDAD.INCOMPATIBLE, 'salto-cambiado', antes, `"${rotulo}": ${o.salto} → ${otra.salto}`);
    }
    for (const rotulo of rotulosDespues.keys()) if (!rotulosAntes.has(rotulo)) reportar(SEVERIDAD.INFORMATIVO, 'opcion-agregada', antes, `opción nueva "${rotulo}"`);
    if (antes.titulo !== despues.titulo) reportar(SEVERIDAD.AVISO, 'titulo-cambiado', antes, `"${antes.titulo}" → "${despues.titulo}"`);
    if (antes.ayuda !== despues.ayuda) reportar(SEVERIDAD.INFORMATIVO, 'ayuda-cambiada', antes, 'cambió el texto de ayuda');
  }
  for (const [id, despues] of Object.entries(nuevo.preguntas)) {
    if (base.preguntas[id]) continue;
    if (despues.obligatoria) reportar(SEVERIDAD.INCOMPATIBLE, 'pregunta-obligatoria-nueva', despues, 'pregunta obligatoria que el mapeo no conoce');
    else reportar(SEVERIDAD.AVISO, 'pregunta-opcional-nueva', despues, 'pregunta opcional nueva');
  }

  // Estructura de secciones: ids y orden; y que ninguna pregunta existente cambie de sección (altera la navegación y qué se exige).
  const idsBase = base.secciones.map((s) => s.id); const idsNuevo = nuevo.secciones.map((s) => s.id);
  if (!mismo(idsBase, idsNuevo)) reportar(SEVERIDAD.INCOMPATIBLE, 'secciones-cambiadas', null, `${JSON.stringify(idsBase)} → ${JSON.stringify(idsNuevo)}`);
  else {
    const seccionDe = (c) => Object.fromEntries(c.secciones.flatMap((s) => s.preguntas.map((id) => [id, s.id])));
    const antesDe = seccionDe(base); const despuesDe = seccionDe(nuevo);
    for (const [id, q] of Object.entries(base.preguntas)) {
      if (nuevo.preguntas[id] && antesDe[id] !== despuesDe[id]) reportar(SEVERIDAD.INCOMPATIBLE, 'pregunta-cambio-de-seccion', q, `pasó de la sección ${antesDe[id]} a la ${despuesDe[id]}`);
    }
    base.secciones.forEach((s, i) => { if (s.titulo !== nuevo.secciones[i].titulo) reportar(SEVERIDAD.AVISO, 'seccion-renombrada', null, `"${s.titulo}" → "${nuevo.secciones[i].titulo}"`); });
  }

  const incompatibles = diferencias.filter((d) => d.severidad === SEVERIDAD.INCOMPATIBLE);
  return { compatible: incompatibles.length === 0, huellaBase: huella(base), huellaNueva: huella(nuevo), diferencias };
}

export function exigirCompatible(resultado) {
  const incompatibles = resultado.diferencias.filter((d) => d.severidad === SEVERIDAD.INCOMPATIBLE);
  if (incompatibles.length > 0) throw new EsquemaIncompatibleError(incompatibles);
  return resultado;
}
