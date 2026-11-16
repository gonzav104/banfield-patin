import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { useSession } from '@/features/auth/session';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { ApiError } from '@/lib/api-error';
import { isSafeFamilyId, isSafeTutorId } from './api';
import { BackToFamily, TutorNotFound } from './AdminTutorDetallePage';
import { FamilyStatusBadge } from './FamilyStatusBadge';
import { useFamilyDetail, useTutorDetail, useUpdateTutor } from './hooks';
import { classifyWriteFailure, detailErrorMessage, isNotFound, type WriteFailure } from './messages';
import type { FamilyDetail, TutorDetail } from './schemas';
import { TutorFields } from './TutorFields';
import { clearedFields, FIELDS, FIELD_LABELS, sameField, sameValues, toUpdateInput, validate, valuesOfTutor, type Errors, type FieldName, type Values } from './tutor-form';
import {
  clearedFieldsText,
  EDIT_CONTINUE_NOTE,
  EDIT_MATCHES_DRAFT,
  EDIT_REVIEW_INTRO,
  EDIT_UNCHANGED,
  FAMILY_INACTIVE_EDIT_NOTICE,
  INACTIVE_TUTOR_EDIT_TEXT,
  STALE_TUTOR_EDIT_TEXT,
  UNCERTAIN_TUTOR_EDIT_TEXT,
  tutorDetailErrorMessage,
  updateTutorErrorMessage,
} from './tutor-messages';
import { useUnsavedChangesGuard } from './useUnsavedChangesGuard';

/** The current server data of BOTH resources, as they are right now. */
interface Fresh {
  tutor: TutorDetail;
  family: FamilyDetail;
}
type Reloaded = { fresh: Fresh; error?: undefined } | { error: ApiError; fresh?: undefined };

/**
 * What the administrator saw when the edit started: BOTH revisions sent are these, and they change ONLY when the administrator,
 * after seeing the current data, explicitly chooses to go on. `original` is what the draft is compared against (is there
 * anything to save?).
 */
interface Base {
  tutorVersion: string;
  familyVersion: string;
  activa: boolean;
  original: Values;
}

const baseOf = (tutor: TutorDetail, family: FamilyDetail): Base => ({
  tutorVersion: tutor.version,
  familyVersion: family.version,
  activa: family.activa,
  original: valuesOfTutor(tutor),
});

type Phase = 'editing' | 'stale' | 'inactive' | 'uncertain' | 'reviewing';
type Purpose = 'review' | 'verify';

const shown = (value: string) => (value.trim() === '' ? 'No informado' : value.trim());

interface TutorEditFormProps {
  /** The tutor and the family as they were when the form opened (the form never follows later refreshes by itself). */
  tutor: TutorDetail;
  family: FamilyDetail;
  /** Reloads BOTH resources on request. */
  reload: () => Promise<Reloaded>;
}

/**
 * Edits a tutor. The draft lives in this component's memory only, and starts from ALL the values of the full detail. Both
 * revisions to send (`base.tutorVersion`, `base.familyVersion`) are captured when the edit opens and change ONLY when the
 * administrator, after seeing the current data (to review a conflict or to verify an uncertain result), explicitly chooses to go
 * on: they are never refreshed silently and there is no forced save. The PUT is a complete replacement, so every field travels
 * and an emptied optional one is an explicit `null` (the screen says which saved data would be removed).
 */
