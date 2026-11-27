import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '@/lib/api-error';
import { changeAccountStatus, getFamilyAccounts, listPendingFamilyInvitations } from './api';
import type { AccountAction, FamilyAccount, FamilyAccounts } from './schemas';

export const familyAccountsKey = (familyId: string) => ['admin', 'family-accounts', familyId] as const;
export const familyInvitationsKey = (familyId: string) => ['admin', 'family-invitations', familyId] as const;

export function useFamilyAccounts(familyId: string) {
  return useQuery({ queryKey: familyAccountsKey(familyId), queryFn: () => getFamilyAccounts(familyId) });
}

export function useFamilyPendingInvitations(familyId: string) {
  return useQuery({ queryKey: familyInvitationsKey(familyId), queryFn: () => listPendingFamilyInvitations(familyId) });
}

/** The response is the account's current state: it replaces that row in the cached list, then the list is refreshed. */
export function useChangeAccountStatus(familyId: string) {
  const queryClient = useQueryClient();
  return useMutation<FamilyAccount, ApiError, { accountId: string; action: AccountAction }>({
    mutationFn: ({ accountId, action }) => changeAccountStatus({ familyId, accountId, action }),
    onSuccess: (saved) => {
      queryClient.setQueryData<FamilyAccounts>(familyAccountsKey(familyId), (current) =>
        current ? { ...current, cuentas: current.cuentas.map((c) => (c.id === saved.id ? saved : c)) } : current,
      );
      return queryClient.invalidateQueries({ queryKey: familyAccountsKey(familyId) });
    },
  });
}
