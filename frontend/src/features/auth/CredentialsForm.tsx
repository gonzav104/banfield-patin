import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import type { Credentials } from './api';

// Mirrors the backend DTO limits (email <= 180, password <= 200). No password policy here: that is
// a registration concern, and enforcing it at login would lock out legitimate accounts.
const credentialsSchema = z.object({
  email: z
    .string()
    .trim()
    .min(1, 'Ingresá tu correo electrónico.')
    .max(180, 'El correo es demasiado largo.')
    .pipe(z.email('Ingresá un correo electrónico válido.')),
  password: z.string().min(1, 'Ingresá tu contraseña.').max(200, 'La contraseña es demasiado larga.'),
});

interface CredentialsFormProps {
  submitLabel: string;
  pendingLabel: string;
  pending: boolean;
  errorMessage?: string | null;
  variant?: 'primary' | 'dark';
  onSubmit: (credentials: Credentials) => void;
}

export function CredentialsForm({
  submitLabel,
  pendingLabel,
  pending,
  errorMessage,
  variant = 'primary',
  onSubmit,
}: CredentialsFormProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<Credentials>({ resolver: zodResolver(credentialsSchema), mode: 'onSubmit' });

  return (
    <form noValidate onSubmit={(event) => void handleSubmit(onSubmit)(event)} className="space-y-5">
      {errorMessage ? <Alert tone="error">{errorMessage}</Alert> : null}
      <Field
        label="Correo electrónico"
        type="email"
        inputMode="email"
        autoComplete="username"
        autoCapitalize="none"
        spellCheck={false}
        required
        error={errors.email?.message}
        {...register('email')}
      />
      <Field
        label="Contraseña"
        type="password"
        autoComplete="current-password"
        required
        revealable
        error={errors.password?.message}
        {...register('password')}
      />
      <Button type="submit" variant={variant} block disabled={pending} aria-busy={pending}>
        {pending ? pendingLabel : submitLabel}
      </Button>
    </form>
  );
}
