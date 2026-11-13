import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { commonErrorMessage } from '@/features/auth/messages';
import type { ApiError } from '@/lib/api-error';
import { isInvitationUnavailable, type RegistrationInput } from './api';

// Mirrors the backend rules: password >= 10 characters and <= 72 bytes of UTF-8 (bcrypt limit).
const MIN_PASSWORD_CHARS = 10;
const MAX_PASSWORD_BYTES = 72;

const schema = z
  .object({
    nombre: z.string().trim().min(1, 'Ingresá tu nombre.').max(100, 'Máximo 100 caracteres.'),
    apellido: z.string().trim().min(1, 'Ingresá tu apellido.').max(100, 'Máximo 100 caracteres.'),
    email: z
      .string()
      .trim()
      .min(1, 'Ingresá tu correo electrónico.')
      .max(180, 'El correo es demasiado largo.')
      .pipe(z.email('Ingresá un correo electrónico válido.')),
    password: z
      .string()
      .refine((value) => [...value].length >= MIN_PASSWORD_CHARS, `Usá al menos ${MIN_PASSWORD_CHARS} caracteres.`)
      .refine((value) => new TextEncoder().encode(value).length <= MAX_PASSWORD_BYTES, 'La contraseña es demasiado larga.'),
    confirm: z.string(),
  })
  .refine((value) => value.password === value.confirm, { path: ['confirm'], message: 'Las contraseñas no coinciden.' });

type FormValues = z.input<typeof schema>;
type DetailField = 'nombre' | 'apellido' | 'email' | 'password';

interface RegistrationFormProps {
  token: string;
  suggestedEmail: string | null | undefined;
  pending: boolean;
  error: ApiError | null;
  onSubmit: (input: RegistrationInput) => void;
}

export function RegistrationForm({ token, suggestedEmail, pending, error, onSubmit }: RegistrationFormProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { nombre: '', apellido: '', email: suggestedEmail ?? '', password: '', confirm: '' },
  });

  const emailTaken = error?.status === 409 && error.code === 'EMAIL_YA_REGISTRADO';
  const detailFor = (field: DetailField) =>
    error?.kind === 'validation' && !isInvitationUnavailable(error)
      ? error.details.find((detail) => detail.campo === field)?.mensaje ?? undefined
      : undefined;
  const generalError = error && !emailTaken ? generalMessage(error) : null;

  return (
    <form
      noValidate
      onSubmit={(event) =>
        void handleSubmit((values) =>
          onSubmit({
            token,
            nombre: values.nombre.trim(),
            apellido: values.apellido.trim(),
            email: values.email.trim(),
            password: values.password,
          }),
        )(event)
      }
      className="space-y-5"
    >
      {generalError ? <Alert tone="error">{generalError}</Alert> : null}
      <Field label="Nombre" autoComplete="given-name" required error={errors.nombre?.message ?? detailFor('nombre')} {...register('nombre')} />
      <Field label="Apellido" autoComplete="family-name" required error={errors.apellido?.message ?? detailFor('apellido')} {...register('apellido')} />
      <Field
        label="Correo electrónico"
        type="email"
        inputMode="email"
        autoComplete="username"
        autoCapitalize="none"
        spellCheck={false}
        required
        hint="Con este correo vas a ingresar al portal."
        error={
          errors.email?.message ??
          (emailTaken ? 'Ya existe una cuenta con ese correo. Ingresá desde el login o usá otro correo.' : detailFor('email'))
        }
        {...register('email')}
      />
      <Field
        label="Contraseña"
        type="password"
        autoComplete="new-password"
        required
        revealable
        hint={`Mínimo ${MIN_PASSWORD_CHARS} caracteres.`}
        error={errors.password?.message ?? detailFor('password')}
        {...register('password')}
      />
      <Field
        label="Repetir contraseña"
        type="password"
        autoComplete="new-password"
        required
        error={errors.confirm?.message}
        {...register('confirm')}
      />
      <Button type="submit" block disabled={pending} aria-busy={pending}>
        {pending ? 'Creando tu cuenta…' : 'Crear mi cuenta'}
      </Button>
    </form>
  );
}

function generalMessage(error: ApiError): string | null {
  if (error.kind === 'validation') {
    return error.details.length > 0 ? 'Revisá los datos marcados e intentá de nuevo.' : 'Revisá los datos ingresados e intentá de nuevo.';
  }
  return commonErrorMessage(error) ?? 'No pudimos crear tu cuenta. Intentá de nuevo.';
}
