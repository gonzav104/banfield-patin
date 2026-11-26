import type { QueryClient } from '@tanstack/react-query';

/** Prefix of every private read of responsibilities (list, history, per athlete and page). */
export const responsablesRootKey = ['admin', 'responsables'] as const;
/** Prefix of the eligible-tutor reads for an alta. */
export const candidatesRootKey = ['admin', 'responsable-candidates'] as const;

/**
 * Closing a responsibility is not only a direct write: deactivating a tutor, a family or an athlete, or revoking a family link, closes the
 * open ones as a side effect. Every such write calls this so no screen keeps showing a responsibility that no longer exists, nor offers
 * a tutor who is no longer eligible.
 */
export function invalidateResponsableQueries(client: QueryClient): Promise<unknown> {
  return Promise.all([
    client.invalidateQueries({ queryKey: responsablesRootKey }),
    client.invalidateQueries({ queryKey: candidatesRootKey }),
  ]);
}
