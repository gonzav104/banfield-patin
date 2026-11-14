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
