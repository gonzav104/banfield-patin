import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { AthleteNotFound } from '@/features/admin-deportistas/AthleteNotFound';
import { AthleteStatusBadge } from '@/features/admin-deportistas/AthleteStatusBadge';
import { useUnsavedChangesGuard } from '@/features/admin-familias/useUnsavedChangesGuard';
import { useSession } from '@/features/auth/session';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { SPORTS_STATUS_LABEL } from '@/features/familia/messages';
import { sportsStatusSchema, type SportsStatus } from '@/features/familia/schemas';
import { ApiError } from '@/lib/api-error';
import { getSportsRow, isSafeId } from './api';
import { useAthleteHeader, useSportsRow, useUpdateSportsRow } from './hooks';
import {
  classifyEditFailure,
  classifyRowAbsence,
  clearedFieldsText,
  EDIT_INTRO,
  EDIT_NOTHING_TO_SAVE,
  EDIT_SUBMIT,
  EDIT_SUBMITTING,
  EDIT_TITLE,
  editErrorMessage,
  isAthleteAbsent,
  KIND_TEXT,
  KIND_TEXT_VERIFY,
  LOADING_TEXT,
  NO_ROW_TEXT,
  NO_ROW_TITLE,
  NUMBER_TAKEN_TEXT,
  REVIEW_CONFLICT_NOTE,
  REVIEW_CONTINUE_NOTE,
  REVIEW_INTRO,
  REVIEW_MATCHES_DRAFT,
  REVIEW_NO_DIFFERENCES,
  SEASON_NOT_FOUND_TEXT,
  SEASON_NOT_FOUND_TITLE,
  sportsErrorMessage,
  VERIFY_UNCHANGED_RETRY_TEXT,
  VERIFY_UNCHANGED_TEXT,
  type EditFailure,
} from './messages';
import { forwardedListState, sportsHistoryPath, sportsRowPath } from './navigation';
import type { AthleteHeader, SportsRow } from './schemas';
import { clearedFields, EDIT_FIELDS, FIELD_LABELS, MAX_LENGTH, sameValues, toUpdateInput, validate, valuesOfRow, type EditField, type Errors, type Values } from './sports-form';
import { classify, merge, unresolved, type Decision, type Decisions } from './sports-merge';
import { Notice, ReadError, Row, SportsLink } from './SportsUi';

/**
 * What the administrator saw when the edit started: the revision sent is this one and changes ONLY when the administrator, after seeing
 * the current data, explicitly chooses to go on. `original` is what the draft is compared against (is there anything to save? what did
 * the administrator change?).
 */
interface Base {
  version: string;
  original: Values;
}

const baseOf = (row: SportsRow): Base => ({ version: row.version, original: valuesOfRow(row) });

type Phase = 'editing' | 'stale' | 'uncertain' | 'reviewing';
type Purpose = 'review' | 'verify';
type Gone = 'athlete' | 'season' | 'row';

const SELECT_CLASS = 'block min-h-11 w-full rounded-lg border border-neutral-400 bg-white px-3 text-base text-ink disabled:bg-neutral-100';

function display(field: EditField, value: string): string {
  if (field === 'estadoDeportivo') return SPORTS_STATUS_LABEL[value as SportsStatus] ?? value;
  const text = value.trim();
  return text === '' ? 'No informado' : text;
}

function BackLink({ to, children }: { to: string; children: string }) {
  return <SportsLink to={to}>{children}</SportsLink>;
}

/** A season or a row that cannot be shown: one calm screen with the way back to the history. */
function Absent({ athleteId, title, children }: { athleteId: string; title: string; children: string }) {
  return (
    <div className="max-w-2xl space-y-4">
      <BackLink to={sportsHistoryPath(athleteId)}>Volver al historial deportivo</BackLink>
      <Notice title={title}>{children}</Notice>
    </div>
  );
}

function GoneScreen({ gone, athleteId }: { gone: Gone; athleteId: string }) {
  if (gone === 'athlete') return <AthleteNotFound />;
  if (gone === 'row') return <Absent athleteId={athleteId} title={NO_ROW_TITLE}>{NO_ROW_TEXT}</Absent>;
  return <Absent athleteId={athleteId} title={SEASON_NOT_FOUND_TITLE}>{SEASON_NOT_FOUND_TEXT}</Absent>;
}

