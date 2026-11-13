import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { clearPrivateState, resetSessionCache, sessionKey } from '@/features/auth/session';
import { subscribeSessionSignals } from '@/features/auth/sessionSync';
import { setSessionEventListener } from '@/lib/http';

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: true },
      // Mutation variables hold passwords, TOTP codes and the enrolment secret: never keep them cached after use.
      mutations: { retry: false, gcTime: 0 },
    },
  });
}

/**
 * Wires the HTTP client and the other tabs to the session cache:
 * - 401 NO_AUTENTICADO on any request, or a logout in another tab: drop the identity and every cached private value,
 *   so route guards send the user to login.
 * - 401 without a code on an authenticated request: re-check /api/auth/me (503 or no connection keep the session).
 * - A sign-in in another tab: the shared cookie may now belong to someone else; drop private data and re-check.
 */
export function AppProviders({ children, client }: { children: ReactNode; client?: QueryClient }) {
  const [queryClient] = useState(() => client ?? createQueryClient());

  useEffect(() => {
    setSessionEventListener((event) => {
      if (event === 'lost') {
        clearPrivateState(queryClient);
        queryClient.setQueryData(sessionKey, null);
      } else {
        void queryClient.invalidateQueries({ queryKey: sessionKey });
      }
    });
    const unsubscribe = subscribeSessionSignals((signal) => {
      if (signal.type === 'logout') {
        resetSessionCache(queryClient);
      } else {
        clearPrivateState(queryClient);
        void queryClient.invalidateQueries({ queryKey: sessionKey });
      }
    });
    return () => {
      setSessionEventListener(null);
      unsubscribe();
    };
  }, [queryClient]);

  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
