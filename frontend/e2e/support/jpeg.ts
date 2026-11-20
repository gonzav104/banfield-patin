/**
 * Synthetic JPEG helpers for the document upload E2E (documentos-dni.real.spec.ts), with no dependency:
 * - `withExif` / `withTrailingBytes` / `addPrivacyHazards` take ANY baseline JPEG (in the E2E, one the real browser rendered
 *   from a canvas) and add what a phone photo carries and the upload must drop: an APP1/Exif segment with GPS tags, and bytes
 *   after the EOI marker (an appended gain map or motion photo, as Ultra HDR / Motion Photo files do).
 * - `inspectJpeg` reports, from the bytes alone, whether a file is a JPEG, carries Exif and GPS, how many bytes follow the
 *   first EOI of the primary image and its size from the SOF segment.
 * - `minimalJpeg` is a STRUCTURAL baseline JPEG (valid markers, not meant to be decoded) for the unit tests of the above.
 * All values are synthetic: the coordinates and the camera name identify nobody.
 */

export interface JpegReport {
  isJpeg: boolean;
  hasExif: boolean;
  hasGps: boolean;
  trailingBytes: number;
  width: number | null;
  height: number | null;
}

type ByteOrder = 'MM' | 'II';

const concat = (...parts: Uint8Array[]): Uint8Array => {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
};
const ascii = (text: string) => new TextEncoder().encode(text);
const u16 = (value: number) => new Uint8Array([(value >> 8) & 0xff, value & 0xff]);
const segment = (marker: number, payload: Uint8Array) => concat(new Uint8Array([0xff, marker]), u16(payload.length + 2), payload);

/** A structural baseline JPEG of `width` x `height` (one grayscale component). */
export function minimalJpeg(width: number, height: number): Uint8Array {
  const jfif = segment(0xe0, concat(ascii('JFIF\0'), new Uint8Array([1, 1, 0, 0, 1, 0, 1, 0, 0])));
  const dqt = segment(0xdb, concat(new Uint8Array([0]), new Uint8Array(64).fill(1)));
  const sof = segment(0xc0, concat(new Uint8Array([8]), u16(height), u16(width), new Uint8Array([1, 1, 0x11, 0])));
  const oneCode = (symbol: number, tableClass: number) => concat(new Uint8Array([tableClass << 4]), new Uint8Array([1]), new Uint8Array(15), new Uint8Array([symbol]));
  const dht = segment(0xc4, concat(oneCode(0, 0), oneCode(0, 1)));
  const sos = segment(0xda, new Uint8Array([1, 1, 0, 0, 0x3f, 0]));
  // Entropy-coded data with a stuffed 0xFF 0x00 pair, so the inspector's scan over it is exercised.
  const entropy = new Uint8Array([0x00, 0x00, 0xff, 0x00, 0x00, 0x00]);
  return concat(new Uint8Array([0xff, 0xd8]), jfif, dqt, sof, dht, sos, entropy, new Uint8Array([0xff, 0xd9]));
}

/** A TIFF structure (the payload of an Exif APP1 after "Exif\0\0") with an orientation tag and, optionally, a GPS IFD. */
function tiff(gps: boolean, order: ByteOrder): Uint8Array {
  const little = order === 'II';
  const bytes = new Uint8Array(gps ? 152 : 26);
  const view = new DataView(bytes.buffer);
  const w16 = (at: number, value: number) => view.setUint16(at, value, little);
  const w32 = (at: number, value: number) => view.setUint32(at, value, little);
  bytes.set(ascii(order), 0);
  w16(2, 42);
  w32(4, 8);
  // IFD0: Orientation (1 = as stored) and, with gps, the GPSInfo pointer (tag 0x8825) to the GPS IFD at 38.
  w16(8, gps ? 2 : 1);
  w16(10, 0x0112);
  w16(12, 3);
  w32(14, 1);
  w16(18, 1);
  if (!gps) {
    w32(22, 0);
    return bytes;
  }
  w16(22, 0x8825);
  w16(24, 4);
  w32(26, 1);
  w32(30, 38);
  w32(34, 0);
  // GPS IFD: version, latitude ref + value, longitude ref + value (rationals stored at 104 and 128).
  const entry = (index: number, tag: number, type: number, count: number, write: (at: number) => void) => {
    const at = 40 + index * 12;
    w16(at, tag);
    w16(at + 2, type);
    w32(at + 4, count);
    write(at + 8);
  };
  w16(38, 5);
  entry(0, 0x0000, 1, 4, (at) => bytes.set([2, 3, 0, 0], at));
  entry(1, 0x0001, 2, 2, (at) => bytes.set(ascii('S\0'), at));
  entry(2, 0x0002, 5, 3, (at) => w32(at, 104));
  entry(3, 0x0003, 2, 2, (at) => bytes.set(ascii('W\0'), at));
  entry(4, 0x0004, 5, 3, (at) => w32(at, 128));
  w32(100, 0);
  const rationals = (at: number, values: [number, number, number]) =>
    values.forEach((value, i) => {
      w32(at + i * 8, value);
      w32(at + i * 8 + 4, 1);
    });
  rationals(104, [12, 34, 56]); // synthetic coordinates
  rationals(128, [65, 43, 21]);
  return bytes;
}

