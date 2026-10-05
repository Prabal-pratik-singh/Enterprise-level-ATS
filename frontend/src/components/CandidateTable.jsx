import Avatar from './Avatar'
import ScoreBars from './ScoreBars'
import StatusChip from './StatusChip'

/**
 * The ranked candidates table, shared by the per-job page and the
 * all-candidates view (which adds the Job column via showJob).
 */
export default function CandidateTable({ rows, freshIds = new Set(), onSelect, showJob = false, emptyMessage }) {
  return (
    <div className="card overflow-hidden !rounded-2xl">
      <table className="w-full text-sm">
        <thead className="text-left text-[11px] uppercase tracking-wider text-dim">
          <tr className="border-b border-white/10 bg-white/5">
            <th className="w-10 px-4 py-3 font-medium">#</th>
            <th className="px-4 py-3 font-medium">Candidate</th>
            {showJob && <th className="w-44 px-4 py-3 font-medium">Job</th>}
            <th className="w-20 px-4 py-3 font-medium">Score</th>
            <th className="w-44 px-4 py-3 font-medium">Breakdown</th>
            <th className="w-28 px-4 py-3 font-medium">Experience</th>
            <th className="w-32 px-4 py-3 font-medium">Status</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={row.applicationId}
                onClick={() => onSelect(row.applicationId)}
                className={`cursor-pointer border-t border-white/5 transition hover:bg-white/5
                            ${freshIds.has(row.applicationId) ? 'bg-marigold/10' : ''}`}>
              <td className="px-4 py-3 tabular-nums text-dim">{index + 1}</td>
              <td className="px-4 py-3">
                <div className="flex items-center gap-3">
                  <Avatar name={row.name} size={32} />
                  <div className="leading-tight">
                    <div className="font-medium">{row.name}</div>
                    <div className="text-xs text-dim">{row.email}</div>
                  </div>
                </div>
              </td>
              {showJob && (
                <td className="px-4 py-3">
                  <span className="line-clamp-2 text-xs text-dim">{row.jobTitle}</span>
                </td>
              )}
              <td className="px-4 py-3">
                <span className={`text-lg font-semibold tabular-nums ${row.knockedOut ? 'text-rose-400' : 'text-marigold'}`}>
                  {row.finalScore != null ? Math.round(row.finalScore) : '—'}
                </span>
              </td>
              <td className="px-4 py-3"><ScoreBars components={row.components} compact /></td>
              <td className="px-4 py-3 text-ink/80">
                {row.totalExperienceMonths != null ? `${row.totalExperienceMonths} mo` : '—'}
                {row.seniority && <span className="block text-xs text-dim">{row.seniority}</span>}
              </td>
              <td className="px-4 py-3"><StatusChip status={row.status} /></td>
            </tr>
          ))}
          {rows.length === 0 && (
            <tr>
              <td colSpan={showJob ? 7 : 6} className="px-4 py-10 text-center text-dim">
                {emptyMessage || 'No candidates match.'}
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}
