import { NavLink, Outlet } from 'react-router-dom';
import { BrandMark, BrandName } from '@/components/brand';
import { Alert } from '@/components/ui/alert';
import { useSession } from '@/features/auth/session';
import { cn } from '@/lib/utils';
import { LogoutButton } from './LogoutButton';

const links = [
  { to: '/familia', label: 'Inicio', end: true, icon: 'M3 11.5 12 4l9 7.5M5.5 10v9.5h13V10' },
  { to: '/familia/deportistas', label: 'Deportistas', end: false, icon: 'M16 19v-1.5a3.5 3.5 0 0 0-3.5-3.5h-5A3.5 3.5 0 0 0 4 17.5V19M10 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm10 8v-1.5a3.5 3.5 0 0 0-2.5-3.35M15 5.2a3 3 0 0 1 0 5.6' },
] as const;

function Icon({ path }: { path: string }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" className="size-6 shrink-0" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d={path} />
    </svg>
  );
}

/**
 * Family portal shell, mobile-first. Below `md`: a fixed bottom bar (thumb reach). From `md`: a lateral navigation.
 * The same <nav> serves both, so there is a single "Principal" landmark. Safe areas keep it clear of the notch and the
 * home indicator, and `min-w-0` on the content prevents horizontal overflow.
 */
export function FamiliaLayout() {
  const session = useSession();
  if (session.status !== 'authenticated') return null;

  return (
    <div className="flex min-h-dvh flex-col">
      <header className="bg-brand-800 pt-[env(safe-area-inset-top)] text-white">
        <div className="mx-auto flex w-full max-w-5xl items-center gap-3 py-3 pl-[max(1rem,env(safe-area-inset-left))] pr-[max(1rem,env(safe-area-inset-right))]">
          <BrandMark className="size-8" />
          <BrandName />
        </div>
      </header>

      <div className="mx-auto flex w-full max-w-5xl flex-1 flex-col md:flex-row md:gap-8 md:pl-[max(1rem,env(safe-area-inset-left))] md:pr-[max(1rem,env(safe-area-inset-right))]">
        <nav
          aria-label="Principal"
          className={cn(
            'fixed inset-x-0 bottom-0 z-20 border-t border-line bg-white pb-[env(safe-area-inset-bottom)] pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]',
            'md:static md:z-auto md:w-52 md:shrink-0 md:border-t-0 md:bg-transparent md:p-0 md:py-6',
          )}
        >
          <ul className="flex md:flex-col md:gap-1">
            {links.map((link) => (
              <li key={link.to} className="flex-1 md:flex-none">
                <NavLink
                  to={link.to}
                  end={link.end}
                  className={({ isActive }) =>
                    cn(
                      'flex min-h-14 flex-col items-center justify-center gap-0.5 px-2 text-xs font-semibold transition-colors',
                      'md:min-h-11 md:flex-row md:justify-start md:gap-3 md:rounded-lg md:px-3 md:text-sm',
                      isActive ? 'text-brand-800 md:bg-brand-50' : 'text-muted hover:text-ink md:hover:bg-black/5',
                    )
                  }
                >
                  <Icon path={link.icon} />
                  {link.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>

        <main className="min-w-0 flex-1 px-4 py-6 md:px-0">
          {session.connectionIssue ? (
            <Alert tone="warning" className="mb-4">
              No pudimos actualizar tu sesión. Mostramos la última información conocida.
            </Alert>
          ) : null}
          <Outlet />
        </main>
      </div>

      {/* Bottom padding reserves the height of the fixed bar so it never covers "Cerrar sesión". */}
      <footer className="border-t border-line bg-white pb-[calc(4rem+env(safe-area-inset-bottom))] md:pb-0">
        <div className="mx-auto flex w-full max-w-5xl flex-col gap-3 px-4 py-4 sm:flex-row sm:items-center sm:justify-between">
          <p className="min-w-0 break-words text-sm text-muted">
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
