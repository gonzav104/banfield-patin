// Navegación de prueba entre secciones con datos ficticios. GUARDA: solo se hace clic en un botón cuyo texto es exactamente
// "Siguiente". Si el botón dice "Enviar" (o cualquier otra cosa), NO se toca y se informa. Este módulo no contiene ninguna ruta que envíe.
import { LEER_CONTROLES } from './leer-controles.mjs';

export function generarElegirYAvanzar({ preguntaRegex, valorRadio, avanzar }) {
  return `async () => {
    const espera = (ms) => new Promise((r) => setTimeout(r, ms));
    const limpiar = (t) => (t ?? '').replace(/\\s+/g, ' ').trim();
    const leer = ${LEER_CONTROLES};
    const bloque = [...document.querySelectorAll('[role="listitem"]')].find((b) => new RegExp(${JSON.stringify(preguntaRegex)}, 'i').test(b.innerText));
    const radio = bloque && [...bloque.querySelectorAll('[role="radio"]')].find((r) => r.getAttribute('data-value') === ${JSON.stringify(valorRadio)});
    if (!radio) return { error: 'no se encontró el radio', antes: leer() };
    radio.click(); await espera(500);
    const boton = () => [...document.querySelectorAll('[role="button"]')].find((b) => /^(Siguiente|Enviar|Atrás)$/.test(limpiar(b.innerText)) && limpiar(b.innerText) !== 'Atrás');
    const etiqueta = limpiar(boton()?.innerText);
    const marcado = radio.getAttribute('aria-checked');
    let accion = 'no se avanzó';
    if (${JSON.stringify(Boolean(avanzar))}) {
      if (etiqueta === 'Siguiente') { boton().click(); accion = 'se hizo clic en Siguiente'; await espera(2000); }
      else accion = 'NO se hizo clic: el botón dice "' + etiqueta + '"';
    }
    const despues = leer();
    return { etiquetaAntesDeAvanzar: etiqueta, radioMarcado: marcado, accion, despues };
  }`;
}
