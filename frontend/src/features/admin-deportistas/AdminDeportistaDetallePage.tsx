import { useState, type ReactNode } from 'react';
import { useLocation, useParams } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { useSession } from '@/features/auth/session';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { ApiError } from '@/lib/api-error';
import { isSafeAthleteId } from './api';
import { AthleteNotFound, BackToListLink } from './AthleteNotFound';
import { AthleteStatusBadge } from './AthleteStatusBadge';
import { useAthleteDetail } from './hooks';
import { CREATED_NOTICE, detailErrorMessage, isNotFound, PRIVATE_DATA_NOTE } from './messages';
import type { AthleteDetail } from './schemas';

function Row({ label, children, breakAll = false }: { label: string; children: string | null | undefined; breakAll?: boolean }) {
  const value = children?.trim();
  return (
    <div className="flex min-w-0 flex-wrap gap-x-2">
      <dt className="font-semibold">{label}:</dt>
      <dd className={breakAll ? 'break-all' : 'break-words'}>{value || 'No informado'}</dd>
    </div>
  );
}

/** «1998-04-09» -> «09/04/1998», without going through Date (no time zone can move the day). */
export function formatBirthDate(iso: string | null | undefined): string | null {
  const match = iso ? /^([0-9]{4})-([0-9]{2})-([0-9]{2})$/.exec(iso) : null;
  return match ? `${match[3]}/${match[2]}/${match[1]}` : (iso ?? null);
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="rounded-2xl border border-line bg-white p-5">
      <h2 className="mb-3 text-base font-bold">{title}</h2>
      <dl className="grid gap-3 text-sm sm:grid-cols-2">{children}</dl>
    </section>
  );
}

/**
 * Individual view. The only screen that shows the FULL DNI and CUIL; the revision is validated but not shown (the edit unit
 * captures its own). The notice after a creation travels in the navigation state: a flag, never data.
 */
function Detail({ athlete, created }: { athlete: AthleteDetail; created: boolean }) {
  const [notice] = useState<string | null>(created ? CREATED_NOTICE : null);
  return (
    <div className="max-w-3xl space-y-6">
      <BackToListLink />
      {notice ? (
        <Alert tone="success" role="status">
          {notice}
        </Alert>
      ) : null}
      <header className="space-y-2">
        <p className="text-sm font-semibold text-brand-700">Deportista</p>
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="min-w-0 break-words text-3xl font-extrabold tracking-tight">
            {athlete.nombre} {athlete.apellido}
          </h1>
          <AthleteStatusBadge active={athlete.activo} />
        </div>
        <p className="text-sm text-muted">{PRIVATE_DATA_NOTE}</p>
      </header>
      <Section title="Identificación">
        <Row label="DNI">{athlete.dni}</Row>
        <Row label="CUIL">{athlete.cuil}</Row>
        <Row label="Fecha de nacimiento">{formatBirthDate(athlete.fechaNacimiento)}</Row>
        <Row label="Nacionalidad">{athlete.nacionalidad}</Row>
      </Section>
      <Section title="Contacto">
        <Row label="Teléfono">{athlete.telefonoContacto}</Row>
        <Row label="Correo federativo" breakAll>
          {athlete.emailFederativo}
        </Row>
      </Section>
      <Section title="Domicilio">
        <Row label="Domicilio">{athlete.domicilio}</Row>
        <Row label="Otros datos del domicilio">{athlete.otrosDatosDomicilio}</Row>
        <Row label="Localidad">{athlete.localidad}</Row>
        <Row label="Partido">{athlete.partido}</Row>
        <Row label="Código postal">{athlete.codigoPostal}</Row>
      </Section>
    </div>
  );
}

function AthleteScreen() {
  const { id } = useParams();
  const location = useLocation();
  const valid = isSafeAthleteId(id);
  const athlete = useAthleteDetail(valid ? id : '', valid);

  if (!valid) return <AthleteNotFound />;
  if (athlete.isError) {
    const error = ApiError.from(athlete.error);
    if (isNotFound(error)) return <AthleteNotFound />;
    return (
      <div className="max-w-3xl space-y-4">
        <BackToListLink />
        <Alert tone="error">{detailErrorMessage(error)}</Alert>
        <Button variant="outline" onClick={() => void athlete.refetch()}>
          Reintentar
        </Button>
      </div>
    );
  }
  if (!athlete.data) {
    return (
      <div className="max-w-3xl space-y-4">
        <BackToListLink />
        <LoadingBlock label="Cargando el deportista…" />
      </div>
    );
  }
  return <Detail key={athlete.data.id} athlete={athlete.data} created={(location.state as { athleteCreated?: unknown } | null)?.athleteCreated === true} />;
}

/**
 * The FULL DNI and CUIL are on screen here: they belong to ONE identity AND ONE resource. Keyed by identity and by id, a session
 * that now belongs to someone else, or a different athlete, starts from nothing (a new query, no data of the previous one)
 * instead of keeping what was already drawn.
 */
export function AdminDeportistaDetallePage() {
  const session = useSession();
  const { id } = useParams();
  const identity = session.status === 'authenticated' ? session.user.id : '';
  return <AthleteScreen key={`${identity}:${id ?? ''}`} />;
}
