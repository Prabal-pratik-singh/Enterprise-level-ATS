// Shared by the recruiter's bulk upload and the candidate's own resume upload.

/** PUT the file to the presigned MinIO URL with real upload progress (fetch can't report it). */
export function putWithProgress(url, file, contentType, onProgress) {
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
