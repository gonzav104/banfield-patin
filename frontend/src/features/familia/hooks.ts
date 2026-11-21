import { useQuery } from '@tanstack/react-query';
import { fetchAthleteDocuments, fetchAthleteFicha, fetchAthletes, fetchMyFamily, fetchUploadAvailability, isAthleteId, type DocumentOwnerKind } from './api';
import { identityKey, type FamilyIdentity } from './identity';

/**
 * Query keys carry the identity (user + family). Besides that, the session code drops every private query when
 * /api/auth/me reports a different identity, so nothing from the previous one is reused even by a still-mounted screen.
 */
export const familyKeys = {
  myFamily: (identity: FamilyIdentity) => ['familia', identityKey(identity), 'mi-familia'] as const,
  athletes: (identity: FamilyIdentity, page: number, size: number) =>
    ['familia', identityKey(identity), 'deportistas', { page, size }] as const,
  /** User + family + athlete: another identity, or another athlete, never shares an entry. */
  ficha: (identity: FamilyIdentity | null, athleteId: string | undefined) =>
    ['familia', identity ? identityKey(identity) : 'sin-sesion', 'ficha', athleteId ?? 'sin-id'] as const,
  documents: (identity: FamilyIdentity | null, athleteId: string | undefined, kind: DocumentOwnerKind = 'deportista') =>
    ['familia', identity ? identityKey(identity) : 'sin-sesion', kind === 'tutor' ? 'documentos-tutor' : 'documentos', athleteId ?? 'sin-id'] as const,
  uploadAvailability: (identity: FamilyIdentity | null) =>
    ['familia', identity ? identityKey(identity) : 'sin-sesion', 'documentos-disponibilidad'] as const,
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

/**
 * The minimal sheet of ONE athlete. Nothing is requested without a valid FAMILIA session (`identity` null), without a
 * family, or with an id that is not a UUID. There is deliberately NO `placeholderData`: while another athlete (or another
 * identity) loads, nothing of the previous one is shown, and late responses of a previous identity are discarded by the
 * session code, which removes every private query when /api/auth/me reports a different identity.
 */
export function useAthleteFicha(identity: FamilyIdentity | null, athleteId: string | undefined) {
  return useQuery({
    queryKey: familyKeys.ficha(identity, athleteId),
    queryFn: () => fetchAthleteFicha(athleteId as string),
    enabled: !!identity?.familyId && isAthleteId(athleteId),
    staleTime: 60_000,
  });
}

/**
 * Current documents of one athlete: same guards as the sheet (session with a family and a UUID id). `gcTime: 0`: the
 * metadata is not kept once no screen shows it, and an entry rebuilt by a screen that is being replaced (for example
 * while the identity changes) is collected as soon as it loses its last observer.
 */
export function useAthleteDocuments(identity: FamilyIdentity | null, athleteId: string | undefined, kind: DocumentOwnerKind = 'deportista') {
  return useQuery({
    queryKey: familyKeys.documents(identity, athleteId, kind),
    queryFn: () => fetchAthleteDocuments(athleteId as string, kind),
    enabled: !!identity?.familyId && isAthleteId(athleteId),
    staleTime: 30_000,
    gcTime: 0,
  });
}

/** Whether uploads are enabled by configuration. The backend still decides every upload. Same `gcTime` as above. */
export function useUploadAvailability(identity: FamilyIdentity | null) {
  return useQuery({
    queryKey: familyKeys.uploadAvailability(identity),
    queryFn: fetchUploadAvailability,
    enabled: !!identity?.familyId,
    staleTime: 60_000,
    gcTime: 0,
  });
}
