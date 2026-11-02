// Cliente MCP mínimo por stdio hacia el servidor de Playwright en modo extensión. Por defecto lo lanza en Windows (ver el plan ODD, V8).
// La conexión a Chrome requiere que la persona apruebe la página de conexión de la extensión.
import { spawn } from 'node:child_process';
import readline from 'node:readline';

export const COMANDO_WINDOWS = ['cmd.exe', '/c', 'cd /d %USERPROFILE% && npx -y @playwright/mcp@0.0.84 --extension'];
const HERRAMIENTAS_PERMITIDAS = new Set(['browser_tabs', 'browser_navigate', 'browser_wait_for', 'browser_snapshot', 'browser_click', 'browser_evaluate']);

export function exigirHerramientaPermitida(nombre) {
  if (!HERRAMIENTAS_PERMITIDAS.has(nombre)) throw new Error('Herramienta MCP no permitida en este experimento');
}

export class ClienteMcp {
  #proc; #siguiente = 0; #pendientes = new Map();
  constructor(proc) {
    this.#proc = proc;
    readline.createInterface({ input: proc.stdout }).on('line', (linea) => {
      if (!linea.trim().startsWith('{')) return;
      let msg; try { msg = JSON.parse(linea); } catch { return; }
      const p = this.#pendientes.get(msg.id); if (!p) return;
      this.#pendientes.delete(msg.id); clearTimeout(p.timer); msg.error ? p.reject(new Error('La herramienta MCP falló; se omitió el detalle para proteger los datos')) : p.resolve(msg.result);
    });
    proc.on('exit', () => { for (const p of this.#pendientes.values()) p.reject(new Error('El servidor MCP terminó')); this.#pendientes.clear(); });
  }
  static async iniciar({ comando = COMANDO_WINDOWS, cwd = '/mnt/c' } = {}) {
    const proc = spawn(comando[0], comando.slice(1), { cwd, stdio: ['pipe', 'pipe', 'ignore'] });
    const c = new ClienteMcp(proc);
    await c.#pedir('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'federacion-spike', version: '0' } }, 120000);
    proc.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })}\n`);
    return c;
  }
  #pedir(method, params, timeoutMs) {
    const id = ++this.#siguiente;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.#pendientes.delete(id); reject(new Error(`Tiempo agotado: ${method}`)); }, timeoutMs);
      this.#pendientes.set(id, { resolve, reject, timer });
      this.#proc.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    });
  }
  async herramienta(nombre, argumentos = {}, { timeoutMs = 300000 } = {}) {
    exigirHerramientaPermitida(nombre);
    const r = await this.#pedir('tools/call', { name: nombre, arguments: argumentos }, timeoutMs);
    return (r.content ?? []).map((c) => c.text ?? '').join('\n');
  }
  cerrar() { try { this.#proc.stdin.end(); } catch { /* ya cerrado */ } setTimeout(() => this.#proc.kill(), 2000).unref(); }
}
