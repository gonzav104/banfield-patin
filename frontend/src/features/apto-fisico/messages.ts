import type { AptoState } from './schemas';

export const APTO_TITLE = 'Apto físico';
export const APTO_INFORMATIVE =
  'El apto físico es anual y, por ahora, es informativo: no impide otras gestiones del club. El club revisa cada apto que se carga.';
export const NO_SEASONS_FAMILY = 'Todavía no hay una temporada cargada para este deportista. Cuando el club la cargue vas a poder subir el apto.';
export const SEASON_LABEL = 'Temporada';
export const UPLOAD_DISABLED = 'La carga de documentos no está habilitada por el momento.';
export const AVAILABILITY_UNKNOWN = 'No pudimos comprobar si la carga de documentos está disponible.';
export const OTHER_FAMILY_DATA =
  'Este apto lo cargó otra familia vinculada al deportista. Podés ver su estado, pero no sus datos.';
export const OBSERVED_BY_OTHER = 'El club observó este apto: hay que reemplazarlo. Podés subir uno nuevo.';

export const STATE_LABEL: Record<AptoState, string> = {
  FALTA: 'Falta',
  EN_REVISION: 'En revisión',
  OBSERVADO: 'Observado',
  CUMPLIDO: 'Cumplido',
  VENCIDO: 'Vencido',
};
export const STATE_TONE: Record<AptoState, 'neutral' | 'positive' | 'warning'> = {
  FALTA: 'neutral',
  EN_REVISION: 'neutral',
  OBSERVADO: 'warning',
  CUMPLIDO: 'positive',
  VENCIDO: 'warning',
};

/** Known notice codes → text written for the screen. The server's own wording is never shown; an unknown code shows nothing. */
export const NOTICE_TEXT: Record<string, string> = {
  REQUISITOS_DE_LA_TEMPORADA_PENDIENTES_DE_CONFIRMACION:
    'Los requisitos del apto para esta temporada todavía no están confirmados. Lo que ves es provisorio y puede cambiar.',
  APTO_OBSERVADO_REQUIERE_REEMPLAZO: OBSERVED_BY_OTHER,
};

const dateFormat = new Intl.DateTimeFormat('es-AR', { dateStyle: 'long', timeZone: 'UTC' });
/** `aaaa-mm-dd` as a long Spanish date, without time-zone shifts (it is a calendar day, not an instant). */
export function formatDay(iso: string): string {
  const date = new Date(`${iso}T00:00:00Z`);
  return Number.isNaN(date.getTime()) ? iso : dateFormat.format(date);
}

export function validityText(vigencia: { hasta: string; confirmada: boolean }, state: AptoState): string {
  const until = formatDay(vigencia.hasta);
  const provisional = vigencia.confirmada ? '' : ' (regla provisoria)';
  return state === 'VENCIDO' ? `Venció el ${until}${provisional}.` : `Vigente hasta el ${until}${provisional}.`;
}
