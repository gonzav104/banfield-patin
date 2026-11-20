import { describe, expect, it } from 'vitest';
import { addPrivacyHazards, inspectJpeg, minimalJpeg, withExif, withTrailingBytes } from './jpeg';

/**
 * The E2E privacy proof (documentos-dni.real.spec.ts) asserts that the object the browser sent has NO Exif/GPS and NO bytes
 * after EOI. That only means something if the fixture really carried them and the inspector really sees them: these tests
 * pin both sides, with no browser.
 */
describe('synthetic JPEG fixture and inspector', () => {
  it('a plain JPEG has no Exif, no GPS and no bytes after EOI, and its size comes from SOF', () => {
    const report = inspectJpeg(minimalJpeg(3000, 2000));
    expect(report).toEqual({ isJpeg: true, hasExif: false, hasGps: false, trailingBytes: 0, width: 3000, height: 2000 });
  });

  it('the hazardous fixture carries an Exif segment WITH GPS and extra bytes after EOI, keeping the image size', () => {
    const plain = minimalJpeg(3000, 2000);
    const hazardous = addPrivacyHazards(plain);
    const report = inspectJpeg(hazardous);
    expect(report.isJpeg).toBe(true);
    expect(report.hasExif).toBe(true);
    expect(report.hasGps).toBe(true);
    expect(report.trailingBytes).toBeGreaterThan(100); // an appended gain map / motion photo, not a stray byte
    expect([report.width, report.height]).toEqual([3000, 2000]);
    expect(hazardous.length).toBeGreaterThan(plain.length);
  });

  it('tells an Exif segment without GPS apart from one with GPS, in both byte orders', () => {
    for (const byteOrder of ['MM', 'II'] as const) {
      expect(inspectJpeg(withExif(minimalJpeg(10, 10), { gps: false, byteOrder })), byteOrder).toMatchObject({ hasExif: true, hasGps: false });
      expect(inspectJpeg(withExif(minimalJpeg(10, 10), { gps: true, byteOrder })), byteOrder).toMatchObject({ hasExif: true, hasGps: true });
    }
  });

  it('counts only what follows the FIRST EOI of the primary image (an appended JPEG is trailing data)', () => {
    const plain = minimalJpeg(64, 48);
    const report = inspectJpeg(withTrailingBytes(plain));
    expect(report).toMatchObject({ hasExif: false, hasGps: false, width: 64, height: 48 });
    expect(report.trailingBytes).toBe(withTrailingBytes(plain).length - plain.length);
  });

  it('reports a non-JPEG as such and refuses a JPEG that never reaches EOI', () => {
    expect(inspectJpeg(new TextEncoder().encode('%PDF-1.4\n')).isJpeg).toBe(false);
    const truncated = minimalJpeg(20, 20).slice(0, -2);
    expect(() => inspectJpeg(truncated)).toThrow(/EOI/);
  });
});
