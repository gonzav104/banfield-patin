/**
 * Prepares a document BEFORE it leaves the phone, with no extra dependency (Canvas only):
 * - A photo is decoded applying its EXIF orientation, scaled down so its long side is at most 2400 px and RE-ENCODED as
 *   JPEG. Re-encoding drops every piece of metadata the original carried (EXIF with GPS location, XMP, the Ultra HDR gain
 *   map, Motion Photo or vendor bytes after the image). The backend still validates the result: this is privacy and
 *   usability, not a security control.
 * - A PDF is sent unchanged (its metadata is NOT removed: an accepted, documented risk).
 * - The file always gets a neutral name, so the original file name never reaches the server.
 * The resolution check only WARNS: a large image can still be blurry; only a person can confirm it is legible.
 */

export const MAX_LONG_SIDE = 2400;
/** Below this short side the family is warned that the image may not be legible. It is a hint, never a guarantee. */
export const LOW_RESOLUTION_SHORT_SIDE = 1000;
const QUALITIES = [0.85, 0.75, 0.65] as const;
/** A source over this size is refused before decoding it: decoding it could exhaust the memory of a phone. */
const MAX_SOURCE_BYTES = 50_000_000;

export interface DecodedImage {
  width: number;
  height: number;
  close: () => void;
}

/** The two browser operations, injectable so the rules can be tested where there is no Canvas (jsdom). */
export interface ImagePipeline {
  decode: (file: File) => Promise<DecodedImage>;
  encode: (image: DecodedImage, width: number, height: number, quality: number) => Promise<Blob>;
}

export interface PreparedDocument {
  kind: 'image' | 'pdf';
  blob: Blob;
  fileName: 'documento.jpg' | 'documento.pdf';
  width: number | null;
  height: number | null;
  lowResolution: boolean;
}

export type PrepareFailure = 'unsupported' | 'too-large' | 'decode-failed' | 'encode-failed';
export type PrepareResult = { ok: true; prepared: PreparedDocument } | { ok: false; reason: PrepareFailure };

export function targetSize(width: number, height: number, maxLongSide = MAX_LONG_SIDE): { width: number; height: number } {
  const scale = Math.min(1, maxLongSide / Math.max(width, height));
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}

const browserPipeline: ImagePipeline = {
  // `from-image` applies the EXIF orientation while decoding: the rotation survives even though EXIF is dropped.
  decode: (file) => createImageBitmap(file, { imageOrientation: 'from-image' }),
  encode: async (image, width, height, quality) => {
    if (!(image instanceof ImageBitmap)) throw new Error('image');
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('canvas');
    context.fillStyle = '#ffffff'; // a transparent PNG would otherwise turn black in a JPEG
    context.fillRect(0, 0, width, height);
    context.imageSmoothingQuality = 'high';
    context.drawImage(image, 0, 0, width, height);
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality));
    canvas.width = 0; // releases the pixel buffer right away
    canvas.height = 0;
    if (!blob) throw new Error('encode');
    return blob;
  },
};

/** Reads a Blob with FileReader, available in every browser (and in jsdom, which lacks `Blob.arrayBuffer`). */
export function readBytes(blob: Blob): Promise<Uint8Array<ArrayBuffer>> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer));
    reader.onerror = () => reject(reader.error ?? new Error('read'));
    reader.readAsArrayBuffer(blob);
  });
}

async function startsLikePdf(file: File): Promise<boolean> {
  const head = await readBytes(file.slice(0, 5));
  return String.fromCharCode(...head) === '%PDF-';
}

function isPdf(file: File): boolean {
  return file.type === 'application/pdf' || (file.type === '' && file.name.toLowerCase().endsWith('.pdf'));
}

export async function prepareDocument(file: File, maxBytes: number, pipeline: ImagePipeline = browserPipeline): Promise<PrepareResult> {
  if (isPdf(file)) {
    if (file.size > maxBytes) return { ok: false, reason: 'too-large' };
    if (!(await startsLikePdf(file))) return { ok: false, reason: 'unsupported' };
    const blob = new Blob([await readBytes(file)], { type: 'application/pdf' });
    return { ok: true, prepared: { kind: 'pdf', blob, fileName: 'documento.pdf', width: null, height: null, lowResolution: false } };
  }
  if (!file.type.startsWith('image/')) return { ok: false, reason: 'unsupported' };
  if (file.size > MAX_SOURCE_BYTES) return { ok: false, reason: 'too-large' };

  let image: DecodedImage;
  try {
    image = await pipeline.decode(file);
  } catch {
    return { ok: false, reason: 'decode-failed' };
  }
  try {
    const { width, height } = targetSize(image.width, image.height);
    for (const quality of QUALITIES) {
      let blob: Blob;
      try {
        blob = await pipeline.encode(image, width, height, quality);
      } catch {
        return { ok: false, reason: 'encode-failed' };
      }
      if (blob.type !== 'image/jpeg') return { ok: false, reason: 'encode-failed' };
      if (blob.size <= maxBytes) {
        const lowResolution = Math.min(width, height) < LOW_RESOLUTION_SHORT_SIDE;
        return { ok: true, prepared: { kind: 'image', blob, fileName: 'documento.jpg', width, height, lowResolution } };
      }
    }
    return { ok: false, reason: 'too-large' };
  } finally {
    image.close();
  }
}
