import { useQuery } from '@tanstack/react-query';
import { getAthleteLinks, getFamilyAthletes } from './api';

/**
 * Both keys start with `admin`, so the session code treats them as PRIVATE: the cache is dropped, and what is in flight aborted, as soon as
 * the identity changes. The links of a minor are kept only while a screen uses them (`gcTime: 0`). There is no write in this unit, so nothing
 * ever invalidates or refreshes them behind the screen's back.
 */
export const athleteLinksKey = (athleteId: string) => ['admin', 'athlete-links', athleteId] as const;
export const familyAthletesKey = (familyId: string) => ['admin', 'family-athletes', familyId] as const;

export function useAthleteLinks(athleteId: string, enabled: boolean) {
  return useQuery({ queryKey: athleteLinksKey(athleteId), queryFn: () => getAthleteLinks(athleteId), enabled, gcTime: 0 });
}

export function useFamilyAthletes(familyId: string, enabled: boolean) {
  return useQuery({ queryKey: familyAthletesKey(familyId), queryFn: () => getFamilyAthletes(familyId), enabled, gcTime: 0 });
}
