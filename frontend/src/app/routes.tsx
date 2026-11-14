import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { AdminLoginPage } from '@/features/auth/AdminLoginPage';
import { PublicOnly, RequireMfaPending, RequireRole } from '@/features/auth/guards';
import { AdminHomePage } from '@/features/auth/HomePages';
import { AthletesPage } from '@/features/familia/AthletesPage';
import { AthleteSheetPage } from '@/features/familia/AthleteSheetPage';
import { FamilyDashboardPage } from '@/features/familia/FamilyDashboardPage';
import { LoginPage } from '@/features/auth/LoginPage';
import { AdminInvitationsPage } from '@/features/invitations/AdminInvitationsPage';
import { RegistrationPage } from '@/features/registration/RegistrationPage';
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

      {/* Registration exists only behind an invitation link; /registro without one explains that. */}
      <Route path="/registro/invitacion/:token?" element={<RegistrationPage />} />
      <Route path="/registro" element={<RegistrationPage />} />

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
          <Route path="/familia" element={<FamilyDashboardPage />} />
          <Route path="/familia/deportistas" element={<AthletesPage />} />
          <Route path="/familia/deportistas/:deportistaId" element={<AthleteSheetPage />} />
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
