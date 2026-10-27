// What a candidate sees of an application's status. The API already sends candidate-facing values
// (RECEIVED, IN_REVIEW, NOT_SELECTED, RESUME_UNREADABLE); the pipeline names below stay as a fallback.
// A fraud flag must look exactly like any other review (no tip-off), and a rejection is a neutral
// "not selected" — the reasons are shared only when a job opts in (Part 2, C5).
const LABELS = {
  RECEIVED: ['Received', 'neutral'],
  IN_REVIEW: ['In review', 'active'],
  NOT_SELECTED: ['Not selected', 'closed'],
  RESUME_UNREADABLE: ["We couldn't read your resume", 'warn'],
  APPLIED: ['Received', 'neutral'],
  PARSED: ['Received', 'neutral'],
  EXTRACTED: ['Received', 'neutral'],
  SCORED: ['In review', 'active'],
  FLAGGED_FOR_REVIEW: ['In review', 'active'],
  SHORTLISTED: ['In review', 'active'],
  REJECTED: ['Not selected', 'closed'],
  PARSE_FAILED: ["We couldn't read your resume", 'warn'],
}

const TONES = {
  neutral: 'bg-white/10 text-dim ring-white/15',
  active: 'bg-cyan/15 text-cyan ring-cyan/30',
  closed: 'bg-white/5 text-dim/80 ring-white/10',
  warn: 'bg-amber-400/15 text-amber-300 ring-amber-400/30',
}

export default function CandidateStatus({ status }) {
  const [label, tone] = LABELS[status] || ['In review', 'active']
  return (
    <span className={`whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ${TONES[tone]}`}>
      {label}
    </span>
  )
}
