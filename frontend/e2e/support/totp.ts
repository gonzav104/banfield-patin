import { createHmac } from 'node:crypto';

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
const STEP_SECONDS = 30;

function base32Decode(input: string): Buffer {
  let bits = 0;
  let value = 0;
  const bytes: number[] = [];
  for (const char of input.replace(/[\s=]/g, '').toUpperCase()) {
    const index = ALPHABET.indexOf(char);
    if (index < 0) throw new Error('Invalid base32 secret');
    value = (value << 5) | index;
    bits += 5;
    if (bits >= 8) {
      bytes.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(bytes);
}

/** RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30 s), the same algorithm authenticator apps use. */
export function totpForStep(secret: string, step: number): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const hmac = createHmac('sha1', base32Decode(secret)).update(counter).digest();
  const offset = hmac[hmac.length - 1]! & 0x0f;
  const binary =
    ((hmac[offset]! & 0x7f) << 24) | (hmac[offset + 1]! << 16) | (hmac[offset + 2]! << 8) | hmac[offset + 3]!;
  return String(binary % 1_000_000).padStart(6, '0');
}

export const currentStep = (): number => Math.floor(Date.now() / 1000 / STEP_SECONDS);

/**
 * The backend rejects a TOTP step that was already used, so each login needs a code from a newer step.
 * Waits until the current step is newer than `lastUsedStep` and has enough time left to be submitted.
 */
export async function freshCode(secret: string, lastUsedStep: number | null): Promise<{ code: string; step: number }> {
  for (;;) {
    const step = currentStep();
    const secondsLeft = STEP_SECONDS - (Date.now() / 1000 - step * STEP_SECONDS);
    if ((lastUsedStep === null || step > lastUsedStep) && secondsLeft > 4) {
      return { code: totpForStep(secret, step), step };
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
}
