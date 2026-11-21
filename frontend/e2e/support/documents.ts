import { createHash } from 'node:crypto';
import type { Page } from '@playwright/test';

/** Synthetic documents shared by the real document E2E (U15 upload, U16 audited download): no personal data in any of them. */

export const sha256 = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex');

/** A minimal, valid one-page PDF (exact xref offsets). `comment` goes into its content stream as a PDF comment line. */
export function syntheticPdf(comment: Uint8Array | null = null): Buffer {
  const text = Buffer.from('BT /F1 24 Tf 72 720 Td (Documento sintetico) Tj ET\n', 'latin1');
  const content = comment ? Buffer.concat([Buffer.from('% ', 'latin1'), Buffer.from(comment), Buffer.from('\n', 'latin1'), text]) : text;
  const objects = [
    Buffer.from('<< /Type /Catalog /Pages 2 0 R >>', 'latin1'),
    Buffer.from('<< /Type /Pages /Kids [3 0 R] /Count 1 >>', 'latin1'),
    Buffer.from('<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>', 'latin1'),
    Buffer.from('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>', 'latin1'),
    Buffer.concat([Buffer.from(`<< /Length ${content.length} >>\nstream\n`, 'latin1'), content, Buffer.from('endstream', 'latin1')]),
  ];
  const parts: Buffer[] = [Buffer.from('%PDF-1.4\n', 'latin1')];
  const offsets: number[] = [];
  let length = parts[0]!.length;
  objects.forEach((body, index) => {
    const object = Buffer.concat([Buffer.from(`${index + 1} 0 obj\n`, 'latin1'), body, Buffer.from('\nendobj\n', 'latin1')]);
    offsets.push(length);
    parts.push(object);
    length += object.length;
  });
  const xref = [`xref\n0 ${objects.length + 1}\n`, '0000000000 65535 f \n', ...offsets.map((offset) => `${String(offset).padStart(10, '0')} 00000 n \n`)].join('');
  parts.push(Buffer.from(`${xref}trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${length}\n%%EOF\n`, 'latin1'));
  return Buffer.concat(parts);
}

/** A JPEG rendered by the real browser from a canvas: a document-like card, large enough to be scaled down. */
export async function browserJpeg(page: Page, size: { width: number; height: number }): Promise<Buffer> {
  const base64 = await page.evaluate(async ({ width, height }) => {
    const canvas = new OffscreenCanvas(width, height);
    const context = canvas.getContext('2d');
    if (!context) throw new Error('no 2d context');
    const gradient = context.createLinearGradient(0, 0, width, height);
    gradient.addColorStop(0, '#dfe8f2');
    gradient.addColorStop(1, '#f6efe0');
    context.fillStyle = gradient;
    context.fillRect(0, 0, width, height);
    context.fillStyle = '#1f2a44';
    context.font = 'bold 120px sans-serif';
    context.fillText('DOCUMENTO SINTETICO', 160, 260);
    context.font = '80px sans-serif';
    for (let line = 0; line < 8; line++) context.fillText(`Campo ${line + 1}: valor de prueba`, 160, 480 + line * 170);
    context.strokeStyle = '#1f2a44';
    context.lineWidth = 12;
    context.strokeRect(60, 60, width - 120, height - 120);
    const blob = await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.92 });
    const bytes = new Uint8Array(await blob.arrayBuffer());
    let binary = '';
    for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    return btoa(binary);
  }, size);
  return Buffer.from(base64, 'base64');
}
