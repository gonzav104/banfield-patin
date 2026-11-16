import type { AthleteInput } from './schemas';
import {
  INVALID_BIRTH_DATE,
  INVALID_CUIL,
  INVALID_DNI,
  INVALID_EMAIL,
  maxLengthText,
  REQUIRED_DNI,
  REQUIRED_LASTNAME,
  REQUIRED_NAME,
} from './messages';

/** The thirteen personal fields of DeportistaSolicitud and the rules of the backend contract. */
export const FIELDS = [
  'dni',
  'nombre',
  'apellido',
  'cuil',
  'fechaNacimiento',
  'nacionalidad',
  'domicilio',
  'otrosDatosDomicilio',
  'localidad',
  'partido',
  'codigoPostal',
  'telefonoContacto',
  'emailFederativo',
] as const;
export type FieldName = (typeof FIELDS)[number];
export type Values = Record<FieldName, string>;
export type Errors = Partial<Record<FieldName, string>>;
export const EMPTY: Values = {
  dni: '',
  nombre: '',
  apellido: '',
  cuil: '',
  fechaNacimiento: '',
  nacionalidad: '',
  domicilio: '',
  otrosDatosDomicilio: '',
  localidad: '',
  partido: '',
  codigoPostal: '',
  telefonoContacto: '',
  emailFederativo: '',
};

/** Maximum lengths of the backend (@Size). */
export const MAX_LENGTH: Record<FieldName, number> = {
  dni: 20,
  nombre: 100,
  apellido: 100,
  cuil: 20,
  fechaNacimiento: 10,
  nacionalidad: 80,
  domicilio: 180,
  otrosDatosDomicilio: 250,
  localidad: 100,
  partido: 100,
  codigoPostal: 15,
  telefonoContacto: 40,
  emailFederativo: 180,
};

/** The same normalization as the backend: DNI without dots and spaces, CUIL without dots, hyphens and spaces. */
export const normalizeDni = (value: string) => value.replace(/[.\s\p{Z}]/gu, '');
export const normalizeCuil = (value: string) => value.replace(/[.\-\s\p{Z}]/gu, '');

const CUIL_WEIGHTS = [5, 4, 3, 2, 7, 6, 5, 4, 3, 2];

/** 11 digits and the check digit of DocumentoIdentidad.cuilValido. */
export function isValidCuil(raw: string): boolean {
  const cuil = normalizeCuil(raw);
  if (!/^[0-9]{11}$/.test(cuil)) return false;
  const sum = CUIL_WEIGHTS.reduce((total, weight, index) => total + Number(cuil[index]) * weight, 0);
  const rest = 11 - (sum % 11);
  if (rest === 10) return false;
  return Number(cuil[10]) === (rest === 11 ? 0 : rest);
}

/** Today's calendar date in the SCHOOL's time zone, as YYYY-MM-DD. */
export const schoolToday = (): string => new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Argentina/Buenos_Aires' }).format(new Date());

/** A real calendar date strictly before today (@Past). */
export function isPastDate(value: string): boolean {
  const match = /^([0-9]{4})-([0-9]{2})-([0-9]{2})$/.exec(value);
  if (!match) return false;
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const date = new Date(Date.UTC(year, month - 1, day));
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return false;
  return value < schoolToday();
}

export function validate(values: Values): Errors {
  const errors: Errors = {};
  const dni = values.dni.trim();
  if (dni === '') errors.dni = REQUIRED_DNI;
  else if (dni.length > MAX_LENGTH.dni) errors.dni = maxLengthText(MAX_LENGTH.dni);
  else if (!/^[0-9]{7,9}$/.test(normalizeDni(dni))) errors.dni = INVALID_DNI;

  const nombre = values.nombre.trim();
  if (nombre === '') errors.nombre = REQUIRED_NAME;
  else if (nombre.length > MAX_LENGTH.nombre) errors.nombre = maxLengthText(MAX_LENGTH.nombre);
  const apellido = values.apellido.trim();
  if (apellido === '') errors.apellido = REQUIRED_LASTNAME;
  else if (apellido.length > MAX_LENGTH.apellido) errors.apellido = maxLengthText(MAX_LENGTH.apellido);

  const cuil = values.cuil.trim();
  if (cuil !== '') {
    if (cuil.length > MAX_LENGTH.cuil) errors.cuil = maxLengthText(MAX_LENGTH.cuil);
    else if (!isValidCuil(cuil)) errors.cuil = INVALID_CUIL;
  }
  const birth = values.fechaNacimiento.trim();
  if (birth !== '' && !isPastDate(birth)) errors.fechaNacimiento = INVALID_BIRTH_DATE;

  for (const field of ['nacionalidad', 'domicilio', 'otrosDatosDomicilio', 'localidad', 'partido', 'codigoPostal', 'telefonoContacto'] as const) {
    if (values[field].trim().length > MAX_LENGTH[field]) errors[field] = maxLengthText(MAX_LENGTH[field]);
  }
  const email = values.emailFederativo.trim();
  if (email !== '') {
    if (email.length > MAX_LENGTH.emailFederativo) errors.emailFederativo = maxLengthText(MAX_LENGTH.emailFederativo);
    else if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) errors.emailFederativo = INVALID_EMAIL;
  }
  return errors;
}

/** Only the three required fields and the filled optionals travel, trimmed; the backend turns a blank optional into null anyway. */
export function toInput(values: Values): AthleteInput {
  const input: AthleteInput = { dni: values.dni.trim(), nombre: values.nombre.trim(), apellido: values.apellido.trim() };
  for (const field of FIELDS) {
    if (field === 'dni' || field === 'nombre' || field === 'apellido') continue;
    const value = values[field].trim();
    if (value !== '') input[field] = value;
  }
  return input;
}

/** How the backend shows a DNI in a summary: only the last three digits (display of the verification hint, never sent). */
export function maskedDni(dni: string): string {
  const digits = normalizeDni(dni);
  const visible = Math.min(3, digits.length);
  return '•'.repeat(digits.length - visible) + digits.slice(digits.length - visible);
}
