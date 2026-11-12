import { useRef, useState } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { formatDateTime } from './format';
import type { CreatedInvitation } from './schemas';

/** The backend builds the link from FRONTEND_URL_BASE; when it is not configured the link is null. */
export function resolveInvitationLink(created: CreatedInvitation, origin: string): { url: string; fromBackend: boolean } {
  if (created.enlaceRegistro) return { url: created.enlaceRegistro, fromBackend: true };
  return { url: `${origin}/registro/invitacion/${created.token}`, fromBackend: false };
}

interface InvitationLinkProps {
  created: CreatedInvitation;
  onDone: () => void;
}

/**
 * One-time display of the invitation link. The token only exists in this component's props: once the
 * admin dismisses the panel it is gone, and the backend offers no way to retrieve it again.
 */
export function InvitationLink({ created, onDone }: InvitationLinkProps) {
  const { url, fromBackend } = resolveInvitationLink(created, window.location.origin);
  const inputRef = useRef<HTMLInputElement>(null);
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'manual'>('idle');

  async function copy() {
    try {
      await navigator.clipboard.writeText(url);
      setCopyState('copied');
    } catch {
      inputRef.current?.select();
      setCopyState('manual');
    }
  }

  return (
    <section aria-labelledby="created-title" className="space-y-4 rounded-2xl border-2 border-brand-700 bg-white p-5">
      <div>
        <h2 id="created-title" className="text-lg font-bold">Invitación creada</h2>
        <p className="mt-1 text-sm text-muted">
          Familia: <strong className="text-ink">{created.familia.nombreReferencia}</strong> · Vence el{' '}
          {formatDateTime(created.expiraEn)}
        </p>
      </div>
      <Alert tone="warning">
        <strong>Copiá el enlace ahora.</strong> Por seguridad se muestra una sola vez y no se puede recuperar después. Si lo
        perdés, revocá esta invitación y creá otra.
      </Alert>
      <div className="space-y-2">
        <label htmlFor="invitation-link" className="block text-sm font-semibold">
          Enlace de registro
        </label>
        <input
          id="invitation-link"
          ref={inputRef}
          readOnly
          value={url}
          onFocus={(event) => event.currentTarget.select()}
          className="block min-h-11 w-full rounded-lg border border-neutral-400 bg-paper px-3 font-mono text-sm"
        />
        {!fromBackend ? (
          <p className="text-sm text-muted">
            El servidor no tiene configurada la dirección del frontend (FRONTEND_URL_BASE); armamos el enlace con la
            dirección actual de esta página.
          </p>
        ) : null}
      </div>
      <div className="flex flex-col gap-2 sm:flex-row">
        <Button onClick={() => void copy()}>Copiar enlace</Button>
        <Button variant="outline" onClick={onDone}>
          Listo, ya lo copié
        </Button>
      </div>
      <p role="status" className="min-h-5 text-sm font-medium text-brand-800">
        {copyState === 'copied' ? 'Enlace copiado al portapapeles.' : null}
        {copyState === 'manual' ? 'No pudimos copiar automáticamente: el enlace quedó seleccionado, copialo con Ctrl+C.' : null}
      </p>
    </section>
  );
}
