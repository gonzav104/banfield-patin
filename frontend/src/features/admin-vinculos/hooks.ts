import { getFamily } from '@/features/admin-familias/api';
import { familyDetailKey } from '@/features/admin-familias/hooks';
import type { AuthUser } from '@/features/auth/api';
import { sessionKey } from '@/features/auth/session';
import { invalidateResponsableQueries } from '@/features/responsables/keys';
import { ApiError } from '@/lib/api-error';
import { useIsMutating, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { changeLink, getAthleteLinks, getFamilyAthletes, linkFamilyAthletes, type LinkBatchSnapshot } from './api';

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

export function linkIdentity(client: QueryClient): string {
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
        // Revoking a family link closes the responsibilities of that family's tutors as a side effect.
        invalidateResponsableQueries(client),
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

const batchWriteKey = (identity: string, familyId: string) => ['admin', 'family-link-write', identity, familyId] as const;
export function useFamilyLinkWrite(familyId: string) {
  const client = useQueryClient();
  const actor = linkIdentity(client);
  const key = batchWriteKey(actor, familyId);
  const pending = useIsMutating({ mutationKey: key }) > 0;
  // Recovery refreshes observed state, not the outcome of the preceding write.
  async function refresh(snapshot: LinkBatchSnapshot) {
    if (!actor || actor !== linkIdentity(client)) return;
    await Promise.all([
      client.invalidateQueries({ queryKey: ['admin', 'families'] }),
      ...snapshot.entries.map((e) => client.invalidateQueries({ queryKey: athleteLinksKey(e.athlete.id) })),
      ...snapshot.familyIds.flatMap((id) => [client.invalidateQueries({ queryKey: familyAthletesKey(id) }), client.invalidateQueries({ queryKey: familyDetailKey(id) })]),
    ]);
  }
  const mutation = useMutation({ mutationKey: key, retry: false,
    mutationFn: (snapshot: LinkBatchSnapshot) => linkFamilyAthletes(snapshot, () => !!actor && actor === linkIdentity(client)),
    onMutate: () => ({ actor }),
    onSuccess: (_result, snapshot, context) => {
      if (!context.actor || context.actor !== linkIdentity(client)) return;
      return refresh(snapshot);
    },
  });
  return { mutation, pending, refresh };
}
