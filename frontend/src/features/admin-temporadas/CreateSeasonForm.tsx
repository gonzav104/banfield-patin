import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { ApiError } from '@/lib/api-error';
import { useCreateSeason, useSeasonWritePending } from './hooks';
import {
  classifyCreateFailure, CONSULT_FAILED_TEXT, CONSULT_SEASONS, CONSULTED_TEXT, CREATE_HEADING, CREATE_SUBMIT, CREATE_SUBMITTING,
  createdText, createErrorMessage, DUPLICATE_YEAR_TEXT, WRITE_IN_PROGRESS_TEXT, YEAR_HINT, YEAR_LABEL, yearErrorText,
} from './messages';
import { parseSeasonYear } from './season-form';

/**
 * Creates a season of one year (it is born INACTIVE). Nothing is retried: when the answer is lost the creation is NOT assumed (neither
 * done nor undone); the administrator consults the current seasons and, only then, may send again (a duplicate is rejected by the backend).
 */
export function CreateSeasonForm({ disabled, refresh }: { disabled: boolean; refresh: () => Promise<unknown> }) {
  const [text, setText] = useState('');
  const [fieldError, setFieldError] = useState('');
  const [alert, setAlert] = useState('');
  const [notice, setNotice] = useState('');
  const [needsVerification, setNeedsVerification] = useState(false);
  const [checking, setChecking] = useState(false);
  const sending = useRef(false);
  const alive = useRef(true);
  const mutation = useCreateSeason();
  const writePending = useSeasonWritePending();
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const busy = mutation.isPending || writePending || checking;

  function submit(event: FormEvent) {
    event.preventDefault();
    if (sending.current || busy || disabled || needsVerification) return;
    setNotice('');
    setAlert('');
    const parsed = parseSeasonYear(text);
    if (!parsed.ok) { setFieldError(yearErrorText(parsed)); return; }
    setFieldError('');
    sending.current = true;
    mutation.mutate(parsed.year, {
      onSuccess: (season) => {
        sending.current = false;
        setText('');
        setNotice(createdText(season.anio));
      },
      onError: (failure) => {
        sending.current = false;
        const error = ApiError.from(failure);
        const kind = classifyCreateFailure(error);
        if (kind === 'duplicate') { setFieldError(DUPLICATE_YEAR_TEXT); return; }
        if (kind === 'uncertain') setNeedsVerification(true);
        setAlert(createErrorMessage(kind, error));
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
      setAlert('');
      setNotice(CONSULTED_TEXT);
    } catch {
      if (alive.current) setAlert(CONSULT_FAILED_TEXT);
    } finally {
      if (alive.current) { sending.current = false; setChecking(false); }
    }
  }

  return (
    <section aria-labelledby="create-season-heading" className="space-y-4 rounded-2xl border border-line bg-white p-5">
      <h2 id="create-season-heading" className="text-lg font-bold">{CREATE_HEADING}</h2>
      <form noValidate onSubmit={submit} className="space-y-4" aria-busy={busy}>
        <Field label={YEAR_LABEL} hint={YEAR_HINT} inputMode="numeric" autoComplete="off" value={text} error={fieldError || undefined}
          disabled={busy} onChange={(event) => { setText(event.target.value); setFieldError(''); }} />
        <Button type="submit" disabled={busy || disabled || needsVerification}>{mutation.isPending ? CREATE_SUBMITTING : CREATE_SUBMIT}</Button>
      </form>
      {writePending && !mutation.isPending ? <p role="status">{WRITE_IN_PROGRESS_TEXT}</p> : null}
      {alert ? <Alert tone="error">{alert}</Alert> : null}
      {needsVerification ? <Button type="button" variant="outline" disabled={busy} onClick={() => void consult()}>{CONSULT_SEASONS}</Button> : null}
      {notice ? <p role="status" className="rounded-lg border border-line p-4">{notice}</p> : null}
    </section>
  );
}
