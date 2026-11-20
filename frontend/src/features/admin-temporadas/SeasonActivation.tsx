import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import type { Season } from '@/features/admin-datos-deportivos/schemas';
import { ApiError } from '@/lib/api-error';
import { ConfirmDialog } from './ConfirmDialog';
import { useActivateSeason, useSeasonWritePending } from './hooks';
import {
  ACTIVATE_ACTION, ACTIVATE_CONFIRM, activateFailureText, activatedText, activateTitle, ALREADY_ACTIVE_TEXT, classifyActivateFailure,
  CONSULT_FAILED_TEXT, CONSULT_SEASONS, CONSULTED_TEXT, HISTORY_KEPT_TEXT, NONE_ACTIVE_NOW, REVIEW_AGAIN, SEASON_MISSING_TEXT,
  WAITING_TEXT, WILL_ACTIVATE, WILL_DEACTIVATE, WRITE_IN_PROGRESS_TEXT,
} from './messages';

type Phase = 'confirm' | 'stale' | 'busy' | 'uncertain' | 'rejected' | 'review';
/**
 * What the administrator decided ON: the season to activate and the active one OBSERVED when the confirmation was opened. `expectedId`
 * belongs to this confirmation, not to the live query: a background refresh never replaces it (the backend compares it, U13).
 */
type Intent = { target: Season; previous: Season | null; expectedId: string | null; phase: Phase; message?: string };

/**
 * What the confirmation told the administrator. After the consultation (`review`) the state it described may be outdated and nothing can be
 * confirmed from it, so only the season is named: the consequences are shown again by the NEW confirmation, built from the current state.
 */
function describe(intent: Intent): ReactNode {
  return (
    <>
      <p className="font-semibold">{WILL_ACTIVATE(intent.target.anio)}</p>
      {intent.phase === 'review' ? null : (
        <>
          <p>{intent.previous ? WILL_DEACTIVATE(intent.previous.anio) : NONE_ACTIVE_NOW}</p>
          <p>{HISTORY_KEPT_TEXT}</p>
        </>
      )}
    </>
  );
}

/** The observed state: the season that is active in THIS snapshot (the list is a coherent one, so there is at most one). */
const activeOf = (seasons: Season[]): Season | null => seasons.find((s) => s.activa) ?? null;

/**
 * One activation at a time for the whole screen. Never retried automatically: after any failure the administrator consults the current
 * seasons and, only then, may open a NEW confirmation that captures the state seen at that moment.
 */
export function SeasonActivation({ seasons, disabled, refresh, children }: {
  seasons: Season[];
  disabled: boolean;
  refresh: () => Promise<unknown>;
  children: (action: (season: Season) => ReactNode) => ReactNode;
}) {
  const [intent, setIntent] = useState<Intent | null>(null);
  const [notice, setNotice] = useState('');
  const [needsVerification, setNeedsVerification] = useState(false);
  const [checking, setChecking] = useState(false);
  const sending = useRef(false);
  const returnFocus = useRef<HTMLElement | null>(null);
  const alive = useRef(true);
  const mutation = useActivateSeason();
  const writePending = useSeasonWritePending();
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const busy = mutation.isPending || writePending || checking;

  function open(target: Season) {
    if (sending.current || intent || disabled || needsVerification || writePending || target.activa) return;
    const previous = activeOf(seasons);
    returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setNotice('');
    setIntent({ target, previous, expectedId: previous?.id ?? null, phase: 'confirm' });
  }

  function submit() {
    if (!intent || intent.phase !== 'confirm' || sending.current || writePending) return;
    sending.current = true;
    mutation.mutate({ seasonId: intent.target.id, expectedActiveId: intent.expectedId }, {
      onSuccess: () => {
        sending.current = false;
        setNotice(activatedText(intent.target.anio));
        setIntent(null);
      },
      onError: (failure) => {
        sending.current = false;
        const phase = classifyActivateFailure(ApiError.from(failure));
        const message = activateFailureText(phase);
        setNeedsVerification(true);
        setNotice(message);
        setIntent({ ...intent, phase: phase === 'gone' ? 'rejected' : phase, message });
      },
    });
  }

  async function consult() {
    if (sending.current) return;
    sending.current = true;
    setChecking(true);
    try {
      await refresh();
      if (!alive.current) return;
      setNeedsVerification(false);
      setNotice(CONSULTED_TEXT);
      if (intent) setIntent({ ...intent, phase: 'review', message: CONSULTED_TEXT });
    } catch {
      if (alive.current) {
        if (intent) setIntent({ ...intent, message: CONSULT_FAILED_TEXT });
        else setNotice(CONSULT_FAILED_TEXT);
      }
    } finally {
      if (alive.current) { sending.current = false; setChecking(false); }
    }
  }

  // After the consultation the target is read again from the CURRENT snapshot: it may have become the active one, or be gone.
  const current = intent ? seasons.find((s) => s.id === intent.target.id) : undefined;
  const reviewNote = intent?.phase === 'review' ? (!current ? SEASON_MISSING_TEXT : current.activa ? ALREADY_ACTIVE_TEXT : null) : null;
  const canReconfirm = intent?.phase === 'review' && !!current && !current.activa && !disabled;

  return (
    <>
      {writePending && !intent ? <p role="status">{WRITE_IN_PROGRESS_TEXT}</p> : null}
      {notice && !intent ? <p role="status" className="rounded-lg border border-line p-4">{notice}</p> : null}
      {needsVerification && !intent ? <Button type="button" disabled={busy} onClick={() => void consult()}>{CONSULT_SEASONS}</Button> : null}
      {children((season) => season.activa ? null : (
        <Button type="button" variant="outline" aria-label={`${ACTIVATE_ACTION} la temporada ${season.anio}`}
          disabled={disabled || busy || needsVerification || intent !== null} onClick={() => open(season)}>{ACTIVATE_ACTION}</Button>
      ))}
      {intent ? (
        <ConfirmDialog title={activateTitle(intent.target.anio)} busy={busy} returnFocus={returnFocus.current} description={describe(intent)}
          onCancel={() => { if (!sending.current) setIntent(null); }}>
          {intent.message ? <Alert>{intent.message}</Alert> : null}
          {reviewNote ? <Alert tone="info">{reviewNote}</Alert> : null}
          {intent.phase === 'confirm' ? <Button type="button" disabled={busy} onClick={submit}>{ACTIVATE_CONFIRM}</Button> : null}
          {intent.phase !== 'confirm' && intent.phase !== 'review' ? <Button type="button" disabled={busy} onClick={() => void consult()}>{CONSULT_SEASONS}</Button> : null}
          {canReconfirm ? <Button type="button" disabled={busy} onClick={() => {
            if (current && !sending.current) {
              const previous = activeOf(seasons);
              setIntent({ target: current, previous, expectedId: previous?.id ?? null, phase: 'confirm' });
            }
          }}>{REVIEW_AGAIN}</Button> : null}
          {busy ? <p role="status">{WAITING_TEXT}</p> : null}
        </ConfirmDialog>
      ) : null}
    </>
  );
}
