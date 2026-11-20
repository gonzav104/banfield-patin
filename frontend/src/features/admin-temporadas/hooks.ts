import { useIsMutating, useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { allSeasonsKey } from '@/features/admin-datos-deportivos/hooks';
import type { AuthUser } from '@/features/auth/api';
import { sessionKey } from '@/features/auth/session';
import { activateSeason, createSeason } from './api';

/**
 * Every season query starts with `['admin', 'seasons']` (the list the sports screens read, its count): ONE invalidation of that prefix
 * refreshes all of them after a CONFIRMED write. The keys start with `admin`, so a change of identity drops them (session code).
 */
export const seasonsKey = ['admin', 'seasons'] as const;
export { allSeasonsKey };

/** Who the session belongs to right now (empty: nobody). */
export function seasonIdentity(client: QueryClient): string {
  const user = client.getQueryData<AuthUser | null>(sessionKey);
  return user ? [user.id, user.rol, user.escuelaId, user.familiaId, user.mfaPendiente].join('|') : '';
}

/** ONE write lane for the school's seasons: a pending creation or activation blocks any other, and outlives the screen that sent it. */
const seasonWriteKey = (identity: string) => ['admin', 'season-write', identity] as const;

export function useSeasonWritePending(): boolean {
  const client = useQueryClient();
  return useIsMutating({ mutationKey: seasonWriteKey(seasonIdentity(client)) }) > 0;
}

/** What a CONFIRMED answer refreshes: the observed state is read again from the server, never patched from the POST answer. */
function useRefreshAfterWrite() {
  const client = useQueryClient();
  return (identity: string | undefined) => {
    if (!identity || identity !== seasonIdentity(client)) return undefined; // an answer for ANOTHER identity is dropped
    return client.invalidateQueries({ queryKey: seasonsKey });
  };
}

export function useCreateSeason() {
  const client = useQueryClient();
  const refresh = useRefreshAfterWrite();
  return useMutation({
    mutationKey: seasonWriteKey(seasonIdentity(client)), retry: false,
    mutationFn: (year: number) => { const actor = seasonIdentity(client); return createSeason(year, () => !!actor && actor === seasonIdentity(client)); },
    onMutate: () => ({ identity: seasonIdentity(client) }),
    onSuccess: (_season, _year, context) => refresh(context.identity),
  });
}

export function useActivateSeason() {
  const client = useQueryClient();
  const refresh = useRefreshAfterWrite();
  return useMutation({
    mutationKey: seasonWriteKey(seasonIdentity(client)), retry: false,
    mutationFn: (input: { seasonId: string; expectedActiveId: string | null }) => { const actor = seasonIdentity(client); return activateSeason(input, () => !!actor && actor === seasonIdentity(client)); },
    onMutate: () => ({ identity: seasonIdentity(client) }),
    onSuccess: (_season, _input, context) => refresh(context.identity),
  });
}
