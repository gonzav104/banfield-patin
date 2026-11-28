import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { validate, EMPTY, sameValues, type Errors, type FieldName, type Values } from '@/features/admin-familias/tutor-form';
import { useFamilyAccount } from '@/features/familia/account';
import { ErrorBlock, LoadingBlock } from '@/features/familia/FamilyStates';
import type { FamilyIdentity } from '@/features/familia/identity';
import { ApiError } from '@/lib/api-error';
import type { ProfileInput } from './api';
import { useClaimPerson, useCreateProfile, useProfile, useUpdateProfile } from './hooks';
import { classify, DISABLED_TEXT, failureText, LOCKED_IDENTITY_TEXT } from './messages';
import type { Person, Profile } from './schemas';

function toInput(values: Values): ProfileInput {
  const input: ProfileInput = { nombre: values.nombre.trim(), apellido: values.apellido.trim() };
  if (values.dni.trim() !== '') input.dni = values.dni.trim();
  if (values.telefono.trim() !== '') input.telefono = values.telefono.trim();
  if (values.correo.trim() !== '') input.email = values.correo.trim();
  if (values.parentesco.trim() !== '') input.parentesco = values.parentesco.trim();
  return input;
}

const valuesOf = (profile: Profile): Values => ({
  nombre: profile.nombre,
  apellido: profile.apellido,
  dni: '',
  telefono: profile.telefono ?? '',
  correo: profile.email ?? '',
  parentesco: profile.parentesco ?? '',
});

/** The same fields the club's own form has, with the identity ones (name, DNI) locked once the club verified them. */
function ProfileForm({ values, errors, lockedIdentity, maskedDni, onChange }: { values: Values; errors: Errors; lockedIdentity: boolean; maskedDni: string | null; onChange: (field: FieldName, value: string) => void }) {
  return (
    <div className="space-y-5">
      {lockedIdentity ? <Alert tone="info">{LOCKED_IDENTITY_TEXT}</Alert> : null}
      <div className="grid gap-5 sm:grid-cols-2">
        <Field label="Nombre" required maxLength={100} autoComplete="given-name" disabled={lockedIdentity} value={values.nombre} error={errors.nombre} onChange={(e) => onChange('nombre', e.target.value)} />
        <Field label="Apellido" required maxLength={100} autoComplete="family-name" disabled={lockedIdentity} value={values.apellido} error={errors.apellido} onChange={(e) => onChange('apellido', e.target.value)} />
      </div>
      {lockedIdentity ? (
        <p className="text-sm text-muted">DNI: {maskedDni ?? 'no informado'}</p>
      ) : (
        <Field
          label={maskedDni ? 'Cambiar el DNI (opcional)' : 'DNI (opcional)'}
          maxLength={20}
          inputMode="numeric"
          autoComplete="off"
          hint={maskedDni ? `Ahora figura ${maskedDni}. Dejalo vacío para no cambiarlo.` : 'Entre 7 y 9 dígitos; podés escribirlo con puntos.'}
          value={values.dni}
          error={errors.dni}
          onChange={(e) => onChange('dni', e.target.value)}
        />
      )}
      <div className="grid gap-5 sm:grid-cols-2">
        <Field label="Teléfono (opcional)" type="tel" maxLength={40} autoComplete="tel" value={values.telefono} error={errors.telefono} onChange={(e) => onChange('telefono', e.target.value)} />
        <Field label="Correo electrónico (opcional)" type="email" inputMode="email" maxLength={180} autoComplete="email" value={values.correo} error={errors.correo} onChange={(e) => onChange('correo', e.target.value)} />
      </div>
      <Field label="Parentesco (opcional)" maxLength={40} autoComplete="off" hint="Por ejemplo: madre, padre, abuela." value={values.parentesco} error={errors.parentesco} onChange={(e) => onChange('parentesco', e.target.value)} />
    </div>
  );
}

