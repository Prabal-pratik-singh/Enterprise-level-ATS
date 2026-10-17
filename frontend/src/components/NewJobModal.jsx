import { useEffect, useState } from 'react'
import { Briefcase, X, Plus } from 'lucide-react'
import { api } from '../api'

/** Chips + an input that suggests known skills (native datalist — no extra library). */
function SkillPicker({ label, hint, value, onChange, known, accent }) {
  const [draft, setDraft] = useState('')
  const add = () => {
    const parts = draft.split(',').map((s) => s.trim()).filter(Boolean)
    const next = [...value]
    for (const p of parts) if (!next.some((v) => v.toLowerCase() === p.toLowerCase())) next.push(p)
    onChange(next)
    setDraft('')
  }
  const listId = `skills-${label.replace(/\W/g, '')}`
  return (
    <div>
      <div className="mb-1 text-xs font-medium uppercase tracking-wider text-dim">{label}</div>
      <div className="flex gap-2">
        <input value={draft} onChange={(e) => setDraft(e.target.value)} list={listId}
               onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); add() } }}
               placeholder="type a skill, press Enter"
               className="min-w-0 flex-1 rounded-lg bg-white/5 px-3 py-2 text-sm ring-1 ring-white/10 outline-none focus:ring-violet/50" />
        <button type="button" onClick={add} className="rounded-lg bg-white/10 px-3 text-sm hover:bg-white/15"><Plus size={14} /></button>
        <datalist id={listId}>{known.map((s) => <option key={s} value={s} />)}</datalist>
      </div>
      <div className="mt-2 flex min-h-6 flex-wrap gap-1.5">
        {value.map((s) => {
          const unknown = known.length > 0 && !known.includes(s.toLowerCase())
          return (
            <span key={s} title={unknown ? 'not in the skill dictionary — check the spelling' : ''}
                  className={`inline-flex items-center gap-1 rounded-lg px-2 py-0.5 text-xs ring-1 ${unknown
                    ? 'bg-amber-400/10 text-amber-300 ring-amber-400/30' : accent}`}>
              {s}{unknown && ' ?'}
              <button type="button" onClick={() => onChange(value.filter((v) => v !== s))} className="opacity-70 hover:opacity-100">
                <X size={11} />
              </button>
            </span>
          )
        })}
        {!value.length && <span className="text-[11px] text-dim/60">{hint}</span>}
      </div>
    </div>
  )
}

/** "New job" form. The server normalises skills and says which ones it didn't recognise. */
export default function NewJobModal({ onClose, onCreated }) {
  const [known, setKnown] = useState([])
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [mustHave, setMustHave] = useState([])
  const [niceToHave, setNiceToHave] = useState([])
  const [minExp, setMinExp] = useState(24)
  const [fresher, setFresher] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => { api.skills().then(setKnown).catch(() => setKnown([])) }, [])

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const created = await api.createJob({
        title, description, mustHave, niceToHave, minExpMonths: fresher ? 0 : minExp, fresherFriendly: fresher,
      })
      onCreated(created)
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 z-30 flex items-start justify-center overflow-y-auto p-6" onClick={onClose}>
      <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" />
      <form onSubmit={submit} onClick={(e) => e.stopPropagation()}
            className="relative mt-10 w-full max-w-xl space-y-5 rounded-2xl border border-white/10 bg-panel/95 p-6 shadow-2xl backdrop-blur-2xl">
        <div className="flex items-center gap-2">
          <Briefcase size={18} className="text-cyan" />
          <h2 className="text-lg font-semibold">New job</h2>
          <button type="button" onClick={onClose} className="ml-auto text-dim hover:text-ink"><X size={18} /></button>
        </div>

        <label className="block">
          <div className="mb-1 text-xs font-medium uppercase tracking-wider text-dim">Title</div>
          <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={120} required autoFocus
                 placeholder="e.g. DevOps Engineer"
                 className="w-full rounded-lg bg-white/5 px-3 py-2 text-sm ring-1 ring-white/10 outline-none focus:ring-violet/50" />
        </label>

        <label className="block">
          <div className="mb-1 text-xs font-medium uppercase tracking-wider text-dim">Description</div>
          <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3}
                    placeholder="What will this person build? (the matcher compares candidates' projects with this text)"
                    className="w-full resize-y rounded-lg bg-white/5 px-3 py-2 text-sm ring-1 ring-white/10 outline-none focus:ring-violet/50" />
        </label>

        <SkillPicker label="Must-have skills" hint="at least one — each missing one lowers a candidate's maximum score"
                     value={mustHave} onChange={setMustHave} known={known}
                     accent="bg-violet/15 text-violet ring-violet/30" />
        <SkillPicker label="Nice-to-have skills" hint="optional bonus points"
                     value={niceToHave} onChange={setNiceToHave} known={known}
                     accent="bg-cyan/10 text-cyan ring-cyan/30" />

        <label className="flex cursor-pointer items-start gap-2 text-sm">
          <input type="checkbox" checked={fresher} onChange={(e) => setFresher(e.target.checked)} className="mt-0.5 accent-marigold" />
          <span>
            Fresher friendly
            <span className="block text-[11px] text-dim/70">experience barely counts; projects and education count more</span>
          </span>
        </label>

        {!fresher && (
          <div>
            <div className="mb-1 flex items-baseline justify-between text-xs font-medium uppercase tracking-wider text-dim">
              <span>Minimum experience</span>
              <span className="normal-case tracking-normal text-ink">{Math.floor(minExp / 12)}y {minExp % 12}m</span>
            </div>
            <input type="range" min="0" max="180" step="6" value={minExp}
                   onChange={(e) => setMinExp(Number(e.target.value))} className="w-full accent-violet" />
            <div className="text-[11px] text-dim/60">candidates with less than half of this are knocked out (still visible)</div>
          </div>
        )}

        {error && <p className="text-sm text-rose-400">{error}</p>}
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} className="rounded-xl px-4 py-2 text-sm text-dim hover:text-ink">Cancel</button>
          <button disabled={busy || !title.trim() || !mustHave.length}
                  className="rounded-xl bg-gradient-to-r from-violet to-cyan px-4 py-2 text-sm font-medium text-white shadow-lg shadow-violet/30 disabled:opacity-40">
            {busy ? 'Creating…' : 'Create job'}
          </button>
        </div>
      </form>
    </div>
  )
}
