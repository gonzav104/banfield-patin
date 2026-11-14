import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { listFamilies, type FamilyListParams } from './api';

export const familyListKey = ['admin', 'families', 'list'] as const;

/** The search term is part of the cache key, which lives in memory only (nothing is persisted). */
export function useFamilyList(params: FamilyListParams) {
  return useQuery({
    queryKey: [...familyListKey, params],
    queryFn: () => listFamilies(params),
    placeholderData: keepPreviousData,
  });
}
