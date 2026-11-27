import { zodResolver } from '@hookform/resolvers/zod';
import { useId, useMemo } from 'react';
import { useForm } from 'react-hook-form';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { CHECK_BEFORE_SENDING, Pickers, Preview } from '@/features/familia/DniSlot';
import { aptoFormSchema, EMISSION_MIN, schoolToday, type AptoData } from './form';
import type { AptoUploadStep } from './use-apto-upload';

export interface AptoUpload {
  state: AptoUploadStep;
  choose: (file: File) => void;
  send: (prepared: Extract<AptoUploadStep, { prepared: unknown }>['prepared'], data: AptoData) => void;
  discard: () => void;
  editAfterFailure: (state: Extract<AptoUploadStep, { step: 'failed' }>) => void;
}

const inputClass =
  'min-h-11 w-full rounded-md border border-line bg-white px-3 text-base focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-700 disabled:opacity-60';

function Field({ label, error, children, id }: { label: string; error: string | undefined; children: React.ReactNode; id: string }) {
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="block text-sm font-semibold">
        {label}
      </label>
      {children}
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-sm font-medium text-red-800">
          {error}
        </p>
      ) : null}
    </div>
  );
}

/**
 * Upload (or replacement) of the apto of one season: choose the file → preview + the three declared data → send → retry.
 * Presentational: the attempt (key, bytes, data) is owned by `useAptoUpload`. The data typed here exist only in this form's
 * memory and in the request; they are never put in the URL, a storage or a message.
 */
export function AptoUploadForm({ upload, replacing, today }: { upload: AptoUpload; replacing: boolean; today?: string }) {
  const id = useId();
  const day = useMemo(() => today ?? schoolToday(), [today]);
  const schema = useMemo(() => aptoFormSchema(day), [day]);
  const { register, handleSubmit, formState } = useForm<AptoData>({
    resolver: zodResolver(schema),
    defaultValues: { fechaEmision: '', medicoNombre: '', medicoMatricula: '' },
  });
  const { errors } = formState;
  const { state } = upload;
  const title = replacing ? 'apto físico (reemplazo)' : 'apto físico';
  const fieldProps = (name: keyof AptoData) => ({
    'aria-invalid': !!errors[name],
    'aria-describedby': errors[name] ? `${id}-${name}-error` : undefined,
    disabled: state.step === 'sending',
  });

  if (state.step === 'sent') return <Alert tone="success">Apto enviado. El club lo va a revisar.</Alert>;

  return (
    <div className="space-y-3">
      {state.step === 'idle' && state.notice ? <Alert tone="error">{state.notice}</Alert> : null}
      {state.step === 'idle' ? <Pickers title={title} onFile={upload.choose} /> : null}
      {state.step === 'preparing' ? (
        <p role="status" className="text-sm text-muted">
          Preparando la imagen…
        </p>
      ) : null}
      {state.step === 'ready' || state.step === 'sending' || state.step === 'failed' ? (
        <>
          <Preview title={title} prepared={state.prepared} previewUrl={state.previewUrl} />
          {state.step === 'ready' && state.notice ? <Alert tone="error">{state.notice}</Alert> : null}
          {state.step === 'failed' ? <Alert tone="error">{state.failure.message}</Alert> : null}
        </>
      ) : null}
      {state.step === 'ready' || state.step === 'sending' ? (
        <form
          className="space-y-3"
          noValidate
          onSubmit={(event) => {
            void handleSubmit((data) => upload.send(state.prepared, data))(event);
          }}
        >
          <Field id={`${id}-fechaEmision`} label="Fecha de emisión" error={errors.fechaEmision?.message}>
            <input id={`${id}-fechaEmision`} type="date" min={EMISSION_MIN} max={day} autoComplete="off" className={inputClass} {...register('fechaEmision')} {...fieldProps('fechaEmision')} />
          </Field>
          <Field id={`${id}-medicoNombre`} label="Nombre del médico" error={errors.medicoNombre?.message}>
            <input id={`${id}-medicoNombre`} type="text" maxLength={150} autoComplete="off" className={inputClass} {...register('medicoNombre')} {...fieldProps('medicoNombre')} />
          </Field>
          <Field id={`${id}-medicoMatricula`} label="Matrícula" error={errors.medicoMatricula?.message}>
            <input id={`${id}-medicoMatricula`} type="text" maxLength={50} autoComplete="off" className={inputClass} {...register('medicoMatricula')} {...fieldProps('medicoMatricula')} />
          </Field>
          <p className="text-sm text-muted">{CHECK_BEFORE_SENDING}</p>
          {state.step === 'sending' ? (
            <p role="status" className="text-sm font-semibold">
              Enviando…{state.progress !== null ? ` ${Math.round(state.progress * 100)} %` : ''}
            </p>
          ) : (
            <div className="flex flex-wrap gap-2">
              <Button type="submit">Enviar</Button>
              <Button type="button" variant="outline" onClick={upload.discard}>
                Elegir otro
              </Button>
            </div>
          )}
        </form>
      ) : null}
      {state.step === 'failed' ? (
        <div className="flex flex-wrap gap-2">
          <Button onClick={() => upload.send(state.prepared, state.data)}>Reintentar</Button>
          <Button variant="outline" onClick={() => upload.editAfterFailure(state)}>
            Cambiar los datos
          </Button>
          <Button variant="outline" onClick={upload.discard}>
            Descartar
          </Button>
        </div>
      ) : null}
    </div>
  );
}
