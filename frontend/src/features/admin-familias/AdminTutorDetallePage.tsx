import { useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { useSession } from '@/features/auth/session';
import { AthleteDocumentsSection } from '@/features/admin-deportistas/AthleteDocumentsSection';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { ApiError } from '@/lib/api-error';
import { isSafeFamilyId, isSafeTutorId } from './api';
import { BackToListLink } from './FamilyNotFound';
import { useTutorDetail } from './hooks';
import { TutorStatusControl } from './TutorStatusControl';
import { isNotFound } from './messages';
import type { TutorDetail } from './schemas';
import { TUTOR_NOT_FOUND_TEXT, TUTOR_NOT_FOUND_TITLE, TUTOR_SAVED_NOTICE, tutorDetailErrorMessage } from './tutor-messages';

export function BackToFamily({ familyId }: { familyId: string | undefined }) {
  if (!familyId) return <BackToListLink />;
  return (
    <Link
      to={`/admin/familias/${encodeURIComponent(familyId)}`}
      className="inline-flex min-h-11 items-center text-sm font-semibold text-brand-700 underline-offset-4 hover:underline"
    >
      Volver a la familia
    </Link>
  );
}

export function TutorNotFound({ familyId }: { familyId: string | undefined }) {
  return (
    <div className="max-w-2xl space-y-4">
      <BackToFamily familyId={familyId} />
      <section className="rounded-2xl border border-dashed border-line bg-white p-6">
        <h1 className="text-xl font-bold">{TUTOR_NOT_FOUND_TITLE}</h1>
        <p className="mt-2 text-sm leading-relaxed text-muted">{TUTOR_NOT_FOUND_TEXT}</p>
      </section>
    </div>
  );
}

const VERIFICATION_TEXT: Record<string, string> = {
  DECLARADO: 'Datos declarados, sin verificar',
  VERIFICADO: 'Identidad verificada',
  OBSERVADO: 'Datos observados',
};

function Row({ label, children, breakAll = false }: { label: string; children: string | null | undefined; breakAll?: boolean }) {
  return (
    <div className="flex min-w-0 flex-wrap gap-x-2">
      <dt className="font-semibold">{label}:</dt>
      <dd className={breakAll ? 'break-all' : 'break-words'}>{children || 'No informado'}</dd>
    </div>
  );
}

/**
 * Individual view. The only screen that shows the FULL DNI; the revision is validated but not shown (the edit screen captures its
 * own). The notice after a saved edit travels in the navigation state: a flag, never data.
 */
function Detail({ tutor, familyId, saved }: { tutor: TutorDetail; familyId: string; saved: boolean }) {
  const [notice, setNotice] = useState<string | null>(saved ? TUTOR_SAVED_NOTICE : null);
  return (
    <div className="max-w-3xl space-y-6">
      <BackToFamily familyId={familyId} />
      {notice ? (
        <Alert tone="success" role="status">
          {notice}
        </Alert>
      ) : null}
      <header className="space-y-2">
        <p className="text-sm font-semibold text-brand-700">Tutor</p>
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="min-w-0 break-words text-3xl font-extrabold tracking-tight">
            {tutor.nombre} {tutor.apellido}
          </h1>
          {tutor.activo ? null : (
            <span className="rounded-full border border-neutral-400 bg-neutral-100 px-2.5 py-0.5 text-xs font-bold text-neutral-800">Inactivo</span>
          )}
          {VERIFICATION_TEXT[tutor.verificacion ?? ''] ? (
            <span className="rounded-full border border-neutral-400 bg-neutral-100 px-2.5 py-0.5 text-xs font-bold text-neutral-800">
              {VERIFICATION_TEXT[tutor.verificacion ?? '']}
            </span>
          ) : null}
        </div>
        <p className="text-sm text-muted">Son datos personales: solo los ven los administradores.</p>
        {tutor.verificacion === 'OBSERVADO' && tutor.motivoObservacion ? <p className="text-sm text-muted">Motivo que ve la familia: {tutor.motivoObservacion}</p> : null}
        <Button asChild variant="outline">
          <Link to={`/admin/familias/${encodeURIComponent(familyId)}/tutores/${encodeURIComponent(tutor.id)}/editar`}>Editar tutor</Link>
        </Button>
      </header>
      <section aria-label="Datos del tutor" className="rounded-2xl border border-line bg-white p-5">
        <dl className="grid gap-3 text-sm sm:grid-cols-2">
          <Row label="Parentesco">{tutor.parentesco}</Row>
          <Row label="DNI">{tutor.dni}</Row>
          <Row label="Teléfono">{tutor.telefono}</Row>
          <Row label="Correo" breakAll>
            {tutor.email}
          </Row>
        </dl>
      </section>
      <TutorStatusControl tutor={tutor} familyId={familyId} onNotice={setNotice} />
      <AthleteDocumentsSection athleteId={tutor.id} kind="tutor" />
    </div>
  );
}

function TutorScreen() {
  const { id, tutorId } = useParams();
  const location = useLocation();
  const validFamily = isSafeFamilyId(id);
  const validTutor = isSafeTutorId(tutorId);
  const tutor = useTutorDetail(validTutor ? tutorId : '', validFamily && validTutor);

  if (!validFamily || !validTutor) return <TutorNotFound familyId={validFamily ? id : undefined} />;
  if (tutor.isError) {
    const error = ApiError.from(tutor.error);
    if (isNotFound(error)) return <TutorNotFound familyId={id} />;
    return (
      <div className="max-w-3xl space-y-4">
        <BackToFamily familyId={id} />
        <Alert tone="error">{tutorDetailErrorMessage(error)}</Alert>
        <Button variant="outline" onClick={() => void tutor.refetch()}>
          Reintentar
        </Button>
      </div>
    );
  }
  if (!tutor.data) {
    return (
      <div className="max-w-3xl space-y-4">
        <BackToFamily familyId={id} />
        <LoadingBlock label="Cargando el tutor…" />
      </div>
    );
  }
  // A tutor that exists but belongs to ANOTHER family is the same «not found» as one that does not exist: the URL never reveals it.
  if (tutor.data.familiaId !== id) return <TutorNotFound familyId={id} />;
  return <Detail tutor={tutor.data} familyId={id} saved={(location.state as { tutorUpdated?: unknown } | null)?.tutorUpdated === true} />;
}

/**
 * The FULL DNI is on screen here: it belongs to ONE identity. Keyed by identity, a session that now belongs to someone else
 * starts from nothing (a new query, no data of the previous administrator) instead of keeping what was already drawn.
 */
export function AdminTutorDetallePage() {
  const session = useSession();
  const identity = session.status === 'authenticated' ? session.user.id : '';
  return <TutorScreen key={identity} />;
}
