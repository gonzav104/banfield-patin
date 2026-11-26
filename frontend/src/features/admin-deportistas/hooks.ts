import { invalidateResponsableQueries } from '@/features/responsables/keys';
import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import type { AuthUser } from '@/features/auth/api';
import { sessionKey } from '@/features/auth/session';
import type { ApiError } from '@/lib/api-error';
import { changeAthleteStatus, createAthlete, fetchAdminDocumentChecklist, getAthlete, listAthleteDocuments, listAthletes, updateAthlete, type AthleteListParams, type AthleteStatusAction, type DocumentOwnerKind } from './api';
import type { AthleteDetail, AthleteInput, AthleteUpdateInput } from './schemas';

/** Prefix of every athlete-related query (list and detail): one invalidation refreshes the lists. */
const athletesKey = ['admin', 'athletes'] as const;
export const athleteListKey = [...athletesKey, 'list'] as const;
export const athleteDetailKey = (id: string) => ['admin', 'athlete-detail', id] as const;
export const athleteChecklistKey = (id: string) => ['admin', 'athlete-checklist', id] as const;
export const athleteDocumentsKey = (id: string, kind: DocumentOwnerKind = 'deportista') =>
  ['admin', kind === 'tutor' ? 'tutor-documents' : 'athlete-documents', id] as const;

/** The search term is part of the cache key, which lives in memory only (nothing is persisted). */
export function useAthleteList(params: AthleteListParams) {
  return useQuery({
    queryKey: [...athleteListKey, params],
    queryFn: () => listAthletes(params),
    placeholderData: keepPreviousData,
  });
}

/**
 * The individual athlete view carries the FULL DNI and CUIL: `gcTime: 0` drops them from memory as soon as the screen no longer
 * uses them (and, being a private query, the session code drops it on any change of identity).
 */
export function useAthleteDetail(id: string, enabled: boolean) {
  return useQuery({ queryKey: athleteDetailKey(id), queryFn: () => getAthlete(id), enabled, gcTime: 0 });
}

/** After a confirmed creation the lists are refreshed from the server; the detail is read fresh by its own screen. */
export function useCreateAthlete() {
  const queryClient = useQueryClient();
  return useMutation<AthleteDetail, ApiError, AthleteInput>({
    mutationFn: createAthlete,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: athletesKey }),
  });
}

/** Who the session belongs to right now (null: nobody). */
const currentUserId = (queryClient: QueryClient): string | null => queryClient.getQueryData<AuthUser | null>(sessionKey)?.id ?? null;

/**
 * After a CONFIRMED write the response (the current state of the athlete, with its new revision) replaces the cached detail and the
 * lists are refreshed from the server: nothing is shown as saved until the backend said so. A write is not cancelled when the
 * session changes while it is in flight: if its answer arrives for ANOTHER identity than the one that issued it, it is dropped
 * (it must never put the previous identity's full detail, DNI and CUIL included, back in a cache that was just cleared).
 */
function useAthleteWrite<TInput extends { id: string }>(write: (input: TInput) => Promise<AthleteDetail>) {
  const queryClient = useQueryClient();
  return useMutation<AthleteDetail, ApiError, TInput, { identity: string | null }>({
    mutationFn: write,
    onMutate: () => ({ identity: currentUserId(queryClient) }),
    onSuccess: (saved, _input, context) => {
      if (context?.identity !== currentUserId(queryClient)) return;
      queryClient.setQueryData(athleteDetailKey(saved.id), saved);
      // Deactivating an athlete closes its open responsibilities as a side effect.
      return Promise.all([queryClient.invalidateQueries({ queryKey: athletesKey }), invalidateResponsableQueries(queryClient)]);
    },
  });
}

export function useUpdateAthlete() {
  return useAthleteWrite((input: { id: string; datos: AthleteUpdateInput; version: string }) => updateAthlete(input));
}

export function useChangeAthleteStatus() {
  return useAthleteWrite((input: { id: string; action: AthleteStatusAction; version: string }) => changeAthleteStatus(input));
}

/** The partial DNI checklist of ONE athlete (U20); like the documents it is derived from, it is not kept once no screen shows it. */
export function useAdminDocumentChecklist(athleteId: string) {
  return useQuery({ queryKey: athleteChecklistKey(athleteId), queryFn: () => fetchAdminDocumentChecklist(athleteId), gcTime: 0 });
}

/**
 * Metadata of the athlete's current documents (never a file: files are fetched on demand and kept out of every cache). `gcTime: 0`
 * drops the list as soon as the detail is no longer on screen.
 */
export function useAthleteDocuments(athleteId: string, kind: DocumentOwnerKind = 'deportista') {
  return useQuery({ queryKey: athleteDocumentsKey(athleteId, kind), queryFn: () => listAthleteDocuments(athleteId, kind), gcTime: 0 });
}
