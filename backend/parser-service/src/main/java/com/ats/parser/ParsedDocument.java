package com.ats.parser;

/**
 * The outcome of parsing one resume file, whatever route it took.
 * parseMethod: "pdfbox" (text PDF) | "ocr" (scanned PDF or image) | "tika" (DOCX).
 * ocrConfidence: 0-100 mean word confidence — only present for OCR.
 * layout: font/color fingerprint — only present for PDFs.
 */
public record ParsedDocument(
        String text,
        Integer pageCount,
        String parseMethod,
        Double ocrConfidence,
        LayoutStats layout) {

    /** A normal text PDF, read directly by PDFBox. */
    public static ParsedDocument pdfbox(String text, int pages, LayoutStats layout) {
        return new ParsedDocument(text, pages, "pdfbox", null, layout);
    }

    /** A scanned/image-only PDF, recovered via Tesseract OCR. */
    public static ParsedDocument ocr(String text, int pages, Double confidence, LayoutStats layout) {
        return new ParsedDocument(text, pages, "ocr", confidence, layout);
    }

    /** A DOCX, read by Apache Tika (page count comes from Word metadata, may be null). */
    public static ParsedDocument tika(String text, Integer pages) {
        return new ParsedDocument(text, pages, "tika", null, null);
    }

    /** A directly uploaded PNG/JPG image — OCR is the only way in. */
    public static ParsedDocument imageOcr(String text, Double confidence) {
        return new ParsedDocument(text, 1, "ocr", confidence, null);
    }
}
