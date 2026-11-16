import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { useUnsavedChangesGuard } from '@/features/admin-familias/useUnsavedChangesGuard';
import { useSession } from '@/features/auth/session';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { ApiError } from '@/lib/api-error';
import { isSafeAthleteId } from './api';
import { clearedFields, FIELD_LABELS, FIELDS, sameValues, toUpdateInput, validate, valuesOfAthlete, type Errors, type FieldName, type Values } from './athlete-form';
import { classify, merge, unresolved, type Decision, type Decisions } from './athlete-merge';
import { AthleteFields } from './AthleteFields';
import { AthleteNotFound, BackToListLink } from './AthleteNotFound';
import { AthleteStatusBadge } from './AthleteStatusBadge';
import { useAthleteDetail, useUpdateAthlete } from './hooks';
import {
  classifyEditFailure,
  clearedFieldsText,
  detailErrorMessage,
  EDIT_CONFLICT_NOTE,
  EDIT_CONTINUE_NOTE,
  EDIT_CUIL_DUPLICATE_TEXT,
  EDIT_DNI_DUPLICATE_TEXT,
  EDIT_DNI_RESERVED_TEXT,
  EDIT_MATCHES_DRAFT,
  EDIT_NO_DIFFERENCES,
  EDIT_REVIEW_INTRO,
  EDIT_UNCHANGED,
  isNotFound,
  STALE_EDIT_TEXT,
  UNCERTAIN_EDIT_TEXT,
  updateAthleteErrorMessage,
  type EditFailure,
} from './messages';
import type { AthleteDetail } from './schemas';

type Reloaded = { fresh: AthleteDetail; error?: undefined } | { error: ApiError; fresh?: undefined };

/**
 * What the administrator saw when the edit started: the revision sent is this one and changes ONLY when the administrator, after
 * seeing the current data, explicitly chooses to go on. `original` is what the draft is compared against (is there anything to
 * save? what did the administrator change?).
 */
interface Base {
  version: string;
  original: Values;
}

const baseOf = (athlete: AthleteDetail): Base => ({ version: athlete.version, original: valuesOfAthlete(athlete) });

type Phase = 'editing' | 'stale' | 'uncertain' | 'reviewing';
type Purpose = 'review' | 'verify';

const shown = (value: string) => (value.trim() === '' ? 'No informado' : value.trim());

const KIND_TEXT = {
  incorporated: 'Cambió otra persona: se incorpora el valor vigente',
  mine: 'Solo cambiaste vos: se conserva tu valor',
  coincide: 'Los dos lo cambiaron igual',
  conflict: 'Conflicto: los dos lo cambiaron distinto',
} as const;

interface AthleteEditFormProps {
  /** The athlete as it was when the form opened (the form never follows later refreshes by itself). */
  athlete: AthleteDetail;
  /** Reloads the athlete on request. */
  reload: () => Promise<Reloaded>;
}

/**
 * Edits an athlete. The draft lives in this component's memory only and starts from ALL the values of the full detail. The
 * revision to send (`base.version`) is captured when the edit opens and changes ONLY when the administrator, after seeing the
 * current data (to review a conflict or to verify an uncertain result), explicitly chooses to go on: it is never refreshed silently
 * and there is no forced save. The PUT is a complete replacement, so every field travels and an emptied optional one is an explicit
 * `null` (the screen says which saved data would be removed). After a conflict the current data are merged field by field: a field
 * the administrator did not touch takes the current value, one only they changed keeps the draft, and one both changed differently
 * waits for an explicit choice. The state (active/inactive) is not part of this form.
 */
