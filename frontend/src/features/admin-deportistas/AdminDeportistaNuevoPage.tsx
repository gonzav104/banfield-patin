import { useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { useUnsavedChangesGuard } from '@/features/admin-familias/useUnsavedChangesGuard';
import { useSession } from '@/features/auth/session';
import { ApiError } from '@/lib/api-error';
import { ATHLETE_VERIFY_PAGE_SIZE, listAthletes } from './api';
import { EMPTY, FIELDS, maskedDni, normalizeDni, toInput, validate, type Errors, type FieldName, type Values } from './athlete-form';
import { AthleteFields } from './AthleteFields';
import { useCreateAthlete } from './hooks';
import {
  classifyCreateFailure,
  createErrorMessage,
  CUIL_DUPLICATE_TEXT,
  DNI_DUPLICATE_TEXT,
  DNI_RESERVED_TEXT,
  DUPLICATE_AFTER_UNCERTAIN_TEXT,
  UNCERTAIN_TEXT,
  VERIFY_ERROR,
  VERIFY_FOUND,
  VERIFY_NOT_OBSERVED,
  VERIFY_RETRY_NOTE,
  verifyIncomplete,
  type CreateFailure,
} from './messages';
import type { AthleteSummary } from './schemas';

/** `editing`: the form can be sent. `uncertain`: a creation whose result is unknown; the resend stays blocked until the administrator verifies. */
type Phase = 'editing' | 'uncertain' | 'verified';

/**
 * New athlete. The draft lives in this component's memory only (nothing is persisted, nothing goes in the address). Nothing is sent
 * until the administrator submits, and only a confirmed `201` navigates to the detail. After an answer that cannot be confirmed
 * (lost response, timeout, 5xx) the form stays closed: the resend is an explicit decision taken after a read-only check by DNI.
 * That check is only a HINT (the backend has no idempotency key): an athlete with that DNI is never presented as proof that OUR
 * request created it, and not finding one is never presented as proof that the request will not be applied later.
 */
function AthleteForm() {
  const navigate = useNavigate();
  const create = useCreateAthlete();
  const [values, setValues] = useState<Values>(EMPTY);
  const [errors, setErrors] = useState<Errors>({});
  const [phase, setPhase] = useState<Phase>('editing');
  const [failure, setFailure] = useState<CreateFailure | null>(null);
  const [failureError, setFailureError] = useState<ApiError | null>(null);
  /** An earlier attempt ended without a confirmed answer: a later duplicate answer is then ambiguous. */
  const [hadUncertain, setHadUncertain] = useState(false);
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState<string | null>(null);
  const [matches, setMatches] = useState<AthleteSummary[]>([]);
  /** The search returned more results than the ones inspected: the check cannot conclude anything about the rest. */
  const [incomplete, setIncomplete] = useState<{ inspected: number; total: number } | null>(null);

  /** Set synchronously on submit: `isPending` only turns true after React Query notifies, so several submits of ONE task would all pass it. */
  const sending = useRef(false);

  const dirty = FIELDS.some((field) => values[field].trim() !== '');
  const guard = useUnsavedChangesGuard(dirty && !create.isPending);
  const saving = create.isPending;
  /** Locked while the request is in flight too: what is typed after the click would not travel and would be lost behind a «created». */
  const locked = phase !== 'editing' || saving;

  function set(field: FieldName, value: string) {
    setValues((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (sending.current || create.isPending || phase !== 'editing') return; // a second submit while one is in flight must never create two
    const found = validate(values);
    if (Object.keys(found).length > 0) {
      setErrors(found);
      return;
    }
    setErrors({});
    setFailure(null);
    setFailureError(null);
    sending.current = true;
    create.mutate(toInput(values), {
      onSettled: () => {
        sending.current = false;
      },
      onSuccess: (created) => void navigate(`/admin/deportistas/${encodeURIComponent(created.id)}`, { replace: true, state: { athleteCreated: true } }),
      onError: (error) => {
        const apiError = ApiError.from(error);
        const kind = classifyCreateFailure(apiError);
        setFailure(kind);
        setFailureError(apiError);
        if (kind === 'dniDuplicate' || kind === 'dniReserved') setErrors({ dni: kind === 'dniDuplicate' ? DNI_DUPLICATE_TEXT : DNI_RESERVED_TEXT });
        if (kind === 'cuilDuplicate') setErrors({ cuil: CUIL_DUPLICATE_TEXT });
        if (kind === 'uncertain') {
          setHadUncertain(true);
          setPhase('uncertain');
        }
      },
    });
  }

  /**
   * Read-only: the list searched by DNI (every status), first page, at most 100 results; the candidates are compared by their
   * masked DNI only (the search is by prefix, so another length or digits is another athlete), never by name. One page, never a
   * walk through the pages: whatever it did not inspect is reported as such. Nothing is cached and nothing goes in the address.
   */
  async function verify() {
    setChecking(true);
    setCheckError(null);
    try {
      const page = await listAthletes({ estado: 'TODOS', busqueda: normalizeDni(values.dni), page: 0, size: ATHLETE_VERIFY_PAGE_SIZE });
      const masked = maskedDni(values.dni);
      setMatches(page.contenido.filter((athlete) => athlete.dniEnmascarado === masked));
      setIncomplete(page.totalElementos > page.contenido.length ? { inspected: page.contenido.length, total: page.totalElementos } : null);
      setPhase('verified');
    } catch {
      setCheckError(VERIFY_ERROR);
    } finally {
      setChecking(false);
    }
  }

  /** The administrator saw the verification and chooses to send again: only now does the form open. */
  function retry() {
    setFailure(null);
    setFailureError(null);
    setPhase('editing');
  }

  const showDuplicateHint = hadUncertain && (failure === 'dniDuplicate' || failure === 'dniReserved' || failure === 'cuilDuplicate');

  return (
    <div className="max-w-2xl space-y-6">
      <Link to="/admin/deportistas" className="inline-flex min-h-11 items-center text-sm font-semibold text-brand-700 underline-offset-4 hover:underline">
        Volver al listado
      </Link>
      <header>
        <p className="text-sm font-semibold text-brand-700">Administración</p>
        <h1 className="mt-1 text-3xl font-extrabold tracking-tight">Nuevo deportista</h1>
        <p className="mt-2 text-muted">
          El deportista se crea sin familia: no se asigna ninguna ni se crea una cuenta o una invitación. Son datos personales: solo los ven los administradores.
        </p>
      </header>

      <form noValidate aria-label="Nuevo deportista" onSubmit={submit} className="space-y-5 rounded-2xl border border-line bg-white p-5">
        {guard.panel}

        {failure === 'uncertain' ? <Alert tone="error">{UNCERTAIN_TEXT}</Alert> : null}
        {failure && failure !== 'uncertain' && failure !== 'dniDuplicate' && failure !== 'dniReserved' && failure !== 'cuilDuplicate' && failureError ? (
          <Alert tone="error">{createErrorMessage(failure, failureError)}</Alert>
        ) : null}
        {showDuplicateHint ? <Alert tone="warning">{DUPLICATE_AFTER_UNCERTAIN_TEXT}</Alert> : null}
        {checkError ? <Alert tone="error">{checkError}</Alert> : null}

        <fieldset disabled={locked} className="m-0 min-w-0 space-y-5 border-0 p-0">
          <legend className="sr-only">Datos del deportista</legend>
          <AthleteFields values={values} errors={errors} onChange={set} />
        </fieldset>

        {phase === 'uncertain' ? (
          <Button variant="outline" disabled={checking} onClick={() => void verify()}>
            {checking ? 'Verificando…' : 'Verificar en el listado'}
          </Button>
        ) : null}

        {phase === 'verified' ? (
          <div role="group" aria-label="Verificación" className="space-y-3 rounded-lg border border-line bg-paper p-4 text-sm">
            {matches.length > 0 ? <p>{VERIFY_FOUND}</p> : incomplete ? null : <p>{VERIFY_NOT_OBSERVED}</p>}
            {incomplete ? <p>{verifyIncomplete(incomplete.inspected, incomplete.total)}</p> : null}
            {matches.length > 0 ? (
              <ul className="space-y-1">
                {matches.map((athlete) => (
                  <li key={athlete.id}>
                    <Link to={`/admin/deportistas/${encodeURIComponent(athlete.id)}`} className="font-semibold text-brand-700 underline underline-offset-4">
                      {athlete.apellido}, {athlete.nombre}
                    </Link>{' '}
                    <span className="text-muted">({athlete.dniEnmascarado})</span>
                  </li>
                ))}
              </ul>
            ) : null}
            <p>{VERIFY_RETRY_NOTE}</p>
            <Button variant="outline" onClick={retry}>
              Volver a intentar
            </Button>
          </div>
        ) : null}

        <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
          <Button asChild variant="outline">
            <Link to="/admin/deportistas">Cancelar</Link>
          </Button>
          <Button type="submit" disabled={saving || locked} aria-busy={saving}>
            {saving ? 'Creando…' : 'Crear deportista'}
          </Button>
        </div>
      </form>
    </div>
  );
}

export function AdminDeportistaNuevoPage() {
  const session = useSession();
  // Whatever the form keeps in memory (the draft) belongs to ONE identity: another one starts clean.
  const identity = session.status === 'authenticated' ? session.user.id : '';
  return <AthleteForm key={identity} />;
}
