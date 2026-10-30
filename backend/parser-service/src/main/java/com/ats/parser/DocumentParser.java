package com.ats.parser;

import com.ats.util.FileTypes;
import org.springframework.stereotype.Component;

/**
 * File bytes → text + layout metadata, routed by the file's REAL type (its first bytes), never by its
 * name. Shared by both front doors: an application's resume (resume.uploaded) and a candidate's own
 * profile resume (profile.resume.uploaded).
 */
@Component
public class DocumentParser {

    private final PdfExtractor pdfExtractor;
    private final DocxExtractor docxExtractor;
    private final OcrEngine ocrEngine;

    public DocumentParser(PdfExtractor pdfExtractor, DocxExtractor docxExtractor, OcrEngine ocrEngine) {
        this.pdfExtractor = pdfExtractor;
        this.docxExtractor = docxExtractor;
        this.ocrEngine = ocrEngine;
    }

    public ParsedDocument parse(byte[] fileBytes) throws Exception {
        String type = FileTypes.detect(fileBytes);
        return switch (type == null ? "unknown" : type) {
            case "pdf" -> pdfExtractor.extract(fileBytes);
            case "docx" -> docxExtractor.extract(fileBytes);
            case "png", "jpg" -> {
                OcrEngine.OcrResult result = ocrEngine.ocrImage(fileBytes);
                yield ParsedDocument.imageOcr(result.text(), result.meanConfidence());
            }
            default -> throw new IllegalStateException("unsupported file type: " + type);
        };
    }
}
