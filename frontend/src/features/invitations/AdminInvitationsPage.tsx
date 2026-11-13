import { useState } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { commonErrorMessage } from '@/features/auth/messages';
import { ApiError } from '@/lib/api-error';
import { cn } from '@/lib/utils';
import { PAGE_SIZE } from './api';
import { CreateInvitationForm } from './CreateInvitationForm';
import { formatDateTime, STATUS_LABEL } from './format';
import { useInvitations, useRevokeInvitation } from './hooks';
import { InvitationLink } from './InvitationLink';
import { revokeErrorMessage } from './messages';
import type { CreatedInvitation, Invitation, InvitationStatus } from './schemas';

const statusStyle: Record<InvitationStatus, string> = {
  PENDIENTE: 'border-brand-700 bg-brand-50 text-brand-900',
  USADA: 'border-ink bg-ink text-white',
  REVOCADA: 'border-red-300 bg-red-50 text-red-900',
  EXPIRADA: 'border-amber-300 bg-amber-50 text-amber-950',
};

function StatusBadge({ status }: { status: InvitationStatus }) {
  return (
    <span className={cn('inline-block rounded-full border px-2.5 py-0.5 text-xs font-bold', statusStyle[status])}>
      {STATUS_LABEL[status]}
    </span>
  );
}

function InvitationRow({ invitation }: { invitation: Invitation }) {
  const revoke = useRevokeInvitation();
  const [confirming, setConfirming] = useState(false);

  return (
    <li className="space-y-3 px-4 py-4">
      <div className="grid gap-3 md:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)_auto] md:items-center">
        <div className="min-w-0">
          <p className="break-words font-semibold">{invitation.familia.nombreReferencia}</p>
          <p className="break-all text-sm text-muted">{invitation.emailSugerido ?? 'Sin correo sugerido'}</p>
        </div>
        <dl className="text-sm text-muted">
          <div className="flex gap-1">
            <dt>Creada:</dt>
            <dd>{formatDateTime(invitation.creadoEn)}</dd>
          </div>
          <div className="flex gap-1">
            <dt>{invitation.estado === 'PENDIENTE' ? 'Vence:' : invitation.estado === 'EXPIRADA' ? 'Venció:' : 'Vigencia hasta:'}</dt>
            <dd>{formatDateTime(invitation.expiraEn)}</dd>
          </div>
          {invitation.usadoEn ? (
            <div className="flex gap-1">
              <dt>Usada:</dt>
              <dd>{formatDateTime(invitation.usadoEn)}</dd>
            </div>
          ) : null}
          {invitation.revocadaEn ? (
            <div className="flex gap-1">
              <dt>Revocada:</dt>
              <dd>{formatDateTime(invitation.revocadaEn)}</dd>
            </div>
          ) : null}
        </dl>
        <div className="flex items-center gap-3 md:justify-end">
          <StatusBadge status={invitation.estado} />
          {invitation.estado === 'PENDIENTE' && !confirming ? (
            <Button
              variant="outline"
              className="min-h-10"
              aria-label={`Revocar invitación de ${invitation.familia.nombreReferencia}`}
              onClick={() => {
                revoke.reset();
                setConfirming(true);
              }}
            >
              Revocar
            </Button>
          ) : null}
        </div>
      </div>

      {confirming ? (
        <div role="group" aria-label="Confirmar revocación" className="space-y-3 rounded-lg border border-red-300 bg-red-50 p-4 text-sm">
          <p>
            ¿Revocar la invitación de <strong>{invitation.familia.nombreReferencia}</strong>? El enlace dejará de funcionar y
            no se puede deshacer.
          </p>
          <div className="flex flex-col gap-2 sm:flex-row">
            <Button
              variant="dark"
              disabled={revoke.isPending}
              onClick={() => revoke.mutate(invitation.id, { onSettled: () => setConfirming(false) })}
            >
              {revoke.isPending ? 'Revocando…' : 'Sí, revocar'}
            </Button>
            <Button variant="outline" disabled={revoke.isPending} onClick={() => setConfirming(false)}>
              Cancelar
            </Button>
          </div>
        </div>
      ) : null}
      {revoke.error ? <Alert tone="error">{revokeErrorMessage(revoke.error)}</Alert> : null}
    </li>
  );
}

