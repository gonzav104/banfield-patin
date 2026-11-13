// @vitest-environment node
import { mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { scanArtifacts } from './artifact-secrets';

const dirs: string[] = [];
function scratch(): string {
  const dir = mkdtempSync(join(tmpdir(), 'bp-artifacts-'));
  dirs.push(dir);
  return dir;
}
afterEach(() => dirs.splice(0).forEach((d) => rmSync(d, { recursive: true, force: true })));

const TOKEN = 'abcdefghijABCDEFGHIJ0123456789_-abcdefghijk'; // 43 chars, fake
const TOTP = 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP';

describe('E2E artifact secret scanner (H-02)', () => {
  it('finds invitation tokens, otpauth URIs, known secrets and generated passwords without echoing them', () => {
    const dir = scratch();
    writeFileSync(join(dir, 'error-context.md'), `textbox "Enlace de registro": http://127.0.0.1:5173/registro/invitacion#${TOKEN}\n`);
    writeFileSync(join(dir, 'snap.md'), `paragraph: otpauth://totp/Banfield:a?secret=${TOTP}\nsecret ${TOTP}\npass Reg-0123456789abcdef`);
    writeFileSync(join(dir, 'clean.md'), 'heading "Hola, Admin"');

    const findings = scanArtifacts([dir], { knownSecrets: [TOTP, 'S3cr3t-admin-pass'], mode: 'check' });
    expect(findings.map((f) => f.kind).sort()).toEqual(['generated-password', 'invitation-token', 'known-secret', 'otpauth-uri']);
    expect(JSON.stringify(findings)).not.toContain(TOKEN);
    expect(JSON.stringify(findings)).not.toContain(TOTP);
  });

  it('also catches legacy path links', () => {
    const dir = scratch();
    writeFileSync(join(dir, 'a.json'), JSON.stringify({ url: `/registro/invitacion/${TOKEN}` }));
    expect(scanArtifacts([dir], { knownSecrets: [], mode: 'check' }).map((f) => f.kind)).toEqual(['invitation-token']);
  });

  it('redacts text artifacts in place and deletes opaque trace archives', () => {
    const dir = scratch();
    mkdirSync(join(dir, 'nested'));
    const md = join(dir, 'nested', 'error-context.md');
    writeFileSync(md, `link /registro/invitacion#${TOKEN} and S3cr3t-admin-pass`);
    const zip = join(dir, 'nested', 'trace.zip');
    writeFileSync(zip, 'PK...');

    const findings = scanArtifacts([dir], { knownSecrets: ['S3cr3t-admin-pass'], mode: 'redact' });
    expect(findings.length).toBeGreaterThan(0);
    const text = readFileSync(md, 'utf8');
    expect(text).not.toContain(TOKEN);
    expect(text).not.toContain('S3cr3t-admin-pass');
    expect(text).toContain('[REDACTED]');
    expect(existsSync(zip)).toBe(false);
    expect(scanArtifacts([dir], { knownSecrets: ['S3cr3t-admin-pass'], mode: 'check' })).toEqual([]);
  });

  it('ignores missing directories and short known secrets', () => {
    expect(scanArtifacts([join(tmpdir(), 'does-not-exist-bp')], { knownSecrets: ['', 'abc'], mode: 'check' })).toEqual([]);
  });
});
