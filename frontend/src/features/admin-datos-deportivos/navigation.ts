import { backToListPath } from '@/features/admin-deportistas/AthleteNotFound';

/**
 * The list the administrator came from (its status filter and page, never a search term) travels with them through the sports
 * screens, so «Volver al listado» of the athlete detail still lands where they were. Only what the list passed on is kept.
 */
export function forwardedListState(state: unknown): { listSearch: string } | undefined {
  const query = backToListPath(state).split('?')[1];
  return query ? { listSearch: query } : undefined;
}

export const athleteDetailPath = (athleteId: string) => `/admin/deportistas/${encodeURIComponent(athleteId)}`;
export const sportsHistoryPath = (athleteId: string) => `${athleteDetailPath(athleteId)}/temporadas`;
export const sportsRowPath = (athleteId: string, seasonId: string) => `${sportsHistoryPath(athleteId)}/${encodeURIComponent(seasonId)}`;
