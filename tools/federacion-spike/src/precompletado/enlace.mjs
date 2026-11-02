// Construye el enlace precompletado (?usp=pp_url&entry.<id>=<valor>) de un formulario a partir de un perfil y valores canónicos.
// Nunca precarga archivos. Nunca inventa un rótulo: si el valor interno no está en el mapa, falla.
const ORDEN_SENSIBILIDAD = { baja: 0, media: 1, alta: 2 };
const FECHA_ISO = /^(\d{4})-(\d{2})-(\d{2})$/;
// Sólo metadatos institucionales públicos. Agregar claves o valores requiere revisión de privacidad.
const CAMPOS_PUBLICOS_AUTORIZADOS = new Map([
  ['escuela.club', { valor: 'BANFIELD_SAN_PEDRO', rotulo: 'Club Social y Deportivo Banfield San Pedro' }],
]);

export class ValorInvalidoError extends Error {
  constructor(clave, mensaje) { super(`${clave}: ${mensaje}`); this.name = 'ValorInvalidoError'; this.clave = clave; }
}

function fechaValida(texto) {
  const m = FECHA_ISO.exec(texto);
  if (!m) return null;
  const [anio, mes, dia] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const d = new Date(Date.UTC(anio, mes - 1, dia));
  return d.getUTCFullYear() === anio && d.getUTCMonth() === mes - 1 && d.getUTCDate() === dia ? { anio, mes, dia } : null;
}

function parametrosDe(pregunta, valor, fechaFormato) {
  switch (pregunta.formato) {
    case 'texto': { const t = String(valor).trim(); return t ? [[`entry.${pregunta.entry}`, t]] : null; }
    case 'digitos': { const t = String(valor).replace(/\D/g, ''); return t ? [[`entry.${pregunta.entry}`, t]] : null; }
    case 'opcion': {
      const rotulo = pregunta.opciones?.[valor];
      if (!rotulo) throw new ValorInvalidoError(pregunta.clave, 'el valor no está en el mapa de opciones del perfil');
      return [[`entry.${pregunta.entry}`, rotulo]];
    }
    case 'fecha': {
      const f = fechaValida(String(valor));
      if (!f) throw new ValorInvalidoError(pregunta.clave, 'fecha inválida (se espera AAAA-MM-DD)');
      if (fechaFormato === 'partes') return [[`entry.${pregunta.entry}_year`, String(f.anio)], [`entry.${pregunta.entry}_month`, String(f.mes)], [`entry.${pregunta.entry}_day`, String(f.dia)]];
      return [[`entry.${pregunta.entry}`, `${String(f.anio).padStart(4, '0')}-${String(f.mes).padStart(2, '0')}-${String(f.dia).padStart(2, '0')}`]];
    }
    default: throw new ValorInvalidoError(pregunta.clave, 'formato desconocido');
  }
}

export function construirEnlace({ perfil, valores, sensibilidadMaxima = 'baja', formId = perfil.formId }) {
  if (!formId) throw new Error('Falta el identificador del formulario');
  if (typeof formId !== 'string' || !/^[A-Za-z0-9_-]+$/.test(formId)) throw new Error('Identificador del formulario inválido');
  const tope = ORDEN_SENSIBILIDAD[sensibilidadMaxima];
  if (!Object.hasOwn(ORDEN_SENSIBILIDAD, sensibilidadMaxima)) throw new Error('sensibilidadMaxima inválida');
  const params = [['usp', 'pp_url']];
  const incluidos = []; const omitidos = [];
  for (const p of [...perfil.preguntas].sort((a, b) => a.itemId - b.itemId)) {
    if (p.formato === 'archivo') { omitidos.push({ clave: p.clave, motivo: 'archivo: Google Forms no permite precargarlo' }); continue; }
    const publico = CAMPOS_PUBLICOS_AUTORIZADOS.get(p.clave);
    if (!publico || p.sensibilidad !== 'baja' || p.formato !== 'opcion') { omitidos.push({ clave: p.clave, motivo: 'campo no autorizado para URL' }); continue; }
    if (!Number.isSafeInteger(p.entry) || p.entry < 0) throw new ValorInvalidoError(p.clave, 'entry inválido');
    if (ORDEN_SENSIBILIDAD[p.sensibilidad] > tope) { omitidos.push({ clave: p.clave, motivo: `sensibilidad ${p.sensibilidad} excluida` }); continue; }
    const valor = valores[p.clave];
    if (valor === undefined || valor === null || valor === '') { omitidos.push({ clave: p.clave, motivo: 'sin dato' }); continue; }
    if (valor !== publico.valor || p.opciones?.[valor] !== publico.rotulo) throw new ValorInvalidoError(p.clave, 'valor público no autorizado');
    const ps = parametrosDe(p, valor, perfil.fechaFormato);
    if (!ps) { omitidos.push({ clave: p.clave, motivo: 'valor vacío tras normalizar' }); continue; }
    params.push(...ps); incluidos.push({ clave: p.clave, entry: p.entry });
  }
  const consulta = params.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
  return { url: `https://docs.google.com/forms/d/e/${formId}/viewform?${consulta}`, incluidos, omitidos };
}
