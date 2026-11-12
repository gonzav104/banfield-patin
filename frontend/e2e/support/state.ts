import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const STATE_FILE = join(dirname(fileURLToPath(import.meta.url)), '..', '.state.json');

/** Local, git-ignored scratch state shared between the serial E2E tests (never committed). */
export interface E2eState {
  adminSecret?: string;
  lastTotpStep?: number;
  family?: { email: string; password: string; nombre: string };
}

export function loadState(): E2eState {
  return existsSync(STATE_FILE) ? (JSON.parse(readFileSync(STATE_FILE, 'utf8')) as E2eState) : {};
}

export function saveState(state: E2eState): void {
  writeFileSync(STATE_FILE, JSON.stringify(state, null, 2), { mode: 0o600 });
}

export function adminCredentials(): { email: string; password: string } {
  const email = process.env.E2E_ADMIN_EMAIL;
  const password = process.env.E2E_ADMIN_PASSWORD;
  if (!email || !password) {
    throw new Error('Set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD (the backend bootstrap administrator).');
  }
  return { email, password };
}
