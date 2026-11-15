import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { useSession } from '@/features/auth/session';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { ApiError } from '@/lib/api-error';
import { isSafeFamilyId } from './api';
import { FamilyNotFound } from './FamilyNotFound';
import { FamilyStatusBadge } from './FamilyStatusBadge';
import { useCreateTutor, useFamilyDetail } from './hooks';
import { classifyWriteFailure, detailErrorMessage, isNotFound, type WriteFailure } from './messages';
import type { FamilyDetail, TutorInput } from './schemas';
import {
  createTutorErrorMessage,
  FAMILY_INACTIVE_NOTICE,
  INACTIVE_TUTOR_TEXT,
  INVALID_DNI,
  INVALID_EMAIL,
  maxLengthText,
  REQUIRED_LASTNAME,
  REQUIRED_NAME,
  STALE_TUTOR_TEXT,
  UNCERTAIN_TUTOR_TEXT,
  VERIFY_DUPLICATE_WARNING,
  VERIFY_NO_MATCH,
  VERIFY_ONE_MATCH,
  VERIFY_RESEND_BLOCKED,
  verifyManyMatches,
} from './tutor-messages';
import { useUnsavedChangesGuard } from './useUnsavedChangesGuard';

type Result = { family: FamilyDetail; error?: undefined } | { error: ApiError; family?: undefined };

const FIELDS = ['nombre', 'apellido', 'dni', 'telefono', 'correo', 'parentesco'] as const;
type FieldName = (typeof FIELDS)[number];
type Values = Record<FieldName, string>;
type Errors = Partial<Record<FieldName, string>>;
const EMPTY: Values = { nombre: '', apellido: '', dni: '', telefono: '', correo: '', parentesco: '' };

/** Same limits and DNI rule as the backend (TutorSolicitud, @Dni: 7 to 9 digits once dots and spaces are removed). */
function validate(values: Values): Errors {
  const errors: Errors = {};
  const nombre = values.nombre.trim();
  const apellido = values.apellido.trim();
  if (nombre === '') errors.nombre = REQUIRED_NAME;
  else if (nombre.length > 100) errors.nombre = maxLengthText(100);
  if (apellido === '') errors.apellido = REQUIRED_LASTNAME;
  else if (apellido.length > 100) errors.apellido = maxLengthText(100);
  const dni = values.dni.trim();
  if (dni !== '') {
    if (dni.length > 20) errors.dni = maxLengthText(20);
    else if (!/^[0-9]{7,9}$/.test(dni.replace(/[.\s]/g, ''))) errors.dni = INVALID_DNI;
  }
  if (values.telefono.trim().length > 40) errors.telefono = maxLengthText(40);
  if (values.parentesco.trim().length > 40) errors.parentesco = maxLengthText(40);
  const correo = values.correo.trim();
  if (correo !== '') {
    if (correo.length > 180) errors.correo = maxLengthText(180);
    else if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(correo)) errors.correo = INVALID_EMAIL;
  }
  return errors;
}

/** Only the filled fields travel, trimmed; the backend turns a blank optional into null anyway. */
function toInput(values: Values): TutorInput {
  const input: TutorInput = { nombre: values.nombre.trim(), apellido: values.apellido.trim() };
  if (values.dni.trim() !== '') input.dni = values.dni.trim();
  if (values.telefono.trim() !== '') input.telefono = values.telefono.trim();
  if (values.correo.trim() !== '') input.email = values.correo.trim();
  if (values.parentesco.trim() !== '') input.parentesco = values.parentesco.trim();
  return input;
}

const same = (a: string, b: string) => a.trim().toLocaleLowerCase('es') === b.trim().toLocaleLowerCase('es');

/** What the administrator saw when the operation started: the revision sent is this one and changes only by an explicit choice. */
interface Base {
  version: string;
  activa: boolean;
  tutorIds: Set<string>;
}

const baseOf = (family: FamilyDetail): Base => ({ version: family.version, activa: family.activa, tutorIds: new Set(family.tutores.map((t) => t.id)) });

type Phase = 'editing' | 'stale' | 'inactive' | 'uncertain' | 'reviewing' | 'verified';

interface TutorFormProps {
  family: FamilyDetail;
  /** Reloads the family on request. */
  reload: () => Promise<Result>;
}

/**
 * New tutor inside a family. The draft lives in this component's memory only. The family revision to send (`base.version`) is
 * captured when the form opens and changes ONLY when the administrator, after seeing the current data (to review a conflict or to
 * verify an uncertain result), explicitly chooses to go on: it is never refreshed silently and there is no forced save.
 * Creating a tutor does not move the family revision, so after an uncertain answer the form stays blocked until the administrator
 * verifies: a blind resend with the same revision could create the tutor twice. The verification is only a HINT (the backend has
 * no idempotency): a same-name tutor is never presented as proof that our request created it, and not finding one is never
 * presented as proof that the request will not be applied later. A new attempt is always an explicit action, with a warning.
 */
