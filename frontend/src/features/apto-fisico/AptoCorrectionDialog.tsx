import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useId, useMemo, useRef } from 'react';
import { useForm } from 'react-hook-form';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { aptoFormSchema, EMISSION_MIN, schoolToday, type AptoData } from './form';
import type { AptoDocument } from './schemas';

export const WITHDRAWS_APPROVAL = 'Corregir los datos de un apto aprobado retira su aprobación: vuelve a «En revisión» y hay que aprobarlo de nuevo.';
export const KEEPS_IN_REVIEW = 'El apto sigue en revisión: la corrección no cambia su estado.';

/** ADMIN correction of the three declared data. Says, before confirming, what happens to the approval. */
export function AptoCorrectionDialog({ document, pending, error, onCancel, onConfirm }: {
  document: AptoDocument;
  pending: boolean;
  error: string | null;
  onCancel: () => void;
  onConfirm: (data: AptoData) => Promise<void>;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const id = useId();
  const day = useMemo(() => schoolToday(), []);
  const defaults: AptoData = { fechaEmision: document.fechaEmision ?? '', medicoNombre: document.medicoNombre ?? '', medicoMatricula: document.medicoMatricula ?? '' };
  const { register, handleSubmit, formState, watch } = useForm<AptoData>({ resolver: zodResolver(aptoFormSchema(day)), defaultValues: defaults });
  const { errors } = formState;
  const approved = document.estado === 'APROBADO';
  const current = watch();
  const unchanged = current.fechaEmision?.trim() === defaults.fechaEmision && current.medicoNombre?.trim() === defaults.medicoNombre && current.medicoMatricula?.trim() === defaults.medicoMatricula;

  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    const trigger = element.ownerDocument.activeElement;
    if (typeof element.showModal === 'function') element.showModal();
    else element.setAttribute('open', '');
    element.querySelector<HTMLElement>('input')?.focus();
    return () => {
      if (typeof element.close === 'function') element.close();
      if (trigger instanceof HTMLElement && trigger.isConnected && !trigger.matches(':disabled')) trigger.focus();
    };
  }, []);

  const input = 'min-h-11 w-full rounded-md border border-line px-3 text-base focus-visible:outline-2 focus-visible:outline-brand-700';
  const field = (name: keyof AptoData, label: string, extra: React.InputHTMLAttributes<HTMLInputElement> = {}) => (
    <div className="space-y-1">
      <label htmlFor={`${id}-${name}`} className="block text-sm font-semibold">
        {label}
      </label>
      <input id={`${id}-${name}`} disabled={pending} aria-invalid={!!errors[name]} aria-describedby={errors[name] ? `${id}-${name}-error` : undefined} autoComplete="off" className={input} {...extra} {...register(name)} />
      {errors[name] ? (
        <p id={`${id}-${name}-error`} role="alert" className="text-sm font-medium text-red-800">
          {errors[name]?.message}
        </p>
      ) : null}
    </div>
  );

  return (
    <dialog
      ref={dialog}
      aria-labelledby={`${id}-title`}
      aria-describedby={`${id}-hint`}
      onCancel={(event) => {
        event.preventDefault();
        if (!pending) onCancel();
      }}
      className="m-auto w-[calc(100%_-_2rem)] max-w-lg rounded-2xl border border-line p-5 shadow-lg backdrop:bg-black/35"
    >
      <form className="space-y-4" noValidate onSubmit={(event) => void handleSubmit((data) => onConfirm(data))(event)}>
        <h3 id={`${id}-title`} className="font-bold">
          Corregir los datos del apto
        </h3>
        <p id={`${id}-hint`} className="text-sm">
          {approved ? <strong>{WITHDRAWS_APPROVAL}</strong> : KEEPS_IN_REVIEW}
        </p>
        {field('fechaEmision', 'Fecha de emisión', { type: 'date', min: EMISSION_MIN, max: day })}
        {field('medicoNombre', 'Nombre del médico', { type: 'text', maxLength: 150 })}
        {field('medicoMatricula', 'Matrícula', { type: 'text', maxLength: 50 })}
        {error ? <Alert tone="error">{error}</Alert> : null}
        {unchanged ? <p className="text-sm text-muted">Todavía no cambiaste ningún dato.</p> : null}
        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" variant="outline" disabled={pending} onClick={onCancel}>
            Cancelar
          </Button>
          <Button type="submit" disabled={pending || formState.isSubmitting || unchanged}>
            {pending ? 'Guardando…' : approved ? 'Corregir y retirar la aprobación' : 'Guardar corrección'}
          </Button>
        </div>
      </form>
    </dialog>
  );
}
