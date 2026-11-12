import { Outlet } from 'react-router-dom';
import { BrandMark, BrandName } from '@/components/brand';
import { Alert } from '@/components/ui/alert';
import { useSession } from '@/features/auth/session';
import { LogoutButton } from './LogoutButton';

/** Family portal shell. Mobile-first: a compact header and a single column that widens on larger screens. */
export function FamiliaLayout() {
  const session = useSession();
  if (session.status !== 'authenticated') return null;

  return (
    <div className="flex min-h-dvh flex-col">
      <header className="bg-brand-800 text-white">
        <div className="mx-auto flex w-full max-w-3xl items-center gap-3 px-4 py-3">
          <BrandMark className="size-8" />
          <BrandName />
        </div>
      </header>
      <div className="mx-auto w-full max-w-3xl flex-1 px-4 py-6">
        {session.connectionIssue ? (
          <Alert tone="warning" className="mb-4">
            No pudimos actualizar tu sesión. Mostramos la última información conocida.
          </Alert>
        ) : null}
        <Outlet />
      </div>
      <footer className="border-t border-line bg-white">
        <div className="mx-auto flex w-full max-w-3xl flex-col gap-3 px-4 py-4 sm:flex-row sm:items-center sm:justify-between">
          <p className="text-sm text-muted">
            Sesión de <strong className="text-ink">{session.user.email}</strong>
          </p>
          <div>
            <LogoutButton redirectTo="/login" className="w-full sm:w-auto" />
          </div>
        </div>
      </footer>
    </div>
  );
}
