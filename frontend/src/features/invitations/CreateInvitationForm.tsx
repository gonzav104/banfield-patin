import { zodResolver } from '@hookform/resolvers/zod';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { FamilyPicker } from './FamilyPicker';
import { useCreateInvitation } from './hooks';
import { createInvitationErrorMessage } from './messages';
import type { CreatedInvitation, CreateInvitationInput } from './schemas';

const formSchema = z
  .object({
    mode: z.enum(['existing', 'new']),
    familyId: z.string().optional(),
    familyName: z.string().optional(),
    email: z.string().optional(),
    days: z.string().optional(),
  })
  .superRefine((value, ctx) => {
    if (value.mode === 'existing' && !value.familyId) {
      ctx.addIssue({ code: 'custom', path: ['familyId'], message: 'Elegí una familia de la lista.' });
    }
    const name = value.familyName?.trim() ?? '';
    if (value.mode === 'new') {
      if (!name) ctx.addIssue({ code: 'custom', path: ['familyName'], message: 'Ingresá un nombre de referencia.' });
      else if (name.length > 150) ctx.addIssue({ code: 'custom', path: ['familyName'], message: 'Máximo 150 caracteres.' });
    }
    const email = value.email?.trim() ?? '';
    if (email && (email.length > 180 || !z.email().safeParse(email).success)) {
      ctx.addIssue({ code: 'custom', path: ['email'], message: 'Ingresá un correo electrónico válido.' });
    }
    const days = value.days?.trim() ?? '';
    if (days && (!/^\d+$/.test(days) || Number(days) < 1)) {
      ctx.addIssue({ code: 'custom', path: ['days'], message: 'Ingresá un número de días entero y mayor a 0.' });
    }
  });

type FormValues = z.infer<typeof formSchema>;

function toInput(values: FormValues): CreateInvitationInput {
  const input: CreateInvitationInput =
    values.mode === 'existing'
      ? { familiaId: values.familyId ?? '' }
      : { nuevaFamilia: { nombreReferencia: (values.familyName ?? '').trim() } };
  const email = values.email?.trim();
  if (email) input.emailSugerido = email;
  const days = values.days?.trim();
  if (days) input.diasVigencia = Number(days);
  return input;
}

interface CreateInvitationFormProps {
  onCreated: (created: CreatedInvitation) => void;
  onCancel: () => void;
}

export function CreateInvitationForm({ onCreated, onCancel }: CreateInvitationFormProps) {
  const create = useCreateInvitation();
  const {
    register,
    handleSubmit,
    control,
    setValue,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: { mode: 'new', familyId: undefined, familyName: '', email: '', days: '' },
  });
  const mode = useWatch({ control, name: 'mode' });

  return (
    <form
      noValidate
      aria-label="Nueva invitación"
      onSubmit={(event) =>
        void handleSubmit((values) => create.mutate(toInput(values), { onSuccess: onCreated }))(event)
      }
      className="space-y-5 rounded-2xl border border-line bg-white p-5"
    >
      <h2 className="text-lg font-bold">Nueva invitación</h2>
      {create.error ? <Alert tone="error">{createInvitationErrorMessage(create.error)}</Alert> : null}

      <fieldset className="space-y-2">
        <legend className="text-sm font-semibold">¿Para qué familia?</legend>
        <div className="grid gap-2 sm:grid-cols-2">
          {(
            [
              ['new', 'Familia nueva'],
              ['existing', 'Familia existente'],
            ] as const
          ).map(([value, label]) => (
            <label
              key={value}
              className="flex min-h-11 cursor-pointer items-center gap-3 rounded-lg border border-line px-3 text-sm font-medium has-[:checked]:border-brand-700 has-[:checked]:bg-brand-50"
            >
              <input type="radio" value={value} className="size-4 accent-brand-700" {...register('mode')} />
              {label}
            </label>
          ))}
        </div>
      </fieldset>

      {mode === 'new' ? (
        <Field
          label="Nombre de referencia de la familia"
          required
          maxLength={150}
          hint="Cómo la identificás vos, por ejemplo el apellido."
          error={errors.familyName?.message}
          {...register('familyName')}
        />
      ) : (
        <Controller
          control={control}
          name="familyId"
          render={({ field }) => (
            <FamilyPicker
              value={field.value}
              onChange={(id) => setValue('familyId', id, { shouldValidate: true })}
              error={errors.familyId?.message}
            />
          )}
        />
      )}

      <Field
        label="Correo sugerido (opcional)"
        type="email"
        inputMode="email"
        autoComplete="off"
        hint="Solo se muestra como sugerencia en el formulario de registro; la persona puede usar otro."
        error={errors.email?.message}
        {...register('email')}
      />
      <Field
        label="Vigencia en días (opcional)"
        inputMode="numeric"
        autoComplete="off"
        hint="Si lo dejás vacío se usa la vigencia por defecto del servidor."
        error={errors.days?.message}
        {...register('days')}
      />

      <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
        <Button variant="outline" onClick={onCancel} disabled={create.isPending}>
          Cancelar
        </Button>
        <Button type="submit" disabled={create.isPending} aria-busy={create.isPending}>
          {create.isPending ? 'Creando…' : 'Crear invitación'}
        </Button>
      </div>
    </form>
  );
}
