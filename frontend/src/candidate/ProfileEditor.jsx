import { useEffect, useState } from 'react'
import { X, Plus, Pencil, RotateCcw, Briefcase, Sparkles } from 'lucide-react'
import { api } from '../api'

// the API's key for a role fix: company + title, lower-case (a fix survives a re-parse that reorders roles)
const roleKey = (role) => `${(role.company || '').trim()}|${(role.title || '').trim()}`.toLowerCase()
const input = 'rounded-lg bg-white/5 px-3 py-1.5 text-sm ring-1 ring-white/10 outline-none focus:ring-violet/50'

/**
 * The profile read from the newest resume, with the candidate's own fixes on top. Every change sends
 * the whole fixes document (PUT) and shows the answer — the same profile the embedder and role fit see.
 */
export default function ProfileEditor({ view, onSaved }) {
  const profile = view.profile
  const fixes = view.overrides || {}
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState(null)

  // a fresh, editable copy of the fixes document
  const draft = () => ({
    skills_added: [...(fixes.skills_added || [])],
    skills_removed: [...(fixes.skills_removed || [])],
    experience: { ...(fixes.experience || {}) },
  })
  const save = async (next) => {
    setBusy(true)
    setMessage(null)
    try {
      onSaved(await api.saveProfileOverrides(next))
      return true
    } catch (e) {
      setMessage(e.message)
      return false
    } finally {
      setBusy(false)
    }
  }

  const derived = profile.derived || {}
  const months = derived.total_experience_months ?? 0
  return (
    <div className="space-y-5">
      <section className="card p-5 !rounded-2xl">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="flex items-center gap-2 font-medium"><Sparkles size={16} className="text-marigold" /> Your profile</h2>
          <span className="text-xs text-dim">{view.embedded ? 'searchable' : 'being indexed for search…'}</span>
        </div>
        <p className="mt-2 text-sm text-dim">
          <span className="text-ink">{Math.floor(months / 12)} yrs {months % 12} mo</span> of experience ·{' '}
          <span className="capitalize text-ink">{derived.seniority || '—'}</span> ·{' '}
          <span className="text-ink">{Math.round((derived.completeness ?? view.completeness ?? 0) * 100)}%</span> complete
        </p>
        {message && <p className="mt-2 text-sm text-rose-400">{message}</p>}
      </section>

      <Skills skills={profile.skills || []} fixes={fixes} busy={busy} draft={draft} save={save} />
      <Experience roles={profile.experience || []} fixes={fixes} busy={busy} draft={draft} save={save} />
    </div>
  )
}

function Skills({ skills, fixes, busy, draft, save }) {
  const added = new Set(fixes.skills_added || [])
  const removed = fixes.skills_removed || []
  const [text, setText] = useState('')
  const [known, setKnown] = useState([])   // the skill vocabulary, for suggestions
  useEffect(() => { api.skills().then(setKnown).catch(() => {}) }, [])

  const remove = (skill) => {
    const d = draft()
    if (d.skills_added.includes(skill)) d.skills_added = d.skills_added.filter((s) => s !== skill)
    else d.skills_removed.push(skill)
    save(d)
  }
  const add = async (e) => {
    e.preventDefault()
    const skill = text.trim()
    if (!skill) return
    const d = draft()
    d.skills_removed = d.skills_removed.filter((s) => s.toLowerCase() !== skill.toLowerCase())
    d.skills_added.push(skill)
    if (await save(d)) setText('')
  }
  const undo = (skill) => {
    const d = draft()
    d.skills_removed = d.skills_removed.filter((s) => s !== skill)
    save(d)
  }

  return (
    <section className="card p-5 !rounded-2xl">
      <h2 className="mb-3 font-medium">Skills <span className="text-sm text-dim">({skills.length})</span></h2>
      <div className="flex flex-wrap gap-2">
        {skills.map((skill) => (
          <span key={skill} className={`inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-sm ring-1 ${added.has(skill)
            ? 'bg-marigold/10 text-marigold ring-marigold/30' : 'bg-white/5 ring-white/10'}`}>
            {skill}
            {added.has(skill) && <span className="text-[10px] uppercase tracking-wider opacity-80">added by you</span>}
            <button onClick={() => remove(skill)} disabled={busy} title={`Remove ${skill}`}
                    className="opacity-60 hover:opacity-100 disabled:opacity-30"><X size={13} /></button>
          </span>
        ))}
      </div>
      {removed.length > 0 && (
        <p className="mt-3 text-xs text-dim">
          Removed by you:{' '}
          {removed.map((skill) => (
            <button key={skill} onClick={() => undo(skill)} disabled={busy}
                    className="mr-2 inline-flex items-center gap-1 text-dim hover:text-ink">
              {skill} <RotateCcw size={11} />
            </button>
          ))}
        </p>
      )}
      <form onSubmit={add} className="mt-4 flex flex-wrap gap-2">
        <input value={text} onChange={(e) => setText(e.target.value)} list="known-skills" maxLength={60}
               placeholder="Add a skill the reader missed" className={`${input} min-w-0 flex-1`} />
        <datalist id="known-skills">{known.map((s) => <option key={s} value={s} />)}</datalist>
        <button disabled={busy || !text.trim()}
                className="flex items-center gap-1.5 rounded-lg bg-white/10 px-3 py-1.5 text-sm hover:bg-white/15 disabled:opacity-40">
          <Plus size={14} /> Add
        </button>
      </form>
    </section>
  )
}

