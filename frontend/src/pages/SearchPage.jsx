import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Search, Sparkles, Type, Layers, Filter, ShieldAlert, X, Info } from 'lucide-react'
import { api } from '../api'
import Avatar from '../components/Avatar'
import CandidateDrawer from '../components/CandidateDrawer'
import StatusChip from '../components/StatusChip'
import { FLAG_THRESHOLD, pts } from '../lib/fraud'

/**
 * Talent Search — hybrid search over everyone who applied to a job.
 * Keyword search (exact words) and semantic search (meaning) run side by side;
 * the backend fuses them with RRF and tells us where each result came from.
 * All state lives in the URL, so a search can be bookmarked or shared.
 */

// how each match source is shown — the spec's "keyword / semantic / both" hint
const SOURCES = {
  both: { label: 'both', icon: Layers, cls: 'bg-emerald-400/15 text-emerald-300 ring-emerald-400/30',
          hint: 'matched your words AND your meaning' },
  keyword: { label: 'keyword', icon: Type, cls: 'bg-cyan/15 text-cyan ring-cyan/30',
             hint: 'contains your words' },
  semantic: { label: 'semantic', icon: Sparkles, cls: 'bg-violet/20 text-violet ring-violet/40',
              hint: 'means something similar — the exact words may never appear' },
  filter: { label: 'filter', icon: Filter, cls: 'bg-white/10 text-dim ring-white/15',
            hint: 'no search text — filtered and sorted by score' },
}

const EXAMPLES = ['event driven backend', 'kubernetes platform engineer', 'payments microservices', 'fresher with projects']

