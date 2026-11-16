import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import type { AuthUser } from '@/features/auth/api';
import { sessionKey } from '@/features/auth/session';
import type { ApiError } from '@/lib/api-error';
import { changeFamilyStatus, createFamily, createTutor, getFamily, getTutor, listFamilies, updateFamily, updateTutor, type FamilyListParams, type FamilyStatusAction } from './api';
import type { FamilyDetail, TutorDetail, TutorInput, TutorUpdateInput } from './schemas';

/** Prefix of every family-related query (list, detail and the invitation picker): one invalidation refreshes them all. */
const familiesKey = ['admin', 'families'] as const;
export const familyListKey = [...familiesKey, 'list'] as const;
export const familyDetailKey = (id: string) => ['admin', 'family-detail', id] as const;

/** The search term is part of the cache key, which lives in memory only (nothing is persisted). */
export function useFamilyList(params: FamilyListParams) {
  return useQuery({
    queryKey: [...familyListKey, params],
    queryFn: () => listFamilies(params),
    placeholderData: keepPreviousData,
  });
}

export function useFamilyDetail(id: string, enabled: boolean) {
  return useQuery({ queryKey: familyDetailKey(id), queryFn: () => getFamily(id), enabled });
}

export function useCreateFamily() {
  const queryClient = useQueryClient();
  return useMutation<FamilyDetail, ApiError, string>({
    mutationFn: createFamily,
    onSuccess: (created) => {
      queryClient.setQueryData(familyDetailKey(created.id), created);
      return queryClient.invalidateQueries({ queryKey: familiesKey });
    },
  });
}

/** After a successful write the response (the new current state, with its new revision) replaces the cached detail. */
function useFamilyWrite<TInput extends { id: string }>(write: (input: TInput) => Promise<FamilyDetail>) {
  const queryClient = useQueryClient();
  return useMutation<FamilyDetail, ApiError, TInput>({
    mutationFn: write,
    onSuccess: (saved) => {
      queryClient.setQueryData(familyDetailKey(saved.id), saved);
      return queryClient.invalidateQueries({ queryKey: familiesKey });
    },
  });
}

export function useUpdateFamily() {
  return useFamilyWrite((input: { id: string; nombreReferencia: string; version: string }) => updateFamily(input));
}

export function useChangeFamilyStatus() {
  return useFamilyWrite((input: { id: string; action: FamilyStatusAction; version: string }) => changeFamilyStatus(input));
}

export const tutorDetailKey = (id: string) => ['admin', 'tutor-detail', id] as const;

/**
 * The individual tutor view carries the FULL DNI: `gcTime: 0` drops it from memory as soon as the screen no longer uses it
 * (and, being a private query, the session code drops it on any change of identity).
 */
export function useTutorDetail(id: string, enabled: boolean) {
  return useQuery({ queryKey: tutorDetailKey(id), queryFn: () => getTutor(id), enabled, gcTime: 0 });
}

/** After a confirmed creation the family (its tutor list and counters) is refreshed from the server. */
export function useCreateTutor() {
  const queryClient = useQueryClient();
  return useMutation<TutorDetail, ApiError, { familyId: string; datos: TutorInput; version: string }>({
    mutationFn: createTutor,
    onSuccess: async (_created, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: familyDetailKey(variables.familyId) }),
        queryClient.invalidateQueries({ queryKey: familiesKey }),
      ]);
    },
  });
}

/** Who the session belongs to right now (null: nobody). */
const currentUserId = (queryClient: QueryClient): string | null => queryClient.getQueryData<AuthUser | null>(sessionKey)?.id ?? null;

/**
 * After a confirmed edit the response (the current state of the tutor, with its new revision) replaces the cached detail and the
 * family (its summary of tutors) is refreshed from the server. Editing a tutor does not move the family revision. A write is not
 * cancelled when the session changes while it is in flight: if its answer arrives for ANOTHER identity than the one that issued it,
 * it is dropped (it must never put the previous identity's full detail, DNI included, back in a cache that was just cleared).
 */
export function useUpdateTutor() {
  const queryClient = useQueryClient();
  return useMutation<TutorDetail, ApiError, { tutorId: string; familyId: string; datos: TutorUpdateInput; version: string; familyVersion: string }, { identity: string | null }>({
    mutationFn: ({ tutorId, datos, version, familyVersion }) => updateTutor({ tutorId, datos, version, familyVersion }),
    onMutate: () => ({ identity: currentUserId(queryClient) }),
    onSuccess: async (saved, variables, context) => {
      if (context?.identity !== currentUserId(queryClient)) return;
      queryClient.setQueryData(tutorDetailKey(saved.id), saved);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: familyDetailKey(variables.familyId) }),
        queryClient.invalidateQueries({ queryKey: familiesKey }),
      ]);
    },
  });
}
