import { useCallback, useEffect, useRef, useState } from 'react'
import { UploadCloud, FileText, Loader2, CheckCircle2, AlertTriangle, Circle } from 'lucide-react'
import { api } from '../api'
import { openEvents } from '../sse'
import { putWithProgress } from '../lib/upload'
import ProfileEditor from './ProfileEditor'

const ACCEPT = ['.pdf', '.docx', '.png', '.jpg', '.jpeg']
const MAX_BYTES = 10 * 1024 * 1024   // the API checks the same 10 MB limit

/**
 * "My Resume" (C2): upload a resume to your own profile, watch it being read live, and correct what
 * the reader got wrong. The page never polls — the API pushes the whole profile whenever it changes.
 */
export default function ResumePage() {
  const [view, setView] = useState(null)        // the API's ProfileView
  const [versions, setVersions] = useState([])
  const [error, setError] = useState(null)
  const [upload, setUpload] = useState(null)    // { name, progress, failed }

  const load = useCallback(() => {
    api.myProfile().then(setView).catch((e) => setError(e.message))
  }, [])
  useEffect(load, [load])

  // live: one "profile" event with the whole view whenever the pipeline moves
  useEffect(() => openEvents('/api/candidate/profile/events', (type, data) => {
    if (type === 'profile') setView(JSON.parse(data))
  }), [])

  // the version list follows the newest upload's status
  const latest = view?.latestResume
  useEffect(() => {
    api.profileVersions().then(setVersions).catch(() => {})
  }, [latest?.id, latest?.status])

  const onFile = async (file) => {
    const ext = file.name.slice(file.name.lastIndexOf('.')).toLowerCase()
    if (!ACCEPT.includes(ext)) return setUpload({ name: file.name, failed: 'Only PDF, DOCX, PNG or JPG files.' })
    if (file.size > MAX_BYTES) return setUpload({ name: file.name, failed: 'That file is over 10 MB.' })
    setUpload({ name: file.name, progress: 0 })
    try {
      const link = await api.profileUploadUrl(file.name)
      await putWithProgress(link.url, file, link.contentType, (p) => setUpload({ name: file.name, progress: p }))
      await api.completeProfileUpload(link.key)
      setUpload(null)
      load()
    } catch (e) {
      setUpload({ name: file.name, failed: e.message })
    }
  }

  if (error) return <p className="text-sm text-rose-400">{error}</p>
  if (!view) return <p className="text-sm text-dim">Loading your profile…</p>

  const shownVersion = versions.find((v) => v.status === 'EXTRACTED')?.version

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">My resume</h1>
        <p className="mt-1 text-sm text-dim">Upload it once — we read it into a profile you can correct.</p>
      </div>

      <UploadCard upload={upload} onFile={onFile} />
      {latest && <PipelineSteps latest={latest} embedded={view.embedded} shownVersion={shownVersion}
                                hasProfile={!!view.profile} />}

      {view.profile
        ? <ProfileEditor view={view} onSaved={setView} />
        : !latest && <p className="card p-5 text-sm text-dim !rounded-2xl">No resume yet — upload one to build your profile.</p>}

      {versions.length > 0 && <Versions versions={versions} />}
    </div>
  )
}

