import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { BrandMark } from '@/components/brand';
import { Button } from '@/components/ui/button';
import type { ApiError } from '@/lib/api-error';
import { useLogout } from './session';

function Frame({ title, children }: { title: string; children: ReactNode }) {
  return (
    <main className="flex min-h-dvh items-center justify-center bg-paper px-4 py-10">
      <div className="w-full max-w-md rounded-2xl border border-line bg-white p-8 text-center shadow-sm">
        <BrandMark className="mx-auto mb-4 size-12" />
        <h1 className="text-xl font-extrabold tracking-tight">{title}</h1>
        <div className="mt-3 space-y-4 text-sm leading-relaxed text-muted">{children}</div>
      </div>
    </main>
  );
}

export function LoadingScreen() {
  return (
    <main className="flex min-h-dvh items-center justify-center bg-paper" aria-busy="true">
      <p role="status" className="flex items-center gap-3 text-sm font-semibold text-muted">
        <span aria-hidden="true" className="size-5 animate-spin rounded-full border-2 border-brand-200 border-t-brand-700" />
        Verificando tu sesión…
      </p>
    </main>
  );
}

/** Shown when the session state cannot be determined (503 or no connection). The session is NOT closed. */
export function UnavailableScreen({ error, retry }: { error: ApiError; retry: () => void }) {
  const offline = error.kind === 'network';
  return (
    <Frame title={offline ? 'Sin conexión con el servidor' : 'Servicio no disponible'}>
      <p>
        {offline
          ? 'No pudimos conectar con el servidor. Revisá tu conexión.'
          : 'No pudimos verificar tu sesión por un problema temporal.'}{' '}
        Tu sesión no se cerró: podés reintentar en unos instantes.
      </p>
      <Button onClick={retry} block>
        Reintentar
      </Button>
    </Frame>
  );
}

export function ForbiddenScreen({ homePath, logoutRedirect }: { homePath: string; logoutRedirect: string }) {
  const logout = useLogout(logoutRedirect);
  return (
    <Frame title="No tenés permiso para ver esta sección">
      <p>Tu cuenta no tiene acceso a esta área. Si creés que es un error, consultá con la escuela.</p>
      <div className="flex flex-col gap-2">
        <Button asChild block>
          <Link to={homePath}>Volver a mi inicio</Link>
        </Button>
        <Button variant="outline" block onClick={() => logout.mutate()} disabled={logout.isPending}>
          Cerrar sesión
        </Button>
      </div>
    </Frame>
  );
}

export function NotFoundScreen({ homePath }: { homePath: string }) {
  return (
    <Frame title="Página no encontrada">
      <p>La dirección que buscás no existe.</p>
      <Button asChild block>
        <Link to={homePath}>Ir al inicio</Link>
      </Button>
    </Frame>
  );
}
