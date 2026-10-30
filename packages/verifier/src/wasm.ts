// Loads prover/verifier-wasm, the Rust verifier compiled to WebAssembly, and calls it.
// The module imports nothing; its interface is documented in prover/verifier-wasm/src/lib.rs.

interface Exports {
  memory: WebAssembly.Memory
  alloc(len: number): number
  dealloc(ptr: number, len: number): void
  verify(request: number, requestLen: number, result: number): number
}

/** Bytes the module writes to its result buffer (`RESULT_LEN` in Rust). */
export const RESULT_LEN = 38

let loading: Promise<Exports> | undefined

/**
 * Loads the WebAssembly module. Optional on Node, where `verifyAge` loads the copy shipped in
 * the package on first use. Elsewhere, pass the module or its bytes (`age_verifier.wasm`).
 */
export function initVerifier(source?: WebAssembly.Module | BufferSource): Promise<void> {
  loading ??= instantiate(source)
  return loading.then(() => undefined)
}

async function instantiate(source?: WebAssembly.Module | BufferSource): Promise<Exports> {
  if (source === undefined) {
    const { readFile } = await import('node:fs/promises')
    source = await readFile(new URL('./age_verifier.wasm', import.meta.url))
  }
  const module = source instanceof WebAssembly.Module ? source : await WebAssembly.compile(source)
  const instance = await WebAssembly.instantiate(module, {})
  return instance.exports as unknown as Exports
}

/** Runs the module's `verify` on an encoded request; returns its status and result bytes. */
export async function runVerify(request: Uint8Array): Promise<{ status: number; result: Uint8Array }> {
  loading ??= instantiate()
  const wasm = await loading
  const requestPtr = wasm.alloc(request.length)
  const resultPtr = wasm.alloc(RESULT_LEN)
  try {
    new Uint8Array(wasm.memory.buffer, requestPtr, request.length).set(request)
    const status = wasm.verify(requestPtr, request.length, resultPtr)
    // Copy out: the view is over memory the module may grow or reuse.
    const result = new Uint8Array(wasm.memory.buffer, resultPtr, RESULT_LEN).slice()
    return { status, result }
  } finally {
    wasm.dealloc(requestPtr, request.length)
    wasm.dealloc(resultPtr, RESULT_LEN)
  }
}
