import { useEffect, useId, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { ConfirmDialog } from '@/features/admin-temporadas/ConfirmDialog';
import { linkIdentity } from '@/features/admin-vinculos/hooks';
import { invalidateResponsableQueries } from '@/features/responsables/keys';
import { TIPO_DISCLAIMER, TIPO_OPTIONS, tipoFromValue, tipoLabel, type ResponsableTipo } from '@/features/responsables/tipo';
import { ApiError } from '@/lib/api-error';
import { listOpenResponsables, type Candidate, type Observation } from './api';
import { forgetAttempt, pendingAttempt, rememberAttempt } from './attempts';
import { useCandidates, useCreateResponsable, useObserveCandidate } from './hooks';
import {
  ADD_BACK, ADD_BUTTON, ADD_CANDIDATES_EMPTY, ADD_CANDIDATES_INTRO, ADD_CANDIDATES_LEGEND, ADD_CANDIDATES_LOADING, ADD_CONFIRM, ADD_CONFLICT,
  ADD_CONSULT, ADD_CONSULTED, ADD_PENDING_BLOCK, ADD_CONTINUE, ADD_DISABLED, ADD_DONE, ADD_DONE_REPLAY, ADD_OBSERVING, ADD_REJECTED, ADD_RETRY_SAME, ADD_SENDING,
  ADD_STALE, ADD_TIPO_HELP, ADD_TIPO_LABEL, ADD_TITLE, ADD_UNCERTAIN, responsablesErrorMessage,
} from './messages';

type Request = { observation: Observation; tipo: ResponsableTipo | null; key: string };
type Issue = 'stale' | 'rejected' | 'disabled' | 'conflict' | 'uncertain';
type Step = { name: 'choose' } | { name: 'confirm'; request: Request } | { name: 'issue'; kind: Issue; message: string; request: Request | null };

/** What an error means for the alta: whether anything may have been written, and therefore what to do with the key. */
function classify(error: ApiError): { kind: Issue; message: string } {
  if (error.status === 503 && error.code === 'ALTA_RESPONSABLES_DESHABILITADA') return { kind: 'disabled', message: ADD_DISABLED };
  if (error.code === 'EDICION_DESACTUALIZADA') return { kind: 'stale', message: ADD_STALE };
  if (error.code === 'CONFLICTO_CONCURRENCIA') return { kind: 'conflict', message: ADD_CONFLICT };
  if (error.status === 404 || error.status === 409 || error.status === 400 || error.kind === 'forbidden' || error.kind === 'csrf' || error.kind === 'validation' || error.kind === 'conflict') {
    return { kind: 'rejected', message: ADD_REJECTED };
  }
  return { kind: 'uncertain', message: ADD_UNCERTAIN }; // network, 5xx, malformed 2xx: the server may have created it
}

/**
 * «Agregar responsable»: choose an eligible tutor, observe the current revisions, optionally declare a type and confirm. The
 * `Idempotency-Key` belongs to the CONFIRMATION: it is born when the confirmation opens, survives every retry of that request and an uncertain
 * outcome (even if the dialog is closed: it is kept in memory per identity and athlete), and is dropped only when the answer is definitive.
 * A new confirmation, after a definitive answer, always gets a new key.
 */
export function AltaResponsable({ athleteId, athleteActive, disabled, onDone }: { athleteId: string; athleteActive: boolean; disabled: boolean; onDone: (notice: string) => void }) {
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const [step, setStep] = useState<Step>({ name: 'choose' });
  const [selected, setSelected] = useState('');
  const [consultMessage, setConsultMessage] = useState('');
  const returnFocus = useRef<HTMLElement | null>(null);
  const sending = useRef(false);
  const alive = useRef(true);
  const tipoId = useId();
  const candidates = useCandidates(athleteId, open && step.name === 'choose');
  const observe = useObserveCandidate(athleteId);
  const create = useCreateResponsable(athleteId);
  const busy = create.isPending || observe.isPending;
  const pendingBlocked = pendingAttempt(linkIdentity(client), athleteId) !== undefined;

  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);

  function openDialog() {
    returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const attempt = pendingAttempt(linkIdentity(client), athleteId);
    setConsultMessage('');
    setSelected('');
    setStep(attempt ? { name: 'issue', kind: 'uncertain', message: ADD_UNCERTAIN, request: { observation: attempt.observation, tipo: attempt.tipo, key: attempt.key } } : { name: 'choose' });
    setOpen(true);
  }

  function close() {
    if (sending.current || busy) return;
    setOpen(false);
  }

  function choose(candidate: Candidate) {
    if (observe.isPending) return;
    observe.mutate(candidate, {
      onSuccess: (observation) => setStep({ name: 'confirm', request: { observation, tipo: null, key: crypto.randomUUID() } }),
      onError: (failure) => {
        const error = ApiError.from(failure);
        const known = ['DEPORTISTA_INACTIVO', 'FAMILIA_INACTIVA', 'ESTADO_INCOMPATIBLE', 'RESPONSABLE_YA_VIGENTE'].includes(error.code ?? '');
        setStep({ name: 'issue', kind: 'stale', request: null, message: known ? ADD_STALE : responsablesErrorMessage(error) });
      },
    });
  }

  function send(request: Request) {
    if (sending.current || create.isPending) return; // a second click while one is in flight must never write twice
    sending.current = true;
    const identity = linkIdentity(client);
    create.mutate(request, {
      onSuccess: (row) => {
        sending.current = false;
        forgetAttempt(identity, athleteId);
        setOpen(false);
        onDone(row.cerradoEn === null ? ADD_DONE : ADD_DONE_REPLAY);
      },
      onError: (failure) => {
        sending.current = false;
        const { kind, message } = classify(ApiError.from(failure));
        if (kind === 'uncertain') rememberAttempt(identity, { key: request.key, athleteId, tipo: request.tipo, observation: request.observation });
        else if (kind !== 'conflict') forgetAttempt(identity, athleteId); // a definitive answer: the key is spent
        setStep({ name: 'issue', kind, message, request: kind === 'uncertain' || kind === 'conflict' ? request : null });
      },
    });
  }

  async function consult(request: Request) {
    if (sending.current) return;
    sending.current = true;
    try {
      const vigentes = await listOpenResponsables(athleteId);
      await invalidateResponsableQueries(client);
      if (!alive.current) return;
      if (vigentes.some((r) => r.tutorId === request.observation.candidate.tutorId)) {
        forgetAttempt(linkIdentity(client), athleteId);
        setOpen(false);
        onDone(ADD_CONSULTED);
      } else {
        setConsultMessage('El tutor todavía no figura entre los vigentes. Podés reintentar la misma solicitud.');
      }
    } catch {
      if (alive.current) setConsultMessage('No pudimos consultar los responsables vigentes. No se reintentó la solicitud.');
    } finally {
      sending.current = false;
    }
  }

  const chosen = candidates.data?.find((c) => c.tutorId === selected);
  return (
    <>
      <Button type="button" disabled={disabled || !athleteActive || busy} onClick={openDialog}>{ADD_BUTTON}</Button>
      {pendingBlocked && !open ? <p role="status" className="text-sm text-muted">{ADD_PENDING_BLOCK} Abrí «{ADD_BUTTON}» para resolverla.</p> : null}
      {open ? (
        <ConfirmDialog title={ADD_TITLE} busy={busy} returnFocus={returnFocus.current} onCancel={close}
          description={<p className="text-sm text-muted">{TIPO_DISCLAIMER}</p>}>
          {step.name === 'choose' ? (
            <div className="space-y-4">
              {candidates.isPending ? <p role="status">{ADD_CANDIDATES_LOADING}</p> : null}
              {candidates.isError ? (
                <div className="space-y-3">
                  <Alert tone="error">{responsablesErrorMessage(ApiError.from(candidates.error))}</Alert>
                  <Button type="button" variant="outline" onClick={() => void candidates.refetch()}>Reintentar</Button>
                </div>
              ) : null}
              {candidates.data && candidates.data.length === 0 ? <p className="text-sm">{ADD_CANDIDATES_EMPTY}</p> : null}
              {candidates.data && candidates.data.length > 0 ? (
                <fieldset className="space-y-2" disabled={busy}>
                  <legend className="text-sm font-semibold">{ADD_CANDIDATES_LEGEND}</legend>
                  <p className="text-sm text-muted">{ADD_CANDIDATES_INTRO}</p>
                  {candidates.data.map((c) => (
                    <label key={c.tutorId} className="flex min-h-11 cursor-pointer items-start gap-3 rounded-lg border border-line p-3">
                      <input type="radio" name="candidato" value={c.tutorId} checked={selected === c.tutorId} onChange={() => setSelected(c.tutorId)} className="mt-1" />
                      <span className="min-w-0 break-words">
                        <span className="block font-semibold">{c.nombre} {c.apellido}</span>
                        <span className="block text-sm text-muted">{c.familiaNombre}{c.parentesco ? ` · ${c.parentesco}` : ''}{c.dniEnmascarado ? ` · DNI ${c.dniEnmascarado}` : ''}</span>
                      </span>
                    </label>
                  ))}
                </fieldset>
              ) : null}
              {observe.isPending ? <p role="status">{ADD_OBSERVING}</p> : null}
              {candidates.data && candidates.data.length > 0 ? (
                <Button type="button" disabled={!chosen || busy} onClick={() => chosen && choose(chosen)}>{ADD_CONTINUE}</Button>
              ) : null}
            </div>
          ) : null}
          {step.name === 'confirm' ? (
            <div className="space-y-4">
              <p className="break-words font-semibold">{step.request.observation.candidate.nombre} {step.request.observation.candidate.apellido}</p>
              <div className="space-y-1">
                <label htmlFor={tipoId} className="block text-sm font-semibold">{ADD_TIPO_LABEL}</label>
                <select id={tipoId} className="min-h-11 w-full rounded-lg border border-line bg-white px-3" disabled={busy} value={step.request.tipo ?? ''}
                  onChange={(event) => setStep({ name: 'confirm', request: { ...step.request, tipo: tipoFromValue(event.target.value) } })}>
                  {TIPO_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
                <p className="text-sm text-muted">{ADD_TIPO_HELP}</p>
              </div>
              <Button type="button" disabled={busy} onClick={() => send(step.request)}>{ADD_CONFIRM}</Button>
              {create.isPending ? <p role="status">{ADD_SENDING}</p> : null}
            </div>
          ) : null}
          {step.name === 'issue' ? (
            <div className="space-y-4">
              <Alert tone={step.kind === 'disabled' ? 'info' : 'error'}>{step.message}</Alert>
              {step.request ? <p className="break-words text-sm">{step.request.observation.candidate.nombre} {step.request.observation.candidate.apellido} · {tipoLabel(step.request.tipo)}</p> : null}
              {consultMessage ? <p role="status" className="text-sm">{consultMessage}</p> : null}
              {step.request && (step.kind === 'uncertain' || step.kind === 'conflict') ? (
                <div className="flex flex-wrap gap-2">
                  <Button type="button" disabled={busy} onClick={() => send(step.request!)}>{ADD_RETRY_SAME}</Button>
                  {step.kind === 'uncertain' ? <Button type="button" variant="outline" disabled={busy} onClick={() => void consult(step.request!)}>{ADD_CONSULT}</Button> : null}
                </div>
              ) : null}
              {step.kind === 'stale' || step.kind === 'rejected' ? (
                <Button type="button" disabled={busy} onClick={() => { setSelected(''); setStep({ name: 'choose' }); void invalidateResponsableQueries(client); }}>{ADD_BACK}</Button>
              ) : null}
              {busy ? <p role="status">{ADD_SENDING}</p> : null}
            </div>
          ) : null}
        </ConfirmDialog>
      ) : null}
    </>
  );
}
