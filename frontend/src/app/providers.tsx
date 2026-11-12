import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { sessionKey } from '@/features/auth/session';
import { setUnauthenticatedListener } from '@/lib/http';

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
 * Wires the HTTP client to the session cache: when any request reports the session is gone (401 NO_AUTENTICADO),
 * the cached identity and every other cached server value are dropped so route guards send the user to login.
 */
export function AppProviders({ children, client }: { children: ReactNode; client?: QueryClient }) {
  const [queryClient] = useState(() => client ?? createQueryClient());

  useEffect(() => {
    setUnauthenticatedListener(() => {
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== sessionKey[0] });
      queryClient.setQueryData(sessionKey, null);
    });
    return () => setUnauthenticatedListener(null);
  }, [queryClient]);

  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
