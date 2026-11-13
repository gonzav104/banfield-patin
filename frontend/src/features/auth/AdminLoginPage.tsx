import { useLocation } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { AuthShell } from './AuthShell';
import { CredentialsForm } from './CredentialsForm';
import type { RedirectState } from './guards';
import { loginErrorMessage, SESSION_EXPIRED_MESSAGE } from './messages';
import { useAdminLogin } from './session';

/** Administrator login, step 1 of 2. A valid password only yields a pending session: the second factor follows. */
export function AdminLoginPage() {
  const login = useAdminLogin();
  const state = (useLocation().state ?? {}) as RedirectState;

  return (
    <AuthShell
      variant="admin"
      title="Acceso administrativo"
      description="Ingresá con tu cuenta de administrador. Después te vamos a pedir el código de verificación."
    >
      {state.expired ? (
        <Alert tone="warning" className="mb-5">
          {SESSION_EXPIRED_MESSAGE}
        </Alert>
      ) : state.from ? (
        <Alert tone="info" className="mb-5">
          Ingresá para acceder a esa sección.
        </Alert>
      ) : null}
      <CredentialsForm
        variant="dark"
        submitLabel="Continuar"
        pendingLabel="Verificando…"
        pending={login.isPending}
        errorMessage={login.error ? loginErrorMessage(login.error) : null}
        onSubmit={(credentials) => login.mutate(credentials)}
      />
    </AuthShell>
  );
}
