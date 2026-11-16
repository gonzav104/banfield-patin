import { z } from 'zod';
import { pageSchema } from '@/features/invitations/schemas';

/** Values accepted by `?estado=` of GET /api/admin/deportistas. The backend default is ACTIVOS, so the client always sends one. */
export const athleteStatusFilterSchema = z.enum(['TODOS', 'ACTIVOS', 'INACTIVOS']);
export type AthleteStatusFilter = z.infer<typeof athleteStatusFilterSchema>;

/**
 * One row of the administrative list (DeportistaResumen). Only what the list shows is read: the DNI arrives MASKED and the
 * birth date, which the summary also carries, is deliberately not read (no wider exposure than the list needs).
 */
export const athleteSummarySchema = z.object({
  id: z.string(),
  nombre: z.string(),
  apellido: z.string(),
  dniEnmascarado: z.string().nullable().optional(),
  activo: z.boolean(),
});
export type AthleteSummary = z.infer<typeof athleteSummarySchema>;

export const athletePageSchema = pageSchema(athleteSummarySchema);
export type AthletePage = z.infer<typeof athletePageSchema>;

/**
 * GET/POST /api/admin/deportistas[/{id}] (DeportistaDetalle): the individual ADMIN view. It carries the FULL DNI and CUIL (never
 * put them in a summary, a URL, a log or a storage) and the revision (`version`, a decimal string) that the edit unit will send
 * back: it is validated here and not shown.
 */
export const athleteDetailSchema = z.object({
  id: z.string(),
  nombre: z.string(),
  apellido: z.string(),
  dni: z.string().nullable().optional(),
  cuil: z.string().nullable().optional(),
  fechaNacimiento: z.string().nullable().optional(),
  nacionalidad: z.string().nullable().optional(),
  domicilio: z.string().nullable().optional(),
  otrosDatosDomicilio: z.string().nullable().optional(),
  localidad: z.string().nullable().optional(),
  partido: z.string().nullable().optional(),
  codigoPostal: z.string().nullable().optional(),
  telefonoContacto: z.string().nullable().optional(),
  emailFederativo: z.string().nullable().optional(),
  activo: z.boolean(),
  version: z.string().regex(/^[0-9]{1,19}$/),
});
export type AthleteDetail = z.infer<typeof athleteDetailSchema>;

/** The data of a new athlete, exactly the fields of the backend contract (DeportistaSolicitud). A blank optional is never sent. */
export interface AthleteInput {
  dni: string;
  nombre: string;
  apellido: string;
  cuil?: string;
  fechaNacimiento?: string;
  nacionalidad?: string;
  domicilio?: string;
  otrosDatosDomicilio?: string;
  localidad?: string;
  partido?: string;
  codigoPostal?: string;
  telefonoContacto?: string;
  emailFederativo?: string;
}
