import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { ConfirmDialog } from '@/features/admin-temporadas/ConfirmDialog';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { formatDateTime } from '@/features/invitations/format';
import { ApiError } from '@/lib/api-error';
import { useChangeAccountStatus, useFamilyAccounts, useFamilyPendingInvitations } from './hooks';
import type { AccountAction, FamilyAccount } from './schemas';

const TEXT: Record<AccountAction, { button: string; title: (name: string) => string; explain: (name: string) => string; confirm: string; done: (name: string) => string }> = {
  desactivar: {
    button: 'Quitar acceso',
    title: (name) => `Quitar el acceso a ${name}`,
    explain: (name) =>
      `${name} no va a poder ingresar más. Si ahora tiene la sesión abierta, se cierra en su próxima acción. No se borra ningún dato de la familia y podés devolverle el acceso cuando quieras.`,
    confirm: 'Quitar acceso',
    done: (name) => `Listo: ${name} ya no puede ingresar.`,
  },
  reactivar: {
    button: 'Devolver acceso',
    title: (name) => `Devolver el acceso a ${name}`,
    explain: (name) =>
      `${name} va a poder volver a ingresar con su correo y su contraseña. Si tenía una sesión abierta de antes, va a tener que ingresar de nuevo.`,
    confirm: 'Devolver acceso',
    done: (name) => `Listo: ${name} puede volver a ingresar.`,
  },
};
const DISABLED = 'Quitar o devolver el acceso todavía no está habilitado en este entorno.';
const NOT_FOUND = 'Esa cuenta ya no está en esta familia. Actualizamos la lista.';
const UNCERTAIN = 'No pudimos confirmar el cambio. Podés volver a intentarlo: repetirlo no tiene efectos no deseados.';

function fullName(account: FamilyAccount) {
  return `${account.nombre} ${account.apellido}`.trim();
}

function AccountItem({ account, canManage, busy, onAction }: { account: FamilyAccount; canManage: boolean; busy: boolean; onAction: (account: FamilyAccount, target: HTMLElement | null) => void }) {
  const action: AccountAction = account.activa ? 'desactivar' : 'reactivar';
  return (
    <li className="flex flex-wrap items-start justify-between gap-3 px-4 py-4">
      <div className="min-w-0 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <p className="min-w-0 break-words font-semibold">{fullName(account)}</p>
          {account.activa ? (
            <span className="rounded-full border border-emerald-600 bg-emerald-50 px-2.5 py-0.5 text-xs font-bold text-emerald-800">Puede ingresar</span>
          ) : (
            <span className="rounded-full border border-neutral-400 bg-neutral-100 px-2.5 py-0.5 text-xs font-bold text-neutral-800">Sin acceso</span>
          )}
        </div>
        <p className="break-all text-sm text-muted">{account.email}</p>
        <p className="text-sm text-muted">{account.ultimoAccesoEn ? `Último ingreso: ${formatDateTime(account.ultimoAccesoEn)}` : 'Todavía no ingresó.'}</p>
      </div>
      {canManage ? (
        <Button type="button" variant="outline" disabled={busy} aria-label={`${TEXT[action].button} de ${fullName(account)}`} onClick={(event) => onAction(account, event.currentTarget)}>
          {TEXT[action].button}
        </Button>
      ) : null}
    </li>
  );
}

/**
 * «Cuentas de acceso» of a family (U21-BIS C0): who can sign in, and the ADMIN action to remove or give back that access. Each person has
 * their own credentials, so the action affects one account only. Pending invitations of the family are shown alongside.
 */
