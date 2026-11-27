import { useQuery } from '@tanstack/react-query';
import { listAllSeasons } from '@/features/admin-datos-deportivos/api';
import { identityKey, type FamilyIdentity } from '@/features/familia/identity';
import { fetchAdminApto, fetchFamilyApto, fetchFamilySeasons } from './api';

const ID = /^[A-Za-z0-9-]{1,64}$/;

/**
 * Keys carry the identity (family) or the area (admin) and the athlete and season: nothing of another identity, athlete or season
 * is ever shown from the cache. Queries use `gcTime: 0`: the medical data is dropped as soon as no screen shows it, and nothing is
 * persisted anywhere (there is no query persister in this app).
 */
export const aptoKeys = {
  familySeasons: (identity: FamilyIdentity | null, athleteId: string) =>
    ['familia', identity ? identityKey(identity) : 'sin-sesion', 'apto-temporadas', athleteId] as const,
  family: (identity: FamilyIdentity | null, athleteId: string, seasonId: string) =>
    ['familia', identity ? identityKey(identity) : 'sin-sesion', 'apto', athleteId, seasonId] as const,
  /** Prefix of every apto of one athlete: invalidated after a review or a correction. */
  adminAthlete: (athleteId: string) => ['admin', 'apto', athleteId] as const,
  admin: (athleteId: string, seasonId: string) => ['admin', 'apto', athleteId, seasonId] as const,
  adminSeasons: ['admin', 'apto-temporadas'] as const,
};

export function useFamilySeasons(identity: FamilyIdentity | null, athleteId: string) {
  return useQuery({
    queryKey: aptoKeys.familySeasons(identity, athleteId),
    queryFn: () => fetchFamilySeasons(athleteId),
    enabled: !!identity?.familyId && ID.test(athleteId),
    staleTime: 30_000,
    gcTime: 0,
  });
}

export function useFamilyApto(identity: FamilyIdentity | null, athleteId: string, seasonId: string | null) {
  return useQuery({
    queryKey: aptoKeys.family(identity, athleteId, seasonId ?? 'sin-temporada'),
    queryFn: () => fetchFamilyApto(athleteId, seasonId as string),
    enabled: !!identity?.familyId && ID.test(athleteId) && seasonId !== null,
    staleTime: 10_000,
    gcTime: 0,
  });
}

export function useAdminSeasons() {
  return useQuery({
    queryKey: aptoKeys.adminSeasons,
    queryFn: async () => (await listAllSeasons()).map((s) => ({ id: s.id, anio: s.anio, activa: s.activa })).sort((a, b) => b.anio - a.anio),
    gcTime: 0,
  });
}

export function useAdminApto(athleteId: string, seasonId: string | null) {
  return useQuery({
    queryKey: aptoKeys.admin(athleteId, seasonId ?? 'sin-temporada'),
    queryFn: () => fetchAdminApto(athleteId, seasonId as string),
    enabled: ID.test(athleteId) && seasonId !== null,
    staleTime: 0,
    gcTime: 0,
  });
}
