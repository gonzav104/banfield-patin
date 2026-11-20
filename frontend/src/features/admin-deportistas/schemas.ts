import { z } from 'zod';
import { documentStatusSchema } from '@/features/familia/schemas';
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

/**
 * The data of an edited athlete, exactly the personal fields of DeportistaActualizacion except the revision. The PUT is a COMPLETE
 * replacement: an empty optional field travels as an explicit `null` (the backend would also read an omission as «clear it»).
 */
export interface AthleteUpdateInput {
  dni: string;
  nombre: string;
  apellido: string;
  cuil: string | null;
  fechaNacimiento: string | null;
  nacionalidad: string | null;
  domicilio: string | null;
  otrosDatosDomicilio: string | null;
  localidad: string | null;
  partido: string | null;
  codigoPostal: string | null;
  telefonoContacto: string | null;
  emailFederativo: string | null;
}

/**
 * One current (vigente) document of an athlete as GET /api/admin/deportistas/{id}/documentos lists it (DocumentoMetadatos). Only
 * what the screen shows is read: the season, the expiry and the `vigente` flag are not. The id is used in a path, so it must have
 * the shape of an id. `motivoObservacion` is only shown for OBSERVADO. The metadata carries no MIME type and no file name.
 */
export const adminDocumentSchema = z.object({
  id: z.string().regex(/^[A-Za-z0-9-]{1,64}$/),
  tipo: z.string(),
  estado: documentStatusSchema,
  creadoEn: z.string(),
  motivoObservacion: z.string().nullable().optional(),
});
export type AdminDocument = z.infer<typeof adminDocumentSchema>;

export const adminDocumentPageSchema = z.object({ contenido: z.array(adminDocumentSchema), totalElementos: z.number() });
