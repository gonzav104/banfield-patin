import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '@/lib/api-error';
import { broadcastSessionSignal } from './sessionSync';
import {
  confirmMfa,
  enrollMfa,
  fetchSession,
  loginAdmin,
  loginFamily,
  logout,
  verifyMfa,
  type AuthUser,
  type Credentials,
  type MfaEnrollment,
} from './api';

export const sessionKey = ['session'] as const;

export type SessionState =
  | { status: 'loading' }
  | { status: 'anonymous' }
  | { status: 'unavailable'; error: ApiError; retry: () => void }
  | { status: 'mfa-pending'; user: AuthUser; connectionIssue: boolean }
  | { status: 'authenticated'; user: AuthUser; connectionIssue: boolean };

/**
 * Server state for the session. A failed refresh (503, no connection) never discards a known user:
 * the session state is unknown, not closed, so we keep the last known identity and flag the problem.
 */
export function useSession(): SessionState {
  const query = useQuery({
    queryKey: sessionKey,
    queryFn: fetchSession,
    staleTime: 60_000,
    retry: false,
  });

  if (query.data) {
    const connectionIssue = query.isError;
    return query.data.mfaPendiente
      ? { status: 'mfa-pending', user: query.data, connectionIssue }
      : { status: 'authenticated', user: query.data, connectionIssue };
  }
  if (query.isError) {
    const error = query.error instanceof ApiError ? query.error : ApiError.from(query.error);
    return { status: 'unavailable', error, retry: () => void query.refetch() };
  }
  if (query.data === null) return { status: 'anonymous' };
  return { status: 'loading' };
}

/** Drops every cached server value: used when the session ends so nothing leaks to the next user. */
export function resetSessionCache(queryClient: QueryClient): void {
  queryClient.clear();
  queryClient.setQueryData(sessionKey, null);
}

/** Drops cached private data but keeps the session entry (identity is about to be replaced or re-checked). */
export function clearPrivateState(queryClient: QueryClient): void {
  queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== sessionKey[0] });
}

/** A new identity (or a privilege upgrade) starts from an empty cache, and other tabs are told to re-check. */
function startSession(queryClient: QueryClient, user: AuthUser): void {
  clearPrivateState(queryClient);
  queryClient.setQueryData(sessionKey, user);
  broadcastSessionSignal({ type: 'login' });
}

export function homePathFor(user: AuthUser): string {
  if (user.rol === 'ADMIN') return user.mfaPendiente ? '/admin/mfa' : '/admin';
  return '/familia';
}

export function useFamilyLogin() {
  const queryClient = useQueryClient();
  return useMutation<AuthUser, ApiError, Credentials>({
    mutationFn: loginFamily,
    onSuccess: (user) => startSession(queryClient, user),
  });
}

export function useAdminLogin() {
  const queryClient = useQueryClient();
  return useMutation<AuthUser, ApiError, Credentials>({
    mutationFn: loginAdmin,
    onSuccess: (user) => startSession(queryClient, user),
  });
}

export function useEnrollMfa() {
  return useMutation<MfaEnrollment, ApiError, void>({ mutationFn: enrollMfa });
}

export function useConfirmMfa() {
  const queryClient = useQueryClient();
  return useMutation<AuthUser, ApiError, string>({
    mutationFn: confirmMfa,
    onSuccess: (user) => startSession(queryClient, user),
  });
}

export function useVerifyMfa() {
  const queryClient = useQueryClient();
  return useMutation<AuthUser, ApiError, string>({
    mutationFn: verifyMfa,
    onSuccess: (user) => startSession(queryClient, user),
  });
}

/** Ends the session on the server first; local state is cleared only once the server confirmed it. */
export function useLogout(redirectTo: string) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  return useMutation<void, ApiError>({
    mutationFn: logout,
    onSuccess: () => {
      resetSessionCache(queryClient);
      broadcastSessionSignal({ type: 'logout' });
      void navigate(redirectTo, { replace: true });
    },
  });
}
