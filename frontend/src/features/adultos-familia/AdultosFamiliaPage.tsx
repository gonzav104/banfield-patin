import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { EMPTY, sameValues, validate, type Errors, type FieldName, type Values } from '@/features/admin-familias/tutor-form';
import { useFamilyAccount } from '@/features/familia/account';
import { ErrorBlock, LoadingBlock } from '@/features/familia/FamilyStates';
import type { FamilyIdentity } from '@/features/familia/identity';
import { StatusBadge, type BadgeTone } from '@/features/familia/StatusBadge';
import { ApiError } from '@/lib/api-error';
import type { AdultInput } from './api';
import { useAdults, useCreateAdult, useUpdateAdult } from './hooks';
import { CAP_REACHED_TEXT, classify, DISABLED_TEXT, failureText, LOCKED_IDENTITY_TEXT, NO_ACCESS_TEXT, OWN_ACCOUNT_TEXT, STATE_TEXT, type AdultFailure } from './messages';
import type { Adult } from './schemas';

type Notice = { tone: 'error' | 'success'; text: string };

const STATE_TONE: Record<string, BadgeTone> = { DECLARADO: 'neutral', VERIFICADO: 'positive', OBSERVADO: 'warning' };

function toInput(values: Values): AdultInput {
  const input: AdultInput = { nombre: values.nombre.trim(), apellido: values.apellido.trim() };
  if (values.dni.trim() !== '') input.dni = values.dni.trim();
  if (values.telefono.trim() !== '') input.telefono = values.telefono.trim();
  if (values.correo.trim() !== '') input.email = values.correo.trim();
  if (values.parentesco.trim() !== '') input.parentesco = values.parentesco.trim();
  return input;
}

/** The DNI field starts empty: empty means «keep the one already loaded», which the family only ever sees masked. */
const valuesOf = (adult: Adult): Values => ({
  nombre: adult.nombre,
  apellido: adult.apellido,
  dni: '',
  telefono: adult.telefono ?? '',
  correo: adult.email ?? '',
  parentesco: adult.parentesco ?? '',
});

interface FormProps {
  values: Values;
  errors: Errors;
  lockedIdentity: boolean;
  maskedDni: string | null;
  onChange: (field: FieldName, value: string) => void;
}

function AdultFields({ values, errors, lockedIdentity, maskedDni, onChange }: FormProps) {
  return (
    <div className="space-y-5">
      {lockedIdentity ? <Alert tone="info">{LOCKED_IDENTITY_TEXT}</Alert> : null}
      <div className="grid gap-5 sm:grid-cols-2">
        <Field label="Nombre" required maxLength={100} autoComplete="off" readOnly={lockedIdentity} disabled={lockedIdentity} value={values.nombre} error={errors.nombre} onChange={(e) => onChange('nombre', e.target.value)} />
        <Field label="Apellido" required maxLength={100} autoComplete="off" readOnly={lockedIdentity} disabled={lockedIdentity} value={values.apellido} error={errors.apellido} onChange={(e) => onChange('apellido', e.target.value)} />
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
        <Field label="Teléfono (opcional)" type="tel" maxLength={40} autoComplete="off" value={values.telefono} error={errors.telefono} onChange={(e) => onChange('telefono', e.target.value)} />
        <Field label="Correo electrónico (opcional)" type="email" inputMode="email" maxLength={180} autoComplete="off" value={values.correo} error={errors.correo} onChange={(e) => onChange('correo', e.target.value)} />
      </div>
      <Field label="Parentesco (opcional)" maxLength={40} autoComplete="off" hint="Por ejemplo: madre, padre, abuela, tía." value={values.parentesco} error={errors.parentesco} onChange={(e) => onChange('parentesco', e.target.value)} />
    </div>
  );
}

interface FormCommon {
  identity: FamilyIdentity;
  onNotice: (notice: Notice | null) => void;
  /** The list on screen may be out of date (or the answer was lost): read it again. */
  onRefresh: () => void;
  onClose: () => void;
}

/** After a definitive refusal that changes what is allowed (cap, flag, someone else's change), the screen shows the current list. */
const REFRESH_ON: AdultFailure[] = ['stale', 'gone', 'uncertain', 'disabled', 'cap', 'account', 'locked', 'dniApproved'];

