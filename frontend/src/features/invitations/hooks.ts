import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '@/lib/api-error';
import { createInvitation, listInvitations, revokeInvitation, searchFamilies } from './api';
import type { CreatedInvitation, CreateInvitationInput, Invitation, InvitationStatus } from './schemas';

const invitationsKey = ['admin', 'invitations'] as const;

export function useInvitations(status: InvitationStatus | undefined, page: number) {
  return useQuery({
    queryKey: [...invitationsKey, { status: status ?? 'ALL', page }],
    queryFn: () => listInvitations({ status, page }),
    placeholderData: keepPreviousData,
  });
}

export function useFamilySearch(search: string, enabled: boolean) {
  return useQuery({
    queryKey: ['admin', 'families', search],
    queryFn: () => searchFamilies(search),
    enabled,
    placeholderData: keepPreviousData,
  });
}

export function useCreateInvitation() {
  const queryClient = useQueryClient();
  return useMutation<CreatedInvitation, ApiError, CreateInvitationInput>({
    mutationFn: createInvitation,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: invitationsKey }),
  });
}

export function useRevokeInvitation() {
  const queryClient = useQueryClient();
  return useMutation<Invitation, ApiError, string>({
    mutationFn: revokeInvitation,
    // Refresh even on failure: a 409 means the invitation changed state under us (e.g. it was just used).
    onSettled: () => queryClient.invalidateQueries({ queryKey: invitationsKey }),
  });
}
