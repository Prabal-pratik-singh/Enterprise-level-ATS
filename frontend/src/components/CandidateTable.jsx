import { useEffect, useState } from 'react'
import Avatar from './Avatar'
import ScoreBars from './ScoreBars'
import StatusChip from './StatusChip'
import { ShieldAlert } from 'lucide-react'
import { FLAG_THRESHOLD, flagMeta, flagsOf, pts } from '../lib/fraud'

/**
 * The ranked candidates table, shared by the per-job page and the
 * all-candidates view (which adds the Job column via showJob).
 */
const STEP = 100 // rows drawn at a time — 10K table rows at once would freeze the browser

export default function CandidateTable({ rows, freshIds = new Set(), onSelect, showJob = false, emptyMessage }) {
  // every row is loaded (search and filters see everyone); only the DRAWING is chunked
  const [limit, setLimit] = useState(STEP)
  useEffect(() => setLimit(STEP), [emptyMessage]) // a new filter/search starts from the top again
  const shown = rows.slice(0, limit)
  const hidden = rows.length - shown.length

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
          {shown.map((row, index) => (
            <tr key={row.applicationId}
                onClick={() => onSelect(row.applicationId)}
                className={`cursor-pointer border-t border-white/5 transition hover:bg-white/5
                            ${freshIds.has(row.applicationId) ? 'bg-marigold/10' : ''}`}>
              <td className="px-4 py-3 tabular-nums text-dim">{index + 1}</td>
              <td className="px-4 py-3">
                <div className="flex items-center gap-3">
                  <Avatar name={row.name} size={32} />
                  <div className="leading-tight">
                    <div className="flex items-center gap-1.5 font-medium">
                      {row.name}
                      {flagsOf(row).length > 0 && (
                        // an SVG ignores title=, so the hover hint lives on a wrapper
                        <span title={flagsOf(row).map((f) => flagMeta(f.code).label).join(' · ')}>
                          <ShieldAlert size={13}
                                       className={(row.fraudScore ?? 0) >= FLAG_THRESHOLD ? 'text-amber-300' : 'text-dim/60'} />
                        </span>
                      )}
                    </div>
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
                {row.fraudPenalty > 0 && (
                  <span className="block text-[10px] tabular-nums text-amber-300/90"
                        title={`match ${pts(row.matchScore)} − fraud penalty ${pts(row.fraudPenalty)}`}>
                    −{pts(row.fraudPenalty)} fraud
                  </span>
                )}
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
      {hidden > 0 && (
        <div className="flex items-center justify-center gap-3 border-t border-white/5 px-4 py-3 text-sm">
          <span className="text-dim">Showing {shown.length} of {rows.length}</span>
          <button onClick={() => setLimit((n) => n + STEP)}
                  className="rounded-lg bg-white/5 px-3 py-1 text-ink ring-1 ring-white/10 hover:bg-white/10">
            Show {Math.min(STEP, hidden)} more
          </button>
          <button onClick={() => setLimit(rows.length)} className="text-xs text-cyan hover:underline">
            show all {rows.length}
          </button>
        </div>
      )}
    </div>
  )
}
