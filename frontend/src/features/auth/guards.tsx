import { Navigate, Outlet, useLocation } from 'react-router-dom';
import type { Rol } from './roles';
import { ForbiddenScreen, LoadingScreen, UnavailableScreen } from './screens';
import { homePathFor, useSession } from './session';

export interface RedirectState {
  from?: string;
  expired?: boolean;
}

/** Only same-app paths inside the expected area are honoured: never an open redirect. */
export function safeReturnPath(from: string | undefined, area: '/familia' | '/admin'): string | null {
  if (!from || from.startsWith('//')) return null;
  return from === area || from.startsWith(`${area}/`) ? from : null;
}

/**
 * Protects an area by role and MFA state. The decision comes from the server-validated session
 * (`/api/auth/me`), never from hidden UI; the backend still authorises every request.
 */
export function RequireRole({ role }: { role: Rol }) {
  const session = useSession();
  const location = useLocation();
  const loginPath = role === 'ADMIN' ? '/admin/login' : '/login';

  switch (session.status) {
    case 'loading':
      return <LoadingScreen />;
    case 'unavailable':
      return <UnavailableScreen {...session} />;
    case 'anonymous':
      return <Navigate to={loginPath} replace state={{ from: location.pathname } satisfies RedirectState} />;
    case 'mfa-pending':
      // Password alone never opens the admin panel (nor any other area): finish the second factor first.
      return <Navigate to="/admin/mfa" replace state={{ from: role === 'ADMIN' ? location.pathname : undefined }} />;
    case 'authenticated':
      if (session.user.rol !== role) {
        return <ForbiddenScreen homePath={homePathFor(session.user)} logoutRedirect={loginPath} />;
      }
      return <Outlet />;
  }
}

/** Login screens: a user who already holds a valid session is sent to their own area. */
export function PublicOnly({ area }: { area: 'family' | 'admin' }) {
  const session = useSession();
  const location = useLocation();
  const state = (location.state ?? {}) as RedirectState;

  if (session.status === 'loading') return <LoadingScreen />;
  if (session.status === 'mfa-pending' && area === 'admin') {
    return <Navigate to="/admin/mfa" replace state={{ from: state.from }} />;
  }
  if (session.status === 'authenticated') {
    const { user } = session;
    const mine = area === 'family' ? user.rol === 'FAMILIA' : user.rol === 'ADMIN';
    if (mine || area === 'family') {
      const target = safeReturnPath(state.from, user.rol === 'ADMIN' ? '/admin' : '/familia');
      return <Navigate to={target ?? homePathFor(user)} replace />;
    }
  }
  // Anonymous, unavailable (the form itself reports connection problems) or mismatched role: show the form.
  return <Outlet />;
}

/** Second-factor screen: only reachable with an ADMIN password-only session. */
export function RequireMfaPending() {
  const session = useSession();
  const location = useLocation();
  const state = (location.state ?? {}) as RedirectState;

  switch (session.status) {
    case 'loading':
      return <LoadingScreen />;
    case 'unavailable':
      return <UnavailableScreen {...session} />;
    case 'anonymous':
      // Someone on the second-factor screen without a session lost it (the pending token lives only minutes).
      return <Navigate to="/admin/login" replace state={{ expired: true } satisfies RedirectState} />;
    case 'authenticated': {
      const target = safeReturnPath(state.from, '/admin');
      return <Navigate to={session.user.rol === 'ADMIN' ? (target ?? '/admin') : homePathFor(session.user)} replace />;
    }
    case 'mfa-pending':
      return <Outlet />;
  }
}
