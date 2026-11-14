import { useSession } from '@/features/auth/session';
import type { FamilyIdentity } from './identity';

/**
 * The signed-in FAMILY user and the identity its data belongs to. Null for anything else (no session, a pending second
 * factor, an administrator): no family data is requested without a valid FAMILIA session. The portal routes already
 * guard by role; this is the second line.
 */
export function useFamilyAccount() {
  const session = useSession();
  if (session.status !== 'authenticated' || session.user.rol !== 'FAMILIA') return null;
  const familyId = session.user.familiaId ?? null;
  const identity: FamilyIdentity = { userId: session.user.id, familyId };
  return { user: session.user, familyId, identity };
}
