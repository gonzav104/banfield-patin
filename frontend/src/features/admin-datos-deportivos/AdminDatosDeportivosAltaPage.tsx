import { useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { AthleteNotFound } from '@/features/admin-deportistas/AthleteNotFound';
import { useUnsavedChangesGuard } from '@/features/admin-familias/useUnsavedChangesGuard';
import { useSession } from '@/features/auth/session';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { SPORTS_STATUS_LABEL } from '@/features/familia/messages';
import { sportsStatusSchema, type SportsStatus } from '@/features/familia/schemas';
import { ApiError } from '@/lib/api-error';
import { getSportsRow, isSafeId } from './api';
import { allSeasonsKey, athleteHeaderKey, athleteSportsKey, sportsHistoryKey, useAllSeasons, useAthleteHeader, useCreateSportsRow, useSportsHistory } from './hooks';
import {
  ALL_USED_TEXT,
  ALL_USED_TITLE,
  classifyLoadFailure,
  DUPLICATE_AFTER_UNCERTAIN_TEXT,
  DUPLICATE_TEXT,
  INACTIVE_LINK,
  INACTIVE_TEXT,
  INACTIVE_TITLE,
  isAthleteAbsent,
  LOAD_INTRO,
  LOAD_SUBMIT,
  LOAD_SUBMITTING,
  LOAD_TITLE,
  loadErrorMessage,
  LOADING_TEXT,
  NO_SEASONS_TITLE,
  NO_SEASONS_TO_LOAD_TEXT,
  NUMBER_TAKEN_TEXT,
  VERIFY_ABSENT_TEXT,
  VERIFY_BUTTON,
  VERIFY_DIFFERENT_TEXT,
  VERIFY_ERROR_TEXT,
  VERIFY_FOUND_TEXT,
  VERIFY_RETRY_TEXT,
  VERIFY_SAME_TEXT,
  VERIFYING,
  type LoadFailure,
} from './messages';
import { athleteDetailPath, forwardedListState, sportsHistoryPath, sportsRowPath } from './navigation';
import type { AthleteHeader, SportsRow } from './schemas';
import { EMPTY_VALUES, availableSeasons, toInput, validate, MAX_LENGTH, type CreateSportsInput, type Errors, type SeasonOption, type TextField, type Values } from './sports-form';
import { Notice, ReadError, Row, SportsLink, SportsStatusBadge, textOrNotInformed } from './SportsUi';

/** `editing`: the form can be sent. `uncertain`: a load whose result is unknown, resend blocked until the administrator verifies. `verified`: they saw the reading. */
type Phase = 'editing' | 'uncertain' | 'verified';

const SELECT_CLASS = 'block min-h-11 w-full rounded-lg border border-neutral-400 bg-white px-3 text-base text-ink aria-[invalid=true]:border-red-700 disabled:bg-neutral-100';

const STATUS_OPTIONS = sportsStatusSchema.options;

function sameAsSent(sent: CreateSportsInput, row: SportsRow): boolean {
  return (
    (row.categoria ?? null) === sent.categoria &&
    (row.rama ?? null) === sent.rama &&
    (row.divisional ?? null) === sent.divisional &&
    (row.numeroCorredor ?? null) === sent.numeroCorredor &&
    row.estadoDeportivo === sent.estadoDeportivo
  );
}

/** The active season is flagged in the text of its own option: never only by colour or position. */
const seasonLabel = (season: SeasonOption) => `Temporada ${season.anio}${season.activa ? ' (activa)' : ''}`;

function BackToHistory({ athleteId }: { athleteId: string }) {
  return <SportsLink to={sportsHistoryPath(athleteId)}>Volver al historial deportivo</SportsLink>;
}

function Header({ athlete }: { athlete: AthleteHeader }) {
  return (
    <header className="space-y-2">
      <h1 className="text-3xl font-extrabold tracking-tight">{LOAD_TITLE}</h1>
      <p className="min-w-0 break-words text-lg font-semibold">
        {athlete.nombre} {athlete.apellido}
      </p>
      <p className="text-sm text-muted">{LOAD_INTRO}</p>
    </header>
  );
}

/** What the server confirmed for the season: shown as the state of the world, never as «what this request did». */
function VerifiedRow({ row, sent }: { row: SportsRow; sent: CreateSportsInput }) {
  return (
    <>
      <p>{VERIFY_FOUND_TEXT}</p>
      <dl className="grid gap-2 sm:grid-cols-2">
        <Row label="Categoría">{textOrNotInformed(row.categoria)}</Row>
        <Row label="Rama">{textOrNotInformed(row.rama)}</Row>
        <Row label="Divisional">{textOrNotInformed(row.divisional)}</Row>
        <Row label="Número de corredor">{textOrNotInformed(row.numeroCorredor)}</Row>
        <Row label={`Estado deportivo de ${row.anio}`}>
          <SportsStatusBadge status={row.estadoDeportivo} />
        </Row>
      </dl>
      <p>{sameAsSent(sent, row) ? VERIFY_SAME_TEXT : VERIFY_DIFFERENT_TEXT}</p>
    </>
  );
}

/**
 * The load form. The draft lives in this component's memory only (nothing is persisted, nothing goes in the address). Nothing is sent
 * until the administrator submits, and only a confirmed `201` navigates to the detail of the new row. After an answer that cannot be
 * confirmed (lost response, timeout, 5xx, unreadable 201) the form stays closed: sending again is an explicit decision taken after
 * a read-only look at THAT season. The look is only evidence: a row that is there is never presented as proof that OUR request
 * created it, and one that is not there is never presented as proof that the request will not be applied later.
 */
function LoadForm({ athlete, available }: { athlete: AthleteHeader; available: SeasonOption[] }) {
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const create = useCreateSportsRow();
  const [values, setValues] = useState<Values>(EMPTY_VALUES);
  const [errors, setErrors] = useState<Errors>({});
  const [phase, setPhase] = useState<Phase>('editing');
  const [failure, setFailure] = useState<LoadFailure | null>(null);
  const [failureError, setFailureError] = useState<ApiError | null>(null);
  /** The exact body of the last request sent: what the verification compares with and what a retry would repeat. */
  const [sent, setSent] = useState<CreateSportsInput | null>(null);
  /** The season a duplicate answer was about, so the administrator can go and look at it. */
  const [duplicateOf, setDuplicateOf] = useState<{ id: string; anio: number } | null>(null);
  /** An earlier attempt ended without a confirmed answer: a later duplicate answer is then ambiguous. */
  const [hadUncertain, setHadUncertain] = useState(false);
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState(false);
  const [verified, setVerified] = useState<{ row: SportsRow | null } | null>(null);
  const [athleteGone, setAthleteGone] = useState(false);

  /** Set synchronously on submit: `isPending` only turns true after React Query notifies, so several submits of ONE task would all pass it. */
  const sending = useRef(false);

  const dirty = values.temporadaId !== '' || values.estadoDeportivo !== 'ACTIVO' || (Object.keys(MAX_LENGTH) as TextField[]).some((field) => values[field].trim() !== '');
  const guard = useUnsavedChangesGuard(dirty && !create.isPending);
  const saving = create.isPending;
  /** Locked while the request is in flight too: what is typed after the click would not travel and would be lost behind a «loaded». */
  const locked = phase !== 'editing' || saving;
  const selected = available.find((season) => season.id === values.temporadaId);
  /** A season that left the list (another administrator loaded it meanwhile) is no longer chosen: what is sent is what the selector shows. */
  const current: Values = { ...values, temporadaId: selected ? values.temporadaId : '' };

  function set<K extends keyof Values>(field: K, value: Values[K]) {
    setValues((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (sending.current || create.isPending || phase !== 'editing') return; // a second submit while one is in flight must never load twice
    const found = validate(current);
    if (Object.keys(found).length > 0) {
      setErrors(found);
      return;
    }
    const input = toInput(current);
    setErrors({});
    setFailure(null);
    setFailureError(null);
    setDuplicateOf(null);
    setSent(input);
    sending.current = true;
    create.mutate(
      { athleteId: athlete.id, datos: input },
      {
        onSettled: () => {
          sending.current = false;
        },
        onSuccess: (row) =>
          void navigate(sportsRowPath(athlete.id, row.temporadaId), { replace: true, state: { sportsRowCreated: true, ...forwardedListState(location.state) } }),
        onError: (error) => {
          const apiError = ApiError.from(error);
          const kind = classifyLoadFailure(apiError);
          setFailure(kind);
          setFailureError(apiError);
          if (kind === 'numberTaken') setErrors({ numeroCorredor: NUMBER_TAKEN_TEXT });
          if (kind === 'duplicate') {
            const season = available.find((candidate) => candidate.id === input.temporadaId);
            if (season) setDuplicateOf({ id: season.id, anio: season.anio });
            setValues((current) => ({ ...current, temporadaId: '' }));
            void queryClient.invalidateQueries({ queryKey: sportsHistoryKey(athlete.id) });
          }
          if (kind === 'seasonGone') {
            setValues((current) => ({ ...current, temporadaId: '' }));
            void queryClient.invalidateQueries({ queryKey: allSeasonsKey });
          }
          if (kind === 'inactive') void queryClient.invalidateQueries({ queryKey: athleteHeaderKey(athlete.id) });
          if (kind === 'athleteGone') setAthleteGone(true);
          if (kind === 'uncertain') {
            setHadUncertain(true);
            setPhase('uncertain');
          }
        },
      },
    );
  }

  /**
   * Read-only: ONE GET of the row of the season that was SENT (not the one now selected). Nothing is cached and nothing goes in the
   * address. A 404 of the row is an answer; any other failure is «could not verify», never a conclusion.
   */
  async function verify() {
    if (!sent) return;
    setChecking(true);
    setCheckError(false);
    try {
      const row = await getSportsRow(athlete.id, sent.temporadaId);
      setVerified({ row });
      setPhase('verified');
    } catch (error) {
      const apiError = ApiError.from(error);
      if (apiError.status === 404 && apiError.code === 'DEPORTISTA_TEMPORADA_NO_ENCONTRADA') {
        setVerified({ row: null });
        setPhase('verified');
      } else if (apiError.status === 404 && apiError.code === 'TEMPORADA_NO_ENCONTRADA') {
        setFailure('seasonGone');
        setValues((current) => ({ ...current, temporadaId: '' }));
        void queryClient.invalidateQueries({ queryKey: allSeasonsKey });
        setPhase('editing');
      } else if (apiError.status === 404 || apiError.status === 400) {
        setAthleteGone(true);
      } else {
        setCheckError(true);
      }
    } finally {
      setChecking(false);
    }
  }

  /** The administrator saw the reading and chooses to send again: only now does the form open. The draft is the one they had. */
  function retry() {
    setFailure(null);
    setFailureError(null);
    setPhase('editing');
  }

  /** The reading found data in the season: the administrator moves on to another one (the lists are read again). */
  function chooseAnother() {
    setValues((previous) => ({ ...previous, temporadaId: '' }));
    setHadUncertain(false);
    setFailure(null);
    setFailureError(null);
    setPhase('editing');
    void queryClient.invalidateQueries({ queryKey: athleteSportsKey(athlete.id) });
  }

  if (athleteGone) return <AthleteNotFound />;

  const showDuplicateHint = hadUncertain && failure === 'duplicate';
  const alert =
    failure && failure !== 'numberTaken' && failure !== 'athleteGone' && failureError ? (
      <Alert tone={failure === 'duplicate' ? 'warning' : 'error'}>
        {failure === 'duplicate' ? DUPLICATE_TEXT : loadErrorMessage(failure, failureError)}
        {failure === 'duplicate' && duplicateOf ? (
          <>
            {' '}
            <Link to={sportsRowPath(athlete.id, duplicateOf.id)} className="font-semibold underline underline-offset-4">
              Ver los datos de la temporada {duplicateOf.anio}
            </Link>
          </>
        ) : null}
      </Alert>
    ) : null;

  if (available.length === 0 && phase === 'editing') {
    return (
      <div className="max-w-2xl space-y-6">
        <BackToHistory athleteId={athlete.id} />
        <Header athlete={athlete} />
        {alert}
        {showDuplicateHint ? <Alert tone="warning">{DUPLICATE_AFTER_UNCERTAIN_TEXT}</Alert> : null}
        <Notice title={ALL_USED_TITLE}>{ALL_USED_TEXT}</Notice>
      </div>
    );
  }

  return (
    <div className="max-w-2xl space-y-6">
      <BackToHistory athleteId={athlete.id} />
      <Header athlete={athlete} />

      <form noValidate aria-label="Cargar datos deportivos" onSubmit={submit} className="space-y-5 rounded-2xl border border-line bg-white p-5">
        {guard.panel}
        {alert}
        {showDuplicateHint ? <Alert tone="warning">{DUPLICATE_AFTER_UNCERTAIN_TEXT}</Alert> : null}
        {checkError ? <Alert tone="error">{VERIFY_ERROR_TEXT}</Alert> : null}

        <fieldset disabled={locked} className="m-0 min-w-0 space-y-5 border-0 p-0">
          <legend className="sr-only">Datos deportivos de la temporada</legend>
          <SeasonSelect value={current.temporadaId} error={errors.temporadaId} seasons={available} onChange={(id) => set('temporadaId', id)} />
          {selected?.activa ? <p className="-mt-3 text-sm text-muted">Es la temporada activa. Las temporadas inactivas también admiten datos.</p> : null}
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
          <StatusSelect value={values.estadoDeportivo} onChange={(status) => set('estadoDeportivo', status)} />
        </fieldset>

        {phase === 'uncertain' ? (
          <Button variant="outline" disabled={checking} onClick={() => void verify()}>
            {checking ? VERIFYING : VERIFY_BUTTON}
          </Button>
        ) : null}

        {phase === 'verified' && verified && sent ? (
          <div role="group" aria-label="Verificación" className="space-y-3 rounded-lg border border-line bg-paper p-4 text-sm">
            {verified.row ? (
              <>
                <VerifiedRow row={verified.row} sent={sent} />
                <div className="flex flex-col gap-2 sm:flex-row">
                  <Link to={sportsRowPath(athlete.id, verified.row.temporadaId)} className="inline-flex min-h-11 items-center font-semibold text-brand-700 underline underline-offset-4">
                    Ver los datos de la temporada {verified.row.anio}
                  </Link>
                  <Button variant="outline" onClick={chooseAnother}>
                    Elegir otra temporada
                  </Button>
                </div>
              </>
            ) : (
              <>
                <p>{VERIFY_ABSENT_TEXT}</p>
                <p>{VERIFY_RETRY_TEXT}</p>
                <Button variant="outline" onClick={retry}>
                  Volver a intentar
                </Button>
              </>
            )}
          </div>
        ) : null}

        <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
          <Button asChild variant="outline">
            <SportsLink to={sportsHistoryPath(athlete.id)} className="">
              Cancelar
            </SportsLink>
          </Button>
          <Button type="submit" disabled={saving || locked} aria-busy={saving}>
            {saving ? LOAD_SUBMITTING : LOAD_SUBMIT}
          </Button>
        </div>
      </form>
    </div>
  );
}

function FieldLabel({ htmlFor, children, required = false }: { htmlFor: string; children: ReactNode; required?: boolean }) {
  return (
    <label htmlFor={htmlFor} className="block text-sm font-semibold">
      {children}
      {required ? <span aria-hidden="true" className="text-red-700"> *</span> : null}
    </label>
  );
}

function SeasonSelect({ value, error, seasons, onChange }: { value: string; error: string | undefined; seasons: SeasonOption[]; onChange: (id: string) => void }) {
  return (
    <div className="space-y-1.5">
      <FieldLabel htmlFor="sports-season" required>
        Temporada
      </FieldLabel>
      <select
        id="sports-season"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        required
        aria-required="true"
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? 'sports-season-error' : undefined}
        className={SELECT_CLASS}
      >
        <option value="">Elegí una temporada</option>
        {seasons.map((season) => (
          <option key={season.id} value={season.id}>
            {seasonLabel(season)}
          </option>
        ))}
      </select>
      {error ? (
        <p id="sports-season-error" className="text-sm text-red-700">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function StatusSelect({ value, onChange }: { value: SportsStatus; onChange: (status: SportsStatus) => void }) {
  return (
    <div className="space-y-1.5">
      <FieldLabel htmlFor="sports-status">Estado deportivo</FieldLabel>
      <select id="sports-status" value={value} onChange={(event) => onChange(sportsStatusSchema.parse(event.target.value))} className={SELECT_CLASS}>
        {STATUS_OPTIONS.map((status) => (
          <option key={status} value={status}>
            {SPORTS_STATUS_LABEL[status]}
          </option>
        ))}
      </select>
    </div>
  );
}

function LoadScreen() {
  const { id } = useParams();
  const valid = isSafeId(id);
  const athleteId = valid ? id : '';
  const header = useAthleteHeader(athleteId, valid);
  const history = useSportsHistory(athleteId, valid);
  const seasons = useAllSeasons(valid);

  if (!valid) return <AthleteNotFound />;
  // A failed REFRESH must not take the form (and the draft) off the screen: only an error with nothing to show is an error screen.
  const failed = [header, history, seasons].find((query) => query.isError && !query.data);
  if (failed) {
    const error = ApiError.from(failed.error);
    if ((failed === header || failed === history) && isAthleteAbsent(error)) return <AthleteNotFound />;
    return (
      <div className="max-w-2xl space-y-4">
        <BackToHistory athleteId={athleteId} />
        <ReadError
          error={error}
          onRetry={() => {
            for (const query of [header, history, seasons]) if (query.isError) void query.refetch();
          }}
        />
      </div>
    );
  }
  if (!header.data || !history.data || !seasons.data) {
    return (
      <div className="max-w-2xl space-y-4">
        <BackToHistory athleteId={athleteId} />
        <LoadingBlock label={LOADING_TEXT} />
      </div>
    );
  }
  if (!header.data.activo) {
    return (
      <div className="max-w-2xl space-y-4">
        <BackToHistory athleteId={athleteId} />
        <Notice title={INACTIVE_TITLE}>{INACTIVE_TEXT}</Notice>
        <Button asChild variant="outline">
          <SportsLink to={athleteDetailPath(athleteId)} className="">
            {INACTIVE_LINK}
          </SportsLink>
        </Button>
      </div>
    );
  }
  if (seasons.data.length === 0) {
    return (
      <div className="max-w-2xl space-y-4">
        <BackToHistory athleteId={athleteId} />
        <Notice title={NO_SEASONS_TITLE}>{NO_SEASONS_TO_LOAD_TEXT}</Notice>
      </div>
    );
  }
  return <LoadForm athlete={header.data} available={availableSeasons(seasons.data, history.data)} />;
}

/**
 * Loading sports data for ONE athlete and ONE identity. Keyed by both, a session that now belongs to someone else, or a different
 * athlete, starts from nothing (new queries, an empty draft, no data of the previous one) instead of keeping what was already drawn.
 */
export function AdminDatosDeportivosAltaPage() {
  const session = useSession();
  const { id } = useParams();
  const identity = session.status === 'authenticated' ? session.user.id : '';
  return <LoadScreen key={`${identity}:${id ?? ''}`} />;
}
