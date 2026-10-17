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

/** Translate an extraction (LLM) failure into recruiter-readable English. */
export function explainExtractionFailure(raw) {
  const s = rootCause(raw).toLowerCase()
  if (s.includes('cannot reach') || s.includes('unresolvedaddress') || s.includes('(dns)') || s.includes('connectexception'))
    return 'The AI service could not be reached (internet or DNS problem) — even after waiting about a minute.'
  if (s.includes('json_validate_failed') || s.includes('invalid profile') || s.includes('not a json'))
    return 'The AI model could not turn this resume into a valid profile, even with the stronger backup model.'
  if (s.includes('timeout') || s.includes('timed out')) return 'The AI model took too long to answer.'
  if (s.includes('http 401') || s.includes('api_key') || s.includes('not set')) return 'The AI service rejected our API key — check GROQ_API_KEY.'
  if (s.includes('http 429')) return 'The AI service\'s free-tier rate limit was hit too many times in a row.'
  if (s.includes('no text')) return 'The parser found no text in this file, so there was nothing to read.'
  return 'Profile extraction failed 4 times, then the resume was parked in the dead-letter queue.'
}