function UploadCard({ upload, onFile }) {
  const input = useRef(null)
  const [dragging, setDragging] = useState(false)
  const busy = upload && !upload.failed

  return (
    <div onDragOver={(e) => { e.preventDefault(); setDragging(true) }}
         onDragLeave={() => setDragging(false)}
         onDrop={(e) => { e.preventDefault(); setDragging(false); if (e.dataTransfer.files[0]) onFile(e.dataTransfer.files[0]) }}
         className={`card flex flex-wrap items-center gap-4 p-5 !rounded-2xl ${dragging ? 'ring-1 ring-cyan/60' : ''}`}>
      <UploadCloud size={26} className="shrink-0 text-cyan" />
      <div className="min-w-0 flex-1 text-sm">
        {busy ? (
          <>
            <div className="truncate font-medium">Uploading {upload.name}…</div>
            <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-white/10">
              <div className="h-full rounded-full bg-gradient-to-r from-violet to-cyan transition-all"
                   style={{ width: `${Math.round((upload.progress || 0) * 100)}%` }} />
            </div>
          </>
        ) : (
          <>
            <div className="font-medium">Drop your resume here</div>
            <div className="text-xs text-dim">PDF, DOCX, PNG or JPG · up to 10 MB · a new upload replaces what we read, never your fixes</div>
            {upload?.failed && <div className="mt-1 text-xs text-rose-400">{upload.name}: {upload.failed}</div>}
          </>
        )}
      </div>
      <input ref={input} type="file" accept={ACCEPT.join(',')} className="hidden"
             onChange={(e) => { if (e.target.files[0]) onFile(e.target.files[0]); e.target.value = '' }} />
      <button onClick={() => input.current.click()} disabled={busy}
              className="rounded-xl bg-gradient-to-r from-violet to-cyan px-4 py-2 text-sm font-medium text-white shadow-lg shadow-violet/30 disabled:opacity-40">
        Choose a file
      </button>
    </div>
  )
}

// Uploaded → Read → Profile built → Searchable, for the newest upload — pushed live by the API
function PipelineSteps({ latest, embedded, shownVersion, hasProfile }) {
  const failed = latest.status === 'PARSE_FAILED' || latest.status === 'EXTRACT_FAILED'
  if (failed) {
    return (
      <div className="card flex items-start gap-3 p-4 ring-1 ring-rose-400/30 !rounded-2xl">
        <AlertTriangle size={18} className="mt-0.5 shrink-0 text-rose-300" />
        <div className="text-sm">
          <p className="font-medium">Version {latest.version} didn't work</p>
          <p className="text-dim">{latest.error}</p>
          {hasProfile && shownVersion && <p className="mt-1 text-xs text-dim">Your profile still shows version {shownVersion}.</p>}
        </div>
      </div>
    )
  }
  const reached = { UPLOADED: 1, PARSED: 2, EXTRACTED: 3 }[latest.status] + (latest.status === 'EXTRACTED' && embedded ? 1 : 0)
  const steps = ['Uploaded', 'Read', 'Profile built', 'Searchable']
  return (
    <div className="card flex flex-wrap items-center gap-x-5 gap-y-2 p-4 text-sm !rounded-2xl">
      <span className="text-dim">Version {latest.version}:</span>
      {steps.map((label, i) => {
        const done = i < reached
        const now = i === reached
        return (
          <span key={label} className={`flex items-center gap-1.5 ${done ? 'text-ink' : 'text-dim/60'}`}>
            {done ? <CheckCircle2 size={15} className="text-emerald-300" />
              : now ? <Loader2 size={15} className="animate-spin text-cyan" /> : <Circle size={15} />}
            {label}
          </span>
        )
      })}
    </div>
  )
}

const VERSION_LABELS = {
  UPLOADED: 'Waiting', PARSED: 'Being read', EXTRACTED: 'Read',
  PARSE_FAILED: "Couldn't read", EXTRACT_FAILED: "Couldn't build a profile",
}

function Versions({ versions }) {
  return (
    <section className="card p-5 !rounded-2xl">
      <h2 className="mb-2 font-medium">Versions</h2>
      <ul className="divide-y divide-white/10 text-sm">
        {versions.map((v) => (
          <li key={v.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2">
            <FileText size={14} className="text-dim" />
            <span className="font-medium">v{v.version}</span>
            <span className="text-xs text-dim">{new Date(v.uploadedAt).toLocaleString()}</span>
            <span className={`ml-auto text-xs ${v.status.endsWith('FAILED') ? 'text-rose-300' : 'text-dim'}`}>
              {VERSION_LABELS[v.status] || v.status}
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}
