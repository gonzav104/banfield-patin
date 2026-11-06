package com.banfieldpatin.backend.documentos.carga;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdfparser.PDFParser;

/**
 * Entrada exclusiva del proceso acotado; sin Spring, red, salida documental ni evaluación de
 * acciones.
 */
public final class InspeccionContenido {
  private InspeccionContenido() {}

  public static void main(String[] args) {
    try {
      Path file = Path.of(args[0]);
      String mime = args[1];
      long pixels = Long.parseLong(args[2]);
      if (mime.equals("application/pdf")) pdf(file);
      else {
        if (mime.equals("image/png")) png(file, pixels);
        else if (mime.equals("image/jpeg")) jpeg(file, pixels);
        else throw new IOException();
        imagen(file, pixels);
      }
      System.exit(0);
    } catch (Throwable ex) {
      System.exit(42);
    }
  }

  private static void pdf(Path file) throws IOException {
    try (var source = new RandomAccessReadBufferedFile(file);
        var doc = new PDFParser(source).parse(false)) {
      if (doc.isEncrypted() || doc.getNumberOfPages() < 1 || doc.getNumberOfPages() > 1000)
        throw new IOException();
      Set<COSBase> vistos = Collections.newSetFromMap(new IdentityHashMap<>());
      Deque<COSBase> cola = new ArrayDeque<>();
      cola.add(doc.getDocumentCatalog().getCOSObject());
      for (var key : doc.getDocument().getXrefTable().keySet())
        cola.add(doc.getDocument().getObjectFromPool(key));
      long decodificados = 0;
      int count = 0;
      while (!cola.isEmpty()) {
        COSBase obj = cola.removeFirst();
        if (!vistos.add(obj)) continue;
        if (++count > 50000) throw new IOException();
        if (obj instanceof COSObject ref) {
          if (ref.getObject() != null) cola.add(ref.getObject());
        } else if (obj instanceof COSDictionary dict) {
          for (COSName k : dict.keySet()) {
            if (Set.of("JS", "JavaScript", "EmbeddedFiles", "EF", "AF", "XFA")
                .contains(k.getName())) throw new IOException();
            COSBase val = dict.getItem(k);
            if (val != null) cola.add(val);
          }
          if (Set.of(
                      "JavaScript",
                      "Launch",
                      "SubmitForm",
                      "ImportData",
                      "GoToR",
                      "Rendition",
                      "RichMediaExecute")
                  .contains(dict.getNameAsString(COSName.S, ""))
              || "EmbeddedFile".equals(dict.getNameAsString(COSName.TYPE))) throw new IOException();
          if (dict instanceof COSStream stream)
            try (var in = stream.createInputStream()) {
              byte[] buf = new byte[8192];
              int n;
              while ((n = in.read(buf)) != -1) {
                decodificados += n;
                if (decodificados > 32000000) throw new IOException();
              }
            }
        } else if (obj instanceof COSArray array)
          for (COSBase value : array) if (value != null) cola.add(value);
      }
    }
  }

  private static void dimensiones(long w, long h, long limite) throws IOException {
    if (w < 1 || h < 1 || w > 16000 || h > 16000 || w * h > limite) throw new IOException();
  }

