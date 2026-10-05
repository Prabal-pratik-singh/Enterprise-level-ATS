// Tiny fetch helpers — one place for paths, JSON parsing and error handling.
async function request(path, options = {}) {
  const res = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  if (!res.ok) throw new Error(`${options.method || 'GET'} ${path} → HTTP ${res.status}`)
  return res.status === 204 ? null : res.json()
}

export const api = {
  jobs: () => request('/api/jobs'),
  candidates: (jobId, page = 0, size = 100) =>
    request(`/api/jobs/${jobId}/candidates?sort=score&page=${page}&size=${size}`),
  report: (applicationId) => request(`/api/candidates/${applicationId}/report`),
  decide: (applicationId, decision, reason) =>
    request(`/api/candidates/${applicationId}/decision`, {
      method: 'POST',
      body: JSON.stringify({ decision, reason }),
    }),
}
