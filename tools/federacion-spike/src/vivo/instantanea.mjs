// Lectura del texto de una instantánea de accesibilidad (browser_snapshot). Funciones puras, probadas sin navegador.
export const refDe = (instantanea, patron) => {
  for (const linea of instantanea.split('\n')) if (patron.test(linea)) return /\[ref=([^\]\s]+)\]/.exec(linea)?.[1] ?? null;
  return null;
};

// La referencia del radio está en el contenedor, la línea anterior a `- radio "<rótulo>"`.
export function refRadio(instantanea, rotulo) {
  const lineas = instantanea.split('\n');
  const i = lineas.findIndex((l) => l.trim().startsWith(`- radio "${rotulo}"`));
  return i > 0 ? (/\[ref=([^\]\s]+)\]/.exec(lineas[i - 1])?.[1] ?? null) : null;
}

export const botonesDe = (instantanea) => [...instantanea.matchAll(/button "(Siguiente|Enviar|Atrás|Borrar formulario)"/g)].map((m) => m[1]);

export function dialogoBorrador(instantanea) {
  if (!instantanea.includes('alertdialog')) return null;
  const encabezado = /heading "([^"]+)"/.exec(instantanea.split('alertdialog')[1] ?? '');
  return { titulo: encabezado?.[1] ?? null, refContinuar: refDe(instantanea, /link "Continuar"/) };
}

export const refCasillaCorreo = (instantanea) => refDe(instantanea, /checkbox "Registrar .* como el correo/);
export const resultadoDe = (texto) => { const m = /### Result\s*\n([\s\S]*?)(?:\n### |$)/.exec(texto); return m ? JSON.parse(m[1]) : null; };
