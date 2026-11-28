import { z } from 'zod';

/** GET/POST/PUT /api/familia/mi-perfil → PerfilRespuesta. The DNI only ever arrives masked. */
export const profileSchema = z.object({
  tutorId: z.string(),
  nombre: z.string(),
  apellido: z.string(),
  dniEnmascarado: z.string().nullable().optional(),
  telefono: z.string().nullable().optional(),
  email: z.string().nullable().optional(),
  parentesco: z.string().nullable().optional(),
  /** DECLARADO / VERIFICADO / OBSERVADO. */
  estado: z.string(),
  /** True only when the club invited this very person: the club confirmed that this account is them. Claiming «Soy yo» never sets it. */
  cuentaConfirmada: z.boolean(),
  /** False once the club verified the identity: name and DNI can no longer be changed from here. */
  identidadEditable: z.boolean(),
  version: z.string(),
});
export type Profile = z.infer<typeof profileSchema>;

export const personSchema = z.object({
  tutorId: z.string(),
  nombre: z.string(),
  apellido: z.string(),
  parentesco: z.string().nullable().optional(),
  version: z.string(),
});
export type Person = z.infer<typeof personSchema>;

export const profileStateSchema = z.object({
  /** False while AUTOSERVICIO_PERFIL_HABILITADO is off: the screen explains it and offers no form. */
  habilitado: z.boolean(),
  perfil: profileSchema.nullable().optional(),
  personasSinCuenta: z.array(personSchema),
});
export type ProfileState = z.infer<typeof profileStateSchema>;