/** Inserts an APP1/Exif segment right after SOI and the JFIF APP0 (when present), as cameras write it. */
export function withExif(jpeg: Uint8Array, options: { gps: boolean; byteOrder?: ByteOrder }): Uint8Array {
  if (jpeg[0] !== 0xff || jpeg[1] !== 0xd8) throw new Error('withExif: not a JPEG');
  const app1 = segment(0xe1, concat(ascii('Exif\0\0'), tiff(options.gps, options.byteOrder ?? 'MM')));
  let at = 2;
  if (jpeg[2] === 0xff && jpeg[3] === 0xe0) at = 4 + ((jpeg[4]! << 8) | jpeg[5]!);
  return concat(jpeg.slice(0, at), app1, jpeg.slice(at));
}

/** Appends what Ultra HDR / Motion Photo files keep after the primary image: a second JPEG (gain map) and vendor bytes. */
export function withTrailingBytes(jpeg: Uint8Array): Uint8Array {
  const vendor = concat(ascii('MotionPhoto_Data'), new Uint8Array(96).map((_, i) => (i * 37) & 0xff));
  return concat(jpeg, minimalJpeg(16, 16), vendor);
}

/** What a phone photo carries that the upload must drop: Exif with GPS, plus bytes after EOI. */
export function addPrivacyHazards(jpeg: Uint8Array): Uint8Array {
  return withTrailingBytes(withExif(jpeg, { gps: true }));
}

/** True when the TIFF structure of an Exif segment points to a GPS IFD holding a latitude or longitude. */
function tiffHasGps(data: Uint8Array): boolean {
  if (data.length < 8) return false;
  const order = String.fromCharCode(data[0]!, data[1]!);
  if (order !== 'MM' && order !== 'II') return false;
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const little = order === 'II';
  const r16 = (at: number) => (at + 2 <= data.length ? view.getUint16(at, little) : null);
  const r32 = (at: number) => (at + 4 <= data.length ? view.getUint32(at, little) : null);
  const entries = (ifd: number) => {
    const count = r16(ifd) ?? 0;
    return Array.from({ length: count }, (_, i) => ifd + 2 + i * 12).filter((at) => at + 12 <= data.length);
  };
  const ifd0 = r32(4);
  if (ifd0 === null) return false;
  const pointer = entries(ifd0).find((at) => r16(at) === 0x8825);
  if (pointer === undefined) return false;
  const gpsIfd = r32(pointer + 8);
  if (gpsIfd === null) return false;
  return entries(gpsIfd).some((at) => [0x0002, 0x0004].includes(r16(at) ?? -1));
}

export function inspectJpeg(bytes: Uint8Array): JpegReport {
  const report: JpegReport = { isJpeg: false, hasExif: false, hasGps: false, trailingBytes: 0, width: null, height: null };
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return report;
  report.isJpeg = true;
  let i = 2;
  while (i < bytes.length) {
    if (bytes[i] !== 0xff) throw new Error(`inspectJpeg: expected a marker at ${i}`);
    while (bytes[i] === 0xff) i++;
    const marker = bytes[i];
    if (marker === undefined) break;
    i++;
    if (marker === 0xd9) {
      report.trailingBytes = bytes.length - i;
      return report;
    }
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
    if (i + 2 > bytes.length) break;
    const length = (bytes[i]! << 8) | bytes[i + 1]!;
    const payload = bytes.subarray(i + 2, i + length);
    if (marker === 0xe1 && String.fromCharCode(...payload.subarray(0, 6)) === 'Exif\0\0') {
      report.hasExif = true;
      report.hasGps ||= tiffHasGps(payload.subarray(6));
    }
    if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker) && payload.length >= 5) {
      report.height = (payload[1]! << 8) | payload[2]!;
      report.width = (payload[3]! << 8) | payload[4]!;
    }
    i += length;
    if (marker === 0xda) {
      // Entropy-coded data: skip to the next marker that is neither a stuffed 0x00 nor a restart marker.
      while (i < bytes.length) {
        if (bytes[i] === 0xff && i + 1 < bytes.length) {
          const next = bytes[i + 1]!;
          if (next !== 0x00 && next !== 0xff && !(next >= 0xd0 && next <= 0xd7)) break;
        }
        i++;
      }
    }
  }
  throw new Error('inspectJpeg: the primary image never reaches EOI');
}
