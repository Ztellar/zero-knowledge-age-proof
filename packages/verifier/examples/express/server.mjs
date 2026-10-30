// A minimal site that asks for a proof of age with zkage-verifier.
//
//     ORIGIN=https://your.domain node server.mjs
//
// The app only sends proofs over HTTPS, so ORIGIN must be the public HTTPS address this
// server is reached at (behind your TLS proxy, for example). Sessions live in memory: use
// your own session store in production.

import express from 'express'
import { CHALLENGE_TTL_SECONDS, MAX_BODY_BYTES, newAgeRequest, phoneResponse, verifyAge } from 'zkage-verifier'

const ORIGIN = process.env.ORIGIN ?? 'https://localhost:3000'
const PORT = Number(process.env.PORT ?? 3000)

/** Session id → { challenge, expiresAt, result }. The challenge is dropped once answered. */
const sessions = new Map()

const app = express()

// 1. The page: a fresh challenge and its QR code for every visit.
app.get('/', (req, res) => {
  const id = crypto.randomUUID()
  const request = newAgeRequest({ origin: ORIGIN, url: `${ORIGIN}/age/proof?s=${id}` })
  sessions.set(id, { challenge: request.challenge, expiresAt: request.expiresAt, result: null })
  res.type('html').send(`<!doctype html>
<html lang="en">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Age check</title>
<main style="font: 18px/1.5 system-ui, sans-serif; max-width: 28rem; margin: 2rem auto; padding: 0 1rem">
  <h1>Show that you are over 18</h1>
  <p>Open the age proof app, tap <b>Mostrar mi prueba</b> and scan this code.</p>
  <div style="width: 16rem">${request.qrSvg}</div>
  <p id="status" role="status">Waiting for your phone…</p>
</main>
<script>
  const status = document.getElementById('status')
  const poll = setInterval(async () => {
    const { state } = await (await fetch('/age/status?s=${id}')).json()
    if (state === 'pending') return
    clearInterval(poll)
    status.textContent = state === 'accepted' ? 'Done: you are over 18.' : 'The proof was not accepted.'
  }, 1500)
</script>
</html>`)
})

// 2. Where the phone sends the proof. One answer per challenge, within its lifetime.
app.post('/age/proof', express.raw({ type: 'application/octet-stream', limit: MAX_BODY_BYTES }), async (req, res) => {
  const session = sessions.get(req.query.s)
  if (!session || session.expiresAt < new Date()) return res.status(404).send('unknown or expired session')
  if (!session.challenge) return res.status(409).send('already answered')
  const { challenge } = session
  session.challenge = null // single use, before verifying
  const result = await verifyAge(Buffer.isBuffer(req.body) ? req.body : new Uint8Array(), { origin: ORIGIN, challenge })
  session.result = result
  res.json(phoneResponse(result))
})

// 3. What the page polls.
app.get('/age/status', (req, res) => {
  const session = sessions.get(req.query.s)
  if (!session) return res.status(404).json({ state: 'unknown' })
  if (!session.result) return res.json({ state: 'pending' })
  res.json({ state: session.result.accepted ? 'accepted' : 'rejected' })
})

// Forget sessions after their lifetime.
setInterval(() => {
  const cutoff = Date.now() - CHALLENGE_TTL_SECONDS * 1000
  for (const [id, s] of sessions) if (s.expiresAt.getTime() < cutoff) sessions.delete(id)
}, 60_000).unref()

app.listen(PORT, () => console.log(`Listening on port ${PORT}, as ${ORIGIN}`))
