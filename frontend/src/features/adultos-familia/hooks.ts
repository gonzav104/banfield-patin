import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { familyKeys } from '@/features/familia/hooks';
import type { FamilyIdentity } from '@/features/familia/identity';
import { identityKey } from '@/features/familia/identity';
import { profileKey } from '@/features/mis-datos/hooks';
import type { ApiError } from '@/lib/api-error';
import { createAdult, fetchAdults, updateAdult, type AdultInput } from './api';
import type { Adult } from './schemas';

/** User + family in the key, like every private family query: another identity never shares an entry. */
export const adultsKey = (identity: FamilyIdentity) => ['familia', identityKey(identity), 'adultos'] as const;

export function useAdults(identity: FamilyIdentity) {
  return useQuery({ queryKey: adultsKey(identity), queryFn: fetchAdults });
}

/** A write changes the list, the family summary (its tutors) and, for the own profile, «Mis datos»: all are refreshed. */
function useAdultWrite<TInput>(identity: FamilyIdentity, write: (input: TInput) => Promise<Adult>) {
  const queryClient = useQueryClient();
  return useMutation<Adult, ApiError, TInput>({
    mutationFn: write,
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: adultsKey(identity) }),
        queryClient.invalidateQueries({ queryKey: familyKeys.myFamily(identity) }),
        queryClient.invalidateQueries({ queryKey: profileKey(identity) }),
      ]),
  });
}

export const useCreateAdult = (identity: FamilyIdentity) => useAdultWrite(identity, (input: { data: AdultInput; key: string }) => createAdult(input.data, input.key));
export const useUpdateAdult = (identity: FamilyIdentity) => useAdultWrite(identity, (input: { id: string; data: AdultInput; version: string }) => updateAdult(input.id, { ...input.data, version: input.version }));
