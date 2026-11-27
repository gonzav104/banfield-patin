import type { z } from 'zod';
import { ApiError } from '@/lib/api-error';
import { apiGet, apiWrite } from '@/lib/http';
import { invitationPageSchema, type InvitationPage } from '@/features/invitations/schemas';
import { familyAccountSchema, familyAccountsSchema, type AccountAction, type FamilyAccount, type FamilyAccounts } from './schemas';

function parse<T>(schema: z.ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data);
  if (!result.success) throw new ApiError('server', 'El servidor respondió con un formato inesperado.');
  return result.data;
}

export async function getFamilyAccounts(familyId: string): Promise<FamilyAccounts> {
  return parse(familyAccountsSchema, await apiGet(`/admin/familias/${encodeURIComponent(familyId)}/cuentas`));
}

/**
 * POST /api/admin/familias/{familyId}/cuentas/{accountId}/desactivar|reactivar. The action states the final state, so repeating it is
 * harmless (the backend answers the current state). Still never retried automatically (apiWrite): the person decides.
 */
export async function changeAccountStatus(input: { familyId: string; accountId: string; action: AccountAction }): Promise<FamilyAccount> {
  const path = `/admin/familias/${encodeURIComponent(input.familyId)}/cuentas/${encodeURIComponent(input.accountId)}/${input.action}`;
  return parse(familyAccountSchema, await apiWrite('post', path));
}

/** Pending invitations of ONE family (GET /api/admin/invitaciones?familiaId=…&estado=PENDIENTE). */
export async function listPendingFamilyInvitations(familyId: string): Promise<InvitationPage> {
  const query = new URLSearchParams({ familiaId: familyId, estado: 'PENDIENTE', page: '0', size: '20' });
  return parse(invitationPageSchema, await apiGet(`/admin/invitaciones?${query.toString()}`));
}
