// Sesión de prueba sobre la pestaña compartida: abre, lee, elige opciones y avanza SOLO si la guarda lo permite. Nunca envía ni adjunta.
import { LEER_CONTROLES } from './leer-controles.mjs';
import { botonesDe, dialogoBorrador, refCasillaCorreo, refDe, refRadio, resultadoDe } from './instantanea.mjs';
import { decidirAvance, seleccionesDe } from './guarda-navegacion.mjs';

const ALERTAS = `() => [...document.querySelectorAll('[role=alert]')].map((a) => ({ texto: (a.innerText || '').replace(/\\s+/g, ' ').trim(), cerca: ((a.closest('[role=listitem]')?.querySelector('[role=heading]') ?? a.parentElement?.querySelector('[role=heading]'))?.innerText ?? '').replace(/\\s+/g, ' ').trim().slice(0, 45) })).filter((x) => x.texto)`;
const ESQUEMA = '() => { const r = window.FB_PUBLIC_LOAD_DATA_; return { titulo: r?.[3] ?? null, descripcion: r?.[1]?.[0] ?? null, items: r?.[1]?.[1] ?? null, config: r?.[1]?.[10] ?? null }; }';

export class SesionFormulario {
  constructor(cliente, contrato) { this.c = cliente; this.contrato = contrato; this.bitacora = []; }
  #anotar(accion, detalle) { this.bitacora.push({ accion, ...detalle }); }
  snapshot() { return this.c.herramienta('browser_snapshot'); }

  // Si hay un borrador guardado, Google pregunta qué usar y el borrador anula la precarga: se elige "Continuar" (la URL precompletada).
  async abrir(url) {
    await this.c.herramienta('browser_navigate', { url });
    const s = await this.snapshot(); const d = dialogoBorrador(s);
    if (d?.refContinuar) await this.c.herramienta('browser_click', { element: 'enlace Continuar del diálogo de borrador', target: d.refContinuar });
    this.#anotar('abrir', { dialogoBorrador: Boolean(d) });
    return { dialogoBorrador: d?.titulo ?? null };
  }
  async esquemaVivo() { return resultadoDe(await this.c.herramienta('browser_evaluate', { function: ESQUEMA })); }
  async controles() { return resultadoDe(await this.c.herramienta('browser_evaluate', { function: LEER_CONTROLES })); }
  async alertas() { return resultadoDe(await this.c.herramienta('browser_evaluate', { function: ALERTAS })) ?? []; }

  async elegir(rotulo) {
    const ref = refRadio(await this.snapshot(), rotulo);
    if (!ref) { this.#anotar('elegir', { rotulo, error: 'sin referencia' }); return false; }
    const out = await this.c.herramienta('browser_click', { element: `opción ${rotulo}`, target: ref });
    const ok = !out.includes('### Error'); this.#anotar('elegir', { rotulo, ok }); return ok;
  }
  // Consentimiento del correo de la cuenta: casilla obligatoria del formulario, en un borrador (no se envía).
  async marcarCorreo() {
    const ref = refCasillaCorreo(await this.snapshot());
    if (!ref) return false;
    const out = await this.c.herramienta('browser_click', { element: 'casilla de consentimiento del correo (borrador, sin enviar)', target: ref });
    return !out.includes('### Error');
  }
  async avanzar() {
    const s = await this.snapshot(); const botones = botonesDe(s); const controles = await this.controles();
    const etiquetaBoton = botones.includes('Siguiente') ? 'Siguiente' : (botones.find((b) => b !== 'Borrar formulario' && b !== 'Atrás') ?? '');
    const decision = decidirAvance({ etiquetaBoton, botones, contrato: this.contrato, selecciones: seleccionesDe(controles, this.contrato) });
    if (!decision.permitido) { this.#anotar('avanzar', { permitido: false, motivo: decision.motivo }); return { avanzo: false, motivo: decision.motivo, botones }; }
    const ref = refDe(s, /button "Siguiente"/);
    await this.c.herramienta('browser_click', { element: 'botón Siguiente', target: ref });
    await this.c.herramienta('browser_wait_for', { time: 2 });
    this.#anotar('avanzar', { permitido: true });
    return { avanzo: true, botones: botonesDe(await this.snapshot()) };
  }
}
