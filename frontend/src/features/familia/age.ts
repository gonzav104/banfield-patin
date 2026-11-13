/**
 * Age in whole years from a calendar date (YYYY-MM-DD, no time zone), as of `today` (local date).
 * Returns null when there is no usable date: missing, malformed, impossible (2018-02-30), in the future or older
 * than 120 years. Nothing is invented. Someone born on 29 February turns a year older on 1 March in non-leap years.
 */
const MAX_AGE = 120;

function isLeap(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

function daysIn(month: number, year: number): number {
  return [31, isLeap(year) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1] ?? 0;
}

export function calculateAge(birthDate: string | null | undefined, today: Date = new Date()): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(birthDate ?? '');
  if (!match) return null;
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  if (month < 1 || month > 12 || day < 1 || day > daysIn(month, year)) return null;

  const [ty, tm, td] = [today.getFullYear(), today.getMonth() + 1, today.getDate()];
  // A 29 February birthday falls on 1 March in years without 29 February.
  const [bm, bd] = month === 2 && day === 29 && !isLeap(ty) ? [3, 1] : [month, day];
  let age = ty - year;
  if (tm < bm || (tm === bm && td < bd)) age -= 1;
  return age < 0 || age > MAX_AGE ? null : age;
}

export function formatAge(age: number | null): string {
  if (age === null) return 'Edad no informada';
  if (age === 0) return 'Menos de 1 año';
  return age === 1 ? '1 año' : `${age} años`;
}
