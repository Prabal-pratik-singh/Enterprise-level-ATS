import { useEffect, useRef, useState } from 'react'
import { Workflow, Timer, TriangleAlert, ShieldAlert } from 'lucide-react'
import { api } from '../api'

/**
 * Live Flow — the pipeline as a living diagram, flowing TOP → BOTTOM like a
 * waterfall. Every number is real: stage nodes show current occupancy (+ "ever
 * passed" from the audit trail), queue edges show ACTUAL Kafka consumer lag,
 * and the particles fall faster when a queue is backed up. Polls every 3s.
 * The right rail explains the river's losses: WHY each parse failed.
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

/** Strip Spring's listener wrapper — the real cause sits after "threw exception; ". */
function rootCause(raw) {
  if (!raw) return 'unknown parser failure'
  const marker = 'threw exception; '
  const i = raw.lastIndexOf(marker)
  return i >= 0 ? raw.slice(i + marker.length) : raw
}

/** Translate parser exception text into recruiter-readable English. */
function explainFailure(raw) {
  const s = rootCause(raw).toLowerCase()
  if (s.includes('unsupported file type')) return 'Unsupported file type — only PDF, DOCX, PNG and JPG can be parsed.'
  if (s.includes('password') || s.includes('encrypt')) return 'Password-protected PDF — the parser cannot open it.'
  if (s.includes('trailer') || s.includes('root object') || s.includes('header') || s.includes('end-of-file') || s.includes('eof'))
    return 'Corrupt or truncated PDF — the file structure is unreadable, so no text could be extracted.'
  if (s.includes('ocr')) return 'Image OCR failed — the picture was too noisy to read.'
  return 'The parser crashed on this file 3 times, then gave up (dead-letter queue).'
}

function StageNode({ stage, current, ever, pulse }) {
  return (
    <div className={`card flex w-full max-w-md items-center gap-4 p-4 transition ${pulse ? 'ring-2 ring-marigold/60' : ''}`}
         style={{ boxShadow: `0 20px 60px rgba(0,0,0,0.35), 0 0 24px ${stage.color}22` }}>
      <span className="h-2.5 w-2.5 shrink-0 rounded-full"
            style={{ background: stage.color, boxShadow: `0 0 10px ${stage.color}` }} />
      <div className="min-w-0 flex-1 text-left">
        <div className="text-sm font-medium" style={{ color: stage.color }}>{stage.label}</div>
        <div className="text-[11px] leading-tight text-dim/70">{stage.desc}</div>
      </div>
      <div className="text-right">
        <div className="text-3xl font-semibold leading-none tabular-nums">{current}</div>
        <div className="mt-1 text-[10px] text-dim">∑ ever: {ever}</div>
      </div>
    </div>
  )
}

/** Vertical edge between stages: centered falling dots, lag badge to the side. */
function QueueEdge({ worker, lag }) {
  const busy = lag > 0
  // more lag -> more dots, falling faster (capped so it never looks silly)
  const dots = busy ? Math.min(1 + Math.ceil(lag / 3), 5) : 1
  const duration = busy ? Math.max(0.7, 2.4 - lag * 0.08) : 6

  return (
    // 3-column grid keeps the track EXACTLY on the nodes' center line,
    // whatever width the labels on the right take up.
    <div className="grid w-full max-w-md grid-cols-[1fr_auto_1fr] items-center">
      <div />
      <div className="relative h-20 w-1 overflow-hidden rounded-full bg-white/10">
        {Array.from({ length: dots }).map((_, i) => (
          <span key={i}
                className="flow-dot-v absolute left-1/2 h-1.5 w-1.5 -translate-x-1/2 rounded-full"
                style={{
                  background: busy ? '#e4952b' : '#4cc9f0',
                  opacity: busy ? 0.95 : 0.4,
                  boxShadow: busy ? '0 0 8px rgba(228,149,43,0.9)' : '0 0 6px rgba(76,201,240,0.6)',
                  animationDuration: `${duration}s`,
                  animationDelay: `${(i * duration) / dots}s`,
                }} />
        ))}
      </div>
      <div className="flex flex-col items-start gap-1 pl-3">
        <div className={`flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] ring-1 transition
                         ${busy ? 'bg-marigold/15 text-marigold ring-marigold/40' : 'bg-white/5 text-dim ring-white/10'}`}>
          <Timer size={10} />
          {busy ? `${lag} in queue` : 'idle'}
        </div>
        <div className="text-[10px] text-dim/70">{QUEUE_LABEL[worker]} <span className="text-dim/40">· Kafka lag</span></div>
      </div>
    </div>
  )
}

