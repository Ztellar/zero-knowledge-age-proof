// The expected results of docs/protocol.md, section 7, through the package's API.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import {
  CHILE_CSCAS,
  MAX_BODY_BYTES,
  newAgeRequest,
  phoneResponse,
  verifyAge,
} from '../dist/index.js'

const fixtures = new URL('../../../prover/fixtures/', import.meta.url)
const body = readFileSync(new URL('vectors/presentation-body.bin', fixtures))
const testCsca = createHash('sha256').update(readFileSync(new URL('out/csca_spki.der', fixtures))).digest('hex')

const vector = {
  origin: 'https://verifier.example',
  challenge: 'c4'.repeat(32),
  extraCscas: [testCsca],
  now: new Date('2026-10-22T12:00:00Z'),
}

test('accepts the vector', async () => {
  const result = await verifyAge(body, vector)
  assert.deepEqual(result, {
    accepted: true,
    minAge: 18,
    provedOn: '2026-10-22',
    chipAuthenticated: true,
    csca: { sha256: testCsca },
  })
  assert.deepEqual(phoneResponse(result), {
    state: 'accepted',
    min_age: 18,
    proved_on: '2026-10-22',
    csca: testCsca,
    chip_authenticated: true,
  })
})

test('expected results of the protocol', async () => {
  const reason = async (changes, data = body) => {
    const result = await verifyAge(data, { ...vector, ...changes })
    return result.accepted ? 'accepted' : result.reason
  }
  assert.equal(await reason({ now: new Date('2027-10-22T23:59:59Z') }), 'accepted')
  assert.equal(await reason({ now: new Date('2027-10-23T00:00:00Z') }), 'expired_proof')
  assert.equal(await reason({ now: new Date('2026-10-21T23:59:59Z') }), 'future_proof')
  assert.equal(await reason({ minAge: 21 }), 'age_too_low')
  assert.equal(await reason({ extraCscas: [] }), 'untrusted_csca')
  assert.equal(await reason({ origin: 'https://evil.example' }), 'signature')
  assert.equal(await reason({ challenge: '00'.repeat(32) }), 'signature')

  const tampered = Buffer.from(body)
  tampered[4 + body.readUInt32LE(0) / 2] ^= 1
  assert.equal(await reason({}, tampered), 'proof')
  assert.equal(await reason({}, body.subarray(0, body.length - 1)), 'malformed_body')
  assert.equal(await reason({}, new Uint8Array(MAX_BODY_BYTES + 1)), 'malformed_body')
  assert.deepEqual(phoneResponse({ accepted: false, reason: 'signature' }), { state: 'rejected', reason: 'signature' })
})

test('the dates are UTC', async () => {
  // 2026-10-21 at 23:30 in Chile (UTC-3) is already the 22nd in UTC.
  const result = await verifyAge(body, { ...vector, now: new Date('2026-10-21T23:30:00-03:00') })
  assert.equal(result.accepted, true)
})

test('refuses options that ask for less than the decisions allow', async () => {
  await assert.rejects(verifyAge(body, { ...vector, minAge: 17 }), TypeError)
  await assert.rejects(verifyAge(body, { ...vector, maxProofAgeDays: 366 }), TypeError)
  await assert.rejects(verifyAge(body, { ...vector, challenge: 'C4'.repeat(32) }), TypeError)
  await assert.rejects(verifyAge(body, { ...vector, extraCscas: ['xyz'] }), TypeError)
  await assert.rejects(verifyAge(body, { ...vector, origin: 'http://verifier.example' }), TypeError)
  // A shorter window is allowed.
  assert.equal((await verifyAge(body, { ...vector, maxProofAgeDays: 0 })).accepted, true)
})

test('origins must be written as browsers write them', () => {
  const ok = { origin: 'https://shop.example', url: 'https://shop.example/age/proof' }
  for (const origin of ['https://shop.example/', 'https://Shop.example', 'https://shop.example:443', 'https://u@shop.example', 'http://shop.example']) {
    assert.throws(() => newAgeRequest({ ...ok, origin }), TypeError, origin)
  }
  assert.throws(() => newAgeRequest({ ...ok, url: 'https://shop.example.evil.com/proof' }), TypeError)
  assert.throws(() => newAgeRequest({ ...ok, url: 'https://shop.example' }), TypeError)
  assert.doesNotThrow(() => newAgeRequest({ origin: 'https://shop.example:8443', url: 'https://shop.example:8443/p?s=1' }))
})

test('the link follows docs/protocol.md, section 1', () => {
  const request = newAgeRequest({ origin: 'https://shop.example', url: 'https://shop.example/age/proof?s=8f2a' })
  assert.match(request.challenge, /^[0-9a-f]{64}$/)
  const link = new URL(request.link)
  assert.equal(link.protocol, 'zkage:')
  assert.equal(link.host, 'present')
  assert.deepEqual(Object.fromEntries(link.searchParams), {
    v: '2',
    origin: 'https://shop.example',
    url: 'https://shop.example/age/proof?s=8f2a',
    challenge: request.challenge,
  })
  assert.match(request.qrSvg, /^<svg[\s\S]*<\/svg>$/)
  const ttl = request.expiresAt.getTime() - Date.now()
  assert.ok(ttl > 299_000 && ttl <= 300_000, String(ttl))
  assert.notEqual(newAgeRequest({ origin: 'https://shop.example', url: 'https://shop.example/a' }).challenge, request.challenge)
})

test('the CSCA list matches the one in the WebAssembly module', () => {
  const rust = readFileSync(new URL('../../../prover/verifier/src/chile_csca.rs', import.meta.url), 'utf8')
  const rows = [...rust.matchAll(/\("([^"]+)", \[([^\]]+)\]\)/g)].map(([, label, bytes]) => ({
    label,
    sha256: bytes.split(',').map((b) => Number(b.trim()).toString(16).padStart(2, '0')).join(''),
  }))
  assert.equal(rows.length, 4)
  assert.deepEqual(CHILE_CSCAS, rows)
})
