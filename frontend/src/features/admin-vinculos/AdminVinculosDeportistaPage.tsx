import { Button } from '@/components/ui/button';
import { useParams } from 'react-router-dom';
import { AthleteNotFound } from '@/features/admin-deportistas/AthleteNotFound';
import { AthleteStatusBadge } from '@/features/admin-deportistas/AthleteStatusBadge';
import { isSafeAthleteId } from '@/features/admin-deportistas/api';
import { useAthleteHeader } from '@/features/admin-datos-deportivos/hooks';
import type { AthleteHeader } from '@/features/admin-datos-deportivos/schemas';
import { Row, SportsLink } from '@/features/admin-datos-deportivos/SportsUi';
import { useSession } from '@/features/auth/session';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { ApiError } from '@/lib/api-error';
import { groupLinks, isPrincipalLink } from './grouping';
import { useAthleteLinks } from './hooks';
import {
  ATHLETE_ACCESS_NOTE,
  ATHLETE_INACTIVE_NOTE,
  ATHLETE_LOADING,
  ATHLETE_NO_ACTIVE_LINKS,
  ATHLETE_NO_LINKS,
  ATHLETE_TITLE,
  athleteLinksErrorMessage,
  BACK_TO_ATHLETE,
  isAthleteAbsent,
  NO_PRINCIPAL_NOTE,
  PRINCIPAL_FAMILY,
} from './messages';
import { athleteDetailPath, familyDetailPath } from './navigation';
import { LinkManagement } from './LinkActions';
import type { AthleteLinks, LinkRow } from './schemas';
import { LinkGroups, LinksNotice, LinksReadError, LinkStateBadge, LinkTarget, PrincipalBadge } from './LinksUi';

function FamilyItem({ row }: { row: LinkRow }) {
  return (
    <>
      <LinkTarget to={familyDetailPath(row.familiaId)}>{row.familiaNombre}</LinkTarget>
      <LinkStateBadge state={row.estado} />
      {isPrincipalLink(row) ? <PrincipalBadge>{PRINCIPAL_FAMILY}</PrincipalBadge> : null}
    </>
  );
}

function Links({ athlete, snapshot, disabled, refresh }: { athlete: AthleteHeader; snapshot: AthleteLinks; disabled: boolean; refresh: () => Promise<unknown> }) {
  const rows = snapshot.vinculos;
  const { active } = groupLinks(rows);
  return (
    <div className="max-w-3xl space-y-6">
      <SportsLink to={athleteDetailPath(athlete.id)}>{BACK_TO_ATHLETE}</SportsLink>
      <header className="space-y-2">
        <h1 tabIndex={-1} className="text-3xl font-extrabold tracking-tight">{ATHLETE_TITLE}</h1>
        <p className="min-w-0 break-words text-lg font-semibold">
          {athlete.nombre} {athlete.apellido}
        </p>
        <dl className="text-sm">
          <Row label="Estado en la escuela">
            <AthleteStatusBadge active={athlete.activo} />
          </Row>
        </dl>
        <p className="text-sm text-muted">{ATHLETE_ACCESS_NOTE}</p>
        {athlete.activo ? null : <p className="text-sm font-semibold">{ATHLETE_INACTIVE_NOTE}</p>}
      </header>
      {rows.length === 0 ? <LinksNotice>{ATHLETE_NO_LINKS}</LinksNotice> : null}
      {rows.length > 0 && active.length === 0 ? <LinksNotice>{ATHLETE_NO_ACTIVE_LINKS}</LinksNotice> : null}
      {active.length > 0 && !active.some(isPrincipalLink) ? <p className="text-sm text-muted">{NO_PRINCIPAL_NOTE}</p> : null}
      <LinkManagement snapshot={snapshot} disabled={disabled} refresh={refresh}>
        {(actions) => <LinkGroups rows={rows} level="h2" renderItem={(row) => <><FamilyItem row={row} />{actions(row)}</>} />}
      </LinkManagement>
    </div>
  );
}

function LinksScreen() {
  const { id } = useParams();
  const valid = isSafeAthleteId(id);
  const athleteId = valid ? id : '';
  const header = useAthleteHeader(athleteId, valid);
  const links = useAthleteLinks(athleteId, valid);

  if (!valid) return <AthleteNotFound />;
  // A failed REFRESH must not take the data off the screen: only an error with nothing to show is an error screen.
  const failed = (header.isError && !header.data ? header : null) ?? (links.isError && !links.data ? links : null);
  if (failed) {
    const error = ApiError.from(failed.error);
    if (isAthleteAbsent(error)) return <AthleteNotFound />;
    return (
      <div className="max-w-3xl space-y-4">
        <SportsLink to={athleteDetailPath(athleteId)}>{BACK_TO_ATHLETE}</SportsLink>
        <LinksReadError
          message={athleteLinksErrorMessage(error)}
          onRetry={() => {
            if (header.isError) void header.refetch();
            if (links.isError) void links.refetch();
          }}
        />
      </div>
    );
  }
  if (!header.data || !links.data) {
    return (
      <div className="max-w-3xl space-y-4">
        <SportsLink to={athleteDetailPath(athleteId)}>{BACK_TO_ATHLETE}</SportsLink>
        <LoadingBlock label={ATHLETE_LOADING} />
      </div>
    );
  }
  return <>
    {links.isError ? <div role="status" className="space-y-2">
      <p>No pudimos actualizar los vínculos. Consultá el estado vigente antes de operar.</p>
      <Button variant="outline" onClick={() => void links.refetch()}>Consultar vínculos vigentes</Button>
    </div> : null}
    <Links athlete={header.data} snapshot={links.data} disabled={links.isError || links.isFetching}
      refresh={() => links.refetch({ throwOnError: true })} />
  </>;
}

/**
 * The families linked to ONE athlete, for ONE identity. Keyed by both, a session that now belongs to someone else, or a different
 * athlete, starts from nothing (new queries, no data of the previous one) instead of keeping what was already drawn.
 */
export function AdminVinculosDeportistaPage() {
  const session = useSession();
  const { id } = useParams();
  const identity = session.status === 'authenticated' ? [session.user.id, session.user.rol, session.user.escuelaId, session.user.familiaId].join('|') : '';
  return <LinksScreen key={`${identity}:${id ?? ''}`} />;
}