function TutorEditForm({ tutor, family, reload }: TutorEditFormProps) {
  const navigate = useNavigate();
  const update = useUpdateTutor();
  const [base, setBase] = useState<Base>(() => baseOf(tutor, family));
  const [values, setValues] = useState<Values>(() => valuesOfTutor(tutor));
  const [errors, setErrors] = useState<Errors>({});
  const [phase, setPhase] = useState<Phase>('editing');
  const [purpose, setPurpose] = useState<Purpose>('review');
  const [failure, setFailure] = useState<{ kind: WriteFailure; error: ApiError } | null>(null);
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState<string | null>(null);
  const [fresh, setFresh] = useState<Fresh | null>(null);
  /** Set synchronously on submit: `isPending` only turns true after React Query notifies, so several submits of ONE task would all pass it. */
  const sending = useRef(false);

  const dirty = !sameValues(values, base.original);
  const guard = useUnsavedChangesGuard(dirty && !update.isPending);
  const familyPath = `/admin/familias/${encodeURIComponent(family.id)}`;
  const tutorPath = `${familyPath}/tutores/${encodeURIComponent(tutor.id)}`;
  const removed = phase === 'editing' ? clearedFields(base.original, values) : [];

  function set(field: FieldName, value: string) {
    setValues((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (sending.current || update.isPending || phase !== 'editing' || !base.activa || !dirty) return; // a second submit while one is in flight must never write twice
    const found = validate(values);
    if (Object.keys(found).length > 0) {
      setErrors(found);
      return;
    }
    setErrors({});
    setFailure(null);
    sending.current = true;
    update.mutate(
      { tutorId: tutor.id, familyId: family.id, datos: toUpdateInput(values), version: base.tutorVersion, familyVersion: base.familyVersion },
      {
        onSettled: () => {
          sending.current = false;
        },
        onSuccess: () => void navigate(tutorPath, { replace: true, state: { tutorUpdated: true } }),
        onError: (error) => {
          const apiError = ApiError.from(error);
          const kind = classifyWriteFailure(apiError);
          setFailure({ kind, error: apiError });
          if (kind === 'stale') setPhase('stale');
          else if (kind === 'inactive') setPhase('inactive');
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
    setPhase('reviewing');
  }

  /**
   * The administrator saw the current data and chooses to build on them: only now do BOTH revisions change. The PUT replaces
   * everything, so sending the whole draft would silently undo what somebody else changed in a field the administrator never
   * touched: those fields take the current values and only the ones the administrator changed keep the draft.
   */
  function continueOnCurrent() {
    if (!fresh) return;
    const latest = valuesOfTutor(fresh.tutor);
    setValues((draft) => Object.fromEntries(FIELDS.map((field) => [field, sameField(field, draft[field], base.original[field]) ? latest[field] : draft[field]])) as Values);
    setErrors({});
    setBase(baseOf(fresh.tutor, fresh.family));
    setFailure(null);
    setFresh(null);
    setPhase('editing');
  }

  /** After a verification that found nothing saved: the administrator chooses to try again with the SAME revisions. */
  function tryAgain() {
    setFailure(null);
    setFresh(null);
    setPhase('editing');
  }

  const blocked = !base.activa && phase === 'editing';
  const needsCheck = phase === 'stale' || phase === 'inactive';
  const saving = update.isPending;

  const current = fresh ? valuesOfTutor(fresh.tutor) : null;
  const unchanged =
    fresh !== null &&
    purpose === 'verify' &&
    fresh.tutor.version === base.tutorVersion &&
    fresh.family.version === base.familyVersion &&
    fresh.family.activa === base.activa;
  const matchesDraft = current !== null && sameValues(current, values);

  return (
    <div className="max-w-2xl space-y-6">
      <Link to={tutorPath} className="inline-flex min-h-11 items-center text-sm font-semibold text-brand-700 underline-offset-4 hover:underline">
        Volver al tutor
      </Link>
      <header>
        <p className="text-sm font-semibold text-brand-700">Familia: {family.nombreReferencia}</p>
        <h1 className="mt-1 text-3xl font-extrabold tracking-tight">Editar tutor</h1>
        <p className="mt-2 text-muted">
          Los datos se guardan solo cuando tocás «Guardar cambios». Son datos personales: solo los ven los administradores.
        </p>
      </header>

      {blocked ? (
        <Alert tone="warning">
          {FAMILY_INACTIVE_EDIT_NOTICE}{' '}
          <Link to={familyPath} className="font-semibold underline underline-offset-4">
            Ir a la familia
          </Link>
        </Alert>
      ) : (
        <form noValidate aria-label="Editar tutor" onSubmit={submit} className="space-y-5 rounded-2xl border border-line bg-white p-5">
          {guard.panel}

          {failure && phase !== 'reviewing' ? (
            <Alert tone="error">
              {failure.kind === 'stale' ? (
                STALE_TUTOR_EDIT_TEXT
              ) : failure.kind === 'inactive' ? (
                <>
                  {INACTIVE_TUTOR_EDIT_TEXT}{' '}
                  <Link to={familyPath} className="font-semibold underline underline-offset-4">
                    Ir a la familia
                  </Link>
                </>
              ) : failure.kind === 'uncertain' ? (
                UNCERTAIN_TUTOR_EDIT_TEXT
              ) : (
                updateTutorErrorMessage(failure.kind, failure.error)
              )}
            </Alert>
          ) : null}
          {checkError ? <Alert tone="error">{checkError}</Alert> : null}

          {/* Locked while the request is in flight: what is typed after the click would not travel and would be lost behind a «saved». */}
          <fieldset disabled={saving} className="m-0 min-w-0 space-y-5 border-0 p-0">
            <legend className="sr-only">Datos del tutor</legend>
            <TutorFields values={values} errors={errors} onChange={set} />
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

          {phase === 'reviewing' && fresh && current ? (
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
                    <span className="font-semibold">Familia:</span>
                    <span className="break-words">{fresh.family.nombreReferencia}</span>
                    <FamilyStatusBadge active={fresh.family.activa} />
                  </div>
                  {fresh.family.activa ? null : <p>{FAMILY_INACTIVE_EDIT_NOTICE}</p>}
                  {matchesDraft || !fresh.family.activa ? null : <p>{EDIT_CONTINUE_NOTE}</p>}
                  {matchesDraft ? null : (
                    <div className="overflow-x-auto">
                      <table aria-label="Comparación de datos" className="w-full min-w-[20rem] border-collapse text-left">
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
                          {FIELDS.map((field) => {
                            const differs = !sameField(field, current[field], values[field]);
                            return (
                              <tr key={field} className="border-t border-line align-top">
                                <th scope="row" className="py-1 pr-3 font-semibold">
                                  {FIELD_LABELS[field]}
                                  {differs ? <span className="font-bold text-amber-900"> (distinto)</span> : null}
                                </th>
                                <td className="break-words py-1 pr-3">{shown(current[field])}</td>
                                <td className="break-words py-1">{shown(values[field])}</td>
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                    </div>
                  )}
                  <div className="flex flex-col gap-2 sm:flex-row">
                    {matchesDraft ? (
                      <Button variant="outline" onClick={() => void navigate(tutorPath)}>
                        Cerrar
                      </Button>
                    ) : (
                      <>
                        {fresh.family.activa ? (
                          <Button variant="outline" onClick={continueOnCurrent}>
                            Seguir editando mi borrador
                          </Button>
                        ) : null}
                        <Button variant="outline" onClick={() => void navigate(tutorPath)}>
                          Descartar mi borrador
                        </Button>
                      </>
                    )}
                  </div>
                </>
              )}
            </div>
          ) : null}

          <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
            <Button asChild variant="outline">
              <Link to={tutorPath}>Cancelar</Link>
            </Button>
            <Button type="submit" disabled={saving || phase !== 'editing' || !dirty} aria-busy={saving}>
              {saving ? 'Guardando…' : 'Guardar cambios'}
            </Button>
          </div>
        </form>
      )}
    </div>
  );
}

function EditScreen() {
  const { id, tutorId } = useParams();
  const validFamily = isSafeFamilyId(id);
  const validTutor = isSafeTutorId(tutorId);
  const enabled = validFamily && validTutor;
  const tutor = useTutorDetail(validTutor ? tutorId : '', enabled);
  const family = useFamilyDetail(validFamily ? id : '', enabled);

  // The form opens once, with BOTH resources fetched after this screen mounted (never from an older cache): the two revisions it
  // captures are those of one and the same moment. After it opened, a failed background refresh must not take the draft away.
  const ready = enabled && tutor.isFetchedAfterMount && family.isFetchedAfterMount && !!tutor.data && !!family.data;
  const [opened, setOpened] = useState(false);
  useEffect(() => {
    if (ready) setOpened(true);
  }, [ready]);
  const show = opened || ready;

  if (!enabled) return <TutorNotFound familyId={validFamily ? id : undefined} />;
  // A tutor that exists but belongs to ANOTHER family is the same «not found» as one that does not exist.
  if (tutor.data && tutor.data.familiaId !== id) return <TutorNotFound familyId={id} />;
  if (!show && (tutor.isError || family.isError)) {
    const error = ApiError.from(tutor.isError ? tutor.error : family.error);
    if (isNotFound(error)) return <TutorNotFound familyId={id} />;
    return (
      <div className="max-w-2xl space-y-4">
        <BackToFamily familyId={id} />
        <Alert tone="error">{tutorDetailErrorMessage(error)}</Alert>
        <Button variant="outline" onClick={() => void Promise.all([tutor.refetch(), family.refetch()])}>
          Reintentar
        </Button>
      </div>
    );
  }
  if (!show || !tutor.data || !family.data) {
    return (
      <div className="max-w-2xl space-y-4">
        <BackToFamily familyId={id} />
        <LoadingBlock label="Cargando el tutor…" />
      </div>
    );
  }
  const reload = async (): Promise<Reloaded> => {
    const [freshTutor, freshFamily] = await Promise.all([tutor.refetch(), family.refetch()]);
    if (freshTutor.isError || !freshTutor.data) return { error: ApiError.from(freshTutor.error) };
    if (freshFamily.isError || !freshFamily.data) return { error: ApiError.from(freshFamily.error) };
    return { fresh: { tutor: freshTutor.data, family: freshFamily.data } };
  };
  return <TutorEditForm tutor={tutor.data} family={family.data} reload={reload} />;
}

/**
 * The FULL data of the tutor, the draft and the captured revisions belong to ONE identity: keyed by identity, a session that now
 * belongs to someone else starts from nothing (new queries, an empty draft, new revisions).
 */
export function AdminTutorEditarPage() {
  const session = useSession();
  const identity = session.status === 'authenticated' ? session.user.id : '';
  return <EditScreen key={identity} />;
}
