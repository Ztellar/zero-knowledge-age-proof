// Verify the age proofs of zero-knowledge-age-proof from your own server: decision 0010 and
// the protocol of docs/protocol.md, version 2.

import { renderSVG } from 'uqr'
import { CHILE_CSCAS, type Csca } from './csca.js'
import { RESULT_LEN, initVerifier, runVerify } from './wasm.js'

export { CHILE_CSCAS, type Csca, initVerifier }

/** The youngest age a verifier may ask for. */
export const MIN_AGE = 18
/** The oldest proof a verifier may accept, in days. */
export const MAX_PROOF_AGE_DAYS = 365
/** How long a challenge may stay valid, in seconds (docs/protocol.md, section 1). */
export const CHALLENGE_TTL_SECONDS = 300
/** The largest body to accept from a phone. A real one is about 229 KB. */
export const MAX_BODY_BYTES = 1 << 20

/** Why a presentation was rejected (docs/protocol.md, section 5), or `malformed_body`. */
export type RejectionReason =
  | 'proof'
  | 'untrusted_csca'
  | 'age_too_low'
  | 'future_proof'
  | 'expired_proof'
  | 'chip_not_authenticated'
  | 'device_key'
  | 'signature'
  | 'malformed_body'

// The module's status numbers, in order (Status in prover/verifier-wasm/src/lib.rs).
const STATUS = [
  'accepted',
  'proof',
  'untrusted_csca',
  'age_too_low',
  'future_proof',
  'expired_proof',
  'chip_not_authenticated',
  'device_key',
  'signature',
  'malformed_body',
  'bad_request',
] as const

export interface AgeRequestOptions {
  /** Your site's origin, exactly as browsers write it: `https://shop.example`. */
  origin: string
  /** Where the phone will POST the proof. Must start with `origin` + `/`. */
  url: string
}

export interface AgeRequest {
  /** 64 lowercase hex characters. Store it, accept one answer for it, drop it at `expiresAt`. */
  challenge: string
  /** The `zkage://present` link the QR code carries. */
  link: string
  /** The QR code for `link`, as an SVG document. */
  qrSvg: string
  expiresAt: Date
}

/** Creates a challenge and the QR code that asks the app for a proof of age. */
export function newAgeRequest(options: AgeRequestOptions): AgeRequest {
  const origin = checkOrigin(options.origin)
  const url = checkUrl(options.url, origin)
  const challenge = toHex(crypto.getRandomValues(new Uint8Array(32)))
  const link =
    `zkage://present?v=2&origin=${encodeURIComponent(origin)}` +
    `&url=${encodeURIComponent(url)}&challenge=${challenge}`
  return {
    challenge,
    link,
    qrSvg: renderSVG(link),
    expiresAt: new Date(Date.now() + CHALLENGE_TTL_SECONDS * 1000),
  }
}

export interface VerifyOptions {
  /** The same origin you passed to `newAgeRequest`. */
  origin: string
  /** The challenge you stored for this request. */
  challenge: string
  /** At least 18. Defaults to 18. */
  minAge?: number
  /** At most 365. Defaults to 365. */
  maxProofAgeDays?: number
  /** SHA-256 (hex) of CSCA public keys to trust besides Chile's. */
  extraCscas?: readonly string[]
  /** The date to check against, in UTC. Defaults to now. */
  now?: Date
}

export type AgeResult =
  | {
      accepted: true
      /** The age the proof shows the person has reached, at least `minAge`. */
      minAge: number
      /** The day the phone made the proof, `YYYY-MM-DD`. */
      provedOn: string
      /** Always true: a proof without the ID card's chip is rejected. */
      chipAuthenticated: true
      /** The state key that signed the ID card. `label` is set for known ones. */
      csca: { sha256: string; label?: string }
    }
  | { accepted: false; reason: RejectionReason }

/**
 * Verifies the body a phone POSTed to your `url`. It tells you only whether the person is at
 * least `minAge`: the proof holds no name, ID number, date of birth or photo.
 *
 * Throws `TypeError` for invalid options, not for an invalid proof.
 */
