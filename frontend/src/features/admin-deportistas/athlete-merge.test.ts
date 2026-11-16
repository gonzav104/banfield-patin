import { describe, expect, it } from 'vitest';
import { clearedFields, EMPTY, sameField, toUpdateInput, valuesOfAthlete, type Values } from './athlete-form';
import { classify, merge, unresolved } from './athlete-merge';

const O: Values = { ...EMPTY, dni: '30111322', nombre: 'Lucía', apellido: 'Sintética', telefonoContacto: '11 5555-0000', localidad: 'Banfield' };
const v = (over: Partial<Values>): Values => ({ ...O, ...over });

describe('three-way comparison after a revision conflict', () => {
  it('classifies every field by who changed it', () => {
    const draft = v({ nombre: 'Lucía María', telefonoContacto: '11 6666-1111', localidad: 'Temperley' });
    const current = v({ apellido: 'Gómez', telefonoContacto: '11 6666-1111', localidad: 'Lanús' });
    const kinds = classify(O, draft, current);
    expect(kinds.nombre).toBe('mine'); // only the administrator
    expect(kinds.apellido).toBe('incorporated'); // only somebody else
    expect(kinds.telefonoContacto).toBe('coincide'); // both, to the same value
    expect(kinds.localidad).toBe('conflict'); // both, to different values
    expect(kinds.dni).toBe('unchanged');
  });

  it('compares the way the backend does: DNI without dots, CUIL without hyphens, e-mail in lowercase, trimmed text', () => {
    expect(sameField('dni', '30.111.322', '30111322')).toBe(true);
    expect(sameField('cuil', '20-30111322-7', '20301113227')).toBe(true);
    expect(sameField('emailFederativo', ' A@Example.test ', 'a@example.test')).toBe(true);
    expect(sameField('nombre', ' Lucía ', 'Lucía')).toBe(true);
    expect(sameField('nombre', 'Lucía', 'lucía')).toBe(false); // names are NOT case-folded
    const kinds = classify(O, v({ dni: '30.111.322' }), v({ dni: '30111322' }));
    expect(kinds.dni).toBe('unchanged'); // a re-formatted DNI is not a change
  });

  it('lists the conflicts that still wait for a decision and merges only after each one', () => {
    const draft = v({ nombre: 'Mía', localidad: 'Temperley' });
    const current = v({ nombre: 'Otra', apellido: 'Gómez', localidad: 'Lanús' });
    const kinds = classify(O, draft, current);
    expect(unresolved(kinds, {})).toEqual(['nombre', 'localidad']);
    expect(unresolved(kinds, { nombre: 'mine' })).toEqual(['localidad']);
    expect(unresolved(kinds, { nombre: 'mine', localidad: 'current' })).toEqual([]);
    const merged = merge(draft, current, kinds, { nombre: 'mine', localidad: 'current' });
    expect(merged.nombre).toBe('Mía'); // the explicit choice
    expect(merged.localidad).toBe('Lanús'); // the explicit choice
    expect(merged.apellido).toBe('Gómez'); // somebody else's change on a field the administrator never touched
    expect(merged.dni).toBe('30111322');
  });

  it('never drops a change of the administrator nor overwrites a field they did not touch', () => {
    const draft = v({ nombre: 'Mía' });
    const current = v({ apellido: 'Gómez' });
    const merged = merge(draft, current, classify(O, draft, current), {});
    expect(merged).toEqual(v({ nombre: 'Mía', apellido: 'Gómez' }));
  });
});

describe('edit form helpers', () => {
  it('sends every field and an explicit null for each empty optional (complete replacement)', () => {
    const input = toUpdateInput(v({ nombre: '  Lucía ', telefonoContacto: '   ', localidad: '' }));
    expect(input).toEqual({
      dni: '30111322',
      nombre: 'Lucía',
      apellido: 'Sintética',
      cuil: null,
      fechaNacimiento: null,
      nacionalidad: null,
      domicilio: null,
      otrosDatosDomicilio: null,
      localidad: null,
      partido: null,
      codigoPostal: null,
      telefonoContacto: null,
      emailFederativo: null,
    });
    expect(Object.keys(input)).toHaveLength(13);
    expect('activo' in input).toBe(false); // the state is never part of the personal data
  });

  it('starts from ALL the values of the detail, with null as an empty field', () => {
    const values = valuesOfAthlete({ id: 'a', nombre: 'Lucía', apellido: 'S', dni: '30111322', cuil: null, activo: true, version: '3', fechaNacimiento: undefined });
    expect(values.cuil).toBe('');
    expect(values.fechaNacimiento).toBe('');
    expect(values.dni).toBe('30111322');
  });

  it('names (never values) the optional data that an emptied field would remove', () => {
    expect(clearedFields(O, v({ telefonoContacto: '', localidad: ' ' }))).toEqual(['localidad', 'telefonoContacto']);
    expect(clearedFields(O, v({ nombre: '' }))).toEqual([]); // required fields are validated, not «removed»
  });
});
