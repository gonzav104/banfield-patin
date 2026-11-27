import { useId, useState } from 'react';
import { Button } from '@/components/ui/button';
import { ErrorBlock, LoadingBlock } from '@/features/familia/FamilyStates';
import { PRIVACY_NOTE } from '@/features/familia/DocumentsSection';
import { useUploadAvailability } from '@/features/familia/hooks';
import type { FamilyIdentity } from '@/features/familia/identity';
import { AptoSummary } from './AptoSummary';
import { AptoUploadForm } from './AptoUploadForm';
import { useFamilyApto, useFamilySeasons } from './hooks';
import { APTO_INFORMATIVE, APTO_TITLE, AVAILABILITY_UNKNOWN, NO_SEASONS_FAMILY, SEASON_LABEL, UPLOAD_DISABLED } from './messages';
import type { Apto, SeasonOption } from './schemas';
import { useAptoUpload } from './use-apto-upload';

export const APTO_SECTION_ID = 'apto-fisico-deportista';

/** The active season, else the newest one the athlete has data in. */
export const defaultSeason = (seasons: SeasonOption[]): string | null => (seasons.find((s) => s.activa) ?? seasons[0])?.id ?? null;

/** One upload attempt of one season (remounted per season; the hook drops what was chosen when another observed apto appears). */
function Uploader({ identity, athleteId, seasonId, maxBytes, apto }: { identity: FamilyIdentity; athleteId: string; seasonId: string; maxBytes: number; apto: Apto }) {
  const replaces = apto.estado === 'OBSERVADO' && apto.documento ? apto.documento.id : null;
  const upload = useAptoUpload({ identity, athleteId, seasonId, maxBytes, replaces });
  // Offered while the season has no apto, or its current apto is OBSERVED and can be replaced. The confirmation stays after the send.
  const eligible = apto.estado === 'FALTA' || (apto.estado === 'OBSERVADO' && replaces !== null);
  if (!eligible && upload.state.step !== 'sent') return null;
  return (
    <div className="space-y-2 border-t border-line pt-4">
      <h3 className="font-bold">{replaces ? 'Reemplazar el apto observado' : 'Subir el apto'}</h3>
      <AptoUploadForm upload={upload} replacing={replaces !== null} />
    </div>
  );
}

/**
 * «Apto físico» in the family athlete sheet: the state the server derived for one season and, when uploads are offered and the apto is
 * missing or observed, the upload flow with its three declared data. The server authorizes and validates everything; the data and the
 * review reason of an apto uploaded by ANOTHER family are never available here (`datosVisibles`), and nothing is persisted.
 */
export function FamilyAptoSection({ identity, athleteId }: { identity: FamilyIdentity; athleteId: string }) {
  const titleId = 'apto-title';
  const selectId = useId();
  const seasons = useFamilySeasons(identity, athleteId);
  const availability = useUploadAvailability(identity);
  const [chosen, setChosen] = useState<string | null>(null);
  const options = seasons.data ?? [];
  const seasonId = chosen && options.some((s) => s.id === chosen) ? chosen : defaultSeason(options);
  const apto = useFamilyApto(identity, athleteId, seasonId);
  const maxBytes = availability.data?.habilitada ? availability.data.maxBytes : null;
  const needsUpload = apto.data ? apto.data.estado === 'FALTA' || apto.data.estado === 'OBSERVADO' : false;

  return (
    <section id={APTO_SECTION_ID} tabIndex={-1} aria-labelledby={titleId} className="space-y-4 rounded-2xl border border-line bg-white p-5 outline-none">
      <div className="space-y-2">
        <h2 id={titleId} className="text-lg font-bold">
          {APTO_TITLE}
        </h2>
        <p className="text-sm leading-relaxed text-muted">{APTO_INFORMATIVE}</p>
      </div>
      {seasons.isPending ? <LoadingBlock label="Cargando el apto físico…" /> : null}
      {seasons.isError ? <ErrorBlock error={seasons.error} onRetry={() => void seasons.refetch()} /> : null}
      {seasons.data && options.length === 0 ? <p className="text-sm">{NO_SEASONS_FAMILY}</p> : null}
      {options.length > 1 ? (
        <div className="space-y-1">
          <label htmlFor={selectId} className="block text-sm font-semibold">
            {SEASON_LABEL}
          </label>
          <select
            id={selectId}
            value={seasonId ?? ''}
            onChange={(event) => setChosen(event.target.value)}
            className="min-h-11 w-full rounded-md border border-line bg-white px-3 text-base sm:w-auto"
          >
            {options.map((season) => (
              <option key={season.id} value={season.id}>
                {season.anio}
                {season.activa ? ' (actual)' : ''}
              </option>
            ))}
          </select>
        </div>
      ) : null}
      {seasonId && apto.isPending ? <LoadingBlock label="Cargando el apto físico…" /> : null}
      {seasonId && apto.isError ? <ErrorBlock error={apto.error} onRetry={() => void apto.refetch()} /> : null}
      {apto.data && seasonId ? (
        <>
          <AptoSummary apto={apto.data} />
          {maxBytes ? <p className="text-sm leading-relaxed text-muted">{PRIVACY_NOTE}</p> : null}
          {maxBytes ? (
            <Uploader key={seasonId} identity={identity} athleteId={athleteId} seasonId={seasonId} maxBytes={maxBytes} apto={apto.data} />
          ) : needsUpload ? (
            <p className="text-sm text-muted">{availability.isError ? AVAILABILITY_UNKNOWN : availability.data ? UPLOAD_DISABLED : ''}</p>
          ) : null}
          {availability.isError && needsUpload ? (
            <Button variant="outline" onClick={() => void availability.refetch()}>
              Volver a comprobar
            </Button>
          ) : null}
        </>
      ) : null}
    </section>
  );
}
