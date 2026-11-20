import { useEffect, useId, useRef, type ReactNode } from 'react';
import { Button } from '@/components/ui/button';

/**
 * A modal confirmation on the native <dialog>: focus moves to «Cancelar» (the safe action) and is trapped while it is open, Escape does not
 * close it while a request is in flight, and focus returns to the control that opened it (or to the page title when that control is gone,
 * as the «Activar» button of a season that just became the active one).
 */
export function ConfirmDialog({ title, busy, onCancel, returnFocus, description, children }: {
  title: string;
  busy: boolean;
  onCancel: () => void;
  returnFocus: HTMLElement | null;
  description: ReactNode;
  children: ReactNode;
}) {
  const modal = useRef<HTMLDialogElement>(null);
  const heading = useId();
  const descriptionId = useId();
  useEffect(() => {
    const element = modal.current!;
    const trigger = returnFocus;
    element.showModal();
    element.querySelector<HTMLButtonElement>('[data-cancel]')?.focus();
    return () => {
      element.close();
      if (trigger instanceof HTMLElement && trigger.isConnected && !(trigger as HTMLButtonElement).disabled) trigger.focus();
      else document.querySelector<HTMLElement>('main h1')?.focus();
    };
  }, [returnFocus]);
  return (
    <dialog ref={modal} aria-labelledby={heading} aria-describedby={descriptionId} aria-modal="true"
      className="m-auto w-[calc(100%_-_2rem)] max-w-lg rounded-xl border border-line bg-white p-6 text-ink shadow-lg backdrop:bg-black/50"
      onKeyDown={(event) => {
        if (event.key !== 'Tab') return;
        const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'));
        const first = buttons[0];
        const last = buttons.at(-1);
        if (!first || !last) { event.preventDefault(); return; }
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
      }}
      onCancel={(event) => { event.preventDefault(); if (!busy) onCancel(); }}>
      <h2 id={heading} className="text-xl font-bold">{title}</h2>
      <div id={descriptionId} className="my-4 space-y-2">{description}</div>
      <div aria-busy={busy} className="space-y-4">{children}</div>
      <Button data-cancel type="button" variant="outline" disabled={busy} onClick={onCancel} className="mt-4">Cancelar</Button>
    </dialog>
  );
}
