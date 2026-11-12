/**
 * Invitation token from the link. New links carry it in the fragment (`/registro/invitacion#TOKEN`), which browsers
 * never send to the server; legacy links (`/registro/invitacion/TOKEN`) are still accepted temporarily but no longer
 * generated. Returns undefined when there is no token.
 */
export function readInvitationToken(hash: string, legacyPathToken: string | undefined): string | undefined {
  const fromHash = hash.startsWith('#') ? hash.slice(1) : hash;
  if (fromHash) {
    try {
      return decodeURIComponent(fromHash) || undefined;
    } catch {
      return fromHash;
    }
  }
  return legacyPathToken || undefined;
}
