import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { AuthShell } from '@/features/auth/AuthShell';
import { commonErrorMessage } from '@/features/auth/messages';
import { ApiError } from '@/lib/api-error';
import { isInvitationUnavailable, registerFamily, validateInvitation, type RegistrationInput } from './api';
import { RegistrationForm } from './RegistrationForm';

function UnavailableInvitation({ missing }: { missing: boolean }) {
  return (
    <AuthShell
      variant="family"
      title={missing ? 'Necesitás un enlace de invitación' : 'Esta invitación no está disponible'}
      description={
        missing
          ? 'Las cuentas de familia se crean únicamente a partir del enlace que te comparte la escuela.'
          : 'Puede que el enlace sea incorrecto, haya vencido, haya sido revocado o ya se haya utilizado.'
      }
      footer={<a href="/login" className="font-semibold underline">Ir a ingresar</a>}
    >
      <p className="text-sm leading-relaxed text-muted">
        {missing
          ? 'Abrí de nuevo el enlace completo que recibiste.'
          : 'Por seguridad no podemos decirte cuál de esos casos es. Pedile a la escuela que te envíe una invitación nueva.'}
      </p>
    </AuthShell>
  );
}

/**
 * Public registration screen reached from an invitation link: /registro/invitacion/:token.
 * The token is read once into memory and removed from the address bar (history, screenshots, shared tabs);
 * it is never written to browser storage. There is deliberately no registration without an invitation.
 */
export function RegistrationPage() {
  const { token: urlToken } = useParams();
  const navigate = useNavigate();
  const [token] = useState(urlToken);

  useEffect(() => {
    if (urlToken) void navigate('/registro/invitacion', { replace: true });
  }, [urlToken, navigate]);

  const validation = useQuery({
    queryKey: ['invitation-validation', token],
    queryFn: () => validateInvitation(token ?? ''),
    enabled: !!token,
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  });

  const registration = useMutation<void, ApiError, RegistrationInput>({
    mutationFn: registerFamily,
    onSuccess: () => void navigate('/login', { replace: true, state: { registered: true } }),
  });

  if (!token) return <UnavailableInvitation missing />;

  if (validation.isPending) {
    return (
      <AuthShell variant="family" title="Verificando tu invitación">
        <p role="status" className="text-sm text-muted">Un momento…</p>
      </AuthShell>
    );
  }

  if (validation.isError) {
    if (isInvitationUnavailable(validation.error)) return <UnavailableInvitation missing={false} />;
    const error = ApiError.from(validation.error);
    return (
      <AuthShell variant="family" title="No pudimos verificar tu invitación">
        <div className="space-y-4">
          <Alert tone="error">{commonErrorMessage(error) ?? 'Ocurrió un problema. Intentá de nuevo.'}</Alert>
          <Button block onClick={() => void validation.refetch()}>Reintentar</Button>
        </div>
      </AuthShell>
    );
  }

  // The invitation was consumed or revoked between validation and submit.
  if (registration.error && isInvitationUnavailable(registration.error)) return <UnavailableInvitation missing={false} />;

  return (
    <AuthShell
      variant="family"
      title="Creá tu cuenta"
      description={`Te invitaron a ${validation.data.escuelaNombre}. Completá tus datos para crear tu cuenta de familia.`}
      footer={<a href="/login" className="font-semibold underline">Ya tengo cuenta</a>}
    >
      <RegistrationForm
        token={token}
        suggestedEmail={validation.data.emailSugerido}
        pending={registration.isPending}
        error={registration.error}
        onSubmit={(input) => registration.mutate(input)}
      />
    </AuthShell>
  );
}
