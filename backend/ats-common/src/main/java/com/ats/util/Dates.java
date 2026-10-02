package com.ats.util;

import java.time.YearMonth;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lenient resume-date parsing, shared by the extractor (experience math) and
 * the matcher (skill recency decay). Tolerates "2021-03", "2021-3", "2021/03"
 * and bare "2021" (assumes January). Garbage never throws — it just doesn't count.
 */
public final class Dates {

    private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})(?:[-/](\\d{1,2}))?");

    public static Optional<YearMonth> parseYearMonth(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Matcher m = YEAR_MONTH.matcher(raw.trim());
        if (!m.find()) {
            return Optional.empty();
        }
        int year = Integer.parseInt(m.group(1));
        int month = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1; // bare year -> January
        if (year < 1950 || year > 2100 || month < 1 || month > 12) {
            return Optional.empty();
        }
        return Optional.of(YearMonth.of(year, month));
    }

    private Dates() {
    }
}