function SourceBadge({ source }) {
  const s = SOURCES[source] || SOURCES.filter
  const Icon = s.icon
  return (
    <span title={s.hint}
          className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium ring-1 ${s.cls}`}>
      <Icon size={11} /> {s.label}
    </span>
  )
}

export default function SearchPage() {
  const [params, setParams] = useSearchParams()
  const jobId = params.get('job')
  const q = params.get('q') || ''
  const skills = params.get('skills') || ''
  const minExp = params.get('minExp') || ''
  const hideFlagged = params.get('hideFlagged') === 'true'

  const [jobs, setJobs] = useState([])
  const [draft, setDraft] = useState(q)            // the text box, before pressing Search
  const [skillDraft, setSkillDraft] = useState('')
  const [answer, setAnswer] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [selected, setSelected] = useState(null)
  const [refresh, setRefresh] = useState(0)        // bumped after a decision in the drawer → search again

  const set = (patch) => {
    const next = new URLSearchParams(params)
    Object.entries(patch).forEach(([k, v]) => (v === '' || v == null || v === false ? next.delete(k) : next.set(k, v)))
    setParams(next)
  }

  useEffect(() => {
    api.jobs().then((list) => {
      setJobs(list)
      if (!jobId && list.length) set({ job: list[0].id })
    }).catch((e) => setError(e.message))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => setDraft(q), [q])

  // run the search whenever the URL (= the search) changes
  useEffect(() => {
    if (!jobId) return undefined
    let alive = true
    setLoading(true)
    setError(null)
    api.search(jobId, { q, skills, minExp, hideFlagged })
      .then((a) => alive && setAnswer(a))
      .catch((e) => alive && setError(e.message.includes('503')
        ? 'Search is not running — start it with: docker compose --profile search up -d'
        : e.message))
      .finally(() => alive && setLoading(false))
    return () => { alive = false }
  }, [jobId, q, skills, minExp, hideFlagged, refresh])

  const skillList = useMemo(() => skills.split(',').filter(Boolean), [skills])
  const addSkill = (e) => {
    e.preventDefault()
    const s = skillDraft.trim().toLowerCase()
    if (s && !skillList.includes(s)) set({ skills: [...skillList, s].join(',') })
    setSkillDraft('')
  }
  const removeSkill = (s) => set({ skills: skillList.filter((x) => x !== s).join(',') })

  const counts = useMemo(() => {
    const c = { both: 0, keyword: 0, semantic: 0 }
    for (const r of answer?.results || []) if (c[r.source] != null) c[r.source]++
    return c
  }, [answer])

  return (
    <div>
      <div className="mb-1 flex items-baseline gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">Talent search</h1>
        {answer && <span className="text-base text-dim">({answer.count} results)</span>}
      </div>
      <p className="mb-5 max-w-3xl text-sm text-dim">
        Two searches run side by side — <span className="text-cyan">keyword</span> finds your exact words,{' '}
        <span className="text-violet">semantic</span> finds resumes that <i>mean</i> the same thing — and are merged
        by rank (RRF). Try a phrase nobody literally wrote.
      </p>

      {/* job picker */}
      <div className="mb-4 flex flex-wrap gap-2">
        {jobs.map((job) => (
          <button key={job.id} onClick={() => set({ job: job.id })}
                  className={`rounded-full px-3 py-1 text-xs font-medium ring-1 transition ${
                    jobId === job.id
                      ? 'bg-gradient-to-r from-violet/40 to-cyan/25 text-ink ring-violet/50'
                      : 'bg-white/5 text-dim ring-white/10 hover:text-ink'}`}>
            {job.title}
          </button>
        ))}
      </div>

      {/* search box */}
      <form onSubmit={(e) => { e.preventDefault(); set({ q: draft.trim() }) }}
            className="card mb-3 flex items-center gap-3 px-4 py-3 !rounded-2xl">
        <Search size={18} className="shrink-0 text-dim" />
        <input value={draft} onChange={(e) => setDraft(e.target.value)} autoFocus
               placeholder="Describe who you need — e.g. event driven backend"
               className="min-w-0 flex-1 bg-transparent text-base outline-none placeholder:text-dim/60" />
        {draft && (
          <button type="button" onClick={() => { setDraft(''); set({ q: '' }) }} className="text-dim hover:text-ink">
            <X size={16} />
          </button>
        )}
        <button type="submit"
                className="rounded-xl bg-gradient-to-r from-violet to-cyan px-4 py-1.5 text-sm font-medium text-white shadow-lg shadow-violet/30">
          Search
        </button>
      </form>
      <div className="mb-6 flex flex-wrap items-center gap-2 text-xs text-dim">
        <span>Try:</span>
        {EXAMPLES.map((ex) => (
          <button key={ex} onClick={() => set({ q: ex })}
                  className="rounded-full bg-white/5 px-2.5 py-0.5 ring-1 ring-white/10 hover:text-ink">{ex}</button>
        ))}
      </div>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-[15rem_1fr]">
        {/* filter sidebar — applied to BOTH searches on the server */}
        <aside className="card h-fit space-y-5 p-4">
          <div>
            <div className="mb-2 text-xs font-medium uppercase tracking-wider text-dim">Must have skills</div>
            <form onSubmit={addSkill} className="flex gap-2">
              <input value={skillDraft} onChange={(e) => setSkillDraft(e.target.value)} placeholder="e.g. kafka"
                     className="min-w-0 flex-1 rounded-lg bg-white/5 px-2.5 py-1.5 text-sm ring-1 ring-white/10 outline-none focus:ring-violet/50" />
              <button className="rounded-lg bg-white/10 px-2.5 text-sm hover:bg-white/15">Add</button>
            </form>
            <div className="mt-2 flex flex-wrap gap-1.5">
              {skillList.map((s) => (
                <span key={s} className="inline-flex items-center gap-1 rounded-lg bg-cyan/10 px-2 py-0.5 text-xs text-cyan ring-1 ring-cyan/30">
                  {s}
                  <button onClick={() => removeSkill(s)} className="opacity-70 hover:opacity-100"><X size={11} /></button>
                </span>
              ))}
              {!skillList.length && <span className="text-[11px] text-dim/60">every skill must match</span>}
            </div>
          </div>

          <div>
            <div className="mb-2 flex items-baseline justify-between text-xs font-medium uppercase tracking-wider text-dim">
              <span>Min experience</span>
              <span className="normal-case tracking-normal text-ink">
                {minExp ? `${Math.floor(minExp / 12)}y ${minExp % 12}m` : 'any'}
              </span>
            </div>
            <input type="range" min="0" max="144" step="6" value={minExp || 0}
                   onChange={(e) => set({ minExp: e.target.value === '0' ? '' : e.target.value })}
                   className="w-full accent-violet" />
          </div>

          <label className="flex cursor-pointer items-start gap-2 text-sm">
            <input type="checkbox" checked={hideFlagged} onChange={(e) => set({ hideFlagged: e.target.checked })}
                   className="mt-0.5 accent-amber-400" />
            <span>
              Hide flagged for review
              <span className="block text-[11px] leading-snug text-dim/70">
                Off by default — the system flags, it never hides. Keyword-stuffed resumes can match words they hid.
              </span>
            </span>
          </label>

          {answer && answer.semantic !== 'n/a' && (
            <div className="border-t border-white/10 pt-3 text-[11px] text-dim">
              <div className="mb-1.5 uppercase tracking-wider">Where results came from</div>
              {['both', 'keyword', 'semantic'].map((k) => (
                <div key={k} className="flex items-center justify-between py-0.5">
                  <SourceBadge source={k} /><span className="tabular-nums text-ink">{counts[k]}</span>
                </div>
              ))}
            </div>
          )}
        </aside>

        {/* results */}
        <section className="min-w-0">
          {error && <div className="card p-4 text-sm text-rose-300">{error}</div>}
          {answer?.semantic === 'unavailable' && (
            <div className="mb-3 flex items-center gap-2 rounded-xl bg-amber-400/10 p-3 text-xs text-amber-200 ring-1 ring-amber-400/30">
              <Info size={14} /> Meaning search is unavailable right now (Ollama not reachable) — showing keyword matches only.
            </div>
          )}
          {loading && !answer && <p className="text-dim">Searching…</p>}
          {answer && answer.results.length === 0 && !error && (
            <div className="card p-10 text-center text-sm text-dim">No candidates match — loosen a filter or rephrase.</div>
          )}
          <ol className={`space-y-2 transition-opacity ${loading ? 'opacity-60' : ''}`}>
            {(answer?.results || []).map((r, i) => {
              const flagged = r.status === 'FLAGGED_FOR_REVIEW'
              // a human cleared it: the fraud score stays as history, so the shield stays — dimmed
              const cleared = !flagged && (r.fraudScore ?? 0) >= FLAG_THRESHOLD
              return (
                <li key={r.applicationId}>
                  <button onClick={() => setSelected(r.applicationId)}
                          className="card flex w-full items-center gap-4 p-4 text-left transition hover:ring-1 hover:ring-violet/30">
                    <span className="w-6 shrink-0 text-right text-sm tabular-nums text-dim">{i + 1}</span>
                    <Avatar name={r.name} size={38} />
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">{r.name}</span>
                        {flagged && <span title="flagged by the Trust Service"><ShieldAlert size={14} className="text-amber-300" /></span>}
                        {cleared && <span title={`fraud score ${r.fraudScore} — reviewed by a human`}><ShieldAlert size={14} className="text-dim/50" /></span>}
                        <SourceBadge source={r.source} />
                        <StatusChip status={r.status} />
                      </div>
                      <div className="mt-1 flex flex-wrap gap-1">
                        {(r.skills || []).slice(0, 10).map((s) => (
                          <span key={s}
                                className={`rounded-md px-1.5 py-0.5 text-[10px] ring-1 ${skillList.includes(s)
                                  ? 'bg-cyan/15 text-cyan ring-cyan/30' : 'bg-white/5 text-dim ring-white/10'}`}>{s}</span>
                        ))}
                        {(r.skills || []).length > 10 && <span className="text-[10px] text-dim/60">+{r.skills.length - 10}</span>}
                      </div>
                    </div>
                    <div className="hidden shrink-0 text-right text-xs text-dim sm:block">
                      {r.totalExperienceMonths != null && <div>{r.totalExperienceMonths} mo · {r.seniority}</div>}
                      {r.rrfScore != null && (
                        <div className="mt-0.5 tabular-nums" title="rank in each list, then the fused RRF score">
                          {r.keywordRank ? `kw #${r.keywordRank}` : 'kw —'} · {r.semanticRank ? `sem #${r.semanticRank}` : 'sem —'}
                        </div>
                      )}
                    </div>
                    <div className="w-14 shrink-0 text-right">
                      <div className="text-lg font-semibold tabular-nums text-marigold">{pts(r.finalScore)}</div>
                      <div className="text-[10px] text-dim">score</div>
                    </div>
                  </button>
                </li>
              )
            })}
          </ol>
        </section>
      </div>

      {selected && (
        <CandidateDrawer applicationId={selected} onClose={() => setSelected(null)}
                         // the index catches up ~2s after a decision — then search again
                         onDecision={() => setTimeout(() => setRefresh((n) => n + 1), 2500)} />
      )}
    </div>
  )
}
