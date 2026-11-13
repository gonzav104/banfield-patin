import { useSession } from '@/features/auth/session';

/**
 * The signed-in family user and the family id of the account (null when the account has no family). The portal
 * routes only render for an authenticated FAMILIA session, so a missing session simply yields null.
 */
export function useFamilyAccount() {
  const session = useSession();
  if (session.status !== 'authenticated') return null;
  return { user: session.user, familyId: session.user.familiaId ?? null };
}