/** Right-rail card: every PARSE_FAILED application with the recorded reason. */
function FailuresPanel({ count, items }) {
  return (
    <div className="card p-4">
      <div className="flex items-center gap-2">
        <TriangleAlert size={15} className="text-rose-300" />
        <div className="text-sm font-medium text-rose-300">Parse failed</div>
        <span className="ml-auto rounded-full bg-rose-400/10 px-2 py-0.5 text-xs tabular-nums text-rose-300 ring-1 ring-rose-400/25">
          {count}
        </span>
      </div>
      <p className="mt-1 text-[11px] leading-snug text-dim/70">
        Files the parser gave up on after 3 retries — they leave the river at the parser queue and wait for a human.
      </p>
      {items.length > 0 && (
        <div className="mt-3 flex flex-col gap-2">
          {items.map((f) => (
            <div key={f.applicationId} className="rounded-xl bg-rose-400/5 p-3 ring-1 ring-rose-400/20">
              <div className="flex items-baseline justify-between gap-2">
                <div className="truncate text-xs font-medium">{f.name}</div>
                <div className="shrink-0 text-[10px] text-dim/60">{new Date(f.at).toLocaleDateString()}</div>
              </div>
              <div className="mt-1 text-[11px] leading-snug text-rose-200/90">{explainFailure(f.reason)}</div>
              <div className="mt-1 break-words font-mono text-[10px] leading-snug text-dim/50">{rootCause(f.reason)}</div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export default function FlowPage() {
  const [jobs, setJobs] = useState([])
  const [jobId, setJobId] = useState(null) // null = all jobs
  const [stats, setStats] = useState(null)
  const [lag, setLag] = useState({})
  const [failures, setFailures] = useState([])
  const [error, setError] = useState(null)
  const prevCounts = useRef({})
  const [pulses, setPulses] = useState({})

  useEffect(() => {
    api.jobs().then(setJobs).catch((e) => setError(e.message))
  }, [])

  // the heartbeat: all three aggregates every 3 seconds
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
      // failure reasons ride separately so one missing endpoint never kills the page
      api.failures(jobId)
        .then((list) => alive && setFailures(Array.isArray(list) ? list : []))
        .catch(() => alive && setFailures([]))
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

      <div className="grid grid-cols-1 items-start gap-5 xl:grid-cols-[minmax(0,1fr)_340px]">
        {/* the waterfall: stages top to bottom, queues falling between them */}
        <div className="card p-6">
          <div className="flex flex-col items-center">
            {STAGES.map((stage, i) => (
              <div key={stage.key} className="flex w-full flex-col items-center">
                <StageNode stage={stage}
                           current={n(current, stage.key)}
                           ever={n(ever, stage.key)}
                           pulse={pulses[stage.key]} />
                {i < QUEUES.length && <QueueEdge worker={QUEUES[i]} lag={lag[QUEUES[i]] || 0} />}
              </div>
            ))}

            {/* terminal split: no Kafka here — a human makes this move */}
            <div className="grid w-full max-w-md grid-cols-[1fr_auto_1fr] items-center">
              <div />
              <div className="h-10 w-px bg-white/10" />
              <div className="pl-3 text-[10px] text-dim/60">human decision</div>
            </div>
            <div className="grid w-full max-w-md grid-cols-2 gap-3">
              <div className={`card p-3 text-center transition ${pulses.SHORTLISTED ? 'ring-2 ring-emerald-400/60' : ''}`}>
                <div className="text-xs font-medium text-emerald-400">Shortlisted</div>
                <div className="text-2xl font-semibold tabular-nums">{n(current, 'SHORTLISTED')}</div>
              </div>
              <div className={`card p-3 text-center transition ${pulses.REJECTED ? 'ring-2 ring-rose-400/60' : ''}`}>
                <div className="text-xs font-medium text-rose-400">Rejected</div>
                <div className="text-2xl font-semibold tabular-nums">{n(current, 'REJECTED')}</div>
              </div>
            </div>
          </div>

          <div className="mt-5 border-t border-white/5 pt-3 text-center text-[10px] text-dim/60">
            node = live occupancy · ∑ = ever passed (audit trail) · queue = real Kafka consumer lag · refresh 3s
          </div>
        </div>

        {/* right rail: the river's losses, explained */}
        <div className="flex flex-col gap-4">
          <FailuresPanel count={n(current, 'PARSE_FAILED')} items={failures} />

          <div className="card p-4">
            <div className="flex items-center gap-2">
              <ShieldAlert size={15} className="text-amber-300" />
              <div className="text-sm font-medium text-amber-300">Flagged for review</div>
              <span className="ml-auto rounded-full bg-amber-400/10 px-2 py-0.5 text-xs tabular-nums text-amber-300 ring-1 ring-amber-400/25">
                {n(current, 'FLAGGED_FOR_REVIEW')}
              </span>
            </div>
            <p className="mt-1 text-[11px] leading-snug text-dim/70">
              Suspicious resumes (hidden text, impossible timelines, anachronisms) get pulled aside here —
              the Trust Service arrives in <span className="text-amber-300/90">Phase 6</span>.
            </p>
          </div>

          <div className="card p-4">
            <div className="text-sm font-medium">Try it live</div>
            <p className="mt-1 text-[11px] leading-snug text-dim/70">
              Run <code className="text-marigold">./tools/seed.sh 10</code> and watch the queues swell top-to-bottom,
              then drain as the workers chew through them. In Phase 8 this page is the 10,000-resume show.
            </p>
          </div>
        </div>
      </div>
    </div>
  )
}
