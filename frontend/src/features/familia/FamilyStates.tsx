import type { ReactNode } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { ApiError } from '@/lib/api-error';
import {
  FAMILY_UNAVAILABLE_TEXT,
  FAMILY_UNAVAILABLE_TITLE,
  familyErrorMessage,
  NO_ATHLETES_TEXT,
  NO_ATHLETES_TITLE,
  NO_FAMILY_TEXT,
  NO_FAMILY_TITLE,
  SHEET_NOT_FOUND_TEXT,
  SHEET_NOT_FOUND_TITLE,
} from './messages';

function Notice({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section role="status" className="rounded-2xl border border-dashed border-line bg-white p-6 text-center">
      <h2 className="text-lg font-bold">{title}</h2>
      <p className="mt-2 text-sm leading-relaxed text-muted">{children}</p>
    </section>
  );
}

/** The account has no family id: nothing was requested. Not the same as a family with no athletes. */
export function NoFamilyNotice() {
  return <Notice title={NO_FAMILY_TITLE}>{NO_FAMILY_TEXT}</Notice>;
}

/** The account has a family id but the backend answered 404 FAMILIA_NO_ENCONTRADA. */
export function FamilyUnavailableNotice() {
  return <Notice title={FAMILY_UNAVAILABLE_TITLE}>{FAMILY_UNAVAILABLE_TEXT}</Notice>;
}

/** A valid family whose list of linked athletes is empty. */
export function NoAthletesNotice() {
  return <Notice title={NO_ATHLETES_TITLE}>{NO_ATHLETES_TEXT}</Notice>;
}

/** One screen for every athlete that is not yours or does not exist: it never says which. */
export function AthleteNotFoundNotice() {
  return <Notice title={SHEET_NOT_FOUND_TITLE}>{SHEET_NOT_FOUND_TEXT}</Notice>;
}

export function LoadingBlock({ label }: { label: string }) {
  return (
    <p role="status" className="flex items-center gap-3 rounded-2xl border border-line bg-white p-5 text-sm text-muted">
      <span aria-hidden="true" className="size-5 shrink-0 animate-spin rounded-full border-2 border-brand-200 border-t-brand-700" />
      {label}
    </p>
  );
}

/** Network and 503 errors keep the session; the user can retry in place. */
export function ErrorBlock({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  return (
    <div className="space-y-3">
      <Alert tone="error">{familyErrorMessage(ApiError.from(error))}</Alert>
      <Button variant="outline" onClick={onRetry}>
        Reintentar
      </Button>
    </div>
  );
}
