import type { TutorDetail, TutorInput, TutorUpdateInput } from './schemas';
import { INVALID_DNI, INVALID_EMAIL, maxLengthText, REQUIRED_LASTNAME, REQUIRED_NAME } from './tutor-messages';

/**
 * What the new-tutor and the edit-tutor forms share: the fields and the rules of the backend contract (TutorSolicitud and
 * TutorActualizacion have the same personal data and the same limits), so both apply exactly the same validation.
 */
export const FIELDS = ['nombre', 'apellido', 'dni', 'telefono', 'correo', 'parentesco'] as const;
export type FieldName = (typeof FIELDS)[number];
export type Values = Record<FieldName, string>;
export type Errors = Partial<Record<FieldName, string>>;
export const EMPTY: Values = { nombre: '', apellido: '', dni: '', telefono: '', correo: '', parentesco: '' };

/** Same limits and DNI rule as the backend (@Dni: 7 to 9 digits once dots and spaces are removed). */
export function validate(values: Values): Errors {
  const errors: Errors = {};
  const nombre = values.nombre.trim();
  const apellido = values.apellido.trim();
  if (nombre === '') errors.nombre = REQUIRED_NAME;
  else if (nombre.length > 100) errors.nombre = maxLengthText(100);
  if (apellido === '') errors.apellido = REQUIRED_LASTNAME;
  else if (apellido.length > 100) errors.apellido = maxLengthText(100);
  const dni = values.dni.trim();
  if (dni !== '') {
    if (dni.length > 20) errors.dni = maxLengthText(20);
    else if (!/^[0-9]{7,9}$/.test(dni.replace(/[.\s]/g, ''))) errors.dni = INVALID_DNI;
  }
  if (values.telefono.trim().length > 40) errors.telefono = maxLengthText(40);
  if (values.parentesco.trim().length > 40) errors.parentesco = maxLengthText(40);
  const correo = values.correo.trim();
  if (correo !== '') {
    if (correo.length > 180) errors.correo = maxLengthText(180);
    else if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(correo)) errors.correo = INVALID_EMAIL;
  }
  return errors;
}

/** New tutor: only the filled fields travel, trimmed; the backend turns a blank optional into null anyway. */
export function toInput(values: Values): TutorInput {
  const input: TutorInput = { nombre: values.nombre.trim(), apellido: values.apellido.trim() };
  if (values.dni.trim() !== '') input.dni = values.dni.trim();
  if (values.telefono.trim() !== '') input.telefono = values.telefono.trim();
  if (values.correo.trim() !== '') input.email = values.correo.trim();
  if (values.parentesco.trim() !== '') input.parentesco = values.parentesco.trim();
  return input;
}

/**
 * Edit: the PUT is a COMPLETE replacement, so every field travels and an optional one that is empty is sent as an explicit
 * `null` (the backend would also read an omission as «clear it»). The form starts from ALL the values of the full detail, so a
 * field is only cleared when the administrator cleared it.
 */
export function toUpdateInput(values: Values): TutorUpdateInput {
  const optional = (value: string) => (value.trim() === '' ? null : value.trim());
  return {
    nombre: values.nombre.trim(),
    apellido: values.apellido.trim(),
    dni: optional(values.dni),
    telefono: optional(values.telefono),
    email: optional(values.correo),
    parentesco: optional(values.parentesco),
  };
}

/** The form values of a tutor as the full detail (never the family summary) carries them. */
export function valuesOfTutor(tutor: TutorDetail): Values {
  return {
    nombre: tutor.nombre,
    apellido: tutor.apellido,
    dni: tutor.dni ?? '',
    telefono: tutor.telefono ?? '',
    correo: tutor.email ?? '',
    parentesco: tutor.parentesco ?? '',
  };
}

export const FIELD_LABELS: Record<FieldName, string> = {
  nombre: 'Nombre',
  apellido: 'Apellido',
  dni: 'DNI',
  telefono: 'Teléfono',
  correo: 'Correo electrónico',
  parentesco: 'Parentesco',
};

/** How the backend would store a value, so that «the same data» is compared the way the server compares it. */
function normalized(field: FieldName, value: string): string {
  const trimmed = value.trim();
  if (field === 'dni') return trimmed.replace(/[.\s]/g, '');
  if (field === 'correo') return trimmed.toLowerCase();
  return trimmed;
}

export const sameField = (field: FieldName, a: string, b: string) => normalized(field, a) === normalized(field, b);

export const sameValues = (a: Values, b: Values) => FIELDS.every((field) => sameField(field, a[field], b[field]));

/** Names (never values) of the optional fields that had data and that this submission would clear. */
export function clearedFields(original: Values, draft: Values): FieldName[] {
  return FIELDS.filter((field) => normalized(field, original[field]) !== '' && normalized(field, draft[field]) === '' && field !== 'nombre' && field !== 'apellido');
}
