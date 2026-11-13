import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { scanArtifacts, type Finding } from './artifact-secrets.ts';
import { loadState } from './state.ts';

const ROOT = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
export const ARTIFACT_DIRS = [join(ROOT, 'test-results'), join(ROOT, 'playwright-report'), join(ROOT, 'e2e', 'artifacts')];

function knownSecrets(): (string | undefined)[] {
  const state = loadState();
  return [process.env.E2E_ADMIN_PASSWORD, state.adminSecret, state.family?.password];
}

export function summarize(findings: Finding[]): string {
  const files = new Set(findings.map((f) => f.file.replace(ROOT, '.')));
  return `${findings.length} finding(s) in ${files.size} file(s): ${[...new Set(findings.map((f) => f.kind))].join(', ')}`;
}

/** Playwright globalTeardown: redact in place, then verify nothing is left. Fails the run if something remains. */
export default function redactArtifacts(): void {
  const redacted = scanArtifacts(ARTIFACT_DIRS, { knownSecrets: knownSecrets(), mode: 'redact' });
  if (redacted.length > 0) console.warn(`[e2e] redacted secrets from artifacts — ${summarize(redacted)}`);
  const remaining = scanArtifacts(ARTIFACT_DIRS, { knownSecrets: knownSecrets(), mode: 'check' });
  if (remaining.length > 0) throw new Error(`[e2e] secrets remain in artifacts — ${summarize(remaining)}`);
}

// `npm run test:e2e:check-artifacts`: verification only, exit code 1 on any finding.
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const findings = scanArtifacts(ARTIFACT_DIRS, { knownSecrets: knownSecrets(), mode: 'check' });
  console.log(findings.length === 0 ? '[e2e] artifacts clean: no secrets found in text artifacts or traces' : `[e2e] ${summarize(findings)}`);
  process.exitCode = findings.length === 0 ? 0 : 1;
}
