import { execFileSync } from 'node:child_process';

/** True when the run has a disposable PostgreSQL container to run SQL against (frontend-e2e.sh sets it). */
export const hasDisposableDb = (): boolean => Boolean(process.env.E2E_DB_CONTAINER);

/**
 * Runs one SQL statement inside the DISPOSABLE database of the E2E run. Only for states the admin API cannot produce
 * (an expired invitation, a school with no active season); everything else is seeded through the real API.
 */
export function dbQuery(sql: string): void {
  const container = process.env.E2E_DB_CONTAINER;
  if (!container) throw new Error('E2E_DB_CONTAINER is required for tests that change the database directly.');
  // psql reads the credentials from the container's own environment: nothing sensitive leaves it.
  // E2E_DB_NAME selects a disposable database inside that container (default: the container's POSTGRES_DB).
  const database = process.env.E2E_DB_NAME ? `'${process.env.E2E_DB_NAME.replace(/[^A-Za-z0-9_]/g, '')}'` : '"$POSTGRES_DB"';
  execFileSync('docker', ['exec', container, 'sh', '-c', `psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d ${database} -c "${sql}"`], { stdio: 'pipe' });
}
