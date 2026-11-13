import { useQuery } from '@tanstack/react-query';
import { fetchAthletes, fetchMyFamily } from './api';
import { identityKey, type FamilyIdentity } from './identity';

/**
 * Query keys carry the identity (user + family). Besides that, the session code drops every private query when
 * /api/auth/me reports a different identity, so nothing from the previous one is reused even by a still-mounted screen.
 */
export const familyKeys = {
  myFamily: (identity: FamilyIdentity) => ['familia', identityKey(identity), 'mi-familia'] as const,
  athletes: (identity: FamilyIdentity, page: number, size: number) =>
    ['familia', identityKey(identity), 'deportistas', { page, size }] as const,
};

export function useMyFamily(identity: FamilyIdentity) {
  return useQuery({
    queryKey: familyKeys.myFamily(identity),
    queryFn: fetchMyFamily,
    enabled: !!identity.familyId,
    staleTime: 60_000,
  });
}

export function useAthletes(identity: FamilyIdentity, page: number, size: number) {
  const key = familyKeys.athletes(identity, page, size);
  return useQuery({
    queryKey: key,
    queryFn: () => fetchAthletes({ page, size }),
    enabled: !!identity.familyId,
    staleTime: 60_000,
    // Paging keeps the previous page on screen while the next loads, but ONLY within the same identity:
    // a placeholder from another user or family would show their athletes for a moment.
    placeholderData: (previous, previousQuery) => (previousQuery?.queryKey[1] === key[1] ? previous : undefined),
  });
}