function StatusField({ label, value, onChange }: { label: string; value: SportsStatus; onChange: (status: SportsStatus) => void }) {
  return (
    <div className="space-y-1.5">
      <label htmlFor="sports-edit-status" className="block text-sm font-semibold">
        {label}
      </label>
      <select id="sports-edit-status" value={value} onChange={(event) => onChange(sportsStatusSchema.parse(event.target.value))} className={SELECT_CLASS}>
        {sportsStatusSchema.options.map((status) => (
          <option key={status} value={status}>
            {SPORTS_STATUS_LABEL[status]}
          </option>
        ))}
      </select>
    </div>
  );
}

/**
 * Edits ONE sports row. The draft lives in this component's memory only and starts from the five values of the row read when the
 * screen opened. The revision to send (`base.version`) is captured then and changes ONLY when the administrator, after seeing the
 * current data (to review a conflict or to verify an uncertain result), explicitly chooses to go on: it is never refreshed silently
 * and there is no forced save. The PUT is a complete replacement, so every field travels and an emptied optional one is an explicit
 * `null` (the screen says which saved data would be removed). After a conflict the current data are merged field by field: a field
 * the administrator did not touch takes the current value, one only they changed keeps the draft, and one both changed differently
 * waits for an explicit choice. The season and the athlete are the identity of the row and cannot be edited.
 */
