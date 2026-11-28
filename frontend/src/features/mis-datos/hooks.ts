import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { familyKeys } from '@/features/familia/hooks';
import type { FamilyIdentity } from '@/features/familia/identity';
import { identityKey } from '@/features/familia/identity';
import type { ApiError } from '@/lib/api-error';
import { claimPerson, createProfile, fetchProfile, updateProfile, type ProfileInput } from './api';
import type { ProfileState } from './schemas';

/** User + family in the key, like every private family query: another identity never shares an entry. */
export const profileKey = (identity: FamilyIdentity) => ['familia', identityKey(identity), 'mi-perfil'] as const;

export function useProfile(identity: FamilyIdentity) {
  return useQuery({ queryKey: profileKey(identity), queryFn: fetchProfile });
}

/** Every write answers with the current state: it replaces the cached profile; the family summary is refreshed too (its tutors changed). */
function useProfileWrite<TInput>(identity: FamilyIdentity, write: (input: TInput) => Promise<ProfileState>) {
  const queryClient = useQueryClient();
  return useMutation<ProfileState, ApiError, TInput>({
    mutationFn: write,
    onSuccess: (state) => {
      queryClient.setQueryData(profileKey(identity), state);
      return queryClient.invalidateQueries({ queryKey: familyKeys.myFamily(identity) });
    },
  });
}

export const useCreateProfile = (identity: FamilyIdentity) => useProfileWrite(identity, (input: ProfileInput) => createProfile(input));
export const useUpdateProfile = (identity: FamilyIdentity) => useProfileWrite(identity, (input: ProfileInput & { version: string }) => updateProfile(input));
export const useClaimPerson = (identity: FamilyIdentity) => useProfileWrite(identity, (input: { tutorId: string; version: string }) => claimPerson(input));
