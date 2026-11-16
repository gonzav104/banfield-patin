import { describe, expect, it } from 'vitest';
import { FIELDS, type FieldName, type Values } from './athlete-form';
import { classify, merge, unresolved, type Decision, type Decisions } from './athlete-merge';

// Independent oracle of the backend normalization (not imported from athlete-form on purpose).
const norm = (f: FieldName, v: string) => {
  const t = v.trim();
  if (f === 'dni') return t.replace(/[.\s]/g, '');
  if (f === 'cuil') return t.replace(/[.\-\s]/g, '');
  if (f === 'emailFederativo') return t.toLowerCase();
  return t;
};
let seed = 12345;
const rnd = () => ((seed = (seed * 1664525 + 1013904223) % 4294967296) / 4294967296);
const pick = <T,>(a: T[]) => a[Math.floor(rnd() * a.length)]!;
const DOMAIN: Record<FieldName, string[]> = {
  dni: ['30111322', '30.111.322', ' 30111322 ', '40222333', '40.222.333'],
  nombre: ['Ana', ' Ana ', 'ana', 'Luz', ''],
  apellido: ['Paz', 'Paz ', 'Gómez'],
  cuil: ['', '20301113227', '20-30111322-7', '20.30111322.7', ' '],
  fechaNacimiento: ['', '2012-05-04', '2011-01-01'],
  nacionalidad: ['', 'Argentina', 'Chile'],
  domicilio: ['', 'Calle 1', ' Calle 1', 'Calle 2'],
  otrosDatosDomicilio: ['', 'x', 'y'],
  localidad: ['', 'Banfield', 'Lanús', ' Banfield '],
  partido: ['', 'LdZ', 'Lanús'],
  codigoPostal: ['', '1828', '1824'],
  telefonoContacto: ['', '11 5555', '11  5555', '11 6666'],
  emailFederativo: ['', 'a@x.com', 'A@X.COM', ' a@x.com ', 'b@x.com'],
};
const gen = (): Values => Object.fromEntries(FIELDS.map((f) => [f, pick(DOMAIN[f])])) as Values;

describe('three-way merge: invariants over random original/draft/current', () => {
  it('never drops an administrator change silently, never overwrites an untouched field, never defaults a conflict', () => {
    const violations: string[] = [];
    const check = (ok: boolean, message: string) => { if (!ok) violations.push(message); };
    for (let i = 0; i < 3000; i++) {
      const O = gen(), B = gen(), V = gen();
      const kinds = classify(O, B, V);
      const open = unresolved(kinds, {});
      const decisions: Decisions = {};
      for (const f of open) decisions[f] = pick<Decision>(['mine', 'current']);
      check(unresolved(kinds, decisions).length === 0, 'unresolved after deciding');
      const M = merge(B, V, kinds, decisions);
      for (const f of FIELDS) {
        const mineChanged = norm(f, B[f]) !== norm(f, O[f]);
        const theirsChanged = norm(f, V[f]) !== norm(f, O[f]);
        const isConflict = mineChanged && theirsChanged && norm(f, B[f]) !== norm(f, V[f]);
        check(open.includes(f) === isConflict, `conflict detection ${f}`);
        check([norm(f, B[f]), norm(f, V[f])].includes(norm(f, M[f])), `${f}: value is neither draft nor current`);
        if (!mineChanged) check(norm(f, M[f]) === norm(f, V[f]), `${f}: untouched field must take the current value`);
        if (mineChanged && !theirsChanged) check(norm(f, M[f]) === norm(f, B[f]), `${f}: only-mine must keep the draft`);
        if (isConflict) check(norm(f, M[f]) === norm(f, decisions[f] === 'mine' ? B[f] : V[f]), `${f}: conflict must follow the decision`);
        if (mineChanged && norm(f, M[f]) !== norm(f, B[f])) check(isConflict && decisions[f] === 'current', `${f}: a change was dropped without an explicit decision`);
      }
    }
    expect(violations.slice(0, 5)).toEqual([]);
  });
});
