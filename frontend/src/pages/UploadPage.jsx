import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { UploadCloud, FileText, CheckCircle2, AlertTriangle, Loader2, X } from 'lucide-react'
import { api } from '../api'
import StatusChip from '../components/StatusChip'
import { explainFailure } from '../lib/failures'
import { pts } from '../lib/fraud'

/**
 * Bulk upload: drop many resumes for one job. Each file gets a placeholder candidate
 * (named after the file), goes straight to MinIO through a presigned link, then the
 * normal pipeline reads it — and the candidate takes the name/email written in the resume.
 */

const MAX_FILES = 50
const MAX_BYTES = 10 * 1024 * 1024            // same 10MB limit the API enforces
const ACCEPT = ['.pdf', '.docx', '.png', '.jpg', '.jpeg']
const PARALLEL = 3                            // uploads at once — gentle on the laptop and the pipeline
const DONE = ['SCORED', 'FLAGGED_FOR_REVIEW', 'PARSE_FAILED', 'SHORTLISTED', 'REJECTED']

const kb = (n) => (n > 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)} MB` : `${Math.ceil(n / 1024)} KB`)

/** PUT the file to the presigned MinIO URL with real upload progress (fetch can't report it). */
function putWithProgress(url, file, contentType, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', url)
    xhr.setRequestHeader('Content-Type', contentType) // must match what the link was signed for
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total)
    xhr.onload = () => (xhr.status < 300 ? resolve() : reject(new Error(`upload failed: HTTP ${xhr.status}`)))
    xhr.onerror = () => reject(new Error('upload failed: network error'))
    xhr.send(file)
  })
}

export default function UploadPage() {
  const [params, setParams] = useSearchParams()
  const jobId = params.get('job')
  const [jobs, setJobs] = useState([])
  const [items, setItems] = useState([])      // one row per file
  const [dragging, setDragging] = useState(false)
  const [notice, setNotice] = useState(null)
  const inputRef = useRef(null)
  const nextId = useRef(1)

  useEffect(() => {
    api.jobs().then((list) => {
      setJobs(list)
      if (!jobId && list.length) setParams({ job: list[0].id })
    }).catch((e) => setNotice(e.message))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const patch = (id, change) => setItems((list) => list.map((it) => (it.id === id ? { ...it, ...change } : it)))

  // ---- adding files: validate in the browser first (the API checks again) ----
  const addFiles = (fileList) => {
    const files = Array.from(fileList)
    const room = MAX_FILES - items.filter((i) => i.state !== 'done' && i.state !== 'error').length
    const rejected = []
    const fresh = []
    for (const file of files) {
      const ext = file.name.slice(file.name.lastIndexOf('.')).toLowerCase()
      if (!ACCEPT.includes(ext)) rejected.push(`${file.name} (not a PDF/DOCX/PNG/JPG)`)
      else if (file.size > MAX_BYTES) rejected.push(`${file.name} (over 10 MB)`)
      else if (fresh.length >= room) rejected.push(`${file.name} (max ${MAX_FILES} at a time)`)
      else fresh.push({ id: nextId.current++, file, jobId, state: 'queued', progress: 0 })
    }
    setNotice(rejected.length ? `Skipped: ${rejected.join(', ')}` : null)
    setItems((list) => [...list, ...fresh])
  }

  // ---- the upload queue: at most PARALLEL files in flight ----
  useEffect(() => {
    const active = items.filter((i) => i.state === 'uploading').length
    const waiting = items.filter((i) => i.state === 'queued').slice(0, PARALLEL - active)
    waiting.forEach((item) => {
      patch(item.id, { state: 'uploading' })
      ;(async () => {
        try {
          const intake = await api.intake(item.jobId, item.file.name)          // placeholder candidate + link
          patch(item.id, { applicationId: intake.applicationId, name: intake.displayName })
          await putWithProgress(intake.upload.url, item.file, intake.upload.contentType,
            (p) => patch(item.id, { progress: p }))
          await api.completeUpload(intake.applicationId, intake.upload.key)     // → resume.uploaded
          patch(item.id, { state: 'processing', progress: 1 })
        } catch (e) {
          patch(item.id, { state: 'error', error: e.message })
        }
      })()
    })
  }, [items])

  // ---- after upload: watch each application until the pipeline has read it ----
  const processingKey = items.filter((i) => i.state === 'processing').map((i) => i.id).join(',')
  useEffect(() => {
    if (!processingKey) return undefined
    const timer = setInterval(() => {
      items.filter((i) => i.state === 'processing').forEach(async (item) => {
        try {
          const report = await api.report(item.applicationId)
          const s = report.summary || {}
          const failure = (report.timeline || []).find((e) => e.event === 'PARSE_FAILED')?.details?.error
          patch(item.id, {
            status: s.status, name: s.name, email: s.email, finalScore: s.finalScore,
            pendingIdentity: (s.email || '').endsWith('@intake.invalid'),
            failure, state: DONE.includes(s.status) ? 'done' : 'processing',
          })
        } catch { /* try again on the next tick */ }
      })
    }, 4000)
    return () => clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [processingKey])

  const counts = items.reduce((c, i) => ({ ...c, [i.state]: (c[i.state] || 0) + 1 }), {})
  const job = jobs.find((j) => j.id === jobId)

  return (
    <div>
      <div className="mb-1 flex items-baseline gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">Upload resumes</h1>
        {items.length > 0 && (
          <span className="text-base text-dim">
            {counts.done || 0} done · {(counts.processing || 0) + (counts.uploading || 0)} in progress
            {counts.error ? ` · ${counts.error} failed` : ''}
          </span>
        )}
      </div>
      <p className="mb-5 max-w-3xl text-sm text-dim">
        Drop a folder&apos;s worth of resumes. No forms to fill: each candidate starts named after the file, and the
        pipeline replaces it with the <span className="text-ink/80">name, email and phone written in the resume</span> —
        then fraud, dedup and scoring run as usual.
      </p>

      {/* which job they apply to */}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <span className="text-xs text-dim">Applying to:</span>
        {jobs.map((j) => (
          <button key={j.id} onClick={() => setParams({ job: j.id })}
                  className={`rounded-full px-3 py-1 text-xs font-medium ring-1 transition ${
                    jobId === j.id
                      ? 'bg-gradient-to-r from-violet/40 to-cyan/25 text-ink ring-violet/50'
                      : 'bg-white/5 text-dim ring-white/10 hover:text-ink'}`}>
            {j.title}
          </button>
        ))}
      </div>

      {/* drop zone */}
      <div onDragOver={(e) => { e.preventDefault(); setDragging(true) }}
           onDragLeave={() => setDragging(false)}
           onDrop={(e) => { e.preventDefault(); setDragging(false); if (jobId) addFiles(e.dataTransfer.files) }}
           onClick={() => jobId && inputRef.current?.click()}
           className={`card mb-4 flex cursor-pointer flex-col items-center gap-2 border-2 border-dashed p-10 text-center transition ${
             dragging ? 'border-cyan/70 bg-cyan/5' : 'border-white/15 hover:border-violet/50'}`}>
        <UploadCloud size={36} className={dragging ? 'text-cyan' : 'text-violet'} />
        <div className="font-medium">Drop resumes here, or click to choose</div>
        <div className="text-xs text-dim">
          PDF, DOCX, PNG or JPG · up to 10 MB each · {MAX_FILES} at a time
          {job && <> · for <span className="text-ink/80">{job.title}</span></>}
        </div>
        <input ref={inputRef} type="file" multiple accept={ACCEPT.join(',')} className="hidden"
               onChange={(e) => { addFiles(e.target.files); e.target.value = '' }} />
      </div>

      {notice && (
        <div className="mb-4 flex items-start gap-2 rounded-xl bg-amber-400/10 p-3 text-xs text-amber-200 ring-1 ring-amber-400/30">
          <AlertTriangle size={14} className="mt-0.5 shrink-0" /> <span className="min-w-0 break-words">{notice}</span>
          <button onClick={() => setNotice(null)} className="ml-auto text-dim hover:text-ink"><X size={14} /></button>
        </div>
      )}

      {/* one row per file */}
      <ul className="space-y-2">
        {items.map((it) => (
          <li key={it.id} className="card flex items-center gap-4 p-3">
            <FileText size={18} className="shrink-0 text-dim" />
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-baseline gap-x-2">
                <span className="truncate text-sm font-medium">
                  {it.name && !it.pendingIdentity && it.state === 'done' ? it.name : it.file.name}
                </span>
                {it.state === 'done' && it.email && !it.pendingIdentity && (
                  <span className="text-xs text-dim">{it.email}</span>
                )}
                {it.state === 'done' && it.pendingIdentity && (
                  <span className="text-xs text-amber-300/80">no email found in the resume</span>
                )}
                <span className="text-[11px] text-dim/60">{kb(it.file.size)}</span>
              </div>

              {it.state === 'uploading' && (
                <div className="mt-1.5 h-1 overflow-hidden rounded-full bg-white/10">
                  <div className="h-full bg-gradient-to-r from-violet to-cyan transition-all" style={{ width: `${Math.round(it.progress * 100)}%` }} />
                </div>
              )}
              {it.state === 'processing' && (
                <div className="mt-1 text-xs text-dim">
                  uploaded · reading the resume{it.status ? ` (${it.status.toLowerCase().replaceAll('_', ' ')})` : ''}…
                </div>
              )}
              {it.state === 'error' && <div className="mt-1 text-xs text-rose-300">{it.error}</div>}
              {it.state === 'done' && it.status === 'PARSE_FAILED' && (
                <div className="mt-1 text-xs text-rose-300/90">{explainFailure(it.failure)}</div>
              )}
            </div>

            <div className="flex shrink-0 items-center gap-3">
              {it.state === 'queued' && <span className="text-xs text-dim">waiting…</span>}
              {(it.state === 'uploading' || it.state === 'processing') && <Loader2 size={16} className="animate-spin text-cyan" />}
              {it.state === 'error' && <AlertTriangle size={16} className="text-rose-400" />}
              {it.state === 'done' && (
                <>
                  <StatusChip status={it.status} />
                  {it.finalScore != null && <span className="w-10 text-right font-semibold tabular-nums text-marigold">{pts(it.finalScore)}</span>}
                  <CheckCircle2 size={16} className={it.status === 'PARSE_FAILED' ? 'text-rose-400' : 'text-emerald-400'} />
                  <Link to={`/candidates?app=${it.applicationId}`} className="text-xs text-cyan hover:underline">open →</Link>
                </>
              )}
            </div>
          </li>
        ))}
      </ul>
      {!items.length && <p className="text-center text-sm text-dim/60">No files yet.</p>}
    </div>
  )
}
