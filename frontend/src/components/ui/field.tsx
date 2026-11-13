import { forwardRef, useId, useState, type InputHTMLAttributes, type ReactNode } from 'react';
import { cn } from '@/lib/utils';

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input(
  { className, type = 'text', ...props },
  ref,
) {
  return (
    <input
      ref={ref}
      type={type}
      className={cn(
        'block min-h-11 w-full rounded-lg border border-neutral-400 bg-white px-3 text-base text-ink placeholder:text-neutral-500 aria-[invalid=true]:border-red-700 disabled:bg-neutral-100',
        className,
      )}
      {...props}
    />
  );
});

interface FieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  label: string;
  error?: string | undefined;
  hint?: ReactNode;
  /** Adds a show/hide toggle to a password input. */
  revealable?: boolean;
}

/** Label + input + hint + inline error, wired together for assistive technology. */
export const Field = forwardRef<HTMLInputElement, FieldProps>(function Field(
  { label, error, hint, revealable = false, type = 'text', required, ...props },
  ref,
) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const [revealed, setRevealed] = useState(false);
  const effectiveType = revealable && revealed ? 'text' : type;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ') || undefined;

  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-sm font-semibold">
        {label}
        {required ? <span aria-hidden="true" className="text-red-700"> *</span> : null}
      </label>
      <div className="relative">
        <Input
          ref={ref}
          id={id}
          type={effectiveType}
          required={required}
          aria-required={required || undefined}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={revealable ? 'pr-20' : undefined}
          {...props}
        />
        {revealable ? (
          <button
            type="button"
            onClick={() => setRevealed((value) => !value)}
            aria-pressed={revealed}
            className="absolute inset-y-0 right-0 min-w-16 rounded-r-lg px-3 text-sm font-semibold text-brand-800 hover:underline"
          >
            {revealed ? 'Ocultar' : 'Mostrar'}
          </button>
        ) : null}
      </div>
      {hint ? (
        <p id={hintId} className="text-sm text-muted">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} className="text-sm font-medium text-red-800">
          {error}
        </p>
      ) : null}
    </div>
  );
});
