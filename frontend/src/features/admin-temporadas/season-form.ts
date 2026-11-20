/** The years the backend accepts for a season (TemporadaSolicitud: an integer between 2020 and 2100). */
export const SEASON_YEAR_MIN = 2020;
export const SEASON_YEAR_MAX = 2100;

export type SeasonYearResult = { ok: true; year: number } | { ok: false; reason: 'empty' | 'format' | 'range' };

/**
 * What the administrator typed, read as a year: only decimal digits (no sign, decimals, exponent or leading zeros) so the value sent is
 * always a JSON INTEGER, which is the only thing the backend accepts. The range is checked here too, but the backend stays the authority.
 */
export function parseSeasonYear(text: string): SeasonYearResult {
  const value = text.trim();
  if (value === '') return { ok: false, reason: 'empty' };
  if (!/^[1-9][0-9]{0,3}$/.test(value)) return { ok: false, reason: 'format' };
  const year = Number(value);
  if (year < SEASON_YEAR_MIN || year > SEASON_YEAR_MAX) return { ok: false, reason: 'range' };
  return { ok: true, year };
}
