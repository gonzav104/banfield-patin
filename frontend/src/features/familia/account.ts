import { useSession } from '@/features/auth/session';
import type { FamilyIdentity } from './identity';

/**
 * The signed-in family user and the identity its data belongs to. The portal routes only render for an authenticated
 * FAMILIA session, so a missing session simply yields null.
 */
export function useFamilyAccount() {
  const session = useSession();
  if (session.status !== 'authenticated') return null;
  const familyId = session.user.familiaId ?? null;
  const identity: FamilyIdentity = { userId: session.user.id, familyId };
  return { user: session.user, familyId, identity };
}
