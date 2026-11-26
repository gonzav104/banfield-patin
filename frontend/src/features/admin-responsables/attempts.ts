import type { ResponsableTipo } from '@/features/responsables/tipo';
import type { Observation } from './api';

/**
 * An alta whose outcome is UNCERTAIN (the answer was lost, the service did not answer, the reply was malformed). The server may or may not
 * have created the responsibility, so the very same request (same `Idempotency-Key`, same body) is kept until it is resolved: repeating it
 * either returns the original or creates it once. It is kept in memory only (never in storage, URL or logs), per identity and athlete, so
 * closing the dialog does not lose it and another administrator never sees it.
 */
export interface AltaAttempt {
  key: string;
  athleteId: string;
  tipo: ResponsableTipo | null;
  observation: Observation;
}

const attempts = new Map<string, AltaAttempt>();
const slot = (identity: string, athleteId: string) => `${identity}|${athleteId}`;

export function rememberAttempt(identity: string, attempt: AltaAttempt): void {
  attempts.set(slot(identity, attempt.athleteId), attempt);
}

export function pendingAttempt(identity: string, athleteId: string): AltaAttempt | undefined {
  return attempts.get(slot(identity, athleteId));
}

export function forgetAttempt(identity: string, athleteId: string): void {
  attempts.delete(slot(identity, athleteId));
}

/** Tests start from nothing. */
export function clearAttempts(): void {
  attempts.clear();
}
