import { z } from 'zod';

/** The school's calendar day (America/Argentina/Buenos_Aires), as `aaaa-mm-dd`; the backend rejects a future issue date in this zone. */
export function schoolToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Argentina/Buenos_Aires' }).format(now);
}

export const EMISSION_MIN = '2000-01-01';
export const DOCTOR_NAME_MAX = 150;
export const LICENSE_MAX = 50;
/** Letters and digits, with space, dot, hyphen and slash as separators (e.g. «MP 12.345», «M.N. 123/4»): the backend rule. */
const LICENSE_PATTERN = /^[\p{L}\p{N}][\p{L}\p{N} ./-]*$/u;
// eslint-disable-next-line no-control-regex
const CONTROL_CHARS = /[\u0000-\u001f\u007f-\u009f]/;

const isRealDate = (iso: string) => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(iso)) return false;
  const [y, m, d] = iso.split('-').map(Number) as [number, number, number];
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d;
};

/** The three declared data of the apto, validated like the backend does (trimmed first). The messages are fixed texts: no value is echoed. */
export function aptoFormSchema(today: string = schoolToday()) {
  return z.object({
    fechaEmision: z
      .string()
      .trim()
      .min(1, 'Ingresá la fecha de emisión.')
      .refine(isRealDate, 'La fecha de emisión no es válida.')
      .refine((v) => !isRealDate(v) || v >= EMISSION_MIN, 'La fecha de emisión no es válida.')
      .refine((v) => !isRealDate(v) || v <= today, 'La fecha de emisión no puede ser futura.'),
    medicoNombre: z
      .string()
      .trim()
      .min(1, 'Ingresá el nombre del médico.')
      .max(DOCTOR_NAME_MAX, `El nombre puede tener hasta ${DOCTOR_NAME_MAX} caracteres.`)
      .refine((v) => !CONTROL_CHARS.test(v), 'El nombre del médico tiene caracteres no válidos.'),
    medicoMatricula: z
      .string()
      .trim()
      .min(1, 'Ingresá la matrícula.')
      .max(LICENSE_MAX, `La matrícula puede tener hasta ${LICENSE_MAX} caracteres.`)
      .refine((v) => LICENSE_PATTERN.test(v), 'La matrícula solo admite letras, números, espacios, puntos, guiones y barras.'),
  });
}
export type AptoFormValues = z.infer<ReturnType<typeof aptoFormSchema>>;
export type AptoData = AptoFormValues;

/** The same data in the same shape and order: two attempts with equal data share an Idempotency-Key, a different one never does. */
export const aptoDataSignature = (data: AptoData) => JSON.stringify([data.fechaEmision, data.medicoNombre, data.medicoMatricula]);
