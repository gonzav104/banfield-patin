import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';

const codeSchema = z.object({
  code: z
    .string()
    .transform((value) => value.replace(/\s+/g, ''))
    .pipe(z.string().regex(/^\d{6}$/, 'Ingresá los 6 dígitos del código.')),
});

type CodeInput = z.input<typeof codeSchema>;

interface MfaCodeFormProps {
  submitLabel: string;
  pendingLabel: string;
  pending: boolean;
  errorMessage?: string | null;
  /** Increment to clear the field and refocus it after a rejected code. */
  resetSignal: number;
  onSubmit: (code: string) => void;
}

export function MfaCodeForm({ submitLabel, pendingLabel, pending, errorMessage, resetSignal, onSubmit }: MfaCodeFormProps) {
  const {
    register,
    handleSubmit,
    reset,
    setFocus,
    formState: { errors },
  } = useForm<CodeInput, unknown, z.output<typeof codeSchema>>({
    resolver: zodResolver(codeSchema),
    defaultValues: { code: '' },
  });

  useEffect(() => {
    if (resetSignal > 0) {
      reset({ code: '' });
      setFocus('code');
    }
  }, [resetSignal, reset, setFocus]);

  return (
    <form noValidate onSubmit={(event) => void handleSubmit(({ code }) => onSubmit(code))(event)} className="space-y-5">
      {errorMessage ? <Alert tone="error">{errorMessage}</Alert> : null}
      <Field
        label="Código de 6 dígitos"
        inputMode="numeric"
        autoComplete="one-time-code"
        maxLength={7}
        spellCheck={false}
        required
        hint="Es el código que muestra tu app autenticadora. Cambia cada 30 segundos."
        error={errors.code?.message}
        className="text-center text-2xl font-semibold tracking-[0.4em]"
        {...register('code')}
      />
      <Button type="submit" variant="dark" block disabled={pending} aria-busy={pending}>
        {pending ? pendingLabel : submitLabel}
      </Button>
    </form>
  );
}
