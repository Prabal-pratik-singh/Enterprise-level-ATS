package com.ats.parser;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

/**
 * DOCX route: a .docx is a ZIP of XML files; Apache Tika's OOXML parser
 * unpacks it and emits plain text. Page count comes from Word's own metadata
 * (may be absent — that's fine, the column is nullable).
 */
@Component
public class DocxExtractor {

    public ParsedDocument extract(byte[] docxBytes) {
        // -1 = no output size limit (default caps at 100k chars; resumes are small anyway)
        BodyContentHandler textCollector = new BodyContentHandler(-1);
        Metadata metadata = new Metadata(); // Tika fills this with document properties while parsing

        try (InputStream in = new ByteArrayInputStream(docxBytes)) {
            new OOXMLParser().parse(in, textCollector, metadata, new ParseContext());
        } catch (Exception e) {
            // Corrupt/fake docx -> loud failure -> retries -> DLQ, same as PDFs
            throw new IllegalStateException("DOCX parsing failed", e);
        }
        return ParsedDocument.tika(textCollector.toString(), pageCount(metadata));
    }

    /** Word stores page count under different metadata keys depending on version. */
    private static Integer pageCount(Metadata metadata) {
        for (String key : new String[]{"meta:page-count", "extended-properties:Pages", "xmpTPg:NPages"}) {
            String value = metadata.get(key);
            if (value != null) {
                try {
                    return Integer.parseInt(value.trim());
                } catch (NumberFormatException ignored) {
                    // fall through to the next key
                }
            }
        }
        return null; // unknown — acceptable
    }
}