function EditForm({ athlete, row }: { athlete: AthleteHeader; row: SportsRow }) {
  const navigate = useNavigate();
  const location = useLocation();
  const update = useUpdateSportsRow();
  const [base, setBase] = useState<Base>(() => baseOf(row));
  const [values, setValues] = useState<Values>(() => valuesOfRow(row));
  const [errors, setErrors] = useState<Errors>({});
  const [phase, setPhase] = useState<Phase>('editing');
  const [purpose, setPurpose] = useState<Purpose>('review');
  const [failure, setFailure] = useState<{ kind: EditFailure; error: ApiError } | null>(null);
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState<string | null>(null);
  const [fresh, setFresh] = useState<SportsRow | null>(null);
  const [decisions, setDecisions] = useState<Decisions>({});
  const [gone, setGone] = useState<Gone | null>(null);
  /** Set synchronously on submit: `isPending` only turns true after React Query notifies, so several submits of ONE task would all pass it. */
  const sending = useRef(false);
  /** The same for the reads (review and verification): the button disables itself only after React renders. */
  const reading = useRef(false);

  const rowPath = sportsRowPath(athlete.id, row.temporadaId);
  const dirty = !sameValues(values, base.original);
  // Also while the request is in flight: leaving then would hide an outcome the administrator is waiting for.
  const guard = useUnsavedChangesGuard(dirty);
  const saving = update.isPending;
  const removed = phase === 'editing' ? clearedFields(base.original, values) : [];

  function set<K extends keyof Values>(field: K, value: Values[K]) {
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
      { athleteId: athlete.id, seasonId: row.temporadaId, datos: toUpdateInput(values), version: base.version },
      {
        onSettled: () => {
          sending.current = false;
        },
        onSuccess: () => void navigate(rowPath, { replace: true, state: { sportsRowUpdated: true, ...forwardedListState(location.state) } }),
        onError: (error) => {
          const apiError = ApiError.from(error);
          const kind = classifyEditFailure(apiError);
          if (kind === 'athleteGone' || kind === 'seasonGone' || kind === 'rowGone') {
            setGone(kind === 'athleteGone' ? 'athlete' : kind === 'seasonGone' ? 'season' : 'row');
            return;
          }
          setFailure({ kind, error: apiError });
          if (kind === 'numberTaken') setErrors({ numeroCorredor: NUMBER_TAKEN_TEXT });
          else if (kind === 'stale') setPhase('stale');
          else if (kind === 'uncertain') setPhase('uncertain');
        },
      },
    );
  }

  /**
   * Read-only: ONE GET of the row, straight from the server (never from a cache). It is used to review a conflict or to verify an
   * uncertain result: the answer is shown, but nothing the form holds changes until the administrator explicitly goes on.
   */
  async function check(why: Purpose) {
    if (reading.current) return; // a second read in the same task must never be sent
    reading.current = true;
    setChecking(true);
    setCheckError(null);
    try {
      const result = await getSportsRow(athlete.id, row.temporadaId);
      setFresh(result);
      setPurpose(why);
      setDecisions({});
      setPhase('reviewing');
    } catch (error) {
      const apiError = ApiError.from(error);
      const absence = classifyRowAbsence(apiError);
      if (absence) setGone(absence);
      else setCheckError(sportsErrorMessage(apiError));
    } finally {
      reading.current = false;
      setChecking(false);
    }
  }

  const current = fresh ? valuesOfRow(fresh) : null;
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

  if (gone) return <GoneScreen gone={gone} athleteId={athlete.id} />;

  const unchanged = fresh !== null && purpose === 'verify' && fresh.version === base.version;
  const matchesDraft = current !== null && sameValues(current, values);
  const conflicts = kinds ? EDIT_FIELDS.filter((field) => kinds[field] === 'conflict') : [];
  const differing = kinds ? EDIT_FIELDS.filter((field) => kinds[field] !== 'unchanged') : [];
  /** Did somebody else change any datum? (A moved revision with nothing changed is possible: a change and its undo.) */
  const serverChanged = kinds ? EDIT_FIELDS.some((field) => kinds[field] !== 'unchanged' && kinds[field] !== 'mine') : false;
  const statusLabel = `Estado deportivo de ${row.anio}`;

  return (
    <div className="max-w-2xl space-y-6">
      <BackLink to={rowPath}>Volver a la temporada</BackLink>
      <header className="space-y-2">
        <h1 className="text-3xl font-extrabold tracking-tight">{EDIT_TITLE}</h1>
        <p className="min-w-0 break-words text-lg font-semibold">
          {athlete.nombre} {athlete.apellido}
        </p>
        <p className="text-base font-semibold text-brand-700">Temporada {row.anio}</p>
        <dl className="text-sm">
          <Row label="Estado en la escuela">
            <AthleteStatusBadge active={athlete.activo} />
          </Row>
        </dl>
        <p className="text-sm text-muted">{EDIT_INTRO}</p>
      </header>

      <form noValidate aria-label="Editar datos deportivos" onSubmit={submit} className="space-y-5 rounded-2xl border border-line bg-white p-5">
        {guard.panel}

        {failure && phase !== 'reviewing' && failure.kind !== 'numberTaken' ? <Alert tone="error">{editErrorMessage(failure.kind, failure.error)}</Alert> : null}
        {checkError ? <Alert tone="error">{checkError}</Alert> : null}

        {/* Locked while the request is in flight too: what is typed after the click would not travel and would be lost behind a «saved». */}
        <fieldset disabled={phase !== 'editing' || saving} className="m-0 min-w-0 space-y-5 border-0 p-0">
          <legend className="sr-only">Datos deportivos de la temporada {row.anio}</legend>
          <Field label="Categoría (opcional)" maxLength={MAX_LENGTH.categoria} autoComplete="off" value={values.categoria} error={errors.categoria} onChange={(event) => set('categoria', event.target.value)} />
          <div className="grid gap-5 sm:grid-cols-2">
            <Field label="Rama (opcional)" maxLength={MAX_LENGTH.rama} autoComplete="off" value={values.rama} error={errors.rama} onChange={(event) => set('rama', event.target.value)} />
            <Field label="Divisional (opcional)" maxLength={MAX_LENGTH.divisional} autoComplete="off" value={values.divisional} error={errors.divisional} onChange={(event) => set('divisional', event.target.value)} />
          </div>
          <Field
            label="Número de corredor (opcional)"
            hint="Se guarda como texto: conserva los ceros iniciales (007)."
            maxLength={MAX_LENGTH.numeroCorredor}
            autoComplete="off"
            value={values.numeroCorredor}
            error={errors.numeroCorredor}
            onChange={(event) => set('numeroCorredor', event.target.value)}
          />
          <StatusField label={statusLabel} value={values.estadoDeportivo} onChange={(status) => set('estadoDeportivo', status)} />
        </fieldset>

        {removed.length > 0 ? (
          <p role="status" className="rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-950">
            {clearedFieldsText(removed.map((field) => FIELD_LABELS[field]))}
          </p>
        ) : null}
        {phase === 'editing' && !dirty ? <p className="text-sm text-muted">{EDIT_NOTHING_TO_SAVE}</p> : null}

        {phase === 'stale' ? (
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
                <p>{VERIFY_UNCHANGED_TEXT}</p>
                <p>{VERIFY_UNCHANGED_RETRY_TEXT}</p>
                <Button variant="outline" onClick={tryAgain}>
                  Volver a intentar
                </Button>
              </>
            ) : (
              <>
                <p>{matchesDraft ? REVIEW_MATCHES_DRAFT : REVIEW_INTRO}</p>
                {matchesDraft || serverChanged ? null : <p>{REVIEW_NO_DIFFERENCES}</p>}
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
                              <span className="block text-xs font-normal text-muted">{(purpose === 'verify' ? KIND_TEXT_VERIFY : KIND_TEXT)[kinds[field] as keyof typeof KIND_TEXT]}</span>
                            </th>
                            <td className="min-w-0 py-1 pr-3 [overflow-wrap:anywhere]">{display(field, current[field])}</td>
                            <td className="min-w-0 py-1 [overflow-wrap:anywhere]">{display(field, values[field])}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                {conflicts.length > 0 && !matchesDraft ? (
                  <div className="space-y-3">
                    <p className="font-semibold">{REVIEW_CONFLICT_NOTE}</p>
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
                            <span className="min-w-0 [overflow-wrap:anywhere]">
                              {choice === 'mine' ? 'Mantener mi valor' : 'Usar el valor vigente'}: {display(field, choice === 'mine' ? values[field] : current[field])}
                            </span>
                          </label>
                        ))}
                      </fieldset>
                    ))}
                  </div>
                ) : null}
                {matchesDraft ? null : <p>{REVIEW_CONTINUE_NOTE}</p>}
                <div className="flex flex-col gap-2 sm:flex-row">
                  {matchesDraft ? (
                    <Button variant="outline" onClick={() => void navigate(rowPath)}>
                      Cerrar
                    </Button>
                  ) : (
                    <>
                      <Button variant="outline" disabled={pending.length > 0} onClick={continueOnCurrent}>
                        Seguir editando con los datos vigentes
                      </Button>
                      <Button variant="outline" onClick={() => void navigate(rowPath)}>
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
            <SportsLink to={rowPath} className="">
              Cancelar
            </SportsLink>
          </Button>
          <Button type="submit" disabled={saving || phase !== 'editing' || !dirty} aria-busy={saving}>
            {saving ? EDIT_SUBMITTING : EDIT_SUBMIT}
          </Button>
        </div>
      </form>
    </div>
  );
}