function AthleteEditForm({ athlete, reload }: AthleteEditFormProps) {
  const navigate = useNavigate();
  const update = useUpdateAthlete();
  const [base, setBase] = useState<Base>(() => baseOf(athlete));
  const [values, setValues] = useState<Values>(() => valuesOfAthlete(athlete));
  const [errors, setErrors] = useState<Errors>({});
  const [phase, setPhase] = useState<Phase>('editing');
  const [purpose, setPurpose] = useState<Purpose>('review');
  const [failure, setFailure] = useState<{ kind: EditFailure; error: ApiError } | null>(null);
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState<string | null>(null);
  const [fresh, setFresh] = useState<AthleteDetail | null>(null);
  const [decisions, setDecisions] = useState<Decisions>({});
  /** Set synchronously on submit: `isPending` only turns true after React Query notifies, so several submits of ONE task would all pass it. */
  const sending = useRef(false);

  const dirty = !sameValues(values, base.original);
  const guard = useUnsavedChangesGuard(dirty && !update.isPending);
  const detailPath = `/admin/deportistas/${encodeURIComponent(athlete.id)}`;
  const removed = phase === 'editing' ? clearedFields(base.original, values) : [];

  function set(field: FieldName, value: string) {
    setValues((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (sending.current || update.isPending || phase !== 'editing' || !dirty) return; // a second submit while one is in flight must never write twice
    const found = validate(values);
    if (Object.keys(found).length > 0) {
      setErrors(found);
      return;
    }
    setErrors({});
    setFailure(null);
    sending.current = true;
    update.mutate(
      { id: athlete.id, datos: toUpdateInput(values), version: base.version },
      {
        onSettled: () => {
          sending.current = false;
        },
        onSuccess: () => void navigate(detailPath, { replace: true, state: { athleteUpdated: true } }),
        onError: (error) => {
          const apiError = ApiError.from(error);
          const kind = classifyEditFailure(apiError);
          setFailure({ kind, error: apiError });
          if (kind === 'dniDuplicate') setErrors({ dni: EDIT_DNI_DUPLICATE_TEXT });
          else if (kind === 'dniReserved') setErrors({ dni: EDIT_DNI_RESERVED_TEXT });
          else if (kind === 'cuilDuplicate') setErrors({ cuil: EDIT_CUIL_DUPLICATE_TEXT });
          else if (kind === 'stale') setPhase('stale');
          else if (kind === 'uncertain') setPhase('uncertain');
        },
      },
    );
  }

  async function check(why: Purpose) {
    setChecking(true);
    setCheckError(null);
    const result = await reload();
    setChecking(false);
    if (result.error) {
      setCheckError(detailErrorMessage(result.error));
      return;
    }
    setFresh(result.fresh);
    setPurpose(why);
    setDecisions({});
    setPhase('reviewing');
  }

  const current = fresh ? valuesOfAthlete(fresh) : null;
  const kinds = current ? classify(base.original, values, current) : null;
  const pending = kinds ? unresolved(kinds, decisions) : [];

  /**
   * The administrator saw the current data and decided every conflict: only now does the revision change. The PUT replaces
   * everything, so sending the whole draft would silently undo what somebody else changed in a field the administrator never
   * touched: those fields take the current values, the ones only the administrator changed keep the draft and the conflicting
   * ones follow the explicit choice.
   */
  function continueOnCurrent() {
    if (!fresh || !current || !kinds || pending.length > 0) return;
    setValues(merge(values, current, kinds, decisions));
    setErrors({});
    setBase(baseOf(fresh));
    setFailure(null);
    setFresh(null);
    setDecisions({});
    setPhase('editing');
  }

  /** After a verification that found nothing saved: the administrator chooses to try again with the SAME revision. */
  function tryAgain() {
    setFailure(null);
    setFresh(null);
    setPhase('editing');
  }

  const needsCheck = phase === 'stale';
  const saving = update.isPending;
  const unchanged = fresh !== null && purpose === 'verify' && fresh.version === base.version;
  const matchesDraft = current !== null && sameValues(current, values);
  const conflicts = kinds ? FIELDS.filter((field) => kinds[field] === 'conflict') : [];
  const differing = kinds ? FIELDS.filter((field) => kinds[field] !== 'unchanged') : [];
  /** Did somebody else change any personal datum? (A moved revision with nothing changed is, for example, a change of state.) */
  const serverChanged = kinds ? FIELDS.some((field) => kinds[field] !== 'unchanged' && kinds[field] !== 'mine') : false;

  return (
    <div className="max-w-2xl space-y-6">
      <Link to={detailPath} className="inline-flex min-h-11 items-center text-sm font-semibold text-brand-700 underline-offset-4 hover:underline">
        Volver al deportista
      </Link>
      <header>
        <p className="text-sm font-semibold text-brand-700">Administración</p>
        <h1 className="mt-1 text-3xl font-extrabold tracking-tight">Editar deportista</h1>
        <p className="mt-2 text-muted">
          Los datos se guardan solo cuando tocás «Guardar cambios». Son datos personales: solo los ven los administradores. El estado (activo o inactivo) se cambia desde el detalle.
        </p>
      </header>

      <form noValidate aria-label="Editar deportista" onSubmit={submit} className="space-y-5 rounded-2xl border border-line bg-white p-5">
        {guard.panel}

        {failure && phase !== 'reviewing' ? (
          failure.kind === 'stale' ? (
            <Alert tone="error">{STALE_EDIT_TEXT}</Alert>
          ) : failure.kind === 'uncertain' ? (
            <Alert tone="error">{UNCERTAIN_EDIT_TEXT}</Alert>
          ) : failure.kind === 'dniDuplicate' || failure.kind === 'dniReserved' || failure.kind === 'cuilDuplicate' ? null : (
            <Alert tone="error">{updateAthleteErrorMessage(failure.kind, failure.error)}</Alert>
          )
        ) : null}
        {checkError ? <Alert tone="error">{checkError}</Alert> : null}

        {/* Locked while the request is in flight too: what is typed after the click would not travel and would be lost behind a «saved». */}
        <fieldset disabled={phase !== 'editing' || saving} className="m-0 min-w-0 space-y-5 border-0 p-0">
          <legend className="sr-only">Datos del deportista</legend>
          <AthleteFields values={values} errors={errors} onChange={set} />
        </fieldset>

        {removed.length > 0 ? (
          <p role="status" className="rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-950">
            {clearedFieldsText(removed.map((field) => FIELD_LABELS[field]))}
          </p>
        ) : null}

        {needsCheck ? (
          <Button variant="outline" disabled={checking} onClick={() => void check('review')}>
            {checking ? 'Cargando…' : 'Ver datos vigentes'}
          </Button>
        ) : null}
        {phase === 'uncertain' ? (
          <Button variant="outline" disabled={checking} onClick={() => void check('verify')}>
            {checking ? 'Verificando…' : 'Verificar datos vigentes'}
          </Button>
        ) : null}

        {phase === 'reviewing' && fresh && current && kinds ? (
          <div role="group" aria-label={purpose === 'verify' ? 'Verificación' : 'Datos vigentes'} className="space-y-3 rounded-lg border border-line bg-paper p-4 text-sm">
            {unchanged ? (
              <>
                <p>{EDIT_UNCHANGED}</p>
                <Button variant="outline" onClick={tryAgain}>
                  Volver a intentar
                </Button>
              </>
            ) : (
              <>
                <p>{matchesDraft ? EDIT_MATCHES_DRAFT : EDIT_REVIEW_INTRO}</p>
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-semibold">Estado actual:</span>
                  <AthleteStatusBadge active={fresh.activo} />
                </div>
                {matchesDraft || serverChanged ? null : <p>{EDIT_NO_DIFFERENCES}</p>}
                {matchesDraft || differing.length === 0 ? null : (
                  <div className="overflow-x-auto">
                    <table aria-label="Comparación de datos" className="w-full min-w-[22rem] border-collapse text-left">
                      <thead>
                        <tr>
                          <th scope="col" className="py-1 pr-3 font-semibold">
                            Campo
                          </th>
                          <th scope="col" className="py-1 pr-3 font-semibold">
                            Datos vigentes
                          </th>
                          <th scope="col" className="py-1 font-semibold">
                            Tu borrador
                          </th>
                        </tr>
                      </thead>
                      <tbody>
                        {differing.map((field) => (
                          <tr key={field} className="border-t border-line align-top">
                            <th scope="row" className="py-1 pr-3 font-semibold">
                              {FIELD_LABELS[field]}
                              <span className="block text-xs font-normal text-muted">{KIND_TEXT[kinds[field] as keyof typeof KIND_TEXT]}</span>
                            </th>
                            <td className="break-words py-1 pr-3">{shown(current[field])}</td>
                            <td className="break-words py-1">{shown(values[field])}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                {conflicts.length > 0 && !matchesDraft ? (
                  <div className="space-y-3">
                    <p className="font-semibold">{EDIT_CONFLICT_NOTE}</p>
                    {conflicts.map((field) => (
                      <fieldset key={field} className="space-y-1 rounded-lg border border-amber-300 bg-amber-50 p-3">
                        <legend className="px-1 font-semibold">{FIELD_LABELS[field]}</legend>
                        {(['mine', 'current'] as const).map((choice: Decision) => (
                          <label key={choice} className="flex min-h-11 items-start gap-2 py-1">
                            <input
                              type="radio"
                              name={`resolve-${field}`}
                              checked={decisions[field] === choice}
                              onChange={() => setDecisions((previous) => ({ ...previous, [field]: choice }))}
                              className="mt-1 size-4"
                            />
                            <span className="min-w-0 break-words">
                              {choice === 'mine' ? 'Mantener mi valor' : 'Usar el valor vigente'}: {shown(choice === 'mine' ? values[field] : current[field])}
                            </span>
                          </label>
                        ))}
                      </fieldset>
                    ))}
                  </div>
                ) : null}
                {matchesDraft ? null : <p>{EDIT_CONTINUE_NOTE}</p>}
                <div className="flex flex-col gap-2 sm:flex-row">
                  {matchesDraft ? (
                    <Button variant="outline" onClick={() => void navigate(detailPath)}>
                      Cerrar
                    </Button>
                  ) : (
                    <>
                      <Button variant="outline" disabled={pending.length > 0} onClick={continueOnCurrent}>
                        Seguir editando con los datos vigentes
                      </Button>
                      <Button variant="outline" onClick={() => void navigate(detailPath)}>
                        Descartar mi borrador
                      </Button>
                    </>
                  )}
                </div>
                {pending.length > 0 && !matchesDraft ? <p role="status">Resolvé {pending.length === 1 ? 'el conflicto' : `los ${pending.length} conflictos`} para poder seguir.</p> : null}
              </>
            )}
          </div>
        ) : null}

        <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
          <Button asChild variant="outline">
            <Link to={detailPath}>Cancelar</Link>
          </Button>
          <Button type="submit" disabled={saving || phase !== 'editing' || !dirty} aria-busy={saving}>
            {saving ? 'Guardando…' : 'Guardar cambios'}
          </Button>
        </div>
      </form>
    </div>
  );
}

function EditScreen() {
  const { id } = useParams();
  const valid = isSafeAthleteId(id);
  const athlete = useAthleteDetail(valid ? id : '', valid);

  // The form opens once, with a read made AFTER this screen mounted (never from an older cache): the revision it captures is that of
  // that moment. After it opened, a failed background refresh must not take the draft away.
  const ready = valid && athlete.isFetchedAfterMount && !!athlete.data;
  const [opened, setOpened] = useState(false);
  useEffect(() => {
    if (ready) setOpened(true);
  }, [ready]);
  const show = opened || ready;

  if (!valid) return <AthleteNotFound />;
  if (!show && athlete.isError) {
    const error = ApiError.from(athlete.error);
    if (isNotFound(error)) return <AthleteNotFound />;
    return (
      <div className="max-w-2xl space-y-4">
        <BackToListLink />
        <Alert tone="error">{detailErrorMessage(error)}</Alert>
        <Button variant="outline" onClick={() => void athlete.refetch()}>
          Reintentar
        </Button>
      </div>
    );
  }
  if (!show || !athlete.data) {
    return (
      <div className="max-w-2xl space-y-4">
        <BackToListLink />
        <LoadingBlock label="Cargando el deportista…" />
      </div>
    );
  }
  const reload = async (): Promise<Reloaded> => {
    const result = await athlete.refetch();
    if (result.isError || !result.data) return { error: ApiError.from(result.error) };
    return { fresh: result.data };
  };
  return <AthleteEditForm athlete={athlete.data} reload={reload} />;
}

/**
 * The FULL data of the athlete, the draft and the captured revision belong to ONE identity AND ONE resource: keyed by both, a session
 * that now belongs to someone else, or another athlete, starts from nothing (new query, an empty draft, a new revision).
 */
export function AdminDeportistaEditarPage() {
  const session = useSession();
  const { id } = useParams();
  const identity = session.status === 'authenticated' ? session.user.id : '';
  return <EditScreen key={`${identity}:${id ?? ''}`} />;
}
