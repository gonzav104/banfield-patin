import { useEffect, useRef } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useFamilyAccount } from './account';
import { isAthleteId, isFamilyUnavailable } from './api';
import { DocumentsSection } from './DocumentsSection';
import { ErrorBlock, FamilyUnavailableNotice, LoadingBlock, NoFamilyNotice, TutorNotFoundNotice } from './FamilyStates';
import { useMyFamily } from './hooks';
import { identityKey, type FamilyIdentity } from './identity';

function TutorDocuments({ identity, tutorId }: { identity: FamilyIdentity | null; tutorId: string | undefined }) {
  const family = useMyFamily(identity ?? { userId: '', familyId: null });
  const heading = useRef<HTMLHeadingElement>(null);
  const tutor = family.data?.tutores.find((t) => t.id === tutorId);

  useEffect(() => {
    if (tutor) heading.current?.focus(); // a new screen: keyboard and screen-reader users land on its title
  }, [tutor]);

  if (!identity) return null; // the route guard sends anyone without a FAMILIA session to login

  let body;
  if (!identity.familyId) body = <NoFamilyNotice />;
  else if (!isAthleteId(tutorId)) body = <TutorNotFoundNotice />;
  else if (family.isPending) body = <LoadingBlock label="Cargando tu familia…" />;
  else if (family.isError) {
    body = isFamilyUnavailable(family.error) ? <FamilyUnavailableNotice /> : <ErrorBlock error={family.error} onRetry={() => void family.refetch()} />;
  } else if (!tutor) body = <TutorNotFoundNotice />;
  else {
    body = (
      <>
        <section>
          <p className="text-sm font-semibold text-brand-700">Tutor de tu familia</p>
          <h1 ref={heading} tabIndex={-1} className="mt-1 break-words text-3xl font-extrabold tracking-tight outline-none">
            {tutor.nombre} {tutor.apellido}
          </h1>
          {tutor.parentesco ? <p className="mt-1 text-sm text-muted">{tutor.parentesco}</p> : null}
        </section>
        <DocumentsSection identity={identity} athleteId={tutor.id} kind="tutor" />
      </>
    );
  }

  return (
    <div className="space-y-6">
      <Link to="/familia" className="inline-flex min-h-11 items-center gap-1 text-sm font-semibold text-brand-700 hover:underline">
        <span aria-hidden="true">←</span> Volver a tu familia
      </Link>
      {tutor ? null : <h1 className="text-3xl font-extrabold tracking-tight">Documentos del tutor</h1>}
      {body}
    </div>
  );
}

/**
 * Documents of ONE tutor of the family (/familia/tutores/:tutorId, U19). Only the tutors the family itself reports
 * (`/familia/mi-familia`) are reachable; any other id is the same notice, and the backend authorizes every request again.
 * Mounted with a key made of the identity AND the tutor, so another user, family or tutor starts from a clean screen.
 * The session expiry is handled by the global session code (it sends the user to /login and back).
 */
export function TutorDocumentsPage() {
  const { tutorId } = useParams();
  const account = useFamilyAccount();
  const identity = account?.identity ?? null;
  return <TutorDocuments key={`${identity ? identityKey(identity) : 'sin-sesion'}|${tutorId ?? ''}`} identity={identity} tutorId={tutorId} />;
}
