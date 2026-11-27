import { ApiError } from '@/lib/api-error';
import { uploadFailure, type UploadAction } from '@/features/familia/upload-outcome';

/**
 * What the screen does after a failed apto upload or replacement. Same actions as the DNI upload plus `fix-data`: the backend
 * rejected the three declared data (400 VALIDACION). Then the file and its preview are kept and the data are edited; the next send
 * uses a NEW Idempotency-Key (the server includes the data in the identity of the operation, so the old key would be a conflict).
 * Every text is written for the family; the server's message and the values sent are never shown.
 */
export type AptoUploadAction = UploadAction | 'fix-data';
export interface AptoUploadFailure {
  action: AptoUploadAction;
  message: string;
}

const KNOWN_FIELDS = new Set(['fechaEmision', 'medicoNombre', 'medicoMatricula']);
const FIELD_LABEL: Record<string, string> = { fechaEmision: 'la fecha de emisión', medicoNombre: 'el nombre del médico', medicoMatricula: 'la matrícula' };

export function aptoUploadFailure(error: unknown, maxBytes: number | null, replacing: boolean): AptoUploadFailure {
  const failure = ApiError.from(error);
  if (failure.status === 400 && failure.code === 'VALIDACION') {
    const names = [...new Set(failure.details.map((d) => d.campo ?? '').filter((c) => KNOWN_FIELDS.has(c)))].map((c) => FIELD_LABEL[c]!);
    return { action: 'fix-data', message: names.length ? `Revisá ${names.join(', ')}.` : 'Revisá los datos del apto.' };
  }
  if (failure.status === 409 && failure.code === 'OPERACION_NO_REPETIBLE' && !replacing) {
    return {
      action: 'refresh',
      message: 'Ya hay un apto cargado para esta temporada o esta carga cambió. Actualizamos la información: si todavía falta, volvé a cargarlo.',
    };
  }
  return uploadFailure(error, maxBytes, replacing);
}
