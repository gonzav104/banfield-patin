import type { SportsStatus } from '@/features/familia/schemas';

/** The draft of the form: everything the administrator types or chooses, as text. It only ever lives in the component's memory. */
export interface Values {
  temporadaId: string;
  categoria: string;
  rama: string;
  divisional: string;
  numeroCorredor: string;
  estadoDeportivo: SportsStatus;
}

export const EMPTY_VALUES: Values = { temporadaId: '', categoria: '', rama: '', divisional: '', numeroCorredor: '', estadoDeportivo: 'ACTIVO' };

/** The lengths of the database columns (the backend validates them after normalising). There is no official catalogue. */
export const MAX_LENGTH = { categoria: 80, rama: 40, divisional: 80, numeroCorredor: 30 } as const;
export type TextField = keyof typeof MAX_LENGTH;
export const TEXT_FIELDS = Object.keys(MAX_LENGTH) as TextField[];

export type Errors = Partial<Record<'temporadaId' | TextField, string>>;

/** The body of POST /api/admin/deportistas/{id}/temporadas, exactly as it is sent: a null for every blank optional. */
export interface CreateSportsInput {
  temporadaId: string;
  categoria: string | null;
  rama: string | null;
  divisional: string | null;
  numeroCorredor: string | null;
  estadoDeportivo: SportsStatus;
}

/** Category, branch and division: trimmed, inner runs of whitespace collapsed to one space, blank is null (as the DTO does). */
export function normalizeText(value: string): string | null {
  const text = value.trim().replace(/\s+/g, ' ');
  return text === '' ? null : text;
}

/** The runner number is an opaque TEXT: only trimmed, so «007» keeps its zeros and inner spaces; blank is null. */
export function normalizeCode(value: string): string | null {
  const text = value.trim();
  return text === '' ? null : text;
}

const normalizers: Record<TextField, (value: string) => string | null> = {
  categoria: normalizeText,
  rama: normalizeText,
  divisional: normalizeText,
  numeroCorredor: normalizeCode,
};

/** ASCII control characters (the backend rejects them in every text). Written without a regular expression on purpose. */
function hasControlCharacter(value: string): boolean {
  return [...value].some((character) => {
    const code = character.codePointAt(0) ?? 0;
    return code < 32 || code === 127;
  });
}

const TOO_LONG: Record<TextField, string> = {
  categoria: `La categoría puede tener hasta ${MAX_LENGTH.categoria} caracteres.`,
  rama: `La rama puede tener hasta ${MAX_LENGTH.rama} caracteres.`,
  divisional: `La divisional puede tener hasta ${MAX_LENGTH.divisional} caracteres.`,
  numeroCorredor: `El número de corredor puede tener hasta ${MAX_LENGTH.numeroCorredor} caracteres.`,
};

export const REQUIRED_SEASON = 'Elegí una temporada.';
export const CONTROL_CHARACTERS = 'No puede contener caracteres de control.';

/** Only the season is mandatory. Lengths are measured on the NORMALISED value, which is what the backend validates. */
export function validate(values: Values): Errors {
  const errors: Errors = {};
  if (values.temporadaId === '') errors.temporadaId = REQUIRED_SEASON;
  for (const field of TEXT_FIELDS) {
    const normalized = normalizers[field](values[field]);
    if (normalized === null) continue;
    if (hasControlCharacter(normalized)) errors[field] = CONTROL_CHARACTERS;
    else if (normalized.length > MAX_LENGTH[field]) errors[field] = TOO_LONG[field];
  }
  return errors;
}

export function toInput(values: Values): CreateSportsInput {
  return {
    temporadaId: values.temporadaId,
    categoria: normalizeText(values.categoria),
    rama: normalizeText(values.rama),
    divisional: normalizeText(values.divisional),
    numeroCorredor: normalizeCode(values.numeroCorredor),
    estadoDeportivo: values.estadoDeportivo,
  };
}

export interface SeasonOption {
  id: string;
  anio: number;
  activa: boolean;
}

/** The seasons the athlete has no row in, newest first. Inactive seasons are offered too. */
export function availableSeasons(seasons: SeasonOption[], history: { temporadaId: string }[]): SeasonOption[] {
  const used = new Set(history.map((row) => row.temporadaId));
  return seasons.filter((season) => !used.has(season.id)).sort((a, b) => b.anio - a.anio);
}