export async function verifyAge(body: Uint8Array, options: VerifyOptions): Promise<AgeResult> {
  const origin = checkOrigin(options.origin)
  const challenge = fromHex(options.challenge, 32, 'challenge')
  const minAge = options.minAge ?? MIN_AGE
  if (!Number.isInteger(minAge) || minAge < MIN_AGE || minAge > 255) {
    throw new TypeError(`minAge must be an integer from ${MIN_AGE} to 255`)
  }
  const maxDays = options.maxProofAgeDays ?? MAX_PROOF_AGE_DAYS
  if (!Number.isInteger(maxDays) || maxDays < 0 || maxDays > MAX_PROOF_AGE_DAYS) {
    throw new TypeError(`maxProofAgeDays must be an integer from 0 to ${MAX_PROOF_AGE_DAYS}`)
  }
  const extra = (options.extraCscas ?? []).map((hash) => fromHex(hash, 32, 'extraCscas'))
  const now = options.now ?? new Date()
  if (body.length > MAX_BODY_BYTES) return { accepted: false, reason: 'malformed_body' }

  const { status, result } = await runVerify(
    encodeRequest(body, origin, challenge, now, minAge, maxDays, extra),
  )
  const name = STATUS[status]
  if (name === undefined || name === 'bad_request') {
    throw new Error(`verifier module answered status ${status}`)
  }
  if (name !== 'accepted') return { accepted: false, reason: name }
  return decodeResult(result)
}

/**
 * The JSON to send back to the phone (docs/protocol.md, section 6). Answer it with status 200;
 * the app shows the person whether you accepted.
 */
export function phoneResponse(result: AgeResult): Record<string, unknown> {
  if (!result.accepted) return { state: 'rejected', reason: result.reason }
  return {
    state: 'accepted',
    min_age: result.minAge,
    proved_on: result.provedOn,
    csca: result.csca.label ?? result.csca.sha256,
    chip_authenticated: result.chipAuthenticated,
  }
}

function checkOrigin(origin: string): string {
  let parsed: URL
  try {
    parsed = new URL(origin)
  } catch {
    throw new TypeError(`origin is not a URL: ${origin}`)
  }
  // Signed byte for byte, so only the form browsers produce is accepted.
  if (parsed.protocol !== 'https:' || parsed.origin !== origin) {
    throw new TypeError(`origin must be https://host[:port] with nothing else, like ${parsed.origin}`)
  }
  return origin
}

function checkUrl(url: string, origin: string): string {
  if (!url.startsWith(origin + '/')) throw new TypeError(`url must start with ${origin}/`)
  try {
    new URL(url)
  } catch {
    throw new TypeError(`url is not a URL: ${url}`)
  }
  return url
}

// The module's request layout (Request::parse in prover/verifier-wasm/src/lib.rs).
function encodeRequest(
  body: Uint8Array,
  origin: string,
  challenge: Uint8Array,
  now: Date,
  minAge: number,
  maxDays: number,
  extraCscas: Uint8Array[],
): Uint8Array {
  const originBytes = new TextEncoder().encode(origin)
  const out = new Uint8Array(4 + body.length + 4 + originBytes.length + 32 + 4 + 1 + 4 + 4 + 32 * extraCscas.length)
  const view = new DataView(out.buffer)
  let at = 0
  const bytes = (b: Uint8Array) => {
    out.set(b, at)
    at += b.length
  }
  const u32 = (n: number) => {
    view.setUint32(at, n, true)
    at += 4
  }
  u32(body.length)
  bytes(body)
  u32(originBytes.length)
  bytes(originBytes)
  bytes(challenge)
  view.setUint16(at, now.getUTCFullYear(), true)
  at += 2
  bytes(new Uint8Array([now.getUTCMonth() + 1, now.getUTCDate(), minAge]))
  u32(maxDays)
  u32(extraCscas.length)
  extraCscas.forEach(bytes)
  return out
}

function decodeResult(result: Uint8Array): AgeResult {
  if (result.length !== RESULT_LEN) throw new Error('verifier module result has the wrong size')
  const view = new DataView(result.buffer, result.byteOffset, result.length)
  const year = view.getUint16(1, true)
  const pad = (n: number) => String(n).padStart(2, '0')
  const sha256 = toHex(result.subarray(6))
  const label = CHILE_CSCAS.find((c) => c.sha256 === sha256)?.label
  return {
    accepted: true,
    minAge: result[0],
    provedOn: `${year}-${pad(result[3])}-${pad(result[4])}`,
    chipAuthenticated: true,
    csca: label === undefined ? { sha256 } : { sha256, label },
  }
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

function fromHex(hex: string, length: number, name: string): Uint8Array {
  if (typeof hex !== 'string' || !new RegExp(`^[0-9a-f]{${2 * length}}$`).test(hex)) {
    throw new TypeError(`${name} must be ${2 * length} lowercase hex characters`)
  }
  return Uint8Array.from({ length }, (_, i) => parseInt(hex.slice(2 * i, 2 * i + 2), 16))
}
