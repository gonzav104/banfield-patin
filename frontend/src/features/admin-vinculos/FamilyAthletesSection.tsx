import { LinkAthletes } from './LinkAthletes';
import { useSession } from '@/features/auth/session';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { ApiError } from '@/lib/api-error';
import { groupLinks, isPrincipalLink } from './grouping';
import { useFamilyAthletes } from './hooks';
import {
  FAMILY_ACCESS_NOTE,
  FAMILY_INACTIVE_NOTE,
  FAMILY_LOADING,
  FAMILY_NO_ACTIVE_LINKS,
  FAMILY_NO_LINKS,
  FAMILY_SECTION_TITLE,
  familyAthletesErrorMessage,
  INACTIVE_ATHLETE,
  PRINCIPAL_ATHLETE,
} from './messages';
import { athleteDetailPath } from './navigation';
import type { LinkRow } from './schemas';
import { LinkGroups, LinksNotice, LinksReadError, LinkStateBadge, LinkTarget, PrincipalBadge } from './LinksUi';
import { StatusBadge } from '@/features/familia/StatusBadge';

function AthleteItem({ row }: { row: LinkRow }) {
  return (
    <>
      <LinkTarget to={athleteDetailPath(row.deportistaId)}>
        {row.deportistaNombre} {row.deportistaApellido}
      </LinkTarget>
      <LinkStateBadge state={row.estado} />
      {isPrincipalLink(row) ? <PrincipalBadge>{PRINCIPAL_ATHLETE}</PrincipalBadge> : null}
      {row.deportistaActivo ? null : <StatusBadge tone="neutral">{INACTIVE_ATHLETE}</StatusBadge>}
    </>
  );
}

function Body({ familyId }: { familyId: string }) {
  const athletes = useFamilyAthletes(familyId, true);
  // A failed REFRESH must not take the data off the screen: only an error with nothing to show is an error.
  if (athletes.isError && !athletes.data) {
    return <LinksReadError message={familyAthletesErrorMessage(ApiError.from(athletes.error))} onRetry={() => void athletes.refetch()} />;
  }
  if (!athletes.data) return <LoadingBlock label={FAMILY_LOADING} />;
  const rows = athletes.data;
  const { active } = groupLinks(rows);
  return (
    <>
      {rows.length === 0 ? <LinksNotice>{FAMILY_NO_LINKS}</LinksNotice> : null}
      {rows.length > 0 && active.length === 0 ? <LinksNotice>{FAMILY_NO_ACTIVE_LINKS}</LinksNotice> : null}
      <LinkGroups rows={rows} level="h3" renderItem={(row) => <AthleteItem row={row} />} />
    </>
  );
}

/**
 * The athletes linked to ONE family, with opt-in batch linking inside the family detail. It has its own states (loading, error with retry, empty), so a
 * failure here never breaks the family detail. Keyed by identity and family: another identity or another family starts from nothing.
 */
export function FamilyAthletesSection({ familyId, active }: { familyId: string; active: boolean }) {
  const session = useSession();
  const identity = session.status === 'authenticated' ? [session.user.id, session.user.rol, session.user.escuelaId, session.user.familiaId, session.user.mfaPendiente].join('|') : '';
  return (
    <section aria-labelledby="family-athletes-title" className="space-y-3">
      <h2 id="family-athletes-title" className="text-lg font-bold">
        {FAMILY_SECTION_TITLE}
      </h2>
      <p className="text-sm text-muted">{FAMILY_ACCESS_NOTE}</p>
      {active ? null : <p className="text-sm font-semibold">{FAMILY_INACTIVE_NOTE}</p>}
      <LinkAthletes key={`link:${identity}:${familyId}`} familyId={familyId} active={active} />
      <Body key={`${identity}:${familyId}`} familyId={familyId} />
    </section>
  );
}
