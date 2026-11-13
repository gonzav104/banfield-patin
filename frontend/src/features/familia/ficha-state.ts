import { ApiError } from '@/lib/api-error';
import { formatAge } from './age';
import { isAthleteId, isAthleteNotFound } from './api';
import { useAthleteFicha } from './hooks';
import type { FamilyIdentity } from './identity';
import type { AthleteFicha, CurrentSeason } from './schemas';

/**
 * Every state the athlete sheet can be in, typed. The screen decides how to word them; nothing here invents a reason:
 * `season: null` only means "no data for the current season" (the server does not say why, and neither do we).
 * - `no-session`: no valid FAMILIA session (also what an expired session becomes once the global handler clears it).
 * - `invalid-id` and `not-found`: one screen for both. A foreign, non-existent or revoked athlete is the SAME 404.
 * - `session-expired`: a 401 on this request; the global session handling sends the user to /login and back.
 * - `error`: network, 503, 403 or an unexpected format; the session is kept and the user can retry.
 */
export type FichaState =
  | { status: 'no-session' }
  | { status: 'invalid-id' }
  | { status: 'loading' }
  | { status: 'not-found' }
  | { status: 'session-expired' }
  | { status: 'error'; error: ApiError; retry: () => void }
  | { status: 'ready'; ficha: AthleteFicha; ageText: string; season: CurrentSeason | null };

export function useAthleteFichaState(identity: FamilyIdentity | null, athleteId: string | undefined): FichaState {
  const query = useAthleteFicha(identity, athleteId);

  if (!identity) return { status: 'no-session' };
  if (!isAthleteId(athleteId)) return { status: 'invalid-id' };
  // An account without a family has no athletes: same answer as the backend, and nothing is requested.
  if (!identity.familyId) return { status: 'not-found' };
  if (query.data) return { status: 'ready', ficha: query.data, ageText: formatAge(query.data.edad), season: query.data.temporadaVigente };
  if (query.isError) {
    if (isAthleteNotFound(query.error)) return { status: 'not-found' };
    const error = ApiError.from(query.error);
    if (error.kind === 'unauthenticated') return { status: 'session-expired' };
    return { status: 'error', error, retry: () => void query.refetch() };
  }
  return { status: 'loading' };
}
