import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { AdminLoginPage } from '@/features/auth/AdminLoginPage';
import { PublicOnly, RequireMfaPending, RequireRole } from '@/features/auth/guards';
import { AdminHomePage, FamiliaHomePage } from '@/features/auth/HomePages';
import { LoginPage } from '@/features/auth/LoginPage';
import { AdminInvitationsPage } from '@/features/invitations/AdminInvitationsPage';
import { LoadingScreen, NotFoundScreen } from '@/features/auth/screens';
import { AdminLayout } from '@/layouts/AdminLayout';
import { FamiliaLayout } from '@/layouts/FamiliaLayout';

// The MFA screen is the only one that needs the QR library, so it is split out of the login bundle.
const AdminMfaPage = lazy(() => import('@/features/auth/AdminMfaPage').then((m) => ({ default: m.AdminMfaPage })));

/**
 * Route map. There is deliberately no sign-up route and no link to /admin/login from any public screen.
 * `/` has no landing yet in this app: it sends visitors to the family login.
 */
export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/login" replace />} />

      <Route element={<PublicOnly area="family" />}>
        <Route path="/login" element={<LoginPage />} />
      </Route>

      <Route element={<PublicOnly area="admin" />}>
        <Route path="/admin/login" element={<AdminLoginPage />} />
      </Route>
      <Route element={<RequireMfaPending />}>
        <Route
          path="/admin/mfa"
          element={
            <Suspense fallback={<LoadingScreen />}>
              <AdminMfaPage />
            </Suspense>
          }
        />
      </Route>

      <Route element={<RequireRole role="FAMILIA" />}>
        <Route element={<FamiliaLayout />}>
          <Route path="/familia" element={<FamiliaHomePage />} />
          <Route path="/familia/*" element={<Navigate to="/familia" replace />} />
        </Route>
      </Route>

      <Route element={<RequireRole role="ADMIN" />}>
        <Route element={<AdminLayout />}>
          <Route path="/admin" element={<AdminHomePage />} />
          <Route path="/admin/invitaciones" element={<AdminInvitationsPage />} />
          <Route path="/admin/*" element={<Navigate to="/admin" replace />} />
        </Route>
      </Route>

      <Route path="*" element={<NotFoundScreen homePath="/" />} />
    </Routes>
  );
}
