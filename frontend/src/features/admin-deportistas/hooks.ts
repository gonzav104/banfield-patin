import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ApiError } from '@/lib/api-error';
import { createAthlete, getAthlete, listAthletes, type AthleteListParams } from './api';
import type { AthleteDetail, AthleteInput } from './schemas';

/** Prefix of every athlete-related query (list and detail): one invalidation refreshes the lists. */
const athletesKey = ['admin', 'athletes'] as const;
export const athleteListKey = [...athletesKey, 'list'] as const;
export const athleteDetailKey = (id: string) => ['admin', 'athlete-detail', id] as const;

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
