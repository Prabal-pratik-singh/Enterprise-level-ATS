package com.ats.util;

/**
 * Magic-byte file type detection. The first bytes of a file identify its real
 * format regardless of the filename — that's what we trust, never extensions.
 *
 * Demo simplification (documented in README): an allowlist instead of a real
 * virus scanner (ClamAV). Note DOCX is just a ZIP archive, so its signature is
 * the generic ZIP "PK.." — any zip would pass as "docx"; acceptable here.
 */
public final class FileTypes {

    /** Returns "pdf" | "png" | "jpg" | "docx", or null when unrecognized. */
    public static String detect(byte[] h) {
        if (h == null) {
            return null;
        }
        // PDF files start with the ASCII text "%PDF"
        if (h.length >= 5 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F') {
            return "pdf";
        }
        // PNG starts with byte 0x89 then "PNG". (h[0] & 0xFF converts Java's
        // signed byte to 0..255 so we can compare against 0x89.)
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return "png";
        }
        // JPEG always starts with FF D8 FF
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        // ZIP (and therefore DOCX) starts with "PK" + 0x03 0x04
        if (h.length >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 0x03 && h[3] == 0x04) {
            return "docx";
        }
        return null; // unknown type -> caller rejects the file
    }

    private FileTypes() {
        // utility class — never instantiated
    }
}
