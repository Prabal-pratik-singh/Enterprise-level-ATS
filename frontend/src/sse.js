// Live updates (SSE). The browser's EventSource cannot send an Authorization header, so the stream is
// read through fetch instead: same events, same reconnect after 3 s, plus the sign-in token.
import { authFetch } from './auth'

/** Calls onEvent(type, data) for every event; returns a function that closes the stream. */
export function openEvents(path, onEvent) {
  const controller = new AbortController()
  let stopped = false

  const run = async () => {
    while (!stopped) {
      try {
        const res = await authFetch(path, { signal: controller.signal, headers: { Accept: 'text/event-stream' } })
        if (res.ok && res.body) {
          const reader = res.body.pipeThrough(new TextDecoderStream()).getReader()
          let buffer = ''
          for (;;) {
            const { value, done } = await reader.read()
            if (done) break
            buffer += value
            let end
            while ((end = buffer.indexOf('\n\n')) >= 0) { // a blank line ends one event
              dispatch(buffer.slice(0, end), onEvent)
              buffer = buffer.slice(end + 2)
            }
          }
        }
      } catch {
        // closed by us, or the connection dropped — reconnect below unless closed
      }
      if (!stopped) await new Promise((resolve) => setTimeout(resolve, 3000))
    }
  }
  run()

  return () => {
    stopped = true
    controller.abort()
  }
}

// "event:candidate\ndata:{...}" → onEvent('candidate', '{...}'); ":heartbeat" comments carry no data
function dispatch(block, onEvent) {
  let type = 'message'
  const data = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) type = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
  }
  if (data.length) onEvent(type, data.join('\n'))
}
