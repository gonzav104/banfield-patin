// Un perfil es el mapeo (datos, no lógica) entre claves canónicas y preguntas de un formulario concreto de un año.
export class PerfilInvalidoError extends Error {
  constructor(problemas) { super(`El perfil no coincide con el formulario:\n${problemas.map((p) => `- ${p}`).join('\n')}`); this.name = 'PerfilInvalidoError'; this.problemas = problemas; }
}

export function validarPerfil(perfil, contrato) {
  const problemas = [];
  const claves = new Set();
  for (const p of perfil.preguntas) {
    if (claves.has(p.clave)) problemas.push(`clave repetida: ${p.clave}`);
    claves.add(p.clave);
    const q = contrato.preguntas[String(p.itemId)];
    if (!q) { problemas.push(`${p.clave}: la pregunta ${p.itemId} no existe en el formulario`); continue; }
    if (q.entry !== p.entry) problemas.push(`${p.clave}: entry ${p.entry} ≠ ${q.entry}`);
    if (q.tipo !== p.tipo) problemas.push(`${p.clave}: tipo ${p.tipo} ≠ ${q.tipo}`);
    if (p.opciones) {
      const rotulos = new Set(q.opciones.map((o) => o.rotulo));
      for (const [interno, rotulo] of Object.entries(p.opciones)) if (!rotulos.has(rotulo)) problemas.push(`${p.clave}: la opción ${interno} → "${rotulo}" no existe en el formulario`);
    }
  }
  // Toda pregunta obligatoria del formulario debe estar en el perfil: si no, el relleno quedaría incompleto sin que nadie lo sepa.
  const mapeadas = new Set(perfil.preguntas.map((p) => String(p.itemId)));
  for (const q of Object.values(contrato.preguntas)) if (q.obligatoria && !mapeadas.has(String(q.itemId))) problemas.push(`pregunta obligatoria sin mapear: "${q.titulo}" (${q.itemId})`);
  if (problemas.length) throw new PerfilInvalidoError(problemas);
  return true;
}
