import { execFileSync } from 'node:child_process';

/**
 * SQL helper for the few E2E states the admin API cannot produce (an expired invitation, a school with no active season).
 * It is destructive by nature, so it refuses to run unless the target is provably the DISPOSABLE database the E2E script
 * creates, and it never builds SQL from text:
 *  - the container name must have the shape the script gives it, the database must be exactly `bp_e2e`, and the container
 *    must carry the label the script puts on it (`docker run --label`). A name typed by hand, a development container or a
 *    different database are refused before any statement runs. The label can be set by hand too, but only on purpose.
 *  - the SQL is a constant sent through stdin; variable parts travel as psql variables and are quoted by psql itself
 *    (`:'name'`), never concatenated; the shell command is a constant and the values reach it as arguments.
 *  - credentials stay inside the container (psql reads them from its environment); errors carry no environment values.
 */
export const DISPOSABLE_LABEL = 'com.banfieldpatin.e2e.disposable';
const DISPOSABLE_DATABASE = 'bp_e2e';
const CONTAINER_NAME = /^bp-e2e-postgres-[A-Za-z0-9_-]+$/;
const VARIABLE_NAME = /^[a-z][a-z0-9_]*$/;
// A constant script: the database and the psql variables arrive as positional arguments ("$1", "$@"), never inside the text.
const PSQL = 'db="$1"; shift; exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$db" "$@"';

/**
 * The three states of the SQL environment, for the tests that need it:
 *  - not configured (`E2E_DB_CONTAINER` unset or empty): returns false, and the test skips EXPLICITLY;
 *  - configured and provably the disposable database: returns true;
 *  - configured but invalid or unsafe: THROWS (the test fails before doing anything), it never turns into a skip.
 */
export function hasDisposableDb(): boolean {
  if (!process.env.E2E_DB_CONTAINER) return false;
  disposableContainer();
  return true;
}

let inspectedContainer: string | null = null;

/** Returns the container only if it is provably the disposable one; throws otherwise (and never touches the database). */
function disposableContainer(): string {
  const container = process.env.E2E_DB_CONTAINER ?? '';
  if (container === '') throw new Error('E2E SQL unavailable: E2E_DB_CONTAINER is not set.');
  if (!CONTAINER_NAME.test(container)) throw new Error('E2E SQL refused: E2E_DB_CONTAINER is not a container created by frontend-e2e.sh (bp-e2e-postgres-*).');
  // E2E_DB_NAME only confirms the intent: psql always receives the constant below. It is checked on EVERY call, never cached.
  if (process.env.E2E_DB_NAME !== DISPOSABLE_DATABASE) throw new Error(`E2E SQL refused: E2E_DB_NAME must be exactly "${DISPOSABLE_DATABASE}".`);
  if (inspectedContainer === container) return container; // only the (slow) label inspection is remembered
  let label = '';
  try {
    label = execFileSync('docker', ['inspect', '--format', `{{index .Config.Labels "${DISPOSABLE_LABEL}"}}`, container], { stdio: 'pipe' }).toString().trim();
  } catch {
    throw new Error('E2E SQL refused: the container could not be inspected.');
  }
  if (label !== 'true') throw new Error(`E2E SQL refused: the container does not carry the label ${DISPOSABLE_LABEL}=true that frontend-e2e.sh sets.`);
  inspectedContainer = container;
  return container;
}

/**
 * Runs one constant SQL statement in the disposable database and returns psql's output (for example `UPDATE 1`), so the
 * caller can check how many rows it touched. `variables` are referenced in the SQL as `:'name'` (a quoted literal).
 */
export function dbQuery(sql: string, variables: Record<string, string> = {}): string {
  const flags = Object.entries(variables).flatMap(([name, value]) => {
    if (!VARIABLE_NAME.test(name)) throw new Error(`E2E SQL refused: invalid variable name "${name}".`);
    return ['-v', `${name}=${value}`];
  });
  const container = disposableContainer();
  try {
    return execFileSync('docker', ['exec', '-i', container, 'sh', '-c', PSQL, 'sh', DISPOSABLE_DATABASE, ...flags], { input: sql, stdio: ['pipe', 'pipe', 'pipe'] }).toString();
  } catch (error) {
    const stderr = (error as { stderr?: Buffer }).stderr?.toString().trim().split('\n').slice(0, 3).join(' | ') ?? '';
    throw new Error(`E2E SQL failed: ${stderr || 'psql exited with an error'}`);
  }
}

/**
 * The number psql reports for an UPDATE: a line that is EXACTLY `UPDATE <n>` (so `UPDATE 10` is 10, never 1), or null if there
 * is no such line. Callers assert the exact count they seeded.
 */
export function updatedRows(output: string): number | null {
  const line = output.split(/\r?\n/).find((candidate) => /^UPDATE \d+$/.test(candidate));
  return line === undefined ? null : Number(line.slice('UPDATE '.length));
}
