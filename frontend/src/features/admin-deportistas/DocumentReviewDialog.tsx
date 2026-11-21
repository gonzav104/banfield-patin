import { useEffect, useId, useRef } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Button } from '@/components/ui/button';
import type { DocumentReviewAction } from './api';
import { documentTypeLabel } from './messages';
import type { AdminDocument } from './schemas';

export function DocumentReviewDialog({ document, action, pending, onCancel, onConfirm }: {
  document: AdminDocument;
  action: DocumentReviewAction;
  pending: boolean;
  onCancel: () => void;
  onConfirm: (reason: string) => Promise<void>;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const title = useId();
  const reason = useId();
  const hint = useId();
  const error = useId();
  const observing = action === 'OBSERVADO';
  const schema = z.object({
    motivo: z.string().trim().refine((value) => !observing || (value !== '' && [...value].length <= 500),
      'Ingresá un motivo de entre 1 y 500 caracteres.'),
  });
  const { register, handleSubmit, formState } = useForm({ resolver: zodResolver(schema), defaultValues: { motivo: '' } });

  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    const trigger = element.ownerDocument.activeElement;
    const row = trigger?.closest('li');
    if (typeof element.showModal === 'function') element.showModal();
    else element.setAttribute('open', ''); // DOM environments without a modal implementation.
    element.querySelector<HTMLElement>(observing ? 'textarea' : 'button')?.focus();
    return () => {
      if (typeof element.close === 'function') element.close();
      if (trigger instanceof HTMLElement && trigger.isConnected && !trigger.matches(':disabled')) trigger.focus();
      else if (row?.isConnected) row.querySelector('button')?.focus();
    };
  }, [observing]);

  return (
    <dialog ref={dialog} aria-labelledby={title} aria-describedby={hint}
      onCancel={(event) => { event.preventDefault(); if (!pending) onCancel(); }}
      className="m-auto w-[calc(100%_-_2rem)] max-w-lg rounded-2xl border border-line p-5 shadow-lg backdrop:bg-black/35">
      <form className="space-y-4" onSubmit={(event) => {
        void handleSubmit(({ motivo }) => onConfirm(motivo))(event);
      }}>
        <h3 id={title} className="font-bold">{observing ? 'Observar' : 'Aprobar'} documento: {documentTypeLabel(document.tipo)}</h3>
        <p id={hint} className="text-sm">
          {observing && document.estado === 'APROBADO' ? 'Esta decisión revoca la aprobación del documento. ' : null}
          {observing ? 'La familia podrá leer el motivo de la observación.' : 'Confirmá que el documento cumple los requisitos.'}
        </p>
        {observing ? (
          <div className="space-y-1">
            <label htmlFor={reason} className="block text-sm font-semibold">Motivo de la observación</label>
            <textarea id={reason} rows={4} disabled={pending} aria-required="true"
              aria-invalid={!!formState.errors.motivo} aria-describedby={formState.errors.motivo ? error : hint}
              className="w-full rounded-md border border-line p-2 focus-visible:outline-2 focus-visible:outline-brand-700"
              {...register('motivo')} />
            {formState.errors.motivo ? <p id={error} role="alert" className="text-sm">{formState.errors.motivo.message}</p> : null}
          </div>
        ) : null}
        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" variant="outline" disabled={pending} onClick={onCancel}>Cancelar</Button>
          <Button type="submit" disabled={pending || formState.isSubmitting}>
            {pending ? 'Guardando…' : observing ? 'Confirmar observación' : 'Confirmar aprobación'}
          </Button>
        </div>
      </form>
    </dialog>
  );
}
