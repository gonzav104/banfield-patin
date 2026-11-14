import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { ApiError } from '@/lib/api-error';
import { NAME_MAX_LENGTH } from './api';
import { useCreateFamily } from './hooks';
import { createErrorMessage, NAME_REQUIRED, NAME_TOO_LONG } from './messages';

const formSchema = z.object({
  nombreReferencia: z
    .string()
    .trim()
    .min(1, NAME_REQUIRED)
    .max(NAME_MAX_LENGTH, NAME_TOO_LONG),
});
type FormValues = z.infer<typeof formSchema>;

export function AdminFamiliaNuevaPage() {
  const navigate = useNavigate();
  const create = useCreateFamily();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({ resolver: zodResolver(formSchema), defaultValues: { nombreReferencia: '' } });
  const failure = create.error ? createErrorMessage(ApiError.from(create.error)) : null;

  return (
    <div className="max-w-2xl space-y-6">
      <header>
        <p className="text-sm font-semibold text-brand-700">Familias</p>
        <h1 className="mt-1 text-3xl font-extrabold tracking-tight">Nueva familia</h1>
        <p className="mt-2 text-muted">
          Crear una familia no genera una cuenta de usuario. Para que alguien acceda al portal, enviá una invitación desde{' '}
          <Link to="/admin/invitaciones" className="font-semibold text-brand-700 underline underline-offset-4">
            Invitaciones
          </Link>
          .
        </p>
      </header>

      <form
        noValidate
        aria-label="Nueva familia"
        onSubmit={(event) =>
          void handleSubmit((values) => {
            if (create.isPending) return; // a second submit while the first is in flight must never create a second family
            create.mutate(values.nombreReferencia, {
              onSuccess: (created) => void navigate(`/admin/familias/${encodeURIComponent(created.id)}`, { replace: true, state: { created: true } }),
            });
          })(event)
        }
        className="space-y-5 rounded-2xl border border-line bg-white p-5"
      >
        {failure ? (
          <Alert tone="error">
            {failure.text}
            {failure.uncertain ? (
              <>
                {' '}
                <Link to="/admin/familias" className="font-semibold underline underline-offset-4">
                  Revisar el listado
                </Link>
              </>
            ) : null}
          </Alert>
        ) : null}

        <Field
          label="Nombre de referencia"
          required
          maxLength={NAME_MAX_LENGTH}
          autoComplete="off"
          hint="Cómo la identificás vos, por ejemplo el apellido. Antes de crearla, buscala en el listado para no duplicarla."
          error={errors.nombreReferencia?.message}
          {...register('nombreReferencia')}
        />

        <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
          <Button asChild variant="outline">
            <Link to="/admin/familias">Cancelar</Link>
          </Button>
          <Button type="submit" disabled={create.isPending} aria-busy={create.isPending}>
            {create.isPending ? 'Creando…' : 'Crear familia'}
          </Button>
        </div>
      </form>
    </div>
  );
}
