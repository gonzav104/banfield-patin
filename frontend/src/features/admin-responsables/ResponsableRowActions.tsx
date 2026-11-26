import { useId, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { ConfirmDialog } from '@/features/admin-temporadas/ConfirmDialog';
import { invalidateResponsableQueries } from '@/features/responsables/keys';
import { TIPO_OPTIONS, tipoFromValue, tipoLabel, type ResponsableTipo } from '@/features/responsables/tipo';
import { ApiError } from '@/lib/api-error';
import { useResponsableWritePending, useRevokeResponsable, useUpdateResponsableTipo } from './hooks';
import {
  EDIT_BUTTON, EDIT_CONFIRM, EDIT_DONE, EDIT_LABEL, EDIT_TITLE, EDIT_UNCHANGED, REVOKE_BUTTON, REVOKE_CONFIRM, REVOKE_DONE, REVOKE_EXPLAIN, REVOKE_TITLE,
  WRITE_BUSY, WRITE_CONSULTED, WRITE_GONE, WRITE_REJECTED, WRITE_STALE, WRITE_UNCERTAIN,
} from './messages';
import type { Responsable } from './schemas';

type Mode = 'edit' | 'revoke';
type Issue = { kind: 'stale' | 'gone' | 'uncertain' | 'rejected'; message: string };

/** Why a write failed, in terms of what to do next. Nothing is ever retried automatically: a lost answer is verified by consulting, not by repeating. */
function classify(error: ApiError): Issue {
  if (error.code === 'EDICION_DESACTUALIZADA') return { kind: 'stale', message: WRITE_STALE };
  if (error.status === 404 || error.code === 'ESTADO_INCOMPATIBLE') return { kind: 'gone', message: WRITE_GONE };
  if (error.status === 409 || error.status === 400 || error.kind === 'forbidden' || error.kind === 'csrf' || error.kind === 'validation') return { kind: 'rejected', message: WRITE_REJECTED };
  return { kind: 'uncertain', message: WRITE_UNCERTAIN };
}

/**
 * «Cambiar tipo» and «Revocar» of ONE open responsibility. The revision belongs to the confirmation: it is the one of the row when the
 * dialog opened, whatever the live list does in between. Revoking is irreversible and says so. A stale or vanished responsibility refreshes
 * the lists and leaves nothing applied; a lost answer is verified by consulting, never by repeating the write.
 */
export function ResponsableRowActions({ athleteId, row, disabled, onDone }: { athleteId: string; row: Responsable; disabled: boolean; onDone: (notice: string) => void }) {
  const client = useQueryClient();
  const pending = useResponsableWritePending(athleteId);
  const update = useUpdateResponsableTipo(athleteId);
  const revoke = useRevokeResponsable(athleteId);
  const [mode, setMode] = useState<Mode | null>(null);
  const [observed, setObserved] = useState({ version: row.version, tipo: row.tipo });
  const [tipo, setTipo] = useState<ResponsableTipo | null>(row.tipo);
  const [issue, setIssue] = useState<Issue | null>(null);
  const returnFocus = useRef<HTMLElement | null>(null);
  const sending = useRef(false);
  const tipoId = useId();
  const busy = update.isPending || revoke.isPending || pending;
  const name = `${row.nombreTutor} ${row.apellidoTutor}`;

  function openDialog(next: Mode) {
    if (sending.current || busy || disabled) return;
    returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setObserved({ version: row.version, tipo: row.tipo });
    setTipo(row.tipo);
    setIssue(null);
    setMode(next);
  }

  function failed(failure: unknown) {
    sending.current = false;
    const next = classify(ApiError.from(failure));
    if (next.kind === 'stale' || next.kind === 'gone') void invalidateResponsableQueries(client);
    setIssue(next);
  }

  function succeeded(notice: string) {
    sending.current = false;
    setMode(null);
    onDone(notice);
  }

  function submit() {
    if (sending.current || busy || !mode) return;
    sending.current = true;
    if (mode === 'edit') update.mutate({ athleteId, id: row.id, version: observed.version, tipo }, { onSuccess: () => succeeded(EDIT_DONE), onError: failed });
    else revoke.mutate({ athleteId, id: row.id, version: observed.version }, { onSuccess: () => succeeded(REVOKE_DONE), onError: failed });
  }

  async function consult() {
    await invalidateResponsableQueries(client);
    setMode(null);
    onDone(WRITE_CONSULTED);
  }

  const unchanged = mode === 'edit' && tipo === observed.tipo;
  return (
    <>
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="outline" disabled={disabled || busy} aria-label={`${EDIT_BUTTON}: ${name}`} onClick={() => openDialog('edit')}>{EDIT_BUTTON}</Button>
        <Button type="button" variant="outline" disabled={disabled || busy} aria-label={`${REVOKE_BUTTON}: ${name}`} onClick={() => openDialog('revoke')}>{REVOKE_BUTTON}</Button>
      </div>
      {mode ? (
        <ConfirmDialog title={mode === 'edit' ? EDIT_TITLE : REVOKE_TITLE} busy={busy} returnFocus={returnFocus.current}
          onCancel={() => { if (!sending.current) setMode(null); }}
          description={<><p className="break-words font-semibold">{name}</p>{mode === 'revoke' ? <p>{REVOKE_EXPLAIN}</p> : <p className="text-sm text-muted">Tipo actual: {tipoLabel(observed.tipo)}.</p>}</>}>
          {issue ? <Alert tone="error">{issue.message}</Alert> : null}
          {!issue && mode === 'edit' ? (
            <div className="space-y-1">
              <label htmlFor={tipoId} className="block text-sm font-semibold">{EDIT_LABEL}</label>
              <select id={tipoId} className="min-h-11 w-full rounded-lg border border-line bg-white px-3" disabled={busy} value={tipo ?? ''} onChange={(event) => setTipo(tipoFromValue(event.target.value))}>
                {TIPO_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
              {unchanged ? <p className="text-sm text-muted">{EDIT_UNCHANGED}</p> : null}
            </div>
          ) : null}
          {!issue ? <Button type="button" disabled={busy || unchanged} onClick={submit}>{mode === 'edit' ? EDIT_CONFIRM : REVOKE_CONFIRM}</Button> : null}
          {issue?.kind === 'uncertain' ? <Button type="button" disabled={busy} onClick={() => void consult()}>Consultar responsables vigentes</Button> : null}
          {busy ? <p role="status">Esperando respuesta…</p> : null}
          {pending && !busy ? <p role="status">{WRITE_BUSY}</p> : null}
        </ConfirmDialog>
      ) : null}
    </>
  );
}
