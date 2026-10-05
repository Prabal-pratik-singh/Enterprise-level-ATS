const STYLES = {
  SCORED: 'bg-ink-100 text-ink',
  EXTRACTED: 'bg-blue-100 text-blue-800',
  PARSED: 'bg-blue-100 text-blue-800',
  APPLIED: 'bg-gray-100 text-gray-600',
  SHORTLISTED: 'bg-green-100 text-green-700',
  REJECTED: 'bg-red-100 text-red-700',
  FLAGGED_FOR_REVIEW: 'bg-amber-100 text-amber-800',
  PARSE_FAILED: 'bg-red-100 text-red-700',
}

export default function StatusChip({ status }) {
  return (
    <span className={`rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ${STYLES[status] || 'bg-gray-100 text-gray-600'}`}>
      {(status || '').toLowerCase().replaceAll('_', ' ')}
    </span>
  )
}
