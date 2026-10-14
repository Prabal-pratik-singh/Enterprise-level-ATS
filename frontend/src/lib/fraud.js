import { EyeOff, CalendarX, History, MailWarning, ShieldAlert } from 'lucide-react'

// Trust Service flag codes → short recruiter-facing label + icon.
// The full sentence (flag.explanation) always comes from the backend.
const FLAG_META = {
  HIDDEN_TEXT: { label: 'Hidden text', icon: EyeOff },
  OVERLAPPING_JOBS: { label: 'Overlapping jobs', icon: CalendarX },
  IMPOSSIBLE_EXPERIENCE: { label: 'Impossible experience', icon: CalendarX },
  TECH_ANACHRONISM: { label: 'Tech anachronism', icon: History },
  DISPOSABLE_EMAIL: { label: 'Throwaway email', icon: MailWarning },
}

export const FLAG_THRESHOLD = 0.5 // mirrors TrustScorer.FLAG_THRESHOLD

export function flagMeta(code) {
  return FLAG_META[code] || { label: (code || 'flag').toLowerCase().replaceAll('_', ' '), icon: ShieldAlert }
}

/** Flags as an array, whatever the API handed us (null, [], JsonNode array). */
export function flagsOf(row) {
  return Array.isArray(row?.flags) ? row.flags : []
}

/** One-decimal score text: 88.55 → "88.6", 35 → "35". */
export function pts(value) {
  if (value == null) return '—'
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}
