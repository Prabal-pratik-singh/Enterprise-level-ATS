package com.ats.parser;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

/**
 * PDF route: extract the text layer with PDFBox (collecting font/color stats
 * on the way); if there's almost no text, the PDF is a scan — hand the pages
 * to OCR instead. A corrupt PDF fails loadPDF() and the exception deliberately
 * escapes: the Kafka error policy retries 3x then parks it in the DLQ.
 */
@Component
public class PdfExtractor {

    // Spec threshold: a real text resume has thousands of chars per page;
    // under 100/page means the "text" is actually pixels in an image.
    private static final int MIN_CHARS_PER_PAGE = 100;

    private final OcrEngine ocrEngine;

    public PdfExtractor(OcrEngine ocrEngine) {
        this.ocrEngine = ocrEngine;
    }

    public ParsedDocument extract(byte[] pdfBytes) throws IOException {
        // try-with-resources: the document auto-closes even if we throw midway
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int pages = Math.max(document.getNumberOfPages(), 1); // guard the division below

            LayoutCapturingStripper stripper = new LayoutCapturingStripper();
            String text = stripper.getText(document); // extraction happens HERE; the spy watches every char
            LayoutStats layout = stripper.stats();

            double charsPerPage = (double) text.strip().length() / pages;
            if (charsPerPage < MIN_CHARS_PER_PAGE) {
                // Scanned/image-only: re-read the pages as pictures via Tesseract.
                // We still keep the layout stats (they show "no real text layer").
                OcrEngine.OcrResult result = ocrEngine.ocrPdf(document);
                return ParsedDocument.ocr(result.text(), pages, result.meanConfidence(), layout);
            }
            return ParsedDocument.pdfbox(text, pages, layout);
        }
    }
}
