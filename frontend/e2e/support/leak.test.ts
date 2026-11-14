import { describe, expect, it } from 'vitest';
import { leakPattern } from './leak';

const familyA = { athleteName: 'E2E-Ficha-Completa', runnerNumber: '007', category: 'Sub-10', division: 'Primera A', familyName: 'E2E Ficha Familia A' };
const pattern = leakPattern(familyA);

describe('leakPattern', () => {
  it('does not fire on a random family suffix that happens to contain 007 (the false positive seen in CI)', () => {
    expect(pattern.test('Tu familia\n\nE2E Ficha Familia B 000728\n\nTodavía no hay tutores registrados.')).toBe(false);
    expect(pattern.test('Sesión de ficha.b.0007ab@example.test')).toBe(false);
  });

  it('does not fire on the data of another family', () => {
    expect(pattern.test('E2E-Ficha-Ajeno Sintetico\nNúmero de corredor\n099\nSub-14\nTercera')).toBe(false);
  });

  it.each([
    ['the athlete name', 'Ficha del deportista\nE2E-Ficha-Completa Sintetico'],
    ['the runner number as the value of its row', 'Número de corredor\n007\nEstado deportivo'],
    ['the runner number on the same line', 'Número de corredor 007'],
    ['the category', 'Categoría\nSub-10'],
    ['the division', 'Divisional\nPrimera A'],
    ['the family name', 'E2E Ficha Familia A 25fd81'],
  ])('fires on %s of A', (_label, text) => {
    expect(pattern.test(text)).toBe(true);
  });

  it('does not take a longer number for the runner number', () => {
    expect(pattern.test('Número de corredor\n0071')).toBe(false);
  });
});
