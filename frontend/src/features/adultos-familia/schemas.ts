import { z } from 'zod';

/** One ACTIVE adult of the family as GET/POST/PUT /api/familia/tutores send it. The DNI only ever arrives masked. */
export const adultSchema = z.object({
  id: z.string(),
  nombre: z.string(),
  apellido: z.string(),
  dniEnmascarado: z.string().nullable().optional(),
  telefono: z.string().nullable().optional(),
  email: z.string().nullable().optional(),
  parentesco: z.string().nullable().optional(),
  /** State of the DATA: DECLARADO / VERIFICADO / OBSERVADO (with the reason the club gave). Not the same as having an account. */
  estado: z.string(),
  motivoObservacion: z.string().nullable().optional(),
  cargadoPorElClub: z.boolean(),
  esMiPerfil: z.boolean(),
  tieneCuentaPropia: z.boolean(),
  /** False for an adult with their own account: they update their data themselves. */
  editable: z.boolean(),
  /** False once the club verified the identity or approved a DNI image: name and DNI can only be corrected by the club. */
  identidadEditable: z.boolean(),
  version: z.string().regex(/^[0-9]{1,19}$/),
});
export type Adult = z.infer<typeof adultSchema>;

export const adultsStateSchema = z.object({
  /** False while AUTOSERVICIO_TUTORES_HABILITADO is off: the list can be read, nothing can be written. */
  habilitado: z.boolean(),
  tope: z.number(),
  cargadosPorLaFamilia: z.number(),
  adultos: z.array(adultSchema),
});
export type AdultsState = z.infer<typeof adultsStateSchema>;
