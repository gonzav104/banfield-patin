import { randomUUID } from 'node:crypto';
import { existsSync, readFileSync, renameSync, statSync, writeFileSync } from 'node:fs';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { join } from 'node:path';

/**
 * TEST DOUBLE of Supabase Storage for the real upload E2E (started by .github/scripts/frontend-e2e.sh, never by the
 * application). It implements ONLY what the backend adapter (SupabaseAlmacenDocumentos) calls, with the formats verified
 * against the real Storage (docs/DOCUMENTOS.md, D3a, U7b and U7c):
 *   GET  /storage/v1/bucket/{bucket}                          -> 200 {"id","name","public":false,...} (a private bucket)
 *   POST /storage/v1/object/{bucket}/{key}  (x-upsert: false) -> 200 {"Id","Key":"bucket/key"}; an existing key is NEVER
 *                                                                overwritten: 400 {"statusCode":"409","error":"Duplicate",...}
 *   HEAD /storage/v1/object/authenticated/{bucket}/{key}      -> 200 with Content-Type/Content-Length, or 400 when absent
 *   GET  /storage/v1/object/authenticated/{bucket}/{key}      -> 200 with the object's bytes as they are NOW in --objects-dir,
 *                                                                its stored Content-Type and the real Content-Length (so a test
 *                                                                that rewrites the file sees the backend read the rewritten
 *                                                                bytes), or the verified absence answer below when absent
 *   GET  /storage/v1/object/info/authenticated/{bucket}/{key} -> 200 metadata, or the verified absence answer
 *        (400, JSON, exactly statusCode "404", error "not_found", code "NoSuchKey", message)
 * Every request must carry `Authorization: Bearer <credential>` and `apikey: <credential>` (the synthetic credential the
 * script generates, received in E2E_STORAGE_CREDENTIAL); otherwise 400 with statusCode "403", as the real Storage answers.
 * There is no delete, no public URL and no listing: anything else answers 404.
 *
 * Test-only inspection: each stored object is written to `--objects-dir` as `<key>` (the exact bytes received) plus
 * `<key>.meta.json` (content type and size), so the E2E can assert what the backend really stored. Binds to 127.0.0.1 only,
 * on a free port written to `--port-file` (the adapter accepts plain http only for loopback).
 *
 * Usage: E2E_STORAGE_CREDENTIAL=... node e2e/support/fakes/storage-fake.ts --port-file <path> --objects-dir <dir> --bucket <name>
 */

const MAX_BODY_BYTES = 64 * 1024 * 1024; // above the backend's own limit: the backend, not the double, enforces sizes
const KEY = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function argument(name: string): string {
  const index = process.argv.indexOf(name);
  const value = index >= 0 ? process.argv[index + 1] : undefined;
  if (!value) throw new Error(`storage double: missing ${name}`);
  return value;
}

const portFile = argument('--port-file');
const objectsDir = argument('--objects-dir');
const bucket = argument('--bucket');
const credential = process.env.E2E_STORAGE_CREDENTIAL ?? '';
if (credential === '') throw new Error('storage double: E2E_STORAGE_CREDENTIAL is not set');
if (!statSync(objectsDir).isDirectory()) throw new Error('storage double: --objects-dir is not a directory');

interface Meta {
  contentType: string;
  size: number;
  createdAt: string;
}

const metaPath = (key: string) => join(objectsDir, `${key}.meta.json`);
const readMeta = (key: string): Meta | null => (existsSync(metaPath(key)) ? (JSON.parse(readFileSync(metaPath(key), 'utf8')) as Meta) : null);

function json(res: ServerResponse, status: number, body: unknown): void {
  const bytes = Buffer.from(JSON.stringify(body), 'utf8');
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': bytes.length });
  res.end(bytes);
}

const error = (statusCode: string, errorName: string, message: string) => ({ statusCode, error: errorName, message });

