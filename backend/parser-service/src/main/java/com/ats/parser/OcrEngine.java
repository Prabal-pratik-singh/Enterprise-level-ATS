package com.ats.parser;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.Word;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OCR fallback for resumes that carry pixels instead of text (scanned PDFs,
 * photos). Uses the Tesseract engine via tess4j — the native library and the
 * English language pack are installed in the parser-service Docker image.
 */
@Component
public class OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(OcrEngine.class);

    // 200 DPI (spec value): sharp enough for OCR, small enough to stay fast.
    private static final int RENDER_DPI = 200;

    // One shared engine instance. Tesseract is NOT thread-safe, so every use
    // goes through the synchronized ocrOne(...) below. Our listener runs one
    // message at a time per container, so there is no real contention —
    // we scale OCR by scaling parser CONTAINERS, not threads.
    private final Tesseract tesseract = new Tesseract();

    /** Text plus mean word confidence (0-100) for one OCR run. */
    public record OcrResult(String text, Double meanConfidence) {
    }

    public OcrEngine() {
        tesseract.setDatapath(resolveTessdata()); // where the language files live
        tesseract.setLanguage("eng");             // matches tesseract-ocr-eng in the image
    }

    /** OCR a whole PDF: render each page to an image, then read the pixels. */
    public OcrResult ocrPdf(PDDocument document) throws IOException {
        PDFRenderer renderer = new PDFRenderer(document);
        StringBuilder text = new StringBuilder();
        List<Float> confidences = new ArrayList<>();
        for (int page = 0; page < document.getNumberOfPages(); page++) {
            BufferedImage pageImage = renderer.renderImageWithDPI(page, RENDER_DPI);
            text.append(ocrOne(pageImage, confidences)).append('\n');
        }
        return new OcrResult(text.toString(), mean(confidences));
    }

    /** OCR a directly uploaded PNG/JPG. */
    public OcrResult ocrImage(byte[] imageBytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) { // valid magic bytes but undecodable pixels
            throw new IllegalStateException("image could not be decoded for OCR");
        }
        List<Float> confidences = new ArrayList<>();
        String text = ocrOne(image, confidences);
        return new OcrResult(text, mean(confidences));
    }

    /**
     * Two passes over the same image: getWords(...) gives per-word confidence,
     * doOCR(...) gives properly laid-out text (line breaks preserved). Costs a
     * second run, but only image-only resumes ever reach OCR, so it's cheap
     * overall and both numbers matter downstream.
     * synchronized = one image at a time through the non-thread-safe engine.
     */
    private synchronized String ocrOne(BufferedImage image, List<Float> confidences) {
        for (Word word : tesseract.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD)) {
            confidences.add(word.getConfidence());
        }
        try {
            return tesseract.doOCR(image);
        } catch (TesseractException e) {
            throw new IllegalStateException("OCR failed", e); // -> retries -> DLQ
        }
    }

    private static Double mean(List<Float> values) {
        if (values.isEmpty()) {
            return null; // blank page: no words, no confidence to report
        }
        double sum = 0;
        for (float v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    /** Find the tesseract language-data folder across common install locations. */
    private static String resolveTessdata() {
        String env = System.getenv("TESSDATA_PREFIX"); // optional override; normally we auto-probe below
        if (env != null && !env.isBlank()) {
            return env;
        }
        for (String candidate : new String[]{
                "/usr/share/tesseract-ocr/5/tessdata",    // Ubuntu 24.04 (our runtime image)
                "/usr/share/tesseract-ocr/4.00/tessdata", // older Ubuntu
                "/usr/share/tessdata"}) {                 // generic fallback
            if (Files.isDirectory(Path.of(candidate))) {
                return candidate;
            }
        }
        log.warn("no tessdata directory found — OCR will fail if it is ever needed");
        return "";
    }
}
