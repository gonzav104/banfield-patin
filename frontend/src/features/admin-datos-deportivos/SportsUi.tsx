import type { ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { SPORTS_STATUS_LABEL } from '@/features/familia/messages';
import { StatusBadge, type BadgeTone } from '@/features/familia/StatusBadge';
import type { SportsStatus } from '@/features/familia/schemas';
import type { ApiError } from '@/lib/api-error';
import { sportsErrorMessage } from './messages';
import { forwardedListState } from './navigation';

const LINK_CLASS = 'inline-flex min-h-11 items-center text-sm font-semibold text-brand-700 underline-offset-4 hover:underline';
const STATUS_TONE: Record<SportsStatus, BadgeTone> = { ACTIVO: 'positive', INACTIVO: 'neutral', SUSPENDIDO: 'warning' };

/** A link that carries the list the administrator came from (filter and page only) to the next sports screen. */
export function SportsLink({ to, children, className = LINK_CLASS }: { to: string; children: ReactNode; className?: string }) {
  const location = useLocation();
  return (
    <Link to={to} state={forwardedListState(location.state)} className={className}>
      {children}
    </Link>
  );
}

/** The sports status of a season, as text: never only colour. */
export function SportsStatusBadge({ status }: { status: SportsStatus }) {
  return <StatusBadge tone={STATUS_TONE[status]}>{SPORTS_STATUS_LABEL[status]}</StatusBadge>;
}

/** «Label: value», with «No informado» for a missing value. The label carries its own colon so a screen reader reads a sentence. */
export function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex min-w-0 flex-wrap items-center gap-x-2">
      <dt className="font-semibold">{label}:</dt>
      {/* A long value with no spaces (a 79-character category, a 30-character number) must wrap inside the card on a phone: `anywhere` also lowers the minimum width, which `break-words` does not. */}
      <dd className="min-w-0 [overflow-wrap:anywhere]">{children}</dd>
    </div>
  );
}

export function textOrNotInformed(value: string | null | undefined): string {
  const text = value?.trim();
  return text ? text : 'No informado';
}

/** A neutral card for «there is nothing here»: a title and one sentence, with no action to create anything. */
export function Notice({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-2xl border border-dashed border-line bg-white p-6">
      <h2 className="text-lg font-bold">{title}</h2>
      <p className="mt-2 text-sm leading-relaxed text-muted">{children}</p>
    </section>
  );
}

/** A failed read, with its fixed message and an explicit retry. A refresh that fails never gets here while data is on screen. */
export function ReadError({ error, onRetry }: { error: ApiError; onRetry: () => void }) {
  return (
    <div className="space-y-4">
      <Alert tone="error">{sportsErrorMessage(error)}</Alert>
      <Button variant="outline" onClick={onRetry}>
        Reintentar
      </Button>
    </div>
  );
}

