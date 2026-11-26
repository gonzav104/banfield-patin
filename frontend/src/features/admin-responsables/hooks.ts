import { useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { linkIdentity } from '@/features/admin-vinculos/hooks';
import { candidatesRootKey, invalidateResponsableQueries, responsablesRootKey } from '@/features/responsables/keys';
import {
  createResponsable, getCandidates, getResponsables, observeCandidate, revokeResponsable, updateResponsableTipo,
  type Candidate, type CreateInput, type RevokeInput, type UpdateTipoInput,
} from './api';

/** Private reads: the key carries the athlete, the history flag and the page, and nothing survives once no screen observes it. */
export const responsablesKey = (athleteId: string, historial: boolean, page: number) => [...responsablesRootKey, athleteId, historial, page] as const;
export const candidatesKey = (athleteId: string) => [...candidatesRootKey, athleteId] as const;

export function useResponsables(athleteId: string, historial: boolean, page: number, enabled: boolean) {
  return useQuery({ queryKey: responsablesKey(athleteId, historial, page), queryFn: () => getResponsables(athleteId, historial, page), enabled, gcTime: 0 });
}

/** Eligible tutors, read only while the alta dialog is open. */
export function useCandidates(athleteId: string, enabled: boolean) {
  return useQuery({ queryKey: candidatesKey(athleteId), queryFn: () => getCandidates(athleteId), enabled, gcTime: 0 });
}

/** Revisions and states of the chosen candidate, read together at the moment of choosing (never cached: it is a decision, not a view). */
export function useObserveCandidate(athleteId: string) {
  return useMutation({ mutationKey: ['admin', 'responsable-observe', athleteId], retry: false, mutationFn: (candidate: Candidate) => observeCandidate(athleteId, candidate) });
}

const writeKey = (identity: string, athleteId: string) => ['admin', 'responsable-write', identity, athleteId] as const;

/** A pending write outlives a route's observer but never blocks another identity or athlete. */
export function useResponsableWritePending(athleteId: string): boolean {
  const client = useQueryClient();
  return useIsMutating({ mutationKey: writeKey(linkIdentity(client), athleteId) }) > 0;
}

/** The three writes share one mutation key per identity and athlete: only one operation at a time, whatever the dialog that sent it. */
function useWrite<TInput, TResult>(athleteId: string, fn: (input: TInput) => Promise<TResult>) {
  const client = useQueryClient();
  const identity = () => linkIdentity(client);
  return useMutation({
    mutationKey: writeKey(identity(), athleteId), mutationFn: fn, retry: false,
    onMutate: () => ({ identity: identity() }),
    onSuccess: async (_row, _input, context) => {
      if (!context.identity || context.identity !== identity()) return;
      await invalidateResponsableQueries(client);
    },
  });
}

export const useCreateResponsable = (athleteId: string) => useWrite<CreateInput, Awaited<ReturnType<typeof createResponsable>>>(athleteId, createResponsable);
export const useUpdateResponsableTipo = (athleteId: string) => useWrite<UpdateTipoInput, Awaited<ReturnType<typeof updateResponsableTipo>>>(athleteId, updateResponsableTipo);
export const useRevokeResponsable = (athleteId: string) => useWrite<RevokeInput, Awaited<ReturnType<typeof revokeResponsable>>>(athleteId, revokeResponsable);
