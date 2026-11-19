import { getFamily } from '@/features/admin-familias/api';
import { familyDetailKey } from '@/features/admin-familias/hooks';
import type { AuthUser } from '@/features/auth/api';
import { sessionKey } from '@/features/auth/session';
import { ApiError } from '@/lib/api-error';
import { useIsMutating, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { changeLink, getAthleteLinks, getFamilyAthletes } from './api';

/** Private queries are removed on identity changes; sensitive reads live only while observed. */
export const athleteLinksKey = (athleteId: string) => ['admin', 'athlete-links', athleteId] as const;
export const familyAthletesKey = (familyId: string) => ['admin', 'family-athletes', familyId] as const;

export function useAthleteLinks(athleteId: string, enabled: boolean) {
  return useQuery({ queryKey: athleteLinksKey(athleteId), queryFn: () => getAthleteLinks(athleteId), enabled, gcTime: 0 });
}

export function useFamilyAthletes(familyId: string, enabled: boolean) {
  return useQuery({ queryKey: familyAthletesKey(familyId), queryFn: () => getFamilyAthletes(familyId), enabled, gcTime: 0 });
}

/** Only family eligibility is cached here, never the additional detail fields of the existing family endpoint. */
export const familyEligibilityKey = (id: string) => ['admin', 'families', 'link-eligibility', id] as const;

export function useFamilyEligibility(id: string, enabled: boolean) {
  return useQuery({ queryKey: familyEligibilityKey(id), queryFn: async () => {
    const family = await getFamily(id);
    if (family.id !== id) throw new ApiError('server', 'El servidor respondió con datos que no corresponden.');
    return family.activa;
  }, enabled, gcTime: 0 });
}

function linkIdentity(client: QueryClient): string {
  const user = client.getQueryData<AuthUser | null>(sessionKey);
  return user ? [user.id, user.rol, user.escuelaId, user.familiaId, user.mfaPendiente].join('|') : '';
}

const linkWriteKey = (athleteId: string, identity: string) => ['admin', 'link-write', identity, athleteId] as const;

/** Pending mutation state outlives a route's observer, but never blocks another identity or athlete. */
export function useLinkWritePending(athleteId: string) {
  const client = useQueryClient();
  return useIsMutating({ mutationKey: linkWriteKey(athleteId, linkIdentity(client)) }) > 0;
}

export function useChangeLink(athleteId: string) {
  const client = useQueryClient();
  const identity = () => linkIdentity(client);
  return useMutation({ mutationKey: linkWriteKey(athleteId, identity()), mutationFn: changeLink, retry: false,
    onMutate: () => ({ identity: identity() }),
    onSuccess: async (_row, input, context) => {
      if (!context.identity || context.identity !== identity()) return;
      // A principal change affects two rows. Never publish the single POST row as a complete GET snapshot.
      await Promise.all([
        client.invalidateQueries({ queryKey: athleteLinksKey(input.athleteId) }),
        client.invalidateQueries({ queryKey: ['admin', 'families'] }),
        ...[...new Set(input.familyIds)].flatMap((id) => [
          client.invalidateQueries({ queryKey: familyAthletesKey(id) }),
          client.invalidateQueries({ queryKey: familyDetailKey(id) }),
        ]),
      ]);
    },
  });
}
