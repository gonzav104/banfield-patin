import { useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { useSession } from '@/features/auth/session';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { ApiError } from '@/lib/api-error';
import { isSafeFamilyId } from './api';
import { useFamilyDetail } from './hooks';
import { detailErrorMessage, isNotFound, NOT_FOUND_TEXT, NOT_FOUND_TITLE } from './messages';
import { FamilyNameEditor } from './FamilyNameEditor';
import { FamilyStatusBadge } from './FamilyStatusBadge';
import { FamilyStatusControl } from './FamilyStatusControl';
import type { FamilyDetail, FamilyTutor } from './schemas';

function BackLink() {
  return (
    <Link to="/admin/familias" className="inline-flex min-h-11 items-center text-sm font-semibold text-brand-700 underline-offset-4 hover:underline">
      Volver al listado
    </Link>
  );
}

function NotFound() {
  return (
    <div className="max-w-2xl space-y-4">
      <BackLink />
      <section className="rounded-2xl border border-dashed border-line bg-white p-6">
        <h1 className="text-xl font-bold">{NOT_FOUND_TITLE}</h1>
        <p className="mt-2 text-sm leading-relaxed text-muted">{NOT_FOUND_TEXT}</p>
      </section>
    </div>
  );
}

function TutorItem({ tutor }: { tutor: FamilyTutor }) {
  return (
    <li className="space-y-1 px-4 py-4">
      <div className="flex flex-wrap items-center gap-2">
        <p className="min-w-0 break-words font-semibold">
          {tutor.nombre} {tutor.apellido}
        </p>
        {tutor.activo ? null : (
          <span className="rounded-full border border-neutral-400 bg-neutral-100 px-2.5 py-0.5 text-xs font-bold text-neutral-800">Inactivo</span>
        )}
      </div>
      <dl className="grid gap-x-6 gap-y-1 text-sm text-muted sm:grid-cols-2">
        <div className="flex gap-1">
          <dt>Parentesco:</dt>
          <dd>{tutor.parentesco || 'No informado'}</dd>
        </div>
        <div className="flex gap-1">
          <dt>DNI:</dt>
          <dd>{tutor.dniEnmascarado || 'No informado'}</dd>
        </div>
        <div className="flex gap-1">
          <dt>Teléfono:</dt>
          <dd>{tutor.telefono || 'No informado'}</dd>
        </div>
        <div className="flex min-w-0 gap-1">
          <dt>Correo:</dt>
          <dd className="break-all">{tutor.email || 'No informado'}</dd>
        </div>
      </dl>
    </li>
  );
}

function Detail({ family, created, reload }: { family: FamilyDetail; created: boolean; reload: () => Promise<ApiError | null> }) {
  const [editing, setEditing] = useState(false);
  const [notice, setNotice] = useState<string | null>(created ? 'La familia se creó correctamente. Todavía no tiene tutores ni cuenta de acceso.' : null);

  return (
    <div className="max-w-3xl space-y-6">
      <BackLink />
      <header className="space-y-2">
        <p className="text-sm font-semibold text-brand-700">Familia</p>
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="min-w-0 break-words text-3xl font-extrabold tracking-tight">{family.nombreReferencia}</h1>
          <FamilyStatusBadge active={family.activa} />
        </div>
        {editing ? null : (
          <Button
            variant="outline"
            onClick={() => {
              setNotice(null);
              setEditing(true);
            }}
          >
            Editar nombre
          </Button>
        )}
      </header>

      {notice ? (
        <Alert tone="success" role="status">
          {notice}
        </Alert>
      ) : null}

      {editing ? (
        <FamilyNameEditor
          family={family}
          reload={reload}
          onClose={(message) => {
            setEditing(false);
            if (message) setNotice(message);
          }}
        />
      ) : null}

      <FamilyStatusControl
        family={family}
        disabled={editing}
        reload={reload}
        onNotice={(message) => setNotice(message)}
      />

      <section aria-labelledby="tutors-title" className="space-y-3">
        <h2 id="tutors-title" className="text-lg font-bold">
          Tutores
        </h2>
        {family.tutores.length === 0 ? (
          <div className="space-y-3 rounded-2xl border border-dashed border-line bg-white p-6 text-sm text-muted">
            <p>Esta familia todavía no tiene tutores registrados.</p>
            <p>
              El acceso de las familias se gestiona con invitaciones. <Link to="/admin/invitaciones" className="font-semibold text-brand-700 underline underline-offset-4">Ir a Invitaciones</Link>
            </p>
          </div>
        ) : (
          <ul aria-label="Tutores" className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-white">
            {family.tutores.map((tutor) => (
              <TutorItem key={tutor.id} tutor={tutor} />
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

export function AdminFamiliaDetallePage() {
  const { id } = useParams();
  const location = useLocation();
  const session = useSession();
  const valid = isSafeFamilyId(id);
  const family = useFamilyDetail(valid ? id : '', valid);
  const created = (location.state as { created?: unknown } | null)?.created === true;
  // Whatever the screen keeps in memory (an edit draft, an open confirmation) belongs to ONE identity: another one starts clean.
  const identity = session.status === 'authenticated' ? session.user.id : '';

  if (!valid) return <NotFound />;
  if (family.isError) {
    const error = ApiError.from(family.error);
    if (isNotFound(error)) return <NotFound />;
    // A failed RELOAD must not take the screen (and an unsaved draft) away: whoever asked for it shows the error.
    if (!family.data) {
      return (
        <div className="max-w-3xl space-y-4">
          <BackLink />
          <Alert tone="error">{detailErrorMessage(error)}</Alert>
          <Button variant="outline" onClick={() => void family.refetch()}>
            Reintentar
          </Button>
        </div>
      );
    }
  }
  if (!family.data) {
    return (
      <div className="max-w-3xl space-y-4">
        <BackLink />
        <LoadingBlock label="Cargando la familia…" />
      </div>
    );
  }
  const reload = async (): Promise<ApiError | null> => {
    const result = await family.refetch();
    return result.isError ? ApiError.from(result.error) : null;
  };
  return <Detail key={identity} family={family.data} created={created} reload={reload} />;
}
