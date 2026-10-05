import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Briefcase, Users, Gauge, UserCheck, Trophy, Sparkles, ArrowRight,
} from 'lucide-react'
import { api } from '../api'
import Avatar from '../components/Avatar'
import StatusChip from '../components/StatusChip'

/* ============ tiny hand-rolled sparkline (no chart library needed) ============ */
function Sparkline({ values, stroke = 'url(#spark)' }) {
  if (!values || values.length < 2) return <div className="h-8" />
  const w = 120
  const h = 32
  const max = Math.max(...values, 1)
  const min = Math.min(...values, 0)
  const span = Math.max(max - min, 1)
  const points = values.map((v, i) =>
    `${((i / (values.length - 1)) * w).toFixed(1)},${(h - 3 - ((v - min) / span) * (h - 6)).toFixed(1)}`)
  return (
    <svg width={w} height={h} className="overflow-visible">
      <defs>
        <linearGradient id="spark" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0%" stopColor="#4cc9f0" />
          <stop offset="100%" stopColor="#8b5cf6" />
        </linearGradient>
      </defs>
      <polyline points={points.join(' ')} fill="none" stroke={stroke} strokeWidth="2"
                strokeLinecap="round" strokeLinejoin="round" opacity="0.9" />
    </svg>
  )
}

/* pipeline stages = OUR real state machine, each with its own glow accent */
const STAGES = [
  { key: 'APPLIED', label: 'Applied', accent: 'text-dim', glow: 'shadow-white/5', bar: 'bg-white/30' },
  { key: 'PARSED', label: 'Parsed', accent: 'text-cyan', glow: 'shadow-cyan/20', bar: 'bg-cyan' },
  { key: 'EXTRACTED', label: 'Extracted', accent: 'text-violet', glow: 'shadow-violet/20', bar: 'bg-violet' },
  { key: 'SCORED', label: 'Scored', accent: 'text-marigold', glow: 'shadow-marigold/20', bar: 'bg-marigold' },
  { key: 'FLAGGED_FOR_REVIEW', label: 'Review', accent: 'text-amber-400', glow: 'shadow-amber-400/20', bar: 'bg-amber-400' },
  { key: 'SHORTLISTED', label: 'Shortlisted', accent: 'text-emerald-400', glow: 'shadow-emerald-400/20', bar: 'bg-emerald-400' },
  { key: 'REJECTED', label: 'Rejected', accent: 'text-rose-400', glow: 'shadow-rose-400/20', bar: 'bg-rose-400' },
]

const COMPONENT_LABELS = {
  skill_match: 'Skill match',
  experience_fit: 'Experience fit',
  project_relevance: 'Project relevance',
  education: 'Education',
  certifications: 'Certifications',
  resume_quality: 'Resume quality',
}

