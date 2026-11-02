// Sesión de prueba sobre la pestaña compartida: abre, lee, elige opciones y avanza SOLO si la guarda lo permite. Nunca envía ni adjunta.
import { LEER_CONTROLES } from './leer-controles.mjs';
import { botonesDe, dialogoBorrador, refCasillaCorreo, refConfirmarBorrado, refDe, refRadio, resultadoDe } from './instantanea.mjs';
import { decidirAvance, seleccionesDe } from './guarda-navegacion.mjs';
import { sanitizarBitacora } from './diagnosticos.mjs';

const ALERTAS = `() => [...document.querySelectorAll('[role=alert]')].map((a) => ({ texto: (a.innerText || '').replace(/\\s+/g, ' ').trim(), cerca: ((a.closest('[role=listitem]')?.querySelector('[role=heading]') ?? a.parentElement?.querySelector('[role=heading]'))?.innerText ?? '').replace(/\\s+/g, ' ').trim().slice(0, 45) })).filter((x) => x.texto)`;
const ESQUEMA = '() => { const r = window.FB_PUBLIC_LOAD_DATA_; return { titulo: r?.[3] ?? null, descripcion: r?.[1]?.[0] ?? null, items: r?.[1]?.[1] ?? null, config: r?.[1]?.[10] ?? null }; }';

export class SesionFormulario {
  constructor(cliente, contrato) { this.c = cliente; this.contrato = contrato; this.bitacora = []; }
  #anotar(accion, detalle) { this.bitacora.push(...sanitizarBitacora([{ accion, ...detalle }])); }
  snapshot() { return this.c.herramienta('browser_snapshot'); }

  // Si aparece un diálogo de borrador, se elige "Continuar" y se espera a que la página quede lista.
  // L2 no demostró si un radio ya marcado provenía de precarga o de un borrador previo.
  async abrir(url) {
    await this.c.herramienta('browser_navigate', { url });
    let titulo = null;
    for (let intento = 0; intento < 10; intento++) {
      await this.c.herramienta('browser_wait_for', { time: 2 });
      const s = await this.snapshot(); const d = dialogoBorrador(s);
      if (d) {
        titulo = d.titulo;
        if (d.refContinuar) await this.c.herramienta('browser_click', { element: 'enlace Continuar del diálogo de borrador', target: d.refContinuar });
        continue;
      }
      if (botonesDe(s).length > 0) { this.#anotar('abrir', { dialogoBorrador: titulo !== null, intentos: intento + 1 }); return { dialogoBorrador: titulo }; }
    }
    throw new Error('El formulario no quedó listo (con botones y sin diálogo de borrador): no se lee ni se rellena nada');
  }
  // Borra el borrador de prueba (datos ficticios) para que no contamine la precarga siguiente. No envía nada.
  async limpiarBorrador(urlBase) {
    await this.abrir(urlBase);
    let confirmar = null; let despues = ''; const clics = [];
    // El primer clic puede llegar antes de que la página termine de activarse: se reintenta con una referencia nueva.
    for (let intento = 0; intento < 3 && !confirmar; intento++) {
      const antes = await this.snapshot(); const ref = refDe(antes, /button "Borrar formulario"/);
      if (!ref) { this.#anotar('limpiar', { ok: false }); return false; }
      const salida = await this.c.herramienta('browser_click', { element: 'botón Borrar formulario (borra el borrador ficticio)', target: ref });
      clics.push(salida.includes('### Error') ? 'error' : 'ok');
      for (let k = 0; k < 4 && !confirmar; k++) { await this.c.herramienta('browser_wait_for', { time: 1 }); despues = await this.snapshot(); confirmar = refConfirmarBorrado(despues); }
    }
    if (!confirmar) {
      // Google solo pide confirmar si hay respuestas que borrar: sin diálogo, el formulario debe estar realmente vacío.
      const c = await this.controles();
      const lleno = c.preguntas.some((q) => q.textos.some(Boolean) || q.fechas.some(Boolean) || q.radios.length || q.listas.length);
      if (lleno) { this.#anotar('limpiar', { ok: false, clics }); return false; }
      this.#anotar('limpiar', { ok: true, nota: 'ya estaba vacío', clics }); return true;
    }
    await this.c.herramienta('browser_click', { element: 'confirmar borrado del borrador ficticio', target: confirmar });
    await this.c.herramienta('browser_wait_for', { time: 2 });
    this.#anotar('limpiar', { ok: true, clics }); return true;
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
