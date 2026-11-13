import { ApiError } from '@/lib/api-error';
import { apiGet, apiWrite } from '@/lib/http';
import type { z } from 'zod';
import {
  createdInvitationSchema,
  familyPageSchema,
  invitationPageSchema,
  invitationSchema,
  type CreatedInvitation,
  type CreateInvitationInput,
  type FamilyPage,
  type Invitation,
  type InvitationPage,
  type InvitationStatus,
} from './schemas';

function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return result.data;
}

export const PAGE_SIZE = 10;

export async function listInvitations(params: { status?: InvitationStatus | undefined; page: number }): Promise<InvitationPage> {
  const query = new URLSearchParams({ page: String(params.page), size: String(PAGE_SIZE) });
  if (params.status) query.set('estado', params.status);
  return parse(invitationPageSchema, await apiGet(`/admin/invitaciones?${query.toString()}`));
}

export async function searchFamilies(search: string): Promise<FamilyPage> {
  const query = new URLSearchParams({ busqueda: search, page: '0', size: '8' });
  return parse(familyPageSchema, await apiGet(`/admin/familias?${query.toString()}`));
}

export async function createInvitation(input: CreateInvitationInput): Promise<CreatedInvitation> {
  return parse(createdInvitationSchema, await apiWrite('post', '/admin/invitaciones', input));
}

export async function revokeInvitation(id: string): Promise<Invitation> {
  return parse(invitationSchema, await apiWrite('post', `/admin/invitaciones/${encodeURIComponent(id)}/revocar`));
}
