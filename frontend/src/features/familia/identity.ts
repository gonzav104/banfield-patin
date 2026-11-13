/**
 * Who the family data belongs to. Cache keys carry the user AND the family, so two identities never share an entry:
 * not two families, and not two users of the same family either.
 */
export interface FamilyIdentity {
  userId: string;
  /** null = the account has no family: nothing is requested. */
  familyId: string | null;
}

export function identityKey(identity: FamilyIdentity): string {
  return `${identity.userId}:${identity.familyId ?? 'sin-familia'}`;
}
