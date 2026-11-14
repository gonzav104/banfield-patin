import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '@/lib/api-error';
import { changeFamilyStatus, createFamily, getFamily, listFamilies, updateFamily, type FamilyListParams, type FamilyStatusAction } from './api';
import type { FamilyDetail } from './schemas';

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
