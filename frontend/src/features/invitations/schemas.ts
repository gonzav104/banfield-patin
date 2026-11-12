import { z } from 'zod';

export const invitationStatusSchema = z.enum(['PENDIENTE', 'USADA', 'REVOCADA', 'EXPIRADA']);
export type InvitationStatus = z.infer<typeof invitationStatusSchema>;

const familySummarySchema = z.object({ id: z.string(), nombreReferencia: z.string() });
export type FamilySummary = z.infer<typeof familySummarySchema>;

/** Administrative view of an invitation. The backend never returns the token here. */
export const invitationSchema = z.object({
  id: z.string(),
  familia: familySummarySchema,
  emailSugerido: z.string().nullable().optional(),
  estado: invitationStatusSchema,
  creadoEn: z.string(),
  expiraEn: z.string(),
  usadoEn: z.string().nullable().optional(),
  revocadaEn: z.string().nullable().optional(),
});
export type Invitation = z.infer<typeof invitationSchema>;

/** Creation response: the only time the token (and the link built from it) is ever returned. */
export const createdInvitationSchema = z.object({
  id: z.string(),
  estado: invitationStatusSchema,
  token: z.string(),
  enlaceRegistro: z.string().nullable().optional(),
  expiraEn: z.string(),
  familia: familySummarySchema,
  emailSugerido: z.string().nullable().optional(),
});
export type CreatedInvitation = z.infer<typeof createdInvitationSchema>;

export function pageSchema<T extends z.ZodType>(item: T) {
  return z.object({
    contenido: z.array(item),
    pagina: z.number(),
    tamanio: z.number(),
    totalElementos: z.number(),
  });
}

export const invitationPageSchema = pageSchema(invitationSchema);
export type InvitationPage = z.infer<typeof invitationPageSchema>;

export const familyPageSchema = pageSchema(familySummarySchema);
export type FamilyPage = z.infer<typeof familyPageSchema>;

/** Body of POST /api/admin/invitaciones: exactly one of familiaId / nuevaFamilia (the backend rejects extras). */
export interface CreateInvitationInput {
  familiaId?: string;
  nuevaFamilia?: { nombreReferencia: string };
  emailSugerido?: string;
  diasVigencia?: number;
}