function Experience({ roles, fixes, busy, draft, save }) {
  const fixed = fixes.experience || {}
  const [editing, setEditing] = useState(null)   // the role key being edited

  return (
    <section className="card p-5 !rounded-2xl">
      <h2 className="mb-1 flex items-center gap-2 font-medium"><Briefcase size={16} className="text-cyan" /> Experience</h2>
      <p className="mb-3 text-xs text-dim">Dates wrong? Fix them here — your fix stays even if you upload a new version.</p>
      {roles.length === 0 && <p className="text-sm text-dim">No roles were found in your resume.</p>}
      <ul className="divide-y divide-white/10">
        {roles.map((role) => {
          const key = roleKey(role)
          return (
            <li key={key} className="py-3">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <div className="min-w-0">
                  <div className="font-medium">{role.title}</div>
                  <div className="text-sm text-dim">{role.company}</div>
                </div>
                <div className="flex items-center gap-2 text-sm">
                  <span className="tabular-nums">{role.start_date || '?'} → {role.current || !role.end_date ? 'present' : role.end_date}</span>
                  {fixed[key] && <span className="rounded bg-marigold/10 px-1.5 text-[10px] uppercase tracking-wider text-marigold">your fix</span>}
                  <button onClick={() => setEditing(editing === key ? null : key)} disabled={busy}
                          className="text-dim hover:text-ink" title="Edit dates"><Pencil size={14} /></button>
                </div>
              </div>
              {editing === key && (
                <DateForm role={role} fix={fixed[key]} busy={busy}
                          onCancel={() => setEditing(null)}
                          onReset={fixed[key] ? async () => {
                            const d = draft()
                            delete d.experience[key]
                            if (await save(d)) setEditing(null)
                          } : null}
                          onSave={async (start, end) => {
                            const d = draft()
                            d.experience[key] = { start_date: start || undefined, end_date: end || undefined }
                            if (await save(d)) setEditing(null)
                          }} />
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}

function DateForm({ role, busy, onCancel, onReset, onSave }) {
  const [start, setStart] = useState(role.start_date || '')
  const [stillThere, setStillThere] = useState(!!role.current || !role.end_date)
  const [end, setEnd] = useState(role.end_date || '')

  return (
    <form onSubmit={(e) => { e.preventDefault(); onSave(start, stillThere ? 'present' : end) }}
          className="mt-3 flex flex-wrap items-end gap-3 rounded-xl bg-white/5 p-3 text-sm">
      <label className="flex flex-col gap-1 text-xs text-dim">
        Started
        <input type="month" value={start} onChange={(e) => setStart(e.target.value)} className={input} />
      </label>
      <label className="flex flex-col gap-1 text-xs text-dim">
        Ended
        <input type="month" value={end} disabled={stillThere} onChange={(e) => setEnd(e.target.value)}
               className={`${input} disabled:opacity-40`} />
      </label>
      <label className="flex items-center gap-2 pb-1.5 text-xs">
        <input type="checkbox" checked={stillThere} onChange={(e) => setStillThere(e.target.checked)} className="accent-marigold" />
        I still work here
      </label>
      <div className="ml-auto flex gap-2">
        {onReset && (
          <button type="button" onClick={onReset} disabled={busy} className="px-2 text-xs text-dim hover:text-ink">
            Use what was read
          </button>
        )}
        <button type="button" onClick={onCancel} className="px-2 text-xs text-dim hover:text-ink">Cancel</button>
        <button disabled={busy || !start || (!stillThere && !end)}
                className="rounded-lg bg-gradient-to-r from-violet to-cyan px-3 py-1.5 text-xs font-medium text-white disabled:opacity-40">
          Save dates
        </button>
      </div>
    </form>
  )
}