function ClaimList({ identity, people, onNone, onNotice, onStale }: { identity: FamilyIdentity; people: Person[]; onNone: () => void; onNotice: (notice: Notice | null) => void; onStale: () => void }) {
  const claim = useClaimPerson(identity);
  const sending = useRef(false);

  function choose(person: Person) {
    if (sending.current || claim.isPending) return;
    sending.current = true;
    onNotice(null);
    claim.mutate({ tutorId: person.tutorId, version: person.version }, {
      onSettled: () => {
        sending.current = false;
      },
      onSuccess: () => onNotice({ tone: 'success', text: 'Listo: asociamos tus datos. Revisalos y, si hace falta, completalos.' }),
      onError: (failure) => {
        const error = ApiError.from(failure);
        onNotice({ tone: 'error', text: failureText(classify(error), error) });
        // The person is gone (or someone else took it): show the list as it is now, not the one the person already saw.
        onStale();
      },
    });
  }

  return (
    <section aria-labelledby="claim-title" className="space-y-4">
      <h2 id="claim-title" className="text-lg font-bold">¿Sos alguna de estas personas?</h2>
      <p className="text-sm text-muted">El club ya tiene estas personas en tu familia. Si alguna sos vos, tocá «Soy yo» y no hace falta cargar tus datos de nuevo.</p>
      <ul aria-label="Personas de tu familia" className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-white">
        {people.map((person) => (
          <li key={person.tutorId} className="flex flex-wrap items-center justify-between gap-3 px-4 py-4">
            <p className="min-w-0 break-words font-semibold">
              {person.nombre} {person.apellido}
              {person.parentesco ? <span className="font-normal text-muted"> · {person.parentesco}</span> : null}
            </p>
            <Button type="button" variant="outline" disabled={claim.isPending} aria-label={`Soy ${person.nombre} ${person.apellido}`} onClick={() => choose(person)}>
              Soy yo
            </Button>
          </li>
        ))}
      </ul>
      <Button type="button" variant="ghost" onClick={onNone}>
        No soy ninguna de ellas
      </Button>
    </section>
  );
}

type Notice = { tone: 'error' | 'success'; text: string };

