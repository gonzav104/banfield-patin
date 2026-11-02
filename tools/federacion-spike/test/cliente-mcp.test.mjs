import test from 'node:test';
import assert from 'node:assert/strict';
import { exigirHerramientaPermitida } from '../src/vivo/cliente-mcp.mjs';

test('el cliente MCP sólo acepta las herramientas usadas por el experimento', () => {
  for (const nombre of ['browser_tabs', 'browser_navigate', 'browser_wait_for', 'browser_snapshot', 'browser_click', 'browser_evaluate']) {
    assert.doesNotThrow(() => exigirHerramientaPermitida(nombre), nombre);
  }
  assert.throws(() => exigirHerramientaPermitida('browser_file_upload'), /no permitida/i);
  assert.throws(() => exigirHerramientaPermitida('browser_run_code'), /no permitida/i);
});
