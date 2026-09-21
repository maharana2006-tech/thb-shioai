package com.multiship.backend.service.printing;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Turns a PDF into a PCL 5 print job, one 300-dpi black-and-white raster per page.
 *
 * <p>Most office laser printers — Samsung, HP, Brother, Kyocera — speak PCL and
 * not PDF: a Samsung K2200 lists PCL, PCL XL, QPDL and plain text, nothing else.
 * Sent a PDF it prints the file's bytes as text; sent ZPL it prints the label's
 * commands as pages of hex. A rendered raster is the one thing every PCL printer
 * draws the same way, whatever fonts or PDF features the invoice uses.
 *
 * <p>Rows are PackBits-compressed (PCL compression method 2), which keeps a
 * mostly-white invoice page to tens of kilobytes instead of a megabyte.
 */
public final class PclRasterizer {

    private PclRasterizer() { }

    static final int DPI = 300;
    private static final byte ESC = 0x1B;

    /** PCL logical page size codes (Technical Reference, "Page Size"). */
    private static int pclPageSize(String paper) {
        return "A4".equals(paper) ? 26 : 2; // 2 = Letter
    }

    /** The whole PDF as a PCL job, sized for {@code paper} (LETTER or A4). */
    public static byte[] fromPdf(byte[] pdf, String paper) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        command(out, "E");                                    // printer reset
        command(out, "&l" + pclPageSize(paper) + "A");       // page size
        command(out, "&l0O");                                 // portrait
        command(out, "&l0E");                                 // no top margin: we place the raster ourselves
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                BufferedImage page = renderer.renderImageWithDPI(i, DPI, ImageType.BINARY);
                writePage(out, page);
                out.write(0x0C);                              // form feed: eject this sheet
            }
        }
        command(out, "E");                                    // reset again so the next job starts clean
        return out.toByteArray();
    }

    private static void writePage(ByteArrayOutputStream out, BufferedImage page) throws IOException {
        int width = page.getWidth();
        int height = page.getHeight();
        int rowBytes = (width + 7) / 8;
        byte[] pixels = ((DataBufferByte) page.getRaster().getDataBuffer()).getData();
        // A BINARY image's palette is 0 = black, 1 = white; PCL wants 1 = ink.
        // Bits past the right edge of the last byte are padding and must stay
        // white, or every row would end in a black sliver.
        int spare = rowBytes * 8 - width;
        byte edgeMask = (byte) (spare == 0 ? 0xFF : (0xFF << spare) & 0xFF);

        command(out, "*p0x0Y");                               // cursor to the top-left
        command(out, "*t" + DPI + "R");                        // raster resolution
        command(out, "*r" + width + "S");                     // raster width in pixels
        command(out, "*b2M");                                  // compression: PackBits
        command(out, "*r1A");                                  // start graphics at the cursor

        byte[] row = new byte[rowBytes];
        for (int y = 0; y < height; y++) {
            int offset = y * rowBytes;
            boolean blank = true;
            for (int x = 0; x < rowBytes; x++) {
                byte b = (byte) ~pixels[offset + x];
                if (x == rowBytes - 1) b &= edgeMask;
                row[x] = b;
                if (b != 0) blank = false;
            }
            if (blank) {
                command(out, "*b0W");                          // an empty row costs 5 bytes
                continue;
            }
            byte[] packed = packBits(row);
            command(out, "*b" + packed.length + "W");
            out.write(packed);
        }
        command(out, "*rB");                                   // end graphics
    }

    /** PackBits (TIFF / PCL method 2): literal runs and repeat runs of up to 128 bytes. */
    static byte[] packBits(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length / 4 + 8);
        int i = 0;
        while (i < in.length) {
            // A repeat run: the same byte at least twice.
            int run = 1;
            while (i + run < in.length && run < 128 && in[i + run] == in[i]) run++;
            if (run >= 2) {
                out.write(1 - run);                            // -1..-127 means "repeat next byte 2..128 times"
                out.write(in[i]);
                i += run;
                continue;
            }
            // A literal run: bytes until the next pair of equal ones.
            int start = i;
            int len = 0;
            while (i < in.length && len < 128) {
                if (i + 1 < in.length && in[i] == in[i + 1]) break;
                i++;
                len++;
            }
            out.write(len - 1);                               // 0..127 means "copy next 1..128 bytes"
            out.write(in, start, len);
        }
        return out.toByteArray();
    }

    private static void command(ByteArrayOutputStream out, String body) {
        out.write(ESC);
        byte[] b = body.getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
    }
}