function TutorForm({ family, reload }: TutorFormProps) {
  const navigate = useNavigate();
  const create = useCreateTutor();
  const [base, setBase] = useState<Base>(() => baseOf(family));
  const [values, setValues] = useState<Values>(EMPTY);
  const [errors, setErrors] = useState<Errors>({});
  const [phase, setPhase] = useState<Phase>('editing');
  const [failure, setFailure] = useState<{ kind: WriteFailure; error: ApiError } | null>(null);
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState<string | null>(null);
  const [fresh, setFresh] = useState<FamilyDetail | null>(null);
  const [matches, setMatches] = useState(0);

  const dirty = FIELDS.some((field) => values[field].trim() !== '');
  const guard = useUnsavedChangesGuard(dirty && !create.isPending);
  const familyPath = `/admin/familias/${encodeURIComponent(family.id)}`;

  function set(field: FieldName, value: string) {
    setValues((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (create.isPending || phase !== 'editing' || !base.activa) return; // a second submit while one is in flight must never create two
    const found = validate(values);
    if (Object.keys(found).length > 0) {
      setErrors(found);
      return;
    }
    setErrors({});
    setFailure(null);
    create.mutate(
      { familyId: family.id, datos: toInput(values), version: base.version },
      {
        onSuccess: () => void navigate(familyPath, { replace: true, state: { tutorCreated: true } }),
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

  async function check(purpose: 'review' | 'verify') {
    setChecking(true);
    setCheckError(null);
    const result = await reload();
    setChecking(false);
    if (result.error) {
      setCheckError(detailErrorMessage(result.error));
      return;
    }
    setFresh(result.family);
    if (purpose === 'verify') {
      // Only a HINT: new tutors (ids we did not know at the start) with the same name and last name. Nothing more is compared.
      setMatches(result.family.tutores.filter((t) => !base.tutorIds.has(t.id) && same(t.nombre, values.nombre) && same(t.apellido, values.apellido)).length);
      setPhase('verified');
    } else {
      setPhase('reviewing');
    }
  }

  /** The administrator saw the current data and chooses to build on them: only now does the revision change. */
  function continueOnCurrent() {
    if (!fresh) return;
    setBase(baseOf(fresh));
    setFailure(null);
    setPhase('editing');
  }

  const blocked = phase === 'editing' && !base.activa;
  const canSend = phase === 'editing' && base.activa;
  const needsCheck = phase === 'stale' || phase === 'inactive';
  const saving = create.isPending;

  return (
    <div className="max-w-2xl space-y-6">
      <Link to={familyPath} className="inline-flex min-h-11 items-center text-sm font-semibold text-brand-700 underline-offset-4 hover:underline">
        Volver a la familia
      </Link>
      <header>
        <p className="text-sm font-semibold text-brand-700">Familia: {family.nombreReferencia}</p>
        <h1 className="mt-1 text-3xl font-extrabold tracking-tight">Nuevo tutor</h1>
        <p className="mt-2 text-muted">
          Crear un tutor no genera una cuenta de usuario ni una invitación. El acceso de la familia se gestiona con{' '}
          <Link to="/admin/invitaciones" className="font-semibold text-brand-700 underline underline-offset-4">
            Invitaciones
          </Link>
          .
        </p>
      </header>

      {blocked ? (
        <Alert tone="warning">
          {FAMILY_INACTIVE_NOTICE}{' '}
          <Link to={familyPath} className="font-semibold underline underline-offset-4">
            Ir a la familia
          </Link>
        </Alert>
      ) : (
        <form noValidate aria-label="Nuevo tutor" onSubmit={submit} className="space-y-5 rounded-2xl border border-line bg-white p-5">
          {guard.panel}

          {failure && (phase === 'editing' || phase === 'stale' || phase === 'inactive' || phase === 'uncertain') ? (
            <Alert tone="error">
              {failure.kind === 'stale' ? (
                STALE_TUTOR_TEXT
              ) : failure.kind === 'inactive' ? (
                <>
                  {INACTIVE_TUTOR_TEXT}{' '}
                  <Link to={familyPath} className="font-semibold underline underline-offset-4">
                    Ir a la familia
                  </Link>
                </>
              ) : failure.kind === 'uncertain' ? (
                UNCERTAIN_TUTOR_TEXT
              ) : (
                createTutorErrorMessage(failure.kind, failure.error)
              )}
            </Alert>
          ) : null}
          {checkError ? <Alert tone="error">{checkError}</Alert> : null}

          <div className="grid gap-5 sm:grid-cols-2">
            <Field label="Nombre" required maxLength={100} autoComplete="off" value={values.nombre} error={errors.nombre} onChange={(e) => set('nombre', e.target.value)} />
            <Field label="Apellido" required maxLength={100} autoComplete="off" value={values.apellido} error={errors.apellido} onChange={(e) => set('apellido', e.target.value)} />
          </div>
          <Field
            label="DNI (opcional)"
            maxLength={20}
            autoComplete="off"
            hint="Entre 7 y 9 dígitos; podés escribirlo con puntos."
            value={values.dni}
            error={errors.dni}
            onChange={(e) => set('dni', e.target.value)}
          />
          <div className="grid gap-5 sm:grid-cols-2">
            <Field label="Teléfono (opcional)" type="tel" maxLength={40} autoComplete="off" value={values.telefono} error={errors.telefono} onChange={(e) => set('telefono', e.target.value)} />
            <Field label="Correo electrónico (opcional)" type="email" inputMode="email" maxLength={180} autoComplete="off" value={values.correo} error={errors.correo} onChange={(e) => set('correo', e.target.value)} />
          </div>
          <Field
            label="Parentesco (opcional)"
            maxLength={40}
            autoComplete="off"
            hint="Por ejemplo: madre, padre, tutor legal."
            value={values.parentesco}
            error={errors.parentesco}
            onChange={(e) => set('parentesco', e.target.value)}
          />

          {needsCheck ? (
            <Button variant="outline" disabled={checking} onClick={() => void check('review')}>
              {checking ? 'Cargando…' : 'Ver datos vigentes'}
            </Button>
          ) : null}
          {phase === 'uncertain' ? (
            <Button variant="outline" disabled={checking} onClick={() => void check('verify')}>
              {checking ? 'Verificando…' : 'Verificar si se creó'}
            </Button>
          ) : null}

          {phase === 'reviewing' && fresh ? (
            <div role="group" aria-label="Datos vigentes" className="space-y-3 rounded-lg border border-line bg-paper p-4 text-sm">
              <p>Estos son los datos vigentes de la familia. Tu borrador no se envió ni se mezcló con ellos.</p>
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-semibold">Familia:</span>
                <span className="break-words">{fresh.nombreReferencia}</span>
                <FamilyStatusBadge active={fresh.activa} />
              </div>
              {fresh.activa ? null : <p>{FAMILY_INACTIVE_NOTICE}</p>}
              <div className="flex flex-col gap-2 sm:flex-row">
                {fresh.activa ? (
                  <Button variant="outline" onClick={continueOnCurrent}>
                    Seguir con mi borrador
                  </Button>
                ) : null}
                <Button variant="outline" onClick={() => void navigate(familyPath)}>
                  Descartar mi borrador
                </Button>
              </div>
            </div>
          ) : null}

          {phase === 'verified' && fresh ? (
            <div role="group" aria-label="Verificación" className="space-y-3 rounded-lg border border-line bg-paper p-4 text-sm">
              {matches === 0 ? (
                <>
                  <p>{VERIFY_NO_MATCH}</p>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-semibold">Familia:</span>
                    <span className="break-words">{fresh.nombreReferencia}</span>
                    <FamilyStatusBadge active={fresh.activa} />
                  </div>
                  {fresh.activa ? null : <p>{FAMILY_INACTIVE_NOTICE}</p>}
                  <p className="font-semibold">{VERIFY_DUPLICATE_WARNING}</p>
                  <div className="flex flex-col gap-2 sm:flex-row">
                    <Button asChild variant="outline">
                      <Link to={familyPath}>Revisar la familia</Link>
                    </Button>
                    <Button variant="outline" onClick={continueOnCurrent}>
                      Volver a intentar
                    </Button>
                  </div>
                </>
              ) : (
                <>
                  <p>{matches === 1 ? VERIFY_ONE_MATCH : verifyManyMatches(matches)}</p>
                  <p className="font-semibold">{VERIFY_RESEND_BLOCKED}</p>
                  <Button asChild variant="outline">
                    <Link to={familyPath}>Ver la familia</Link>
                  </Button>
                </>
              )}
            </div>
          ) : null}

          <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
            <Button asChild variant="outline">
              <Link to={familyPath}>Cancelar</Link>
            </Button>
            <Button type="submit" disabled={saving || !canSend} aria-busy={saving}>
              {saving ? 'Agregando…' : 'Agregar tutor'}
            </Button>
          </div>
        </form>
      )}
    </div>
  );
}

export function AdminTutorNuevoPage() {
  const { id } = useParams();
  const session = useSession();
  const valid = isSafeFamilyId(id);
  const family = useFamilyDetail(valid ? id : '', valid);
  // Whatever the form keeps in memory (the draft, the captured revision) belongs to ONE identity: another one starts clean.
  const identity = session.status === 'authenticated' ? session.user.id : '';

  if (!valid) return <FamilyNotFound />;
  if (family.isError && !family.data) {
    const error = ApiError.from(family.error);
    if (isNotFound(error)) return <FamilyNotFound />;
    return (
      <div className="max-w-2xl space-y-4">
        <Alert tone="error">{detailErrorMessage(error)}</Alert>
        <Button variant="outline" onClick={() => void family.refetch()}>
          Reintentar
        </Button>
      </div>
    );
  }
  if (!family.data) return <LoadingBlock label="Cargando la familia…" />;
  const reload = async (): Promise<Result> => {
    const result = await family.refetch();
    if (result.isError || !result.data) return { error: ApiError.from(result.error) };
    return { family: result.data };
  };
  return <TutorForm key={identity} family={family.data} reload={reload} />;
}