  private static void imagen(Path file, long limite) throws IOException {
    ImageIO.setUseCache(false);
    try (var input = ImageIO.createImageInputStream(file.toFile())) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new IOException();
      var reader = readers.next();
      try {
        reader.setInput(input, true, true);
        dimensiones(reader.getWidth(0), reader.getHeight(0), limite);
        boolean[] warning = {false};
        reader.addIIOReadWarningListener((r, w) -> warning[0] = true);
        if (reader.read(0) == null || warning[0]) throw new IOException();
      } finally {
        reader.dispose();
      }
    }
  }

  private static void png(Path file, long limite) throws IOException {
    try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
      if (in.readLong() != 0x89504e470d0a1a0aL) throw new IOException();
      boolean head = false, data = false, end = false, afterData = false, paleta = false;
      int color = -1, profundidad = 0;
      while (!end) {
        int len = in.readInt();
        if (len < 0 || len > Files.size(file)) throw new IOException();
        byte[] type = in.readNBytes(4);
        if (type.length != 4) throw new IOException();
        for (int i = 0; i < 4; i++)
          if (!((type[i] >= 'A' && type[i] <= 'Z') || (type[i] >= 'a' && type[i] <= 'z')))
            throw new IOException();
        if (type[2] < 'A' || type[2] > 'Z') throw new IOException();
        String t = new String(type, java.nio.charset.StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(type);
        if (!head && (!t.equals("IHDR") || len != 13)) throw new IOException();
        if (t.equals("IHDR")) {
          if (head || len != 13) throw new IOException();
          byte[] h = in.readNBytes(13);
          if (h.length != 13) throw new IOException();
          crc.update(h);
          var b = java.nio.ByteBuffer.wrap(h);
          dimensiones(b.getInt(), b.getInt(), limite);
          profundidad = h[8] & 255;
          color = h[9] & 255;
          if (!Set.of(0, 2, 3, 4, 6).contains(color)
              || h[10] != 0
              || h[11] != 0
              || (h[12] != 0 && h[12] != 1)) throw new IOException();
          if (!(color == 0
                  ? Set.of(1, 2, 4, 8, 16)
                  : color == 3 ? Set.of(1, 2, 4, 8) : Set.of(8, 16))
              .contains(profundidad)) throw new IOException();
          head = true;
        } else {
          if (t.equals("PLTE")) {
            if (paleta
                || data
                || color == 0
                || color == 4
                || len < 3
                || len > 768
                || len % 3 != 0
                || color == 3 && len / 3 > (1 << profundidad)) throw new IOException();
            paleta = true;
          }
          if (t.equals("IDAT")) {
            if (afterData || color == 3 && !paleta) throw new IOException();
            data = true;
          } else if (data) afterData = true;
          if (Character.isUpperCase((char) type[0]) && !Set.of("IDAT", "PLTE", "IEND").contains(t))
            throw new IOException();
          if (t.equals("IEND")) {
            if (!data || len != 0) throw new IOException();
            end = true;
          }
          byte[] buffer = new byte[8192];
          int remaining = len;
          while (remaining > 0) {
            int n = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (n < 0) throw new IOException();
            crc.update(buffer, 0, n);
            remaining -= n;
          }
        }
        if ((int) crc.getValue() != in.readInt()) throw new IOException();
      }
      if (in.read() != -1) throw new IOException();
    }
  }

  private static void jpeg(Path file, long limite) throws IOException {
    try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
      if (in.readUnsignedShort() != 0xffd8) throw new IOException();
      boolean entropy = false, frame = false, scan = false;
      while (true) {
        int marker;
        if (entropy) {
          int c;
          do {
            c = in.readUnsignedByte();
          } while (c != 255);
          do {
            marker = in.readUnsignedByte();
          } while (marker == 255);
          if (marker == 0 || marker >= 0xd0 && marker <= 0xd7) continue;
          entropy = false;
        } else {
          if (in.readUnsignedByte() != 255) throw new IOException();
          do {
            marker = in.readUnsignedByte();
          } while (marker == 255);
        }
        if (marker == 0xd9) {
          if (!frame || !scan || in.read() != -1) throw new IOException();
          return;
        }
        if (marker == 0xd8 || marker == 0 || marker == 1 || marker >= 0xd0 && marker <= 0xd7)
          throw new IOException();
        int len = in.readUnsignedShort() - 2;
        if (len < 0) throw new IOException();
        if (marker >= 0xc0 && marker <= 0xcf && !Set.of(0xc4, 0xc8, 0xcc).contains(marker)) {
          if (len < 6) throw new IOException();
          in.readUnsignedByte();
          int h = in.readUnsignedShort(), w = in.readUnsignedShort();
          dimensiones(w, h, limite);
          in.skipNBytes(len - 5);
          frame = true;
        } else in.skipNBytes(len);
        if (marker == 0xda) {
          if (!frame) throw new IOException();
          entropy = true;
          scan = true;
        }
      }
    }
  }
}
