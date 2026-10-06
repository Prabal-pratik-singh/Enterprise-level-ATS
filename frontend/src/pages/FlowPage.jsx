import { useEffect, useRef, useState } from 'react'
import { Workflow, Timer } from 'lucide-react'
import { api } from '../api'

/**
 * Live Flow — the pipeline as a living diagram. Every number is real:
 * stage nodes show current occupancy (+ "ever passed" from the audit trail),
 * queue edges show ACTUAL Kafka consumer lag, and the particles speed up
 * when a queue is backed up. Polls every 3s.
 */

const STAGES = [
  { key: 'APPLIED', label: 'Applied', color: '#9aa3c0', desc: 'uploaded, waiting for the pipeline' },
  { key: 'PARSED', label: 'Parsed', color: '#4cc9f0', desc: 'text + layout extracted' },
  { key: 'EXTRACTED', label: 'Extracted', color: '#8b5cf6', desc: 'LLM profile built' },
  { key: 'SCORED', label: 'Scored', color: '#e4952b', desc: 'ranked, awaiting a human' },
]

// the worker queue that sits AFTER each stage (index-aligned with STAGES[0..2])
const QUEUES = ['parser-service', 'extractor-service', 'matcher-service']
const QUEUE_LABEL = { 'parser-service': 'parser', 'extractor-service': 'extractor', 'matcher-service': 'matcher' }

function StageNode({ stage, current, ever, pulse }) {
  return (
    <div className={`card w-40 shrink-0 p-4 text-center transition ${pulse ? 'ring-2 ring-marigold/60' : ''}`}
         style={{ boxShadow: `0 20px 60px rgba(0,0,0,0.35), 0 0 24px ${stage.color}22` }}>
      <div className="text-xs font-medium" style={{ color: stage.color }}>{stage.label}</div>
      <div className="my-1 text-4xl font-semibold tabular-nums">{current}</div>
      <div className="text-[10px] text-dim">∑ ever passed: {ever}</div>
      <div className="mt-1 text-[10px] leading-tight text-dim/70">{stage.desc}</div>
    </div>
  )
}

/** The animated edge between two stages: worker name, lag badge, flowing dots. */
function QueueEdge({ worker, lag }) {
  const busy = lag > 0
  // more lag -> more dots, moving faster (capped so it never looks silly)
  const dots = busy ? Math.min(1 + Math.ceil(lag / 3), 5) : 1
  const duration = busy ? Math.max(0.7, 2.4 - lag * 0.08) : 6

  return (
    <div className="flex w-28 shrink-0 flex-col items-center gap-1 self-center">
      <div className={`flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] ring-1 transition
                       ${busy ? 'bg-marigold/15 text-marigold ring-marigold/40' : 'bg-white/5 text-dim ring-white/10'}`}>
        <Timer size={10} />
        {busy ? `${lag} in queue` : 'idle'}
      </div>
      <div className="relative h-1 w-full overflow-hidden rounded-full bg-white/10">
        {Array.from({ length: dots }).map((_, i) => (
          <span key={i}
                className="flow-dot absolute top-1/2 h-1.5 w-1.5 -translate-y-1/2 rounded-full"
                style={{
                  background: busy ? '#e4952b' : '#4cc9f0',
                  opacity: busy ? 0.95 : 0.4,
                  boxShadow: busy ? '0 0 8px rgba(228,149,43,0.9)' : '0 0 6px rgba(76,201,240,0.6)',
                  animationDuration: `${duration}s`,
                  animationDelay: `${(i * duration) / dots}s`,
                }} />
        ))}
      </div>
      <div className="text-[10px] text-dim/70">{QUEUE_LABEL[worker]}</div>
    </div>
  )
}

