// Función que se evalúa DENTRO de la página del formulario (vía browser_evaluate). Solo lee: no hace clic, no escribe, no adjunta.
// Devuelve, por cada pregunta visible, lo que muestran los controles reales.
export const LEER_CONTROLES = `() => {
  const limpiar = (t) => (t ?? '').replace(/\\s+\\*\\s*$/, '').replace(/\\s+/g, ' ').trim();
  const bloques = [...document.querySelectorAll('[role="listitem"]')];
  const preguntas = bloques.map((b) => ({
    titulo: limpiar(b.querySelector('[role="heading"]')?.innerText),
    textos: [...b.querySelectorAll('input[type="text"], input[type="email"], input[type="number"], input[type="tel"], textarea')].map((i) => i.value),
    fechas: [...b.querySelectorAll('input[type="date"]')].map((i) => i.value),
    radios: [...b.querySelectorAll('[role="radio"][aria-checked="true"]')].map((r) => r.getAttribute('data-value') ?? r.getAttribute('aria-label')),
    listas: [...b.querySelectorAll('[role="option"][aria-selected="true"]')].map((o) => o.getAttribute('data-value') ?? limpiar(o.innerText)).filter((v) => v),
    archivo: Boolean(b.querySelector('[aria-label="Agregar archivo"]')),
    alertas: [...b.querySelectorAll('[role="alert"]')].map((a) => limpiar(a.innerText)).filter(Boolean),
  }));
  const botones = [...document.querySelectorAll('[role="button"]')].map((b) => limpiar(b.innerText)).filter((t) => /^(Siguiente|Atrás|Enviar|Borrar formulario|Borrar|Anterior)/i.test(t));
  const progreso = limpiar(document.querySelector('[role="progressbar"]')?.getAttribute('aria-valuenow') ?? '');
  return { preguntas, botones, progreso, url: location.pathname };
}`;
