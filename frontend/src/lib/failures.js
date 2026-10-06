// Parse-failure helpers shared by the Live Flow rail and the candidate drawer.
// The parser's DLQ bookkeeper stores the raw exception text in the audit trail;
// these two functions turn that into something a recruiter can actually read.

/** Strip Spring's listener wrapper — the real cause sits after "threw exception; ". */
export function rootCause(raw) {
  if (!raw) return 'unknown parser failure'
  const marker = 'threw exception; '
  const i = raw.lastIndexOf(marker)
  return i >= 0 ? raw.slice(i + marker.length) : raw
}

/** Translate parser exception text into recruiter-readable English. */
export function explainFailure(raw) {
  const s = rootCause(raw).toLowerCase()
  if (s.includes('unsupported file type')) return 'Unsupported file type — only PDF, DOCX, PNG and JPG can be parsed.'
  if (s.includes('password') || s.includes('encrypt')) return 'Password-protected PDF — the parser cannot open it.'
  if (s.includes('trailer') || s.includes('root object') || s.includes('header') || s.includes('end-of-file') || s.includes('eof'))
    return 'Corrupt or truncated PDF — the file structure is unreadable, so no text could be extracted.'
  if (s.includes('ocr')) return 'Image OCR failed — the picture was too noisy to read.'
  return 'The parser crashed on this file 3 times, then gave up (dead-letter queue).'
}
