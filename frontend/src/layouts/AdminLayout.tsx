import { NavLink, Outlet } from 'react-router-dom';
import { BrandMark, BrandName } from '@/components/brand';
import { Alert } from '@/components/ui/alert';
import { useSession } from '@/features/auth/session';
import { cn } from '@/lib/utils';
import { LogoutButton } from './LogoutButton';

const navItems = [{ to: '/admin', label: 'Inicio', end: true }];

/** Administration shell: sidebar on desktop, stacked header on small screens. */
export function AdminLayout() {
  const session = useSession();
  if (session.status !== 'authenticated') return null;
  const { user } = session;

  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[16rem_1fr]">
      <aside className="flex flex-col gap-4 bg-ink px-4 py-4 text-white lg:min-h-dvh lg:gap-8 lg:py-6">
        <div className="flex items-center gap-3">
          <BrandMark className="size-9" />
          <BrandName />
        </div>
        <nav aria-label="Principal" className="flex gap-1 lg:flex-col">
          {navItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                cn(
                  'flex min-h-11 items-center rounded-lg px-3 text-sm font-semibold',
                  isActive ? 'bg-brand-700 text-white' : 'text-white/80 hover:bg-white/10',
                )
              }
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="mt-auto space-y-3 border-t border-white/15 pt-4">
          <div className="text-sm">
            <p className="font-semibold">
              {user.nombre} {user.apellido}
            </p>
            <p className="break-all text-white/70">{user.email}</p>
            <p className="mt-1 inline-block rounded-full border border-brand-500 px-2 py-0.5 text-xs font-bold uppercase tracking-wider text-brand-200">
              Administrador
            </p>
          </div>
          <LogoutButton redirectTo="/admin/login" variant="outline" className="w-full border-white/30 bg-transparent text-white hover:bg-white/10" />
        </div>
      </aside>
      <main className="min-w-0 bg-paper px-4 py-6 lg:px-10 lg:py-10">
        {session.connectionIssue ? (
          <Alert tone="warning" className="mb-4">
            No pudimos actualizar tu sesión. Mostramos la última información conocida.
          </Alert>
        ) : null}
        <Outlet />
      </main>
    </div>
  );
}
