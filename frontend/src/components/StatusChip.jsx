const STYLES = {
  SCORED: 'bg-marigold/15 text-marigold ring-marigold/30',
  EXTRACTED: 'bg-violet/15 text-violet ring-violet/30',
  PARSED: 'bg-cyan/15 text-cyan ring-cyan/30',
  APPLIED: 'bg-white/10 text-dim ring-white/15',
  SHORTLISTED: 'bg-emerald-400/15 text-emerald-300 ring-emerald-400/30',
  REJECTED: 'bg-rose-400/15 text-rose-300 ring-rose-400/30',
  FLAGGED_FOR_REVIEW: 'bg-amber-400/15 text-amber-300 ring-amber-400/30',
  PARSE_FAILED: 'bg-rose-400/15 text-rose-300 ring-rose-400/30',
}

export default function StatusChip({ status }) {
  return (
    <span className={`rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ring-1 ${STYLES[status] || 'bg-white/10 text-dim ring-white/15'}`}>
      {(status || '').toLowerCase().replaceAll('_', ' ')}
    </span>
  )
}
