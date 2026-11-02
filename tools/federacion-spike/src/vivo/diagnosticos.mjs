// Frontera de persistencia: sólo claves y tipos explícitos; nunca texto libre obtenido del navegador.
const ACCIONES = new Set(['abrir', 'limpiar', 'elegir', 'avanzar']);
const ESTADOS = new Set(['ok', 'distinto', 'vacio', 'no-visible', 'archivo', 'sin-dato']);
const SENSIBILIDADES = new Set(['baja', 'media', 'alta']);
const FORMULARIOS = ['padron', 'apto'];
const PERFILES = ['menor', 'mayor', 'condicional'];
const CONTEOS = ['ok', 'distinto', 'vacio', 'noVisible', 'archivo', 'sinDato', 'verificables'];

const entero = (v) => Number.isSafeInteger(v) && v >= 0 ? v : null;
const booleano = (v) => typeof v === 'boolean' ? v : null;

export function sanitizarBitacora(entradas = []) {
  if (!Array.isArray(entradas)) return [];
  return entradas.filter((e) => ACCIONES.has(e?.accion)).map((e) => {
    const seguro = { accion: e.accion };
    for (const clave of ['ok', 'permitido', 'dialogoBorrador']) if (typeof e[clave] === 'boolean') seguro[clave] = e[clave];
    if (entero(e.intentos) !== null) seguro.intentos = e.intentos;
    return seguro;
  });
}

function resumen(r) {
  if (!r || typeof r !== 'object') return null;
  const seguro = {};
  for (const clave of CONTEOS) if (entero(r[clave]) !== null) seguro[clave] = r[clave];
  const porcentaje = r.porcentajeSobreVerificables;
  if (porcentaje === null || (typeof porcentaje === 'number' && porcentaje >= 0 && porcentaje <= 100)) seguro.porcentajeSobreVerificables = porcentaje;
  return seguro;
}

function perfilSeguro(p) {
  const seguro = {};
  for (const clave of ['limpio', 'correoMarcado', 'radioElegido']) if (typeof p?.[clave] === 'boolean') seguro[clave] = p[clave];
  if (p?.enlace) seguro.enlace = Object.fromEntries(['parametros', 'largo', 'omitidos'].filter((k) => entero(p.enlace[k]) !== null).map((k) => [k, p.enlace[k]]));
  if (p?.apertura) seguro.apertura = { dialogoBorrador: Boolean(p.apertura.dialogoBorrador) };
  if (p?.avance) seguro.avance = { avanzo: booleano(p.avance.avanzo) };
  for (const clave of ['seccion1', 'seccion2', 'final']) if (p?.[clave]) seguro[clave] = resumen(p[clave]);
  if (Array.isArray(p?.filas)) seguro.filas = p.filas.filter((f) => ESTADOS.has(f?.estado)).map((f) => ({ estado: f.estado, ...(SENSIBILIDADES.has(f.sensibilidad) ? { sensibilidad: f.sensibilidad } : {}) }));
  for (const clave of ['alertas', 'bloqueo']) if (entero(p?.[clave]) !== null) seguro[clave] = p[clave];
  return seguro;
}

export function sanitizarInforme(informe) {
  const seguro = { formularios: {} };
  if (typeof informe?.fecha === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/.test(informe.fecha)) seguro.fecha = informe.fecha;
  for (const form of FORMULARIOS) {
    const r = informe?.formularios?.[form]; if (!r) continue;
    const f = { perfiles: {} };
    if (r.esquema) f.esquema = { compatible: booleano(r.esquema.compatible), diferencias: entero(r.esquema.diferencias) };
    for (const nombre of PERFILES) if (r.perfiles?.[nombre]) f.perfiles[nombre] = perfilSeguro(r.perfiles[nombre]);
    f.bitacora = sanitizarBitacora(r.bitacora);
    seguro.formularios[form] = f;
  }
  return seguro;
}
