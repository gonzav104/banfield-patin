import { athleteDetailPath } from '@/features/admin-datos-deportivos/navigation';

export { athleteDetailPath };
export const athleteLinksPath = (athleteId: string) => `${athleteDetailPath(athleteId)}/vinculos`;
export const familyDetailPath = (familyId: string) => `/admin/familias/${encodeURIComponent(familyId)}`;
