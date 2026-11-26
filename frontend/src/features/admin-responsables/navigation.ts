import { athleteDetailPath } from '@/features/admin-datos-deportivos/navigation';

export { athleteDetailPath };
export const athleteResponsablesPath = (athleteId: string) => `${athleteDetailPath(athleteId)}/responsables`;
