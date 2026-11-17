import { EDIT_FIELDS, sameField, type EditField, type Values } from './sports-form';

/**
 * Three-way comparison of ONE field after a revision conflict on a sports row: `original` is what the administrator saw when the edit
 * started, `draft` what they typed and `current` what the server has now (all compared the way the backend compares them).
 *  - `unchanged`: nobody changed it.
 *  - `incorporated`: only somebody else changed it: the current value is taken (the administrator never touched it).
 *  - `mine`: only the administrator changed it: the draft is kept.
 *  - `coincide`: both changed it to the same value.
 *  - `conflict`: both changed it, to different values: the administrator MUST choose.
 */
export type FieldKind = 'unchanged' | 'incorporated' | 'mine' | 'coincide' | 'conflict';
export type Decision = 'mine' | 'current';
export type Decisions = Partial<Record<EditField, Decision>>;

export function classify(original: Values, draft: Values, current: Values): Record<EditField, FieldKind> {
  const kinds = {} as Record<EditField, FieldKind>;
  for (const field of EDIT_FIELDS) {
    const mineChanged = !sameField(field, draft[field], original[field]);
    const theirsChanged = !sameField(field, current[field], original[field]);
    if (!mineChanged && !theirsChanged) kinds[field] = 'unchanged';
    else if (!mineChanged) kinds[field] = 'incorporated';
    else if (!theirsChanged) kinds[field] = 'mine';
    else kinds[field] = sameField(field, draft[field], current[field]) ? 'coincide' : 'conflict';
  }
  return kinds;
}

/** The conflicting fields the administrator has not decided yet. A conflict is never decided for them. */
export const unresolved = (kinds: Record<EditField, FieldKind>, decisions: Decisions): EditField[] =>
  EDIT_FIELDS.filter((field) => kinds[field] === 'conflict' && decisions[field] === undefined);

/**
 * The draft after the decisions: it never overwrites a field the administrator did not change, nor drops a change they made. The
 * season (the identity of the row) is the draft's own and never changes.
 */
export function merge(draft: Values, current: Values, kinds: Record<EditField, FieldKind>, decisions: Decisions): Values {
  const result: Values = { ...draft };
  for (const field of EDIT_FIELDS) {
    const kind = kinds[field];
    const takeCurrent = kind === 'incorporated' || (kind === 'conflict' && decisions[field] === 'current');
    (result as unknown as Record<EditField, string>)[field] = takeCurrent ? current[field] : draft[field];
  }
  return result;
}
