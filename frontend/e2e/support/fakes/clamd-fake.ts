import { renameSync, writeFileSync } from 'node:fs';
import { createServer, type Socket } from 'node:net';
import { eicarBytes } from './eicar.ts';

/**
 * TEST DOUBLE of clamd for the real upload E2E (started by .github/scripts/frontend-e2e.sh, never by the application). It
 * speaks the subset of the clamd TCP protocol the backend adapter (ClamAvPrivado) uses:
 *   `zINSTREAM\0`, then chunks of <4-byte big-endian length><bytes>, ended by a zero length;
 *   reply `stream: OK\0`, or `stream: Eicar-Test-Signature FOUND\0` when the stream contains the EICAR test string; then close.
 * `zPING\0` answers `PONG\0` (a readiness probe). Anything else answers an error and closes. It binds to 127.0.0.1 only, on a
 * free port that it writes to `--port-file`. It is NOT an antivirus: it only recognises the EICAR test string, so it never
 * replaces the opt-in tests against a real ClamAV (docs/DOCUMENTOS.md).
 *
 * Usage: node e2e/support/fakes/clamd-fake.ts --port-file <path>
 */

const MAX_STREAM_BYTES = 64 * 1024 * 1024; // above the backend's own limit: the backend, not the double, enforces sizes
const EICAR = Buffer.from(eicarBytes());

function argument(name: string): string {
  const index = process.argv.indexOf(name);
  const value = index >= 0 ? process.argv[index + 1] : undefined;
  if (!value) throw new Error(`clamd double: missing ${name}`);
  return value;
}

function handle(socket: Socket): void {
  let pending = Buffer.alloc(0);
  let mode: 'command' | 'stream' | 'done' = 'command';
  const chunks: Buffer[] = [];
  let total = 0;

  const finish = (reply: string, verdict: string) => {
    mode = 'done';
    console.log(`clamd double: ${verdict}`); // the verdict only, never content
    socket.end(Buffer.from(reply, 'ascii'));
  };

  socket.on('data', (data: Buffer) => {
    if (mode === 'done') return;
    pending = Buffer.concat([pending, data]);
    if (mode === 'command') {
      const end = pending.indexOf(0);
      if (end < 0) {
        if (pending.length > 64) finish('UNKNOWN COMMAND\0', 'unknown command');
        return;
      }
      const command = pending.subarray(0, end).toString('ascii');
      pending = pending.subarray(end + 1);
      if (command === 'zPING') return finish('PONG\0', 'ping');
      if (command !== 'zINSTREAM') return finish('UNKNOWN COMMAND\0', 'unknown command');
      mode = 'stream';
    }
    while (mode === 'stream' && pending.length >= 4) {
      const length = pending.readUInt32BE(0);
      if (length === 0) {
        const content = Buffer.concat(chunks);
        return content.includes(EICAR) ? finish('stream: Eicar-Test-Signature FOUND\0', 'FOUND') : finish('stream: OK\0', 'OK');
      }
      if (total + length > MAX_STREAM_BYTES) return finish('INSTREAM size limit exceeded. ERROR\0', 'size limit');
      if (pending.length < 4 + length) return;
      chunks.push(pending.subarray(4, 4 + length));
      total += length;
      pending = pending.subarray(4 + length);
    }
  });
  socket.on('error', () => socket.destroy());
}

const portFile = argument('--port-file');
const server = createServer(handle);
server.listen(0, '127.0.0.1', () => {
  const address = server.address();
  if (address === null || typeof address === 'string') throw new Error('clamd double: no TCP address');
  writeFileSync(`${portFile}.tmp`, String(address.port), { mode: 0o600 });
  renameSync(`${portFile}.tmp`, portFile); // the script reads the port only once it is complete
  console.log(`clamd double: listening on 127.0.0.1:${address.port}`);
});
for (const signal of ['SIGTERM', 'SIGINT'] as const) process.on(signal, () => process.exit(0));