function AddAdultForm({ identity, onNotice, onRefresh, onClose }: FormCommon) {
  const create = useCreateAdult(identity);
  const [values, setValues] = useState<Values>(EMPTY);
  const [errors, setErrors] = useState<Errors>({});
  const sending = useRef(false);
  // One key per add attempt: it is kept while the outcome is uncertain and the data are the same, and replaced as soon as the data change.
  const attempt = useRef<{ key: string; signature: string } | null>(null);

  function change(field: FieldName, value: string) {
    setValues((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
    onNotice(null);
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (sending.current || create.isPending) return;
    const found = validate(values);
    if (Object.keys(found).length > 0) {
      setErrors(found);
      return;
    }
    sending.current = true;
    const data = toInput(values);
    const signature = JSON.stringify(data);
    if (attempt.current?.signature !== signature) attempt.current = { key: newKey(), signature };
    create.mutate({ data, key: attempt.current.key }, {
      onSuccess: () => {
        attempt.current = null;
        onNotice({ tone: 'success', text: 'Listo: cargamos al adulto. El club va a revisar sus datos.' });
        onClose();
      },
      onError: (failure) => {
        const error = ApiError.from(failure);
        const kind = classify(error);
        // A refusal that is final for these data (not an uncertain outcome) never reuses the key.
        if (kind !== 'uncertain') attempt.current = null;
        onNotice({ tone: 'error', text: failureText(kind, error) });
        if (REFRESH_ON.includes(kind)) onRefresh();
      },
      onSettled: () => {
        sending.current = false;
      },
    });
  }

  return (
    <form onSubmit={submit} noValidate aria-labelledby="add-title" className="space-y-5 rounded-2xl border border-line bg-white p-5">
      <h2 id="add-title" className="text-lg font-bold">Agregar otro adulto</h2>
      <p className="text-sm text-muted">No hace falta que tenga una cuenta. {NO_ACCESS_TEXT} El club revisa los datos que cargues.</p>
      <AdultFields values={values} errors={errors} lockedIdentity={false} maskedDni={null} onChange={change} />
      <div className="flex flex-wrap gap-3">
        <Button type="submit" disabled={create.isPending}>Guardar adulto</Button>
        <Button type="button" variant="ghost" onClick={onClose}>Cancelar</Button>
      </div>
    </form>
  );
}

function newKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6]! & 0x0f) | 0x40;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function EditAdultForm({ adult, identity, onNotice, onRefresh, onClose }: FormCommon & { adult: Adult }) {
  const update = useUpdateAdult(identity);
  const initial = valuesOf(adult);
  const [values, setValues] = useState<Values>(initial);
  const [errors, setErrors] = useState<Errors>({});
  const sending = useRef(false);
  const unchanged = sameValues(values, initial);
  const titleId = `edit-title-${adult.id}`;

  function change(field: FieldName, value: string) {
    setValues((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
    onNotice(null);
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (sending.current || update.isPending) return;
    const found = validate(values);
    if (Object.keys(found).length > 0) {
      setErrors(found);
      return;
    }
    sending.current = true;
    update.mutate({ id: adult.id, data: toInput(values), version: adult.version }, {
      onSuccess: () => {
        onNotice({ tone: 'success', text: 'Listo: guardamos los cambios.' });
        onClose();
      },
      onError: (failure) => {
        const error = ApiError.from(failure);
        const kind = classify(error);
        onNotice({ tone: 'error', text: failureText(kind, error) });
        if (REFRESH_ON.includes(kind)) onRefresh();
      },
      onSettled: () => {
        sending.current = false;
      },
    });
  }

  return (
    <form onSubmit={submit} noValidate aria-labelledby={titleId} className="space-y-5 border-t border-line pt-4">
      <h3 id={titleId} className="text-base font-bold">Editar los datos de {adult.nombre} {adult.apellido}</h3>
      <AdultFields values={values} errors={errors} lockedIdentity={!adult.identidadEditable} maskedDni={adult.dniEnmascarado ?? null} onChange={change} />
      <div className="flex flex-wrap gap-3">
        <Button type="submit" disabled={update.isPending || unchanged}>Guardar cambios</Button>
        <Button type="button" variant="ghost" onClick={onClose}>Cancelar</Button>
      </div>
    </form>
  );
}

function AdultCard({ adult, identity, canWrite, editing, onEdit, onClose, onNotice, onRefresh }: FormCommon & { adult: Adult; canWrite: boolean; editing: boolean; onEdit: () => void }) {
  const fullName = `${adult.nombre} ${adult.apellido}`;
  return (
    <li className="space-y-3 px-4 py-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <p className="min-w-0 break-words font-semibold">
          {fullName}
          {adult.parentesco ? <span className="font-normal text-muted"> · {adult.parentesco}</span> : null}
        </p>
        <div className="flex flex-wrap gap-2">
          <StatusBadge tone={STATE_TONE[adult.estado] ?? 'neutral'} srLabel="Estado de los datos: ">{STATE_TEXT[adult.estado] ?? 'En revisión'}</StatusBadge>
          {adult.esMiPerfil ? <StatusBadge tone="neutral">Sos vos</StatusBadge> : null}
          {adult.tieneCuentaPropia ? <StatusBadge tone="neutral">Tiene su propia cuenta</StatusBadge> : null}
          {adult.cargadoPorElClub ? <StatusBadge tone="neutral">Cargado por el club</StatusBadge> : null}
        </div>
      </div>
      <dl className="grid gap-x-6 gap-y-1 text-sm text-muted sm:grid-cols-2">
        <div className="flex gap-1"><dt>DNI:</dt><dd>{adult.dniEnmascarado ?? 'no informado'}</dd></div>
        <div className="flex gap-1"><dt>Teléfono:</dt><dd>{adult.telefono ?? 'no informado'}</dd></div>
        <div className="flex min-w-0 gap-1 sm:col-span-2"><dt>Correo:</dt><dd className="break-all">{adult.email ?? 'no informado'}</dd></div>
      </dl>
      {adult.estado === 'OBSERVADO' && adult.motivoObservacion ? (
        <Alert tone="warning">
          <span className="font-semibold">Lo que pidió el club:</span> {adult.motivoObservacion}
          {canWrite && adult.editable ? ' Corregí los datos y guardá: el club los vuelve a revisar.' : null}
        </Alert>
      ) : null}
      {adult.tieneCuentaPropia && !adult.editable ? <p className="text-sm text-muted">{OWN_ACCOUNT_TEXT}</p> : null}
      {canWrite && adult.editable && !editing ? (
        <Button type="button" variant="outline" aria-label={`Editar los datos de ${fullName}`} onClick={onEdit}>Editar</Button>
      ) : null}
      {adult.esMiPerfil && adult.editable ? (
        <p className="text-sm text-muted">Tus propios datos también se pueden ver y cambiar en <Link className="font-semibold text-brand-700 hover:underline" to="/familia/mis-datos">Mis datos</Link>.</p>
      ) : null}
      {editing ? <EditAdultForm adult={adult} identity={identity} onClose={onClose} onNotice={onNotice} onRefresh={onRefresh} /> : null}
    </li>
  );
}

function Content({ identity }: { identity: FamilyIdentity }) {
  const state = useAdults(identity);
  const [adding, setAdding] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  // The message outlives the form that produced it: saving closes the form.
  const [notice, setNotice] = useState<Notice | null>(null);
  if (state.isPending) return <LoadingBlock label="Cargando los adultos de tu familia…" />;
  if (state.isError) return <ErrorBlock error={state.error} onRetry={() => void state.refetch()} />;
  const { habilitado, tope, cargadosPorLaFamilia, adultos } = state.data;
  const capReached = cargadosPorLaFamilia >= tope;
  const refresh = () => void state.refetch();

  return (
    <div className="space-y-6">
      {habilitado ? null : <Alert tone="info">{DISABLED_TEXT}</Alert>}
      {notice ? <Alert tone={notice.tone} role={notice.tone === 'error' ? 'alert' : 'status'}>{notice.text}</Alert> : null}
      {adultos.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-line bg-white p-6 text-sm text-muted">Todavía no hay otros adultos cargados en tu familia.</p>
      ) : (
        <ul aria-label="Adultos de tu familia" className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-white">
          {adultos.map((adult) => (
            <AdultCard
              key={`${adult.id}:${adult.version}`}
              adult={adult}
              identity={identity}
              canWrite={habilitado}
              editing={editingId === adult.id}
              onEdit={() => {
                setAdding(false);
                setNotice(null);
                setEditingId(adult.id);
              }}
              onClose={() => setEditingId(null)}
              onNotice={setNotice}
              onRefresh={refresh}
            />
          ))}
        </ul>
      )}
      {habilitado ? (
        adding ? (
          <AddAdultForm identity={identity} onClose={() => setAdding(false)} onNotice={setNotice} onRefresh={refresh} />
        ) : capReached ? (
          <Alert tone="info">{CAP_REACHED_TEXT}</Alert>
        ) : (
          <Button
            type="button"
            onClick={() => {
              setEditingId(null);
              setNotice(null);
              setAdding(true);
            }}
          >
            Agregar otro adulto
          </Button>
        )
      ) : null}
    </div>
  );
}

/** «Otros adultos» (/familia/adultos): the adults of the family, with or without an account. Loading one never grants access to athletes. */
export function AdultosFamiliaPage() {
  const account = useFamilyAccount();
  if (!account || !account.familyId) return null;
  return (
    <div className="max-w-2xl space-y-6">
      <Link to="/familia" className="inline-flex min-h-11 items-center text-sm font-semibold text-brand-700 hover:underline">
        ← Volver al inicio
      </Link>
      <header className="space-y-1">
        <p className="text-sm font-semibold text-brand-700">Mi familia</p>
        <h1 className="text-3xl font-extrabold tracking-tight">Otros adultos</h1>
        <p className="text-muted">Los adultos de tu familia que el club conoce. No necesitan una cuenta. {NO_ACCESS_TEXT}</p>
      </header>
      <Content key={`${account.identity.userId}:${account.identity.familyId}`} identity={account.identity} />
    </div>
  );
}