function EditScreen() {
  const { id, temporadaId } = useParams();
  const validAthlete = isSafeId(id);
  const validSeason = isSafeId(temporadaId);
  const enabled = validAthlete && validSeason;
  const athleteId = enabled ? id : '';
  const seasonId = enabled ? temporadaId : '';
  const header = useAthleteHeader(athleteId, enabled);
  const row = useSportsRow(athleteId, seasonId, enabled);

  // The form opens once, with a read made AFTER this screen mounted (never from an older cache): the revision it captures is that of
  // that moment. After it opened, a failed background refresh must not take the draft away.
  const ready = enabled && row.isFetchedAfterMount && !!row.data && !!header.data;
  const [opened, setOpened] = useState(false);
  useEffect(() => {
    if (ready) setOpened(true);
  }, [ready]);
  const show = opened || ready;

  if (!validAthlete) return <AthleteNotFound />;
  if (!validSeason) return <GoneScreen gone="season" athleteId={id} />;
  if (!show && (row.isError || header.isError)) {
    const failed = row.isError ? row : header;
    const error = ApiError.from(failed.error);
    if (failed === row) {
      const absence = classifyRowAbsence(error);
      if (absence) return <GoneScreen gone={absence} athleteId={id} />;
    } else if (isAthleteAbsent(error)) {
      return <AthleteNotFound />;
    }
    return (
      <div className="max-w-2xl space-y-4">
        <BackLink to={sportsHistoryPath(id)}>Volver al historial deportivo</BackLink>
        <ReadError
          error={error}
          onRetry={() => {
            if (row.isError) void row.refetch();
            if (header.isError) void header.refetch();
          }}
        />
      </div>
    );
  }
  if (!show || !row.data || !header.data) {
    return (
      <div className="max-w-2xl space-y-4">
        <BackLink to={sportsHistoryPath(id)}>Volver al historial deportivo</BackLink>
        <LoadingBlock label={LOADING_TEXT} />
      </div>
    );
  }
  return <EditForm athlete={header.data} row={row.data} />;
}

/**
 * The row, the draft and the captured revision belong to ONE identity, ONE athlete AND ONE season: keyed by all three, a session that
 * now belongs to someone else, another athlete or another season starts from nothing (new queries, an empty draft, a new revision).
 */
export function AdminDatosDeportivosEdicionPage() {
  const session = useSession();
  const { id, temporadaId } = useParams();
  const identity = session.status === 'authenticated' ? session.user.id : '';
  return <EditScreen key={`${identity}:${id ?? ''}:${temporadaId ?? ''}`} />;
}
