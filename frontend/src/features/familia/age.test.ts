import { describe, expect, it } from 'vitest';
import { formatAge } from './age';

// The age RULE (time zone, 29 February, limits) lives in the backend (EdadDeportistaTest). The client only words it.
describe('formatAge', () => {
  it.each([
    [null, 'Edad no informada'],
    [undefined, 'Edad no informada'],
    [0, 'Menos de 1 año'],
    [1, '1 año'],
    [2, '2 años'],
    [10, '10 años'],
    [120, '120 años'],
  ])('%s -> %s', (age, text) => {
    expect(formatAge(age)).toBe(text);
  });
});