const FILTERS: { value: InvitationStatus | ''; label: string }[] = [
  { value: '', label: 'Todas' },
  { value: 'PENDIENTE', label: 'Pendientes' },
  { value: 'USADA', label: 'Usadas' },
  { value: 'REVOCADA', label: 'Revocadas' },
  { value: 'EXPIRADA', label: 'Vencidas' },
];

export function AdminInvitationsPage() {
  const [status, setStatus] = useState<InvitationStatus | ''>('');
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<CreatedInvitation | null>(null);
  const invitations = useInvitations(status || undefined, page);

  const data = invitations.data;
  const totalPages = data ? Math.max(1, Math.ceil(data.totalElementos / PAGE_SIZE)) : 1;

  return (
    <div className="max-w-4xl space-y-6">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-sm font-semibold text-brand-700">Familias</p>
          <h1 className="mt-1 text-3xl font-extrabold tracking-tight">Invitaciones</h1>
          <p className="mt-2 text-muted">Las familias se registran solo con un enlace de invitación emitido por un administrador.</p>
        </div>
        {!creating && !created ? <Button onClick={() => setCreating(true)}>Nueva invitación</Button> : null}
      </header>

      {created ? (
        <InvitationLink
          created={created}
          onDone={() => {
            setCreated(null);
            setCreating(false);
          }}
        />
      ) : creating ? (
        <CreateInvitationForm
          onCancel={() => setCreating(false)}
          onCreated={(result) => {
            setCreated(result);
            setCreating(false);
            setPage(0);
          }}
        />
      ) : null}

      <section aria-labelledby="list-title" className="space-y-3">
        <div className="flex items-end justify-between gap-3">
          <h2 id="list-title" className="text-lg font-bold">Listado</h2>
          <div>
            <label htmlFor="status-filter" className="mb-1 block text-sm font-semibold">
              Estado
            </label>
            <select
              id="status-filter"
              value={status}
              onChange={(event) => {
                setStatus(event.target.value as InvitationStatus | '');
                setPage(0);
              }}
              className="min-h-11 rounded-lg border border-neutral-400 bg-white px-3 text-sm"
            >
              {FILTERS.map((filter) => (
                <option key={filter.value} value={filter.value}>
                  {filter.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        {invitations.isError && !data ? (
          <div className="space-y-3">
            <Alert tone="error">
              {commonErrorMessage(ApiError.from(invitations.error)) ??
                (ApiError.from(invitations.error).kind === 'forbidden'
                  ? 'No tenés permiso para ver las invitaciones.'
                  : 'No pudimos cargar las invitaciones.')}
            </Alert>
            <Button variant="outline" onClick={() => void invitations.refetch()}>
              Reintentar
            </Button>
          </div>
        ) : invitations.isPending ? (
          <p role="status" className="rounded-2xl border border-line bg-white p-6 text-sm text-muted">
            Cargando invitaciones…
          </p>
        ) : data && data.contenido.length === 0 ? (
          <p className="rounded-2xl border border-dashed border-line bg-white p-6 text-sm text-muted">
            {status
              ? 'No hay invitaciones con ese estado.'
              : 'Todavía no creaste invitaciones. Usá «Nueva invitación» para invitar a una familia.'}
          </p>
        ) : data ? (
          <>
            {invitations.isError ? (
              <Alert tone="warning">No pudimos actualizar el listado. Mostramos la última información disponible.</Alert>
            ) : null}
            <ul className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-white" aria-busy={invitations.isFetching}>
              {data.contenido.map((invitation) => (
                <InvitationRow key={invitation.id} invitation={invitation} />
              ))}
            </ul>
            <nav aria-label="Paginación" className="flex items-center justify-between gap-3 text-sm">
              <Button variant="outline" disabled={page === 0} onClick={() => setPage((value) => Math.max(0, value - 1))}>
                Anterior
              </Button>
              <p className="text-muted">
                Página {page + 1} de {totalPages} · {data.totalElementos} en total
              </p>
              <Button variant="outline" disabled={page + 1 >= totalPages} onClick={() => setPage((value) => value + 1)}>
                Siguiente
              </Button>
            </nav>
          </>
        ) : null}
      </section>
    </div>
  );
}
