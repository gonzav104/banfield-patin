import { useQuery } from '@tanstack/react-query';
import { countSeasons, getAthleteHeader, getSportsHistory, getSportsRow } from './api';

/**
 * Every key starts with `admin`, so the session code treats it as PRIVATE: the cache is dropped, and what is in flight aborted, as soon
 * as the identity changes. The sports data of a minor and the athlete's name are kept only while a screen uses them (`gcTime: 0`).
 */
const sportsKey = (athleteId: string) => ['admin', 'athlete-sports', athleteId] as const;
export const athleteHeaderKey = (athleteId: string) => [...sportsKey(athleteId), 'header'] as const;
export const sportsHistoryKey = (athleteId: string) => [...sportsKey(athleteId), 'history'] as const;
export const sportsRowKey = (athleteId: string, seasonId: string) => [...sportsKey(athleteId), 'row', seasonId] as const;
export const seasonCountKey = ['admin', 'seasons', 'count'] as const;

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
