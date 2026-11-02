package com.multiship.backend.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract guard for {@link LabelFormatConverter#pdfFirstPageToPng}: a 4×6
 * carrier PDF rasterizes to a ~4"-wide PNG whose translucent/background areas
 * come out LIGHT (white-flattened), never a solid-black band.
 *
 * <p>Context: the client's Stamps label showed a solid-black band over the
 * right ~40% in the preview/^GFA while a PDF viewer (and raw /label/pdf)
 * showed it fine — PDFBox compositing a transparency construct over a black
 * backdrop. The fix renders ARGB and flattens onto white.
 *
 * <p>NOTE: this synthetic PDF uses a simple alpha-constant fill, which PDFBox
 * 3.0.8 already renders over white even on the old RGB path — so this asserts
 * the no-black-band + width CONTRACT, it is not a byte-level repro of the
 * Stamps-specific trigger (a transparency group / soft mask / image XObject
 * that couldn't be reproduced without the live artifact). The definitive
 * check is the reviewer's live re-test on order 900060.
 */
class LabelFormatConverterPdfRasterTest {

    private final LabelFormatConverter converter =
            new LabelFormatConverter(null, null, null, null);

    @Test
    void transparencyGroupRightHalf_rastersWhiteNotBlack() throws Exception {
        byte[] pdf = fourBySixWithTranslucentRightHalf();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(converter.pdfFirstPageToPng(pdf)));

        // 4" wide @ 203 DPI ≈ 812 dots.
        assertTrue(img.getWidth() >= 800 && img.getWidth() <= 824,
                "width should match the 4\" page, was " + img.getWidth());

        // Far-right column must not be the solid-black band from the bug.
        int x = img.getWidth() - 2;
        long sum = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            int rgb = img.getRGB(x, y);
            int luma = ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3;
            sum += luma;
        }
        int avg = (int) (sum / img.getHeight());
        assertTrue(avg > 120, "far-right column should be light (white-flattened), avg luma was " + avg);
    }

    /** 288×432 pt (4×6") page; a translucent fill over the right half via an
     *  extended-graphics-state alpha — the transparency path that used to
     *  composite to black under PDFBox's RGB renderer. */
    private static byte[] fourBySixWithTranslucentRightHalf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(288f, 432f));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // Left: opaque black marker (real content).
                cs.setNonStrokingColor(Color.BLACK);
                cs.addRect(10, 380, 120, 40);
                cs.fill();
                // Right half: translucent black through a transparency group.
                PDExtendedGraphicsState egs = new PDExtendedGraphicsState();
                egs.setNonStrokingAlphaConstant(0.25f);
                cs.saveGraphicsState();
                cs.setGraphicsStateParameters(egs);
                cs.setNonStrokingColor(Color.BLACK);
                cs.addRect(144, 0, 144, 432);
                cs.fill();
                cs.restoreGraphicsState();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