export default function Dashboard() {
  const [jobs, setJobs] = useState([])
  const [rows, setRows] = useState([]) // every candidate across every job (+ jobId/jobTitle)
  const [error, setError] = useState(null)

  useEffect(() => {
    api.jobs()
      .then(async (jobList) => {
        setJobs(jobList)
        const pages = await Promise.all(
          jobList.map((job) => api.candidates(job.id, 0, 200)
            .then((d) => d.items.map((row) => ({ ...row, jobId: job.id, jobTitle: job.title })))),
        )
        setRows(pages.flat())
      })
      .catch((e) => setError(e.message))
  }, [])

  const stats = useMemo(() => {
    const scored = rows.filter((r) => r.finalScore != null)
    const byStatus = {}
    for (const r of rows) byStatus[r.status] = (byStatus[r.status] || 0) + 1
    const avg = scored.length ? scored.reduce((s, r) => s + r.finalScore, 0) / scored.length : 0
    const componentAvg = {}
    for (const key of Object.keys(COMPONENT_LABELS)) {
      const vals = scored.map((r) => r.components?.[key]).filter((v) => v != null)
      componentAvg[key] = vals.length ? vals.reduce((s, v) => s + v, 0) / vals.length : 0
    }
    const sortedScores = scored.map((r) => r.finalScore).sort((a, b) => a - b)
    return {
      byStatus,
      scored,
      avg,
      componentAvg,
      sortedScores,
      strong: scored.filter((r) => r.finalScore >= 80),
      knockouts: rows.filter((r) => r.knockedOut),
      top: [...scored].sort((a, b) => b.finalScore - a.finalScore)[0],
      undecided: scored.filter((r) => r.status === 'SCORED'),
    }
  }, [rows])

  const hour = new Date().getHours()
  const greeting = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening'

  if (error) return <p className="text-rose-400">{error}</p>

  const kpis = [
    { label: 'Active jobs', value: jobs.length, icon: Briefcase, spark: jobs.map((j) => j.applicants) },
    { label: 'Total candidates', value: rows.length, icon: Users, spark: stats.sortedScores },
    { label: 'Scored', value: stats.scored.length, icon: Gauge, spark: stats.sortedScores },
    { label: 'Strong matches (80+)', value: stats.strong.length, icon: Trophy, spark: stats.sortedScores.filter((s) => s >= 60) },
    { label: 'Awaiting decision', value: stats.undecided.length, icon: UserCheck, spark: stats.sortedScores.slice(-8) },
  ]

  return (
    <div className="space-y-6">
      {/* hero */}
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl font-semibold tracking-tight">{greeting}, Recruiter 👋</h1>
          <p className="mt-1 text-sm text-dim">
            Here's what's happening in your hiring pipeline — every number below is live data.
          </p>
        </div>
        <Link to={jobs[0] ? `/jobs/${jobs[0].id}` : '/jobs'}
              className="group flex items-center gap-2 rounded-xl bg-gradient-to-r from-violet to-cyan px-4 py-2.5 text-sm font-medium text-white shadow-lg shadow-violet/30 transition hover:shadow-violet/50">
          Open ranked pipeline
          <ArrowRight size={15} className="transition group-hover:translate-x-0.5" />
        </Link>
      </div>

      {/* KPI cards */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        {kpis.map(({ label, value, icon: Icon, spark }) => (
          <div key={label} className="card p-4">
            <div className="flex items-center justify-between">
              <span className="text-xs text-dim">{label}</span>
              <span className="rounded-lg bg-white/5 p-1.5 ring-1 ring-white/10">
                <Icon size={14} className="text-cyan" />
              </span>
            </div>
            <div className="mt-1 text-3xl font-semibold tabular-nums">{value}</div>
            <Sparkline values={spark} />
          </div>
        ))}
      </div>

      {/* pipeline board + right column */}
      <div className="grid gap-6 xl:grid-cols-[1fr_320px]">
        {/* pipeline — the real state machine as kanban columns */}
        <div className="card p-5">
          <div className="mb-4 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <h2 className="font-semibold">Recruitment pipeline</h2>
              <span className="rounded-full bg-white/5 px-2.5 py-0.5 text-xs text-dim ring-1 ring-white/10">
                {rows.length} candidates
              </span>
            </div>
            <span className="text-[11px] uppercase tracking-wider text-dim/70">status = real state machine</span>
          </div>
          <div className="flex gap-3 overflow-x-auto pb-2">
            {STAGES.filter((s) => s.key !== 'FLAGGED_FOR_REVIEW' || stats.byStatus[s.key]).map((stage) => {
              const stageRows = rows
                .filter((r) => r.status === stage.key)
                .sort((a, b) => (b.finalScore ?? -1) - (a.finalScore ?? -1))
              return (
                <div key={stage.key} className="w-44 shrink-0">
                  <div className="mb-2 flex items-center justify-between px-1">
                    <span className={`text-xs font-medium ${stage.accent}`}>{stage.label}</span>
                    <span className="text-xs tabular-nums text-dim">{stageRows.length}</span>
                  </div>
                  <div className={`h-0.5 rounded-full ${stage.bar} opacity-60 mb-2`} />
                  <div className="space-y-2">
                    {stageRows.slice(0, 3).map((row) => (
                      <Link key={row.applicationId} to={`/jobs/${row.jobId}`}
                            className={`card block p-2.5 !rounded-xl shadow-lg ${stage.glow}`}>
                        <div className="flex items-center gap-2">
                          <Avatar name={row.name} size={26} />
                          <div className="min-w-0 leading-tight">
                            <div className="truncate text-xs font-medium">{row.name}</div>
                            <div className="truncate text-[10px] text-dim">{row.seniority || '—'}</div>
                          </div>
                          {row.finalScore != null && (
                            <span className="ml-auto text-xs font-semibold tabular-nums text-marigold">
                              {Math.round(row.finalScore)}
                            </span>
                          )}
                        </div>
                      </Link>
                    ))}
                    {stageRows.length === 0 && (
                      <div className="rounded-xl border border-dashed border-white/10 p-2.5 text-center text-[10px] text-dim/60">
                        empty
                      </div>
                    )}
                    {stageRows.length > 3 && (
                      <div className="px-1 text-[10px] text-dim/70">+{stageRows.length - 3} more</div>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        </div>

        {/* right column: insights + component averages */}
        <div className="space-y-6">
          <div className="card p-5">
            <div className="mb-3 flex items-center gap-2">
              <Sparkles size={15} className="text-violet" />
              <h2 className="font-semibold">Pipeline insights</h2>
            </div>
            <ul className="space-y-2.5 text-sm">
              {stats.top && (
                <li className="flex gap-2">
                  <span className="text-emerald-400">✓</span>
                  <span>Top candidate: <b>{stats.top.name}</b> at <b>{Math.round(stats.top.finalScore)}</b></span>
                </li>
              )}
              <li className="flex gap-2">
                <span className="text-cyan">◆</span>
                <span><b>{stats.strong.length}</b> candidate(s) score 80+ across your jobs</span>
              </li>
              <li className="flex gap-2">
                <span className="text-amber-400">⚠</span>
                <span><b>{stats.undecided.length}</b> scored candidate(s) still await a human decision</span>
              </li>
              <li className="flex gap-2">
                <span className="text-rose-400">✕</span>
                <span><b>{stats.knockouts.length}</b> knocked out by hard filters (still visible, never hidden)</span>
              </li>
              <li className="flex gap-2 text-dim">
                <span>🛡</span>
                <span>Fraud flags: <b>{stats.byStatus.FLAGGED_FOR_REVIEW || 0}</b> — Trust Service lands in Phase 6</span>
              </li>
            </ul>
          </div>

          <div className="card p-5">
            <h2 className="mb-3 font-semibold">Average score components</h2>
            <div className="space-y-2.5">
              {Object.entries(COMPONENT_LABELS).map(([key, label]) => {
                const v = stats.componentAvg[key] || 0
                return (
                  <div key={key}>
                    <div className="mb-1 flex justify-between text-xs">
                      <span className="text-dim">{label}</span>
                      <span className="tabular-nums">{Math.round(v * 100)}%</span>
                    </div>
                    <div className="h-1.5 overflow-hidden rounded-full bg-white/5 ring-1 ring-white/5">
                      <div className="h-full rounded-full bg-gradient-to-r from-cyan via-violet to-pink"
                           style={{ width: `${v * 100}%` }} />
                    </div>
                  </div>
                )
              })}
            </div>
          </div>
        </div>
      </div>

      {/* jobs */}
      <div>
        <h2 className="mb-3 font-semibold">Open positions</h2>
        <div className="grid gap-4 sm:grid-cols-2">
          {jobs.map((job) => (
            <Link key={job.id} to={`/jobs/${job.id}`} className="card block p-5">
              <div className="flex items-start justify-between gap-2">
                <h3 className="font-semibold">{job.title}</h3>
                {job.fresherFriendly && (
                  <span className="shrink-0 rounded-full bg-marigold/15 px-2 py-0.5 text-xs font-medium text-marigold ring-1 ring-marigold/30">
                    fresher friendly
                  </span>
                )}
              </div>
              <p className="mt-2 line-clamp-2 text-sm text-dim">{job.description}</p>
              <p className="mt-3 text-sm font-medium text-cyan">{job.applicants} applicant(s) →</p>
            </Link>
          ))}
        </div>
      </div>
    </div>
  )
}
