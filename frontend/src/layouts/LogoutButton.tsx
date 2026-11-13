import { Alert } from '@/components/ui/alert';
import { Button, type ButtonProps } from '@/components/ui/button';
import { commonErrorMessage } from '@/features/auth/messages';
import { useLogout } from '@/features/auth/session';

interface LogoutButtonProps extends Pick<ButtonProps, 'variant' | 'className'> {
  redirectTo: string;
}

/** Real logout: the server invalidates the session cookie first; on failure the user stays signed in and is told why. */
export function LogoutButton({ redirectTo, variant = 'outline', className }: LogoutButtonProps) {
  const logout = useLogout(redirectTo);
  return (
    <>
      <Button variant={variant} className={className} onClick={() => logout.mutate()} disabled={logout.isPending}>
        {logout.isPending ? 'Cerrando sesión…' : 'Cerrar sesión'}
      </Button>
      {logout.error ? (
        <Alert tone="error" className="mt-2">
          {commonErrorMessage(logout.error) ?? 'No pudimos cerrar la sesión. Intentá de nuevo.'}
        </Alert>
      ) : null}
    </>
  );
}
