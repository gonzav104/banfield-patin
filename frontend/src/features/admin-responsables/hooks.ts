import { useQuery } from '@tanstack/react-query';
import { getResponsables } from './api';

/** Private reads: the key carries the athlete, the history flag and the page, and nothing survives once no screen observes it. */
export const responsablesKey = (athleteId: string, historial: boolean, page: number) => ['admin', 'responsables', athleteId, historial, page] as const;

export function useResponsables(athleteId: string, historial: boolean, page: number, enabled: boolean) {
  return useQuery({ queryKey: responsablesKey(athleteId, historial, page), queryFn: () => getResponsables(athleteId, historial, page), enabled, gcTime: 0 });
}
