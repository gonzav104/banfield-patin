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
