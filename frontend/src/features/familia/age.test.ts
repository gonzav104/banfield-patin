import { describe, expect, it } from 'vitest';
import { calculateAge, formatAge } from './age';

/** Local calendar date, like the browser's "today" (month is 1-based here). */
const on = (y: number, m: number, d: number) => new Date(y, m - 1, d, 12, 0, 0);

describe('calculateAge', () => {
  it.each([
    ['birthday today', '2018-11-13', on(2026, 11, 13), 8],
    ['the day before the birthday', '2018-11-14', on(2026, 11, 13), 7],
    ['the day after the birthday', '2018-11-12', on(2026, 11, 13), 8],
    ['born today', '2026-11-13', on(2026, 11, 13), 0],
    ['born yesterday', '2026-11-12', on(2026, 11, 13), 0],
    ['New Year boundary', '2016-12-31', on(2026, 1, 1), 9],
    ['leap day, day before the birthday in a leap year', '2016-02-28', on(2028, 2, 28), 12],
    ['leap day baby, 28 Feb of a non-leap year', '2016-02-29', on(2026, 2, 28), 9],
    ['leap day baby, 1 Mar of a non-leap year (birthday)', '2016-02-29', on(2026, 3, 1), 10],
    ['leap day baby, 28 Feb of a leap year', '2016-02-29', on(2028, 2, 28), 11],
    ['leap day baby, 29 Feb of a leap year (birthday)', '2016-02-29', on(2028, 2, 29), 12],
    ['century rule: 1900 is not a leap year but 2000 is', '2000-02-29', on(2025, 3, 1), 25],
  ])('%s', (_label, birth, today, expected) => {
    expect(calculateAge(birth, today)).toBe(expected);
  });

  it.each([
    ['null', null],
    ['undefined', undefined],
    ['empty', ''],
    ['not a date', 'abc'],
    ['no zero padding', '2018-1-1'],
    ['with time', '2018-11-13T00:00:00Z'],
    ['month 13', '2018-13-01'],
    ['month 0', '2018-00-10'],
    ['day 0', '2018-05-00'],
    ['30 February', '2018-02-30'],
    ['29 February of a non-leap year', '2018-02-29'],
    ['31 April', '2018-04-31'],
    ['in the future', '2027-01-01'],
    ['older than 120 years (data error)', '1890-01-01'],
  ])('returns null for %s (nothing is invented)', (_label, birth) => {
    expect(calculateAge(birth, on(2026, 11, 13))).toBeNull();
  });

  it('uses the local calendar date, not UTC', () => {
    // 23:30 local on 13 Nov: still the 13th for a parent in Argentina, whatever UTC says.
    expect(calculateAge('2018-11-13', new Date(2026, 10, 13, 23, 30))).toBe(8);
    expect(calculateAge('2018-11-14', new Date(2026, 10, 13, 23, 30))).toBe(7);
  });
});

describe('formatAge', () => {
  it.each([
    [null, 'Edad no informada'],
    [0, 'Menos de 1 año'],
    [1, '1 año'],
    [2, '2 años'],
    [10, '10 años'],
  ])('%s -> %s', (age, text) => {
    expect(formatAge(age)).toBe(text);
  });
});
