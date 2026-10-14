package com.ats.dedup;

/**
 * LSH banding: the trick that avoids comparing every new resume with EVERY old one.
 * Cut the 128-slot signature into 32 bands of 4 slots; hash each band to a bucket.
 * Two resumes become "candidates" if ANY band lands in the same bucket.
 *
 * Chance two resumes with similarity s share at least one bucket = 1 − (1 − s⁴)³²:
 *   s = 0.70 → 99.98%  (a near-duplicate is practically never missed)
 *   s = 0.12 → 0.66%   (the most-similar HONEST pair in our data rarely even gets compared)
 * Every candidate is then confirmed against the full 128-slot estimate.
 */
public final class Lsh {

    public static final int BANDS = 32;
    public static final int ROWS = 4;   // BANDS × ROWS must equal MinHash.NUM_PERM

    /**
     * One bucket key per band (keys[band]). The band number is stored NEXT to the key
     * in the database, so equal keys in different bands never count as a match.
     */
    public static long[] bucketKeys(long[] signature) {
        long[] keys = new long[BANDS];
        for (int band = 0; band < BANDS; band++) {
            long h = 1_125_899_906_842_597L;              // 64-bit polynomial hash of the band's 4 slots
            for (int row = band * ROWS; row < band * ROWS + ROWS; row++) {
                h = 31 * h + signature[row];
            }
            keys[band] = h;
        }
        return keys;
    }

    private Lsh() {
    }
}
