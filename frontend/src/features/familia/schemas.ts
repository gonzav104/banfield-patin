import { z } from 'zod';

/**
 * Contracts of the read-only family portal (GET /api/familia/*). Zod objects drop every key that is not declared, so
 * data the screens do not need (tutor phone and e-mail, anything extra the server might add) never reaches the UI.
 */
const tutorSchema = z.object({
  id: z.string(),
  nombre: z.string(),
  apellido: z.string(),
  parentesco: z.string().nullable().optional(),
});

export const myFamilySchema = z.object({
  id: z.string(),
  nombreReferencia: z.string().nullable().optional(),
  tutores: z.array(tutorSchema),
});
export type MyFamily = z.infer<typeof myFamilySchema>;
export type FamilyTutor = MyFamily['tutores'][number];

/** `activo` is the athlete's administrative state in the school; it is NOT an enrolment or a season status. */
export const athleteSchema = z.object({
  id: z.string(),
  nombre: z.string(),
  apellido: z.string(),
  fechaNacimiento: z.string().nullable().optional(),
  activo: z.boolean(),
});
export type Athlete = z.infer<typeof athleteSchema>;

export const athletePageSchema = z.object({
  contenido: z.array(athleteSchema),
  pagina: z.number(),
  tamanio: z.number(),
  totalElementos: z.number(),
});
export type AthletePage = z.infer<typeof athletePageSchema>;
