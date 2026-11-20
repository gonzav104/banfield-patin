/**
 * The EICAR anti-malware TEST string (harmless by design: every scanner reports it as a test signature). It is assembled at
 * runtime from pieces so the repository never stores it contiguously: a scanner on a developer machine or a runner must not
 * flag (or quarantine) the source files themselves. Used by the clamd double and by the upload E2E.
 */
const PIECES = ['X5O!P%@AP[4', '\\PZX54(P^)7CC)7}$', 'EICAR-STANDARD-', 'ANTIVIRUS-', 'TEST-FILE!$H+H*'];

export function eicarBytes(): Uint8Array {
  return new TextEncoder().encode(PIECES.join(''));
}
