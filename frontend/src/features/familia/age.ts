/**
 * Text for an age that the SERVER already computed (backend `EdadDeportista`: Buenos Aires date, 29 February rule,
 * null without a reliable birth date). The client never receives or calculates the birth date. Nothing is invented:
 * a missing age says so.
 */
export function formatAge(age: number | null | undefined): string {
  if (age === null || age === undefined) return 'Edad no informada';
  if (age === 0) return 'Menos de 1 año';
  return age === 1 ? '1 año' : `${age} años`;
}
