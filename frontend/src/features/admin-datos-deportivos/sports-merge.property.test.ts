import { describe, expect, it } from 'vitest';
import { EDIT_FIELDS, type EditField, type Values } from './sports-form';
import { classify, merge, unresolved, type Decision, type Decisions } from './sports-merge';

// Independent oracle of the backend normalisation (not imported from sports-form on purpose).
const norm = (field: EditField, value: string) => {
  const trimmed = value.trim();
  if (field === 'categoria' || field === 'rama' || field === 'divisional') return trimmed.replace(/\s+/g, ' ');
  return trimmed;
};
let seed = 20261117;
const random = () => ((seed = (seed * 1664525 + 1013904223) % 4294967296) / 4294967296);
const pick = <T,>(items: T[]): T => items[Math.floor(random() * items.length)]!;
const DOMAIN: Record<EditField, string[]> = {
  categoria: ['', 'Sub 12', ' Sub 12 ', 'Sub  12', 'Sub 14', 'Sub 16'],
  rama: ['', 'Femenino', 'Femenino ', 'Masculino', 'Mixto'],
  divisional: ['', 'A', ' A', 'B', 'C'],
  numeroCorredor: ['', '7', '007', ' 007', '0007', '12  34', '12 34'],
  estadoDeportivo: ['ACTIVO', 'INACTIVO', 'SUSPENDIDO'],
};
const generate = (): Values => ({ temporadaId: 'temp-1', ...Object.fromEntries(EDIT_FIELDS.map((field) => [field, pick(DOMAIN[field])])) }) as Values;

describe('three-way merge of the sports fields: invariants over random original, draft and current', () => {
  it('never drops an administrator change silently, never overwrites an untouched field and never defaults a conflict', () => {
    const violations: string[] = [];
    const check = (ok: boolean, message: string) => {
      if (!ok) violations.push(message);
    };
    for (let i = 0; i < 4000; i += 1) {
      const original = generate();
      const draft = generate();
      const current = generate();
      const kinds = classify(original, draft, current);
      const open = unresolved(kinds, {});
      const decisions: Decisions = {};
      for (const field of open) decisions[field] = pick<Decision>(['mine', 'current']);
      check(unresolved(kinds, decisions).length === 0, 'unresolved after deciding everything');
      const merged = merge(draft, current, kinds, decisions);
      check(merged.temporadaId === draft.temporadaId, 'the identity of the row changed');
      for (const field of EDIT_FIELDS) {
        const mineChanged = norm(field, draft[field]) !== norm(field, original[field]);
        const theirsChanged = norm(field, current[field]) !== norm(field, original[field]);
        const isConflict = mineChanged && theirsChanged && norm(field, draft[field]) !== norm(field, current[field]);
        check(open.includes(field) === isConflict, `conflict detection of ${field}`);
        check([norm(field, draft[field]), norm(field, current[field])].includes(norm(field, merged[field])), `${field}: the value is neither the draft nor the current one`);
        if (!mineChanged) check(norm(field, merged[field]) === norm(field, current[field]), `${field}: an untouched field must take the current value`);
        if (mineChanged && !theirsChanged) check(norm(field, merged[field]) === norm(field, draft[field]), `${field}: a field only the administrator changed must keep the draft`);
        if (isConflict) check(norm(field, merged[field]) === norm(field, decisions[field] === 'mine' ? draft[field] : current[field]), `${field}: a conflict must follow the decision`);
        if (mineChanged && norm(field, merged[field]) !== norm(field, draft[field])) check(isConflict && decisions[field] === 'current', `${field}: a change was dropped without an explicit decision`);
      }
    }
    expect(violations.slice(0, 5)).toEqual([]);
  });

  it('a conflict is never resolved on its own: with no decisions every conflicting field is still open', () => {
    for (let i = 0; i < 2000; i += 1) {
      const original = generate();
      const draft = generate();
      const current = generate();
      const kinds = classify(original, draft, current);
      const open = unresolved(kinds, {});
      const expected = EDIT_FIELDS.filter((field) => kinds[field] === 'conflict');
      expect(open).toEqual(expected);
    }
  });

  it('with no changes by anybody the merge is the draft itself, field by field', () => {
    for (let i = 0; i < 1000; i += 1) {
      const original = generate();
      const merged = merge(original, original, classify(original, original, original), {});
      expect(merged).toEqual(original);
    }
  });
});