function readBody(req: IncomingMessage): Promise<Buffer | null> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let total = 0;
    req.on('data', (chunk: Buffer) => {
      total += chunk.length;
      if (total > MAX_BODY_BYTES) {
        resolve(null);
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

async function route(req: IncomingMessage, res: ServerResponse): Promise<number> {
  const path = new URL(req.url ?? '/', 'http://127.0.0.1').pathname;
  if (req.headers.authorization !== `Bearer ${credential}` || req.headers.apikey !== credential) {
    json(res, 400, error('403', 'Unauthorized', 'invalid signature'));
    return 400;
  }
  const parts = path.split('/').filter(Boolean); // ['storage', 'v1', ...]
  if (parts[0] !== 'storage' || parts[1] !== 'v1') return notFound(res);
  const rest = parts.slice(2);

  if (req.method === 'GET' && rest.length === 2 && rest[0] === 'bucket') {
    if (rest[1] !== bucket) {
      json(res, 400, error('404', 'Bucket not found', 'Bucket not found'));
      return 400;
    }
    const now = new Date(0).toISOString();
    json(res, 200, { id: bucket, name: bucket, owner: '', public: false, file_size_limit: null, allowed_mime_types: null, created_at: now, updated_at: now });
    return 200;
  }

  if (req.method === 'POST' && rest.length === 3 && rest[0] === 'object') {
    const [, target, key] = rest as [string, string, string];
    if (target !== bucket || !KEY.test(key)) return notFound(res);
    if (req.headers['x-upsert'] !== 'false') {
      json(res, 400, error('400', 'InvalidRequest', 'This double only accepts x-upsert: false'));
      return 400;
    }
    const contentType = req.headers['content-type'] ?? '';
    const body = await readBody(req);
    if (body === null) {
      json(res, 413, error('413', 'Payload too large', 'The object exceeded the maximum allowed size'));
      return 413;
    }
    if (contentType === '' || body.length === 0) {
      json(res, 400, error('400', 'InvalidRequest', 'Missing content'));
      return 400;
    }
    if (readMeta(key) !== null) {
      json(res, 400, error('409', 'Duplicate', 'The resource already exists'));
      return 400;
    }
    writeFileSync(join(objectsDir, key), body, { mode: 0o600, flag: 'wx' }); // `wx`: never overwrite, even on a race
    const meta: Meta = { contentType, size: body.length, createdAt: new Date().toISOString() };
    writeFileSync(`${metaPath(key)}.tmp`, JSON.stringify(meta), { mode: 0o600 });
    renameSync(`${metaPath(key)}.tmp`, metaPath(key));
    json(res, 200, { Id: randomUUID(), Key: `${bucket}/${key}` });
    return 200;
  }

  const authenticated = rest[0] === 'object' && rest.length === 4 && rest[1] === 'authenticated';
  if (req.method === 'HEAD' && authenticated) {
    const [, , target, key] = rest as [string, string, string, string];
    const meta = target === bucket && KEY.test(key) ? readMeta(key) : null;
    if (meta === null || !existsSync(join(objectsDir, key))) {
      res.writeHead(400);
      res.end();
      return 400;
    }
    // Like real Storage, HEAD describes the object as it is NOW (same source as GET), not the size recorded at upload time:
    // a test that rewrites the object sees a consistent size in HEAD and GET.
    res.writeHead(200, { 'Content-Type': meta.contentType, 'Content-Length': statSync(join(objectsDir, key)).size });
    res.end();
    return 200;
  }

  if (req.method === 'GET' && authenticated) {
    const [, , target, key] = rest as [string, string, string, string];
    const meta = target === bucket && KEY.test(key) ? readMeta(key) : null;
    if (meta === null || !existsSync(join(objectsDir, key))) {
      json(res, 400, { statusCode: '404', error: 'not_found', message: 'Object not found', code: 'NoSuchKey' });
      return 400;
    }
    const body = readFileSync(join(objectsDir, key));
    res.writeHead(200, { 'Content-Type': meta.contentType, 'Content-Length': body.length });
    res.end(body);
    return 200;
  }

  if (req.method === 'GET' && rest.length === 5 && rest[0] === 'object' && rest[1] === 'info' && rest[2] === 'authenticated') {
    const [, , , target, key] = rest as [string, string, string, string, string];
    const meta = target === bucket && KEY.test(key) ? readMeta(key) : null;
    if (meta === null) {
      // The exact absence answer verified against the real Storage (U7c): four keys, statusCode as text.
      json(res, 400, { statusCode: '404', error: 'not_found', message: 'Object not found', code: 'NoSuchKey' });
      return 400;
    }
    json(res, 200, { id: randomUUID(), name: key, bucket_id: bucket, size: meta.size, content_type: meta.contentType, created_at: meta.createdAt });
    return 200;
  }

  return notFound(res);
}

function notFound(res: ServerResponse): number {
  json(res, 404, error('404', 'not_found', 'Route not implemented by the E2E Storage double'));
  return 404;
}

/** The route shape only: never the object key, the credential or any content. */
const shape = (req: IncomingMessage) =>
  `${req.method} ${new URL(req.url ?? '/', 'http://127.0.0.1').pathname.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g, '{key}')}`;

const server = createServer((req, res) => {
  route(req, res)
    .then((status) => console.log(`storage double: ${shape(req)} -> ${status}`))
    .catch(() => {
      if (!res.headersSent) json(res, 500, error('500', 'internal', 'storage double failure'));
      console.log(`storage double: ${shape(req)} -> 500`);
    });
});
server.listen(0, '127.0.0.1', () => {
  const address = server.address();
  if (address === null || typeof address === 'string') throw new Error('storage double: no TCP address');
  writeFileSync(`${portFile}.tmp`, String(address.port), { mode: 0o600 });
  renameSync(`${portFile}.tmp`, portFile); // the script reads the port only once it is complete
  console.log(`storage double: listening on 127.0.0.1:${address.port}`);
});
for (const signal of ['SIGTERM', 'SIGINT'] as const) process.on(signal, () => process.exit(0));
