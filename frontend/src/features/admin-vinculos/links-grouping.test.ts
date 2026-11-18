import { describe, expect, it } from 'vitest';
import { groupLinks, isPrincipalLink } from './grouping';
import { athleteDetailPath, athleteLinksPath, familyDetailPath } from './navigation';
import { rawLink } from './links-fixtures';
import type { LinkRow } from './schemas';
import { linkRowSchema } from './schemas';

const row = (over: Record<string, unknown>): LinkRow => linkRowSchema.parse(rawLink(over));

const STATES = ['PENDIENTE', 'ACTIVO', 'RECHAZADO', 'REVOCADO'] as const;

describe('grouping of links', () => {
  // The four states x the principal flag x the state of the athlete in the school: 16 cases, one rule each.
  const cases = STATES.flatMap((estado) => [true, false].flatMap((esPrincipal) => [true, false].map((deportistaActivo) => ({ estado, esPrincipal, deportistaActivo }))));

  it.each(cases)('puts %j in the right group and marks the principal only for an active link', ({ estado, esPrincipal, deportistaActivo }) => {
    const item = row({ estado, esPrincipal, deportistaActivo });
    const groups = groupLinks([item]);
    expect(groups.active).toEqual(estado === 'ACTIVO' ? [item] : []);
    expect(groups.previous).toEqual(estado === 'ACTIVO' ? [] : [item]);
    expect(isPrincipalLink(item)).toBe(estado === 'ACTIVO' && esPrincipal);
  });

  it('never loses or duplicates a row and keeps the order of the server inside each group', () => {
    const rows = [
      row({ vinculoId: 'a', estado: 'REVOCADO' }),
      row({ vinculoId: 'b', estado: 'ACTIVO' }),
      row({ vinculoId: 'c', estado: 'PENDIENTE' }),
      row({ vinculoId: 'd', estado: 'ACTIVO', esPrincipal: true }),
      row({ vinculoId: 'e', estado: 'RECHAZADO' }),
    ];
    const groups = groupLinks(rows);
    expect(groups.active.map((r) => r.vinculoId)).toEqual(['b', 'd']);
    expect(groups.previous.map((r) => r.vinculoId)).toEqual(['a', 'c', 'e']);
    expect(groups.active.length + groups.previous.length).toBe(rows.length);
  });

  it('an empty list gives two empty groups', () => {
    expect(groupLinks([])).toEqual({ active: [], previous: [] });
  });
});

describe('paths built from an id that came in an answer', () => {
  it('encode the id, so a hostile id never alters the address (the family one comes from the server)', () => {
    expect(familyDetailPath('f/../x?y#z')).toBe('/admin/familias/f%2F..%2Fx%3Fy%23z');
    expect(athleteDetailPath('a/b?c')).toBe('/admin/deportistas/a%2Fb%3Fc');
    expect(athleteLinksPath('a/b?c')).toBe('/admin/deportistas/a%2Fb%3Fc/vinculos');
  });

  it('keep an ordinary id as it is', () => {
    expect(familyDetailPath('3f2a-77')).toBe('/admin/familias/3f2a-77');
  });
});
