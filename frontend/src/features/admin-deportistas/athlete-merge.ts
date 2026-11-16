import { FIELDS, sameField, type FieldName, type Values } from './athlete-form';

/**
 * Three-way comparison of ONE field after a revision conflict: `original` is what the administrator saw when the edit started,
 * `draft` what they typed and `current` what the server has now (all compared the way the backend compares them).
 *  - `unchanged`: nobody changed it.
 *  - `incorporated`: only somebody else changed it: the current value is taken (the administrator never touched it).
 *  - `mine`: only the administrator changed it: the draft is kept.
 *  - `coincide`: both changed it to the same value.
 *  - `conflict`: both changed it, to different values: the administrator MUST choose.
 */
export type FieldKind = 'unchanged' | 'incorporated' | 'mine' | 'coincide' | 'conflict';
export type Decision = 'mine' | 'current';
export type Decisions = Partial<Record<FieldName, Decision>>;

export function classify(original: Values, draft: Values, current: Values): Record<FieldName, FieldKind> {
  const kinds = {} as Record<FieldName, FieldKind>;
  for (const field of FIELDS) {
    const mineChanged = !sameField(field, draft[field], original[field]);
    const theirsChanged = !sameField(field, current[field], original[field]);
    if (!mineChanged && !theirsChanged) kinds[field] = 'unchanged';
    else if (!mineChanged) kinds[field] = 'incorporated';
    else if (!theirsChanged) kinds[field] = 'mine';
    else kinds[field] = sameField(field, draft[field], current[field]) ? 'coincide' : 'conflict';
  }
  return kinds;
}

/** The conflicting fields the administrator has not decided yet. */
export const unresolved = (kinds: Record<FieldName, FieldKind>, decisions: Decisions): FieldName[] =>
  FIELDS.filter((field) => kinds[field] === 'conflict' && decisions[field] === undefined);

/** The draft after the decisions: it never overwrites a field the administrator did not change, nor drops a change they made. */
export function merge(draft: Values, current: Values, kinds: Record<FieldName, FieldKind>, decisions: Decisions): Values {
  const result = {} as Values;
  for (const field of FIELDS) {
    const kind = kinds[field];
    result[field] = kind === 'incorporated' || (kind === 'conflict' && decisions[field] === 'current') ? current[field] : draft[field];
  }
  return result;
}
