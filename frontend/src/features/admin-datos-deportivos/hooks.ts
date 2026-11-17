import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import type { AuthUser } from '@/features/auth/api';
import { sessionKey } from '@/features/auth/session';
import type { ApiError } from '@/lib/api-error';
import { countSeasons, createSportsRow, getAthleteHeader, getSportsHistory, getSportsRow, listAllSeasons } from './api';
import type { SportsRow } from './schemas';
import type { CreateSportsInput } from './sports-form';

/**
 * Every key starts with `admin`, so the session code treats it as PRIVATE: the cache is dropped, and what is in flight aborted, as soon
 * as the identity changes. The sports data of a minor and the athlete's name are kept only while a screen uses them (`gcTime: 0`).
 */
const sportsKey = (athleteId: string) => ['admin', 'athlete-sports', athleteId] as const;
export const athleteHeaderKey = (athleteId: string) => [...sportsKey(athleteId), 'header'] as const;
export const sportsHistoryKey = (athleteId: string) => [...sportsKey(athleteId), 'history'] as const;
export const sportsRowKey = (athleteId: string, seasonId: string) => [...sportsKey(athleteId), 'row', seasonId] as const;
export const seasonCountKey = ['admin', 'seasons', 'count'] as const;
export const allSeasonsKey = ['admin', 'seasons', 'all'] as const;
/** Everything cached about ONE athlete's sports data (header, history, rows): one invalidation refreshes them all. */
export const athleteSportsKey = sportsKey;

export function useAthleteHeader(athleteId: string, enabled: boolean) {
  return useQuery({ queryKey: athleteHeaderKey(athleteId), queryFn: () => getAthleteHeader(athleteId), enabled, gcTime: 0 });
}

export function useSportsHistory(athleteId: string, enabled: boolean) {
  return useQuery({ queryKey: sportsHistoryKey(athleteId), queryFn: () => getSportsHistory(athleteId), enabled, gcTime: 0 });
}

export function useSportsRow(athleteId: string, seasonId: string, enabled: boolean) {
  return useQuery({ queryKey: sportsRowKey(athleteId, seasonId), queryFn: () => getSportsRow(athleteId, seasonId), enabled, gcTime: 0 });
}

/** Asked only when the history is empty, to tell «the school has no seasons» from «the athlete has no sports data». */
export function useSeasonCount(enabled: boolean) {
  return useQuery({ queryKey: seasonCountKey, queryFn: countSeasons, enabled, gcTime: 0 });
}

/** Every season of the school (all the pages): what the load form offers, minus the ones the athlete already has. */
export function useAllSeasons(enabled: boolean) {
  return useQuery({ queryKey: allSeasonsKey, queryFn: listAllSeasons, enabled, gcTime: 0 });
}

/** Who the session belongs to right now (null: nobody). */
const currentUserId = (queryClient: QueryClient): string | null => queryClient.getQueryData<AuthUser | null>(sessionKey)?.id ?? null;

/**
 * Loads the sports data of an athlete in a season. After a CONFIRMED 201 what was cached about that athlete is refreshed from the
 * server (the history gains the row; nothing is shown as loaded until the backend said so). The request is not cancelled when the
 * session changes while it is in flight: if its answer arrives for ANOTHER identity than the one that sent it, it is dropped and
 * touches no cache. The components' own `mutate` callbacks do not run once their screen is gone, which is what keeps a late answer
 * from navigating anywhere.
 */
export function useCreateSportsRow() {
  const queryClient = useQueryClient();
  return useMutation<SportsRow, ApiError, { athleteId: string; datos: CreateSportsInput }, { identity: string | null }>({
    mutationFn: createSportsRow,
    onMutate: () => ({ identity: currentUserId(queryClient) }),
    onSuccess: (_row, input, context) => {
      if (context?.identity !== currentUserId(queryClient)) return;
      return queryClient.invalidateQueries({ queryKey: athleteSportsKey(input.athleteId) });
    },
  });
}
