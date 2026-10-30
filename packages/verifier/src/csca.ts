// Chile's CSCAs, as in prover/verifier/src/chile_csca.rs, which the WebAssembly module
// trusts. Here only to name the CSCA in results; a test checks both lists match.

/** A country signing CA: a readable label and the SHA-256 of its DER SubjectPublicKeyInfo. */
export interface Csca {
  label: string
  sha256: string
}

/** Chile's CSCAs, oldest first. */
export const CHILE_CSCAS: readonly Csca[] = [
  { label: 'CHL 2013 RSA3072', sha256: '7930662130359d43c3322e2094d0ceb6b0396230e5f7f0645e53d9dc7a7e6a8b' },
  { label: 'CHL 2016 RSA3072', sha256: '10558de8430562bb8208fc8bf4f5178b09dc7179902dadadb3e38c9bac310288' },
  { label: 'CHL 2021 RSA3072', sha256: 'a86664745f1cdc695d02a007894a0ce072ff7f6a33bea7969b290b3fc64a7072' },
  { label: 'CHL 2024 RSA4096', sha256: 'a85bea11ec865b6b3a0f53631d62b3ca0e645437605abda0162df111fa9bec5a' },
]
