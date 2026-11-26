import { z } from 'zod';

/**
 * Declared type of a responsibility. It is a declared datum: it proves no legal representation and grants no access.
 * `null` (nobody declared it) is a different value from `SIN_DETERMINAR` (it was declared as undetermined) and is never merged with it.
 */
export const responsableTipoSchema = z.enum(['MADRE', 'PADRE', 'TUTOR_LEGAL', 'OTRO_RESPONSABLE', 'SIN_DETERMINAR']);
export type ResponsableTipo = z.infer<typeof responsableTipoSchema>;

const LABELS: Record<ResponsableTipo, string> = {
  MADRE: 'Madre',
  PADRE: 'Padre',
  TUTOR_LEGAL: 'Tutor/a legal',
  OTRO_RESPONSABLE: 'Otro responsable',
  SIN_DETERMINAR: 'Sin determinar',
};

export const TIPO_SIN_DECLARAR = 'Sin declarar';

/** Plain-language label. `null` reads "Sin declarar", which is NOT the label of `SIN_DETERMINAR`. */
export function tipoLabel(tipo: ResponsableTipo | null): string {
  return tipo === null ? TIPO_SIN_DECLARAR : LABELS[tipo];
}

export const TIPO_DISCLAIMER =
  'El tipo lo declara el club. No acredita representación legal ni cambia quién puede ver datos o subir documentos.';

/** Options of a type selector. The empty value is "nobody declared it" (`null`), never SIN_DETERMINAR, which is its own option. */
export const TIPO_OPTIONS: { value: ResponsableTipo | ''; label: string }[] = [
  { value: '', label: TIPO_SIN_DECLARAR },
  ...responsableTipoSchema.options.map((value) => ({ value, label: LABELS[value] })),
];

/** A selector value back to a declared type: an empty or unknown value is `null`, never a guess. */
export function tipoFromValue(value: string): ResponsableTipo | null {
  const parsed = responsableTipoSchema.safeParse(value);
  return parsed.success ? parsed.data : null;
}

