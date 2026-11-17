import { describe, expect, it } from 'vitest';
import { classify, merge, unresolved } from './sports-merge';
import { EDIT_FIELDS, type Values } from './sports-form';

const base: Values = { temporadaId: 'temp-1', categoria: 'Sub 12', rama: 'Femenino', divisional: 'A', numeroCorredor: '007', estadoDeportivo: 'ACTIVO' };
const with_ = (over: Partial<Values>): Values => ({ ...base, ...over });

describe('three-way comparison of the sports fields after a revision conflict', () => {
  it('nobody changed anything', () => {
    const kinds = classify(base, base, base);
    expect(EDIT_FIELDS.map((field) => kinds[field])).toEqual(['unchanged', 'unchanged', 'unchanged', 'unchanged', 'unchanged']);
  });

  it('classifies each field by who changed it', () => {
    const original = base;
    const draft = with_({ categoria: 'Sub 14', rama: 'Mixto', divisional: 'C', numeroCorredor: '008' });
    const current = with_({ rama: 'Masculino', divisional: 'C', numeroCorredor: '009', estadoDeportivo: 'SUSPENDIDO' });

    expect(classify(original, draft, current)).toEqual({
      categoria: 'mine', // only the administrator
      rama: 'conflict', // both, to different values
      divisional: 'coincide', // both, to the same value
      numeroCorredor: 'conflict',
      estadoDeportivo: 'incorporated', // only somebody else
    });
  });

  it('compares the way the backend does: spaces around and inside the texts do not make a change, the number keeps its zeros', () => {
    const kinds = classify(base, with_({ categoria: ' Sub   12 ', numeroCorredor: '7' }), with_({ rama: 'Femenino  ', numeroCorredor: '007 ' }));
    expect(kinds.categoria).toBe('unchanged');
    expect(kinds.rama).toBe('unchanged');
    expect(kinds.numeroCorredor).toBe('mine'); // «7» is not «007»
  });

  it('lists the conflicts nobody decided yet, and none once every one is decided', () => {
    const kinds = classify(base, with_({ categoria: 'Sub 14', rama: 'Mixto' }), with_({ categoria: 'Sub 16', rama: 'Masculino' }));
    expect(unresolved(kinds, {})).toEqual(['categoria', 'rama']);
    expect(unresolved(kinds, { categoria: 'mine' })).toEqual(['rama']);
    expect(unresolved(kinds, { categoria: 'mine', rama: 'current' })).toEqual([]);
  });

  it('never chooses for the administrator: a conflict with no decision is not even listed as decided', () => {
    const kinds = classify(base, with_({ categoria: 'Sub 14' }), with_({ categoria: 'Sub 16' }));
    expect(unresolved(kinds, {})).toContain('categoria');
  });
});

describe('merging the draft with the current data', () => {
  const original = base;
  const draft = with_({ categoria: 'Sub 14', rama: 'Mixto', divisional: 'C' });
  const current = with_({ rama: 'Masculino', divisional: 'C', numeroCorredor: '009', estadoDeportivo: 'SUSPENDIDO' });
  const kinds = classify(original, draft, current);

  it('keeps what only the administrator changed and takes what only somebody else changed', () => {
    const merged = merge(draft, current, kinds, { rama: 'mine' });
    expect(merged.categoria).toBe('Sub 14'); // mine
    expect(merged.numeroCorredor).toBe('009'); // the administrator never touched it: the current value
    expect(merged.estadoDeportivo).toBe('SUSPENDIDO'); // same
    expect(merged.divisional).toBe('C');
  });

  it('follows the explicit choice in a conflict', () => {
    expect(merge(draft, current, kinds, { rama: 'mine' }).rama).toBe('Mixto');
    expect(merge(draft, current, kinds, { rama: 'current' }).rama).toBe('Masculino');
  });

  it('keeps the season of the draft: the identity of the row never changes', () => {
    expect(merge(draft, with_({ temporadaId: 'otra' }), kinds, { rama: 'mine' }).temporadaId).toBe('temp-1');
  });
});