export default function FlowPage() {
  const [jobs, setJobs] = useState([])
  const [jobId, setJobId] = useState(null) // null = all jobs
  const [stats, setStats] = useState(null)
  const [lag, setLag] = useState({})
  const [error, setError] = useState(null)
  const prevCounts = useRef({})
  const [pulses, setPulses] = useState({})

  useEffect(() => {
    api.jobs().then(setJobs).catch((e) => setError(e.message))
  }, [])

  // the heartbeat: both aggregates every 3 seconds
  useEffect(() => {
    let alive = true
    const tick = async () => {
      try {
        const [pipelineStats, lagData] = await Promise.all([api.stats(jobId), api.lag()])
        if (!alive) return
        setStats(pipelineStats)
        const byGroup = {}
        for (const q of lagData.queues || []) byGroup[q.group] = q.lag
        setLag(byGroup)

        // pulse any stage whose count just grew — movement should be felt
        const fresh = {}
        for (const [key, value] of Object.entries(pipelineStats.current || {})) {
          if (prevCounts.current[key] != null && value > prevCounts.current[key]) fresh[key] = true
        }
        prevCounts.current = pipelineStats.current || {}
        if (Object.keys(fresh).length) {
          setPulses(fresh)
          setTimeout(() => alive && setPulses({}), 1500)
        }
        setError(null)
      } catch (e) {
        if (alive) setError(e.message)
      }
    }
    tick()
    const interval = setInterval(tick, 3000)
    return () => { alive = false; clearInterval(interval) }
  }, [jobId])

  const current = stats?.current || {}
  const ever = stats?.cumulative || {}
  const n = (m, k) => m[k] || 0
  const totalLag = QUEUES.reduce((s, g) => s + (lag[g] || 0), 0)

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2.5">
          <Workflow size={20} className="text-violet" />
          <h1 className="text-2xl font-semibold tracking-tight">Live pipeline flow</h1>
        </div>
        <div className="flex items-center gap-2 text-sm text-dim">
          <span>{stats?.total ?? '—'} applications</span>
          <span className="text-dim/40">·</span>
          <span className={totalLag > 0 ? 'text-marigold' : ''}>{totalLag} queued</span>
        </div>
      </div>

      {/* job scope */}
      <div className="mb-6 flex flex-wrap gap-2">
        <button onClick={() => setJobId(null)}
                className={`rounded-full px-3 py-1 text-xs font-medium ring-1 transition ${
                  jobId === null
                    ? 'bg-gradient-to-r from-violet/40 to-cyan/25 text-ink ring-violet/50'
                    : 'bg-white/5 text-dim ring-white/10 hover:text-ink'}`}>
          All jobs
        </button>
        {jobs.map((job) => (
          <button key={job.id} onClick={() => setJobId(job.id)}
                  className={`rounded-full px-3 py-1 text-xs font-medium ring-1 transition ${
                    jobId === job.id
                      ? 'bg-gradient-to-r from-violet/40 to-cyan/25 text-ink ring-violet/50'
                      : 'bg-white/5 text-dim ring-white/10 hover:text-ink'}`}>
            {job.title}
          </button>
        ))}
      </div>

      {error && <p className="mb-4 text-sm text-rose-400">{error}</p>}

      {/* the river: stages left to right, queues between them */}
      <div className="card overflow-x-auto p-6">
        <div className="flex min-w-max items-stretch gap-3">
          {STAGES.map((stage, i) => (
            <div key={stage.key} className="flex items-stretch gap-3">
              <StageNode stage={stage}
                         current={n(current, stage.key)}
                         ever={n(ever, stage.key)}
                         pulse={pulses[stage.key]} />
              {i < QUEUES.length && <QueueEdge worker={QUEUES[i]} lag={lag[QUEUES[i]] || 0} />}
            </div>
          ))}

          {/* terminal split: the human's two decisions */}
          <div className="flex flex-col justify-center gap-2 self-stretch pl-2">
            <div className={`card w-36 p-3 text-center ${pulses.SHORTLISTED ? 'ring-2 ring-emerald-400/60' : ''}`}>
              <div className="text-xs font-medium text-emerald-400">Shortlisted</div>
              <div className="text-2xl font-semibold tabular-nums">{n(current, 'SHORTLISTED')}</div>
            </div>
            <div className={`card w-36 p-3 text-center ${pulses.REJECTED ? 'ring-2 ring-rose-400/60' : ''}`}>
              <div className="text-xs font-medium text-rose-400">Rejected</div>
              <div className="text-2xl font-semibold tabular-nums">{n(current, 'REJECTED')}</div>
            </div>
          </div>
        </div>

        {/* side rails: honest losses and flags */}
        <div className="mt-5 flex flex-wrap gap-3 border-t border-white/5 pt-4 text-xs">
          <span className="rounded-full bg-rose-400/10 px-2.5 py-1 text-rose-300 ring-1 ring-rose-400/25">
            parse failed: {n(current, 'PARSE_FAILED')}
          </span>
          <span className="rounded-full bg-amber-400/10 px-2.5 py-1 text-amber-300 ring-1 ring-amber-400/25">
            flagged for review: {n(current, 'FLAGGED_FOR_REVIEW')} <span className="opacity-60">(Trust Service — Phase 6)</span>
          </span>
          <span className="ml-auto text-dim/60">
            node = live occupancy · ∑ = ever passed (audit trail) · queue = real Kafka consumer lag · refresh 3s
          </span>
        </div>
      </div>

      <p className="mt-4 text-xs text-dim/70">
        Try it: run <code className="text-marigold">./tools/seed.sh 10</code> and watch the queues swell left-to-right,
        then drain as the workers chew through them. In Phase 8 this page is the 10,000-resume show.
      </p>
    </div>
  )
}