function ProfileEditor({ identity, profile, editable, onNotice, onStale }: { identity: FamilyIdentity; profile: Profile | null; editable: boolean; onNotice: (notice: Notice | null) => void; onStale: () => void }) {
  const create = useCreateProfile(identity);
  const update = useUpdateProfile(identity);
  const initial = profile ? valuesOf(profile) : EMPTY;
  const [values, setValues] = useState<Values>(initial);
  const [errors, setErrors] = useState<Errors>({});
  const sending = useRef(false);
  const pending = create.isPending || update.isPending;
  const unchanged = profile !== null && sameValues(values, initial);

  function change(field: FieldName, value: string) {
    setValues((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
    onNotice(null);
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (sending.current || pending) return;
    const found = validate(values);
    if (Object.keys(found).length > 0) {
      setErrors(found);
      return;
    }
    sending.current = true;
    const input = toInput(values);
    const done = {
      onSuccess: () => {
        onNotice({ tone: 'success', text: profile ? 'Listo: guardamos tus datos.' : 'Listo: tus datos quedaron cargados. El club los va a revisar.' });
      },
      onError: (failure: unknown) => {
        const error = ApiError.from(failure);
        const kind = classify(error);
        onNotice({ tone: 'error', text: failureText(kind, error) });
        // The data changed under the person (stale) or the answer was lost: show what is stored now instead of guessing.
        if (kind === 'stale' || kind === 'uncertain' || kind === 'exists') onStale();
      },
      onSettled: () => {
        sending.current = false;
      },
    };
    if (profile) update.mutate({ ...input, version: profile.version }, done);
    else create.mutate(input, done);
  }

  if (!editable && !profile) return null;
  return (
    <form onSubmit={submit} noValidate aria-labelledby="data-title" className="space-y-5">
      <h2 id="data-title" className="text-lg font-bold">{profile ? 'Tus datos' : 'Cargá tus datos'}</h2>
      {profile ? (
        <p className="text-sm text-muted">
          {profile.cuentaConfirmada
            ? 'El club confirmó que esta cuenta es tuya.'
            : profile.estado === 'VERIFICADO'
              ? 'Estos datos los cargó el club. Todavía tiene que confirmar que esta cuenta es tuya.'
              : 'El club todavía tiene que revisar tus datos.'}
        </p>
      ) : (
        <p className="text-sm text-muted">Con esto el club te reconoce. No hace falta nada más y no cambia a qué deportistas podés ver.</p>
      )}
      {profile?.estado === 'OBSERVADO' && profile.motivoObservacion ? (
        <Alert tone="warning">
          <span className="font-semibold">Lo que pidió el club:</span> {profile.motivoObservacion} Corregí tus datos y guardá: el club los vuelve a revisar.
        </Alert>
      ) : null}
      <ProfileForm values={values} errors={errors} lockedIdentity={profile ? !profile.identidadEditable : false} maskedDni={profile?.dniEnmascarado ?? null} onChange={change} />
      {editable ? (
        <Button type="submit" disabled={pending || unchanged}>
          {profile ? 'Guardar cambios' : 'Guardar mis datos'}
        </Button>
      ) : null}
    </form>
  );
}

function Content({ identity }: { identity: FamilyIdentity }) {
  const state = useProfile(identity);
  const [declined, setDeclined] = useState(false);
  // The message outlives the form that produced it: saving replaces the profile and re-creates the form.
  const [notice, setNotice] = useState<Notice | null>(null);
  if (state.isPending) return <LoadingBlock label="Cargando tus datos…" />;
  if (state.isError) return <ErrorBlock error={state.error} onRetry={() => void state.refetch()} />;
  const { habilitado, perfil, personasSinCuenta } = state.data;
  const profile = perfil ?? null;
  const showClaims = habilitado && !profile && personasSinCuenta.length > 0 && !declined;

  return (
    <div className="space-y-6">
      {habilitado ? null : <Alert tone="info">{DISABLED_TEXT}</Alert>}
      {notice ? <Alert tone={notice.tone} role={notice.tone === 'error' ? 'alert' : 'status'}>{notice.text}</Alert> : null}
      {showClaims ? <ClaimList identity={identity} people={personasSinCuenta} onNone={() => setDeclined(true)} onNotice={setNotice} onStale={() => void state.refetch()} /> : null}
      {!showClaims && !(profile === null && !habilitado) ? (
        <ProfileEditor key={profile ? `${profile.tutorId}:${profile.version}` : 'nuevo'} identity={identity} profile={profile} editable={habilitado} onNotice={setNotice} onStale={() => void state.refetch()} />
      ) : null}
      {profile === null && !habilitado ? <p className="text-sm text-muted">Todavía no hay datos tuyos cargados.</p> : null}
    </div>
  );
}

/** «Mis datos» (/familia/mis-datos): the account's own tutor profile, in plain words. Declaring data never changes who can see what. */
export function MisDatosPage() {
  const account = useFamilyAccount();
  if (!account || !account.familyId) return null;
  return (
    <div className="max-w-2xl space-y-6">
      <Link to="/familia" className="inline-flex min-h-11 items-center text-sm font-semibold text-brand-700 hover:underline">
        ← Volver al inicio
      </Link>
      <header className="space-y-1">
        <p className="text-sm font-semibold text-brand-700">Mi familia</p>
        <h1 className="text-3xl font-extrabold tracking-tight">Mis datos</h1>
        <p className="text-muted">Tus datos de contacto, para que el club pueda comunicarse con vos.</p>
      </header>
      <Content key={`${account.identity.userId}:${account.identity.familyId}`} identity={account.identity} />
    </div>
  );
}
