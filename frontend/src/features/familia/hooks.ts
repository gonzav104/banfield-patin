import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { fetchAthletes, fetchMyFamily } from './api';

/**
 * Query keys carry the family id, so two identities in the same browser can never share a cache entry even if a
 * clear were missed (the session code also drops every private query when the identity changes).
 */
export const familyKeys = {
  myFamily: (familyId: string) => ['familia', familyId, 'mi-familia'] as const,
  athletes: (familyId: string, page: number, size: number) => ['familia', familyId, 'deportistas', { page, size }] as const,
};

/** `familyId` null/undefined = the account has no family: nothing is requested. */
export function useMyFamily(familyId: string | null | undefined) {
  return useQuery({
    queryKey: familyKeys.myFamily(familyId ?? 'sin-familia'),
    queryFn: fetchMyFamily,
    enabled: !!familyId,
    staleTime: 60_000,
  });
}

export function useAthletes(familyId: string | null | undefined, page: number, size: number) {
  return useQuery({
    queryKey: familyKeys.athletes(familyId ?? 'sin-familia', page, size),
    queryFn: () => fetchAthletes({ page, size }),
    enabled: !!familyId,
    staleTime: 60_000,
    placeholderData: keepPreviousData,
  });
}
