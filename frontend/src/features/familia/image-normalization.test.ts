import { describe, expect, it, vi } from 'vitest';
import { MAX_LONG_SIDE, prepareDocument, readBytes, targetSize, type ImagePipeline } from './image-normalization';

const MAX = 5_000_000;
const file = (parts: BlobPart[], name: string, type: string) => new File(parts, name, { type });
const pdf = (extra = 'contenido') => file([`%PDF-1.7\n${extra}`], 'DNI de Ana Paz.pdf', 'application/pdf');
const photo = () => file([new Uint8Array([0xff, 0xd8, 0xff, 0xe1, 1, 2, 3])], 'IMG_2026_casa.jpg', 'image/jpeg');

function pipeline(width: number, height: number, sizes: number[] = [200_000], type = 'image/jpeg') {
  const close = vi.fn();
  const encoded: { width: number; height: number; quality: number }[] = [];
  const deps: ImagePipeline = {
    decode: vi.fn(() => Promise.resolve({ width, height, close })),
    encode: vi.fn((_bitmap, w: number, h: number, quality: number) => {
      encoded.push({ width: w, height: h, quality });
      const size = sizes[Math.min(encoded.length - 1, sizes.length - 1)]!;
      return Promise.resolve(new Blob([new Uint8Array(size)], { type }));
    }),
  };
  return { deps, close, encoded };
}

describe('targetSize', () => {
  it('only scales DOWN, keeping the aspect ratio, so the long side is at most 2400 px', () => {
    expect(MAX_LONG_SIDE).toBe(2400);
    expect(targetSize(4000, 3000)).toEqual({ width: 2400, height: 1800 });
    expect(targetSize(3000, 6000)).toEqual({ width: 1200, height: 2400 });
    expect(targetSize(1200, 1600)).toEqual({ width: 1200, height: 1600 });
  });
});

describe('prepareDocument: photos are re-encoded', () => {
  it('decodes, scales and re-encodes as JPEG 0.85 with a neutral name (the original name and metadata never leave)', async () => {
    const { deps, close, encoded } = pipeline(4000, 3000);
    const result = await prepareDocument(photo(), MAX, deps);

    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(encoded).toEqual([{ width: 2400, height: 1800, quality: 0.85 }]);
    expect(result.prepared).toMatchObject({ kind: 'image', fileName: 'documento.jpg', width: 2400, height: 1800, lowResolution: false });
    expect(result.prepared.blob.type).toBe('image/jpeg');
    expect(close).toHaveBeenCalledOnce(); // the decoded bitmap is released
  });

  it('lowers the quality step by step (0.75, 0.65) when the JPEG is still over the limit', async () => {
    const { deps, encoded } = pipeline(4000, 3000, [MAX + 1, MAX + 1, 100_000]);
    const result = await prepareDocument(photo(), MAX, deps);

    expect(result.ok).toBe(true);
    expect(encoded.map((e) => e.quality)).toEqual([0.85, 0.75, 0.65]);
  });

  it('reports "too-large" when even the last step is over the limit', async () => {
    const { deps, close } = pipeline(4000, 3000, [MAX + 1]);
    await expect(prepareDocument(photo(), MAX, deps)).resolves.toEqual({ ok: false, reason: 'too-large' });
    expect(close).toHaveBeenCalledOnce();
  });

  it('flags a low resolution (short side under 1000 px) as a warning, not as proof of legibility', async () => {
    const { deps } = pipeline(900, 1200);
    const result = await prepareDocument(photo(), MAX, deps);
    expect(result.ok && result.prepared.lowResolution).toBe(true);
  });

  it('reports "decode-failed" when the browser cannot read the image (for example HEIC outside Safari)', async () => {
    const deps: ImagePipeline = { decode: vi.fn(async () => Promise.reject(new Error('x'))), encode: vi.fn() };
    await expect(prepareDocument(file(['x'], 'IMG.HEIC', 'image/heic'), MAX, deps)).resolves.toEqual({ ok: false, reason: 'decode-failed' });
    expect(deps.encode).not.toHaveBeenCalled();
  });

  it('reports "encode-failed" when the browser does not produce a JPEG', async () => {
    const { deps } = pipeline(2000, 1500, [100_000], 'image/png');
    await expect(prepareDocument(photo(), MAX, deps)).resolves.toEqual({ ok: false, reason: 'encode-failed' });
  });

  it('refuses a huge source file without decoding it (protects the phone memory)', async () => {
    const { deps } = pipeline(4000, 3000);
    const huge = photo();
    Object.defineProperty(huge, 'size', { value: 60_000_000 });
    await expect(prepareDocument(huge, MAX, deps)).resolves.toEqual({ ok: false, reason: 'too-large' });
    expect(deps.decode).not.toHaveBeenCalled();
  });
});

describe('prepareDocument: PDFs and other files', () => {
  it('sends a PDF unchanged, with a neutral name and no preview dimensions', async () => {
    const { deps } = pipeline(1, 1);
    const source = pdf();
    const result = await prepareDocument(source, MAX, deps);

    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.prepared).toMatchObject({ kind: 'pdf', fileName: 'documento.pdf', width: null, height: null, lowResolution: false });
    expect(await readBytes(result.prepared.blob)).toEqual(await readBytes(source));
    expect(deps.decode).not.toHaveBeenCalled();
  });

  it('refuses a PDF over the limit and a "PDF" that does not start like one', async () => {
    const { deps } = pipeline(1, 1);
    const big = pdf();
    Object.defineProperty(big, 'size', { value: MAX + 1 });
    await expect(prepareDocument(big, MAX, deps)).resolves.toEqual({ ok: false, reason: 'too-large' });
    await expect(prepareDocument(file(['hola'], 'x.pdf', 'application/pdf'), MAX, deps)).resolves.toEqual({ ok: false, reason: 'unsupported' });
  });

  it('refuses anything that is neither an image nor a PDF', async () => {
    const { deps } = pipeline(1, 1);
    await expect(prepareDocument(file(['x'], 'notas.txt', 'text/plain'), MAX, deps)).resolves.toEqual({ ok: false, reason: 'unsupported' });
  });
});
