import { NOT_REPORTED, NO_CURRENT_SEASON_TEXT, SPORTS_STATUS_LABEL } from './messages';
import type { CurrentSeason } from './schemas';
import { StatusBadge, type BadgeTone } from './StatusBadge';

const sportsTone: Record<CurrentSeason['estadoDeportivo'], BadgeTone> = { ACTIVO: 'positive', INACTIVO: 'neutral', SUSPENDIDO: 'warning' };

/** Free text from the server, shown as it is (the runner number stays text: "007" is not 7). Blank counts as no data. */
const text = (value: string | null) => (value !== null && value.trim() !== '' ? value : NOT_REPORTED);

export function Row({ term, children }: { term: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 border-t border-line py-3 first:border-t-0 first:pt-0 last:pb-0">
      <dt className="text-sm text-muted">{term}</dt>
      <dd className="min-w-0 break-words text-right font-semibold">{children}</dd>
    </div>
  );
}

/**
 * The CURRENT season of the athlete (the one the server marks as current). With no data for it, a neutral message: there
 * is no reason given, no previous season is shown and nothing is made up.
 */
export function SeasonCard({ season }: { season: CurrentSeason | null }) {
  const title = season ? `Temporada ${season.anio}` : 'Temporada vigente';
  return (
    <section aria-labelledby="season-title" className="rounded-2xl border border-line bg-white p-5">
      <h2 id="season-title" className="mb-4 text-lg font-bold">
        {title}
      </h2>
      {season ? (
        <dl>
          <Row term="Categoría">{text(season.categoria)}</Row>
          <Row term="Rama">{text(season.rama)}</Row>
          <Row term="Divisional">{text(season.divisional)}</Row>
          <Row term="Número de corredor">{text(season.numeroCorredor)}</Row>
          <Row term="Estado deportivo">
            <StatusBadge tone={sportsTone[season.estadoDeportivo]}>{SPORTS_STATUS_LABEL[season.estadoDeportivo]}</StatusBadge>
          </Row>
        </dl>
      ) : (
        <p className="text-sm leading-relaxed text-muted">{NO_CURRENT_SEASON_TEXT}</p>
      )}
    </section>
  );
}
