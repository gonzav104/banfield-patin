import { useLocation } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { AuthShell } from './AuthShell';
import { CredentialsForm } from './CredentialsForm';
import type { RedirectState } from './guards';
import { loginErrorMessage } from './messages';
import { useFamilyLogin } from './session';

/** Family login. There is no sign-up and no role selector: the role is decided by the server. */
export function LoginPage() {
  const login = useFamilyLogin();
  const state = (useLocation().state ?? {}) as RedirectState;

  return (
    <AuthShell
      variant="family"
      title="Ingresá a tu portal"
      description="Accedé con el correo y la contraseña de tu cuenta de familia."
    >
      {state.from ? (
        <Alert tone="info" className="mb-5">
          Ingresá para acceder a esa sección.
        </Alert>
      ) : null}
      <CredentialsForm
        submitLabel="Ingresar"
        pendingLabel="Ingresando…"
        pending={login.isPending}
        errorMessage={login.error ? loginErrorMessage(login.error) : null}
        onSubmit={(credentials) => login.mutate(credentials)}
      />
    </AuthShell>
  );
}
