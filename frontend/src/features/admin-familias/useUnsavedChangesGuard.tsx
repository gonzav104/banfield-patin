import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';

interface Pending {
  run: () => void;
  leaveLabel: string;
}

/**
 * Warns before unsaved work is lost. The app uses a plain BrowserRouter, which has no `useBlocker`, so this covers what
 * can be covered without one: closing or reloading the tab (`beforeunload`), clicks on in-app links (the menu, «Volver
 * al listado»…) and the editor's own «Cancelar» (through `ask`). Known limit: the browser's Back button is not
 * intercepted. The panel returned is plain, inline and focus-managed (no modal, no extra dependency).
 */
export function useUnsavedChangesGuard(active: boolean): { ask: (run: () => void, leaveLabel?: string) => void; panel: ReactNode } {
  const navigate = useNavigate();
  const [pending, setPending] = useState<Pending | null>(null);
  const navigateRef = useRef(navigate);
  navigateRef.current = navigate;

  useEffect(() => {
    if (!active) {
      setPending(null);
      return undefined;
    }
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    const onClick = (event: MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      const anchor = (event.target as Element | null)?.closest?.('a[href]');
      if (!anchor) return;
      const href = anchor.getAttribute('href') ?? '';
      const target = anchor.getAttribute('target');
      if (!href.startsWith('/') || href.startsWith('//') || (target && target !== '_self')) return;
      event.preventDefault();
      event.stopPropagation();
      setPending({ run: () => void navigateRef.current(href), leaveLabel: 'Descartar y salir' });
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    document.addEventListener('click', onClick, true);
    return () => {
      window.removeEventListener('beforeunload', onBeforeUnload);
      document.removeEventListener('click', onClick, true);
    };
  }, [active]);

  const panel = pending ? (
    <div role="group" aria-label="Cambios sin guardar" className="space-y-3 rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950">
      <p>
        <strong>Tenés cambios sin guardar.</strong> Si salís ahora, se pierden.
      </p>
      <div className="flex flex-col gap-2 sm:flex-row">
        <Button autoFocus onClick={() => setPending(null)}>
          Seguir editando
        </Button>
        <Button
          variant="outline"
          onClick={() => {
            const current = pending;
            setPending(null);
            current.run();
          }}
        >
          {pending.leaveLabel}
        </Button>
      </div>
    </div>
  ) : null;

  return {
    ask: (run, leaveLabel = 'Descartar cambios') => {
      if (active) setPending({ run, leaveLabel });
      else run();
    },
    panel,
  };
}