export function FamilyAccountsSection({ familyId }: { familyId: string }) {
  const accounts = useFamilyAccounts(familyId);
  const invitations = useFamilyPendingInvitations(familyId);
  const change = useChangeAccountStatus(familyId);
  const [open, setOpen] = useState<{ account: FamilyAccount; action: AccountAction } | null>(null);
  const [issue, setIssue] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const returnFocus = useRef<HTMLElement | null>(null);
  const sending = useRef(false);

  function openDialog(account: FamilyAccount, target: HTMLElement | null) {
    if (sending.current || change.isPending) return;
    returnFocus.current = target;
    setIssue(null);
    setNotice(null);
    setOpen({ account, action: account.activa ? 'desactivar' : 'reactivar' });
  }

  function confirm() {
    if (!open || sending.current || change.isPending) return;
    sending.current = true;
    const { account, action } = open;
    change.mutate({ accountId: account.id, action }, {
      onSuccess: () => {
        sending.current = false;
        setOpen(null);
        setNotice(TEXT[action].done(fullName(account)));
      },
      onError: (failure) => {
        sending.current = false;
        const error = ApiError.from(failure);
        if (error.code === 'GESTION_CUENTAS_DESHABILITADA') setIssue(DISABLED);
        else if (error.status === 404) {
          setIssue(NOT_FOUND);
          void accounts.refetch();
        } else setIssue(UNCERTAIN);
      },
    });
  }

  const pending = invitations.data?.contenido ?? [];
  return (
    <section aria-labelledby="accounts-title" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="accounts-title" className="text-lg font-bold">Cuentas de acceso</h2>
        <Button asChild variant="outline">
          <Link to="/admin/invitaciones">Invitar a una persona</Link>
        </Button>
      </div>
      <p className="text-sm text-muted">Cada persona de la familia ingresa con su propio correo y contraseña.</p>
      {notice ? <Alert tone="success" role="status">{notice}</Alert> : null}
      {accounts.isError && !accounts.data ? (
        <div className="space-y-2">
          <Alert tone="error">No pudimos cargar las cuentas de la familia.</Alert>
          <Button type="button" variant="outline" onClick={() => void accounts.refetch()}>Reintentar</Button>
        </div>
      ) : !accounts.data ? (
        <LoadingBlock label="Cargando las cuentas…" />
      ) : (
        <>
          {accounts.data.gestionHabilitada ? null : <p className="text-sm text-muted">{DISABLED}</p>}
          {accounts.data.cuentas.length === 0 ? (
            <div className="rounded-2xl border border-dashed border-line bg-white p-6 text-sm text-muted">
              <p>Todavía nadie de esta familia tiene cuenta. Para que alguien pueda ingresar, enviale una invitación.</p>
            </div>
          ) : (
            <ul aria-label="Cuentas de acceso" className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-white">
              {accounts.data.cuentas.map((account) => (
                <AccountItem key={account.id} account={account} canManage={accounts.data.gestionHabilitada} busy={change.isPending} onAction={openDialog} />
              ))}
            </ul>
          )}
        </>
      )}
      {pending.length > 0 ? (
        <div className="space-y-2">
          <h3 className="text-base font-bold">Invitaciones sin usar</h3>
          <ul aria-label="Invitaciones sin usar" className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-white text-sm">
            {pending.map((invitation) => (
              <li key={invitation.id} className="px-4 py-3">
                <p className="break-all font-semibold">{invitation.emailSugerido || 'Sin correo sugerido'}</p>
                <p className="text-muted">Vence: {formatDateTime(invitation.expiraEn)}</p>
              </li>
            ))}
          </ul>
          {(invitations.data?.totalElementos ?? 0) > pending.length ? (
            <p className="text-sm text-muted">
              Se muestran las {pending.length} más recientes de {invitations.data?.totalElementos}. <Link to="/admin/invitaciones" className="font-semibold text-brand-700 underline underline-offset-4">Ver todas</Link>
            </p>
          ) : null}
        </div>
      ) : null}
      {open ? (
        <ConfirmDialog
          title={TEXT[open.action].title(fullName(open.account))}
          busy={change.isPending}
          returnFocus={returnFocus.current}
          onCancel={() => {
            if (!sending.current) setOpen(null);
          }}
          description={<p>{TEXT[open.action].explain(fullName(open.account))}</p>}
        >
          {issue ? <Alert tone="error">{issue}</Alert> : null}
          {issue === DISABLED || issue === NOT_FOUND ? null : (
            <Button type="button" disabled={change.isPending} onClick={confirm}>
              {issue ? 'Volver a intentar' : TEXT[open.action].confirm}
            </Button>
          )}
          {change.isPending ? <p role="status">Esperando respuesta…</p> : null}
        </ConfirmDialog>
      ) : null}
    </section>
  );
}
