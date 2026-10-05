// Six horizontal bars, one per scoring component (values 0..1).
// compact = the thin version used inside table rows.
const LABELS = {
  skill_match: 'skills',
  experience_fit: 'experience',
  project_relevance: 'projects',
  education: 'education',
  certifications: 'certs',
  resume_quality: 'quality',
}

export default function ScoreBars({ components, compact = false }) {
  if (!components) return null
  return (
    <div className={compact ? 'space-y-0.5 w-40' : 'space-y-2'}>
      {Object.entries(LABELS).map(([key, label]) => {
        const value = components[key] ?? 0
        return (
          <div key={key} className="flex items-center gap-2">
            {!compact && <span className="w-24 text-xs text-dim">{label}</span>}
            <div className={`${compact ? 'h-1' : 'h-1.5'} flex-1 rounded-full bg-white/10 overflow-hidden`}
                 title={`${label}: ${(value * 100).toFixed(0)}%`}>
              <div className="h-full rounded-full bg-gradient-to-r from-cyan to-violet"
                   style={{ width: `${value * 100}%` }} />
            </div>
            {!compact && <span className="w-8 text-right text-xs tabular-nums text-ink/90">{(value * 100).toFixed(0)}</span>}
          </div>
        )
      })}
    </div>
  )
}
