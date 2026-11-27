import { z } from 'zod';

/** GET /api/admin/familias/{id}/cuentas → CuentaFamiliar. A closed list: no password, role, MFA or session data. */
export const familyAccountSchema = z.object({
  id: z.string(),
  nombre: z.string(),
  apellido: z.string(),
  email: z.string(),
  activa: z.boolean(),
  ultimoAccesoEn: z.string().nullable(),
  creadaEn: z.string().nullable(),
});
export type FamilyAccount = z.infer<typeof familyAccountSchema>;

export const familyAccountsSchema = z.object({
  /** False while CUENTAS_GESTION_HABILITADA is off: the screen does not offer actions that would fail. */
  gestionHabilitada: z.boolean(),
  cuentas: z.array(familyAccountSchema),
});
export type FamilyAccounts = z.infer<typeof familyAccountsSchema>;

export type AccountAction = 'desactivar' | 'reactivar';
