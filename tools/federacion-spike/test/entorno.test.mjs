import test from 'node:test';
import assert from 'node:assert/strict';
import { exigirEntornoExperimental } from '../src/vivo/entorno.mjs';

test('el ejecutor requiere autorización explícita y no admite producción', () => {
  assert.throws(() => exigirEntornoExperimental({}), /experimental/i);
  assert.throws(() => exigirEntornoExperimental({ FEDERACION_SPIKE_EXPERIMENTAL: '1' }), /producción/i);
  assert.throws(() => exigirEntornoExperimental({ FEDERACION_SPIKE_EXPERIMENTAL: '1', NODE_ENV: 'production' }), /producción/i);
  assert.doesNotThrow(() => exigirEntornoExperimental({ FEDERACION_SPIKE_EXPERIMENTAL: '1', NODE_ENV: 'development' }));
});
