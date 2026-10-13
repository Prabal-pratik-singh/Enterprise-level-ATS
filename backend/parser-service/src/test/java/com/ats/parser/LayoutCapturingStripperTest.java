package com.ats.parser;

import java.io.InputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parser's half of the hidden-text fraud check: the Trust Service never
 * opens the PDF — it only sees these counters, so they must be right.
 */
class LayoutCapturingStripperTest {

    // 18 letters — the stripper deliberately doesn't count spaces
    private static final String STUFFING = "kubernetes kafka aws";

    /** Sets the ink color right before the line under test is drawn. */
    private interface Ink {
        void apply(PDPageContentStream content) throws Exception;
    }

    @Test
    void realGeneratorAttackIsCaughtByBothSignals() throws Exception {
        // The exact file tools/generate_resumes.py plants: white ink AND 1pt font —
        // both signals must fire, not just one.
        byte[] file;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/fraud-hidden-white-1pt.pdf")) {
            file = in.readAllBytes();
        }
        LayoutStats stats = scan(Loader.loadPDF(file));
        assertThat(stats.tinyFontChars()).isEqualTo(378);
        assertThat(stats.nearWhiteChars()).isEqualTo(378);
        assertThat(stats.hiddenTextSample()).startsWith("kuberneteskafkaaws");
    }

    @Test
    void whiteInkAtNormalSizeIsCaught() throws Exception {
        // Readable 11pt text painted white ("1 1 1 rg") — the size check can't see
        // this; only the color check can.
        LayoutStats stats = scan(pdfWithLine(content -> content.setNonStrokingColor(1f, 1f, 1f), 11));
        assertThat(stats.nearWhiteChars()).isEqualTo(18);
        assertThat(stats.tinyFontChars()).isZero();
        assertThat(stats.hiddenTextSample()).isEqualTo("kuberneteskafkaaws");
    }

    @Test
    void lightGrayAndCmykWhiteAlsoCount() throws Exception {
        // Different PDF instructions ("g" gray, "k" CMYK) — same invisible result
        assertThat(scan(pdfWithLine(content -> content.setNonStrokingColor(0.97f), 11)).nearWhiteChars())
                .isEqualTo(18);
        assertThat(scan(pdfWithLine(content -> content.setNonStrokingColor(0f, 0f, 0f, 0f), 11)).nearWhiteChars())
                .isEqualTo(18);
    }

    @Test
    void tinyBlackTextIsStillCaughtBySize() throws Exception {
        // The size check must keep working exactly as before
        LayoutStats stats = scan(pdfWithLine(content -> content.setNonStrokingColor(0f, 0f, 0f), 1));
        assertThat(stats.tinyFontChars()).isEqualTo(18);
        assertThat(stats.nearWhiteChars()).isZero();
    }

    @Test
    void honestBlackTextRaisesNoSignal() throws Exception {
        // No false alarms: a normal resume must come out completely clean
        LayoutStats stats = scan(pdfWithLine(content -> content.setNonStrokingColor(0f, 0f, 0f), 11));
        assertThat(stats.tinyFontChars()).isZero();
        assertThat(stats.nearWhiteChars()).isZero();
        assertThat(stats.hiddenTextSample()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    /** Runs the stripper over a PDF, then closes it — counting happens during extraction. */
    private static LayoutStats scan(PDDocument pdf) throws Exception {
        try (pdf) {
            LayoutCapturingStripper stripper = new LayoutCapturingStripper();
            stripper.getText(pdf);
            return stripper.stats();
        }
    }

    /** One page: a normal black line, then the STUFFING line in the given ink and size. */
    private static PDDocument pdfWithLine(Ink ink, float fontSize) throws Exception {
        PDDocument pdf = new PDDocument();
        PDPage page = new PDPage();
        pdf.addPage(page);
        PDType1Font helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
            content.beginText();
            content.setFont(helvetica, 11);
            content.newLineAtOffset(50, 700);
            content.showText("Senior Java developer"); // the visible part of the "resume"
            content.endText();

            ink.apply(content); // switch the ink color — the thing under test
            content.beginText();
            content.setFont(helvetica, fontSize);
            content.newLineAtOffset(50, 650);
            content.showText(STUFFING);
            content.endText();
        }
        return pdf;
    }
}
