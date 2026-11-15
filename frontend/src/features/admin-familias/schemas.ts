import { z } from 'zod';
import { pageSchema } from '@/features/invitations/schemas';

/** Values accepted by `?estado=` of GET /api/admin/familias/listado. `TODOS` is the backend default. */
export const familyStatusFilterSchema = z.enum(['TODOS', 'ACTIVOS', 'INACTIVOS']);
export type FamilyStatusFilter = z.infer<typeof familyStatusFilterSchema>;

/** One row of the administrative list. By contract it carries no DNI and no contact data. */
export const familyAdminSummarySchema = z.object({
  id: z.string(),
  nombreReferencia: z.string(),
  activa: z.boolean(),
  cantidadTutores: z.number().int().nonnegative(),
  cantidadDeportistasActivos: z.number().int().nonnegative(),
});
export type FamilyAdminSummary = z.infer<typeof familyAdminSummarySchema>;

export const familyAdminPageSchema = pageSchema(familyAdminSummarySchema);
export type FamilyAdminPage = z.infer<typeof familyAdminPageSchema>;

/** A tutor as it appears INSIDE a family detail: the DNI arrives already masked (and may be absent). */
export const familyTutorSchema = z.object({
  id: z.string(),
  nombre: z.string(),
  apellido: z.string(),
  dniEnmascarado: z.string().nullable().optional(),
  telefono: z.string().nullable().optional(),
  email: z.string().nullable().optional(),
  parentesco: z.string().nullable().optional(),
  activo: z.boolean(),
});
export type FamilyTutor = z.infer<typeof familyTutorSchema>;

/**
 * GET/POST /api/admin/familias[/{id}]. `version` (a decimal string) is validated but never shown: it is the optimistic
 * revision that the edit unit will send back; this unit is read-only.
 */
export const familyDetailSchema = z.object({
  id: z.string(),
  nombreReferencia: z.string(),
  activa: z.boolean(),
  tutores: z.array(familyTutorSchema),
  version: z.string().regex(/^[0-9]{1,19}$/),
});
export type FamilyDetail = z.infer<typeof familyDetailSchema>;

/** The same rule as the backend's FamiliaSolicitud/FamiliaActualizacion: not blank once trimmed, at most 150 characters. */
export const familyNameSchema = z.string().trim().min(1, 'Ingresá un nombre de referencia.').max(150, 'Máximo 150 caracteres.');

/**
 * GET/POST /api/admin/tutores/{id}, /api/admin/familias/{id}/tutores: the individual ADMIN view of a tutor. It carries the FULL DNI
 * (never put it in a summary, a URL, a log or a storage) and the revision (`version`, a decimal string) that the edit unit will
 * send back: it is validated here and not shown.
 */
export const tutorDetailSchema = z.object({
  id: z.string(),
  familiaId: z.string(),
  nombre: z.string(),
  apellido: z.string(),
  dni: z.string().nullable().optional(),
  telefono: z.string().nullable().optional(),
  email: z.string().nullable().optional(),
  parentesco: z.string().nullable().optional(),
  activo: z.boolean(),
  version: z.string().regex(/^[0-9]{1,19}$/),
});
export type TutorDetail = z.infer<typeof tutorDetailSchema>;

/** The data of a new tutor, exactly the fields of the backend contract (TutorSolicitud) except the revision. */
export interface TutorInput {
  nombre: string;
  apellido: string;
  dni?: string;
  telefono?: string;
  email?: string;
  parentesco?: string;
}
