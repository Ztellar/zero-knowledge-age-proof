//! [`age_verifier::verify`] as a WebAssembly module, for the npm package of decision 0010.
//!
//! It exports plain functions instead of using `wasm-bindgen`, so any WebAssembly host can
//! call it without generated glue. The host:
//!
//! 1. reserves memory with [`alloc`] and writes a request there (layout in [`Request::parse`]);
//! 2. reserves [`RESULT_LEN`] bytes for the result;
//! 3. calls [`verify`], which returns a [`Status`] and fills the result
//!    (layout in [`Verdict::encode`]);
//! 4. frees both with [`dealloc`].
//!
//! The policy is the one of decisions 0009 and 0010: Chile's CSCAs plus any the host adds, an
//! original chip always required, at least 18 years and proofs at most 365 days old. The host
//! can ask for more, never for less.

use age_core::{Claim, Date};
use age_verifier::{verify as verify_presentation, Policy, Presentation, Rejection, CHILE_CSCAS};

/// The youngest age a verifier may ask for.
pub const MIN_AGE: u8 = 18;
/// The oldest proof a verifier may accept, in days.
pub const MAX_PROOF_AGE_DAYS: u32 = 365;
/// Bytes [`verify`] writes to its result buffer.
pub const RESULT_LEN: usize = 38;

/// What [`verify`] returns. The numbers are part of the module's interface: the npm package
/// maps them back to the names in [`Status::code`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u32)]
pub enum Status {
    Accepted = 0,
    Proof = 1,
    UntrustedCsca = 2,
    AgeTooLow = 3,
    FutureProof = 4,
    ExpiredProof = 5,
    ChipNotAuthenticated = 6,
    DeviceKey = 7,
    Signature = 8,
    /// The body does not have the shape of `docs/protocol.md`, section 4.
    MalformedBody = 9,
    /// The request does not have the shape of [`Request::parse`], or its policy asks for less
    /// than [`MIN_AGE`] or more than [`MAX_PROOF_AGE_DAYS`].
    BadRequest = 10,
}

impl Status {
    /// Every status, in numeric order.
    pub const ALL: [Status; 11] = [
        Status::Accepted,
        Status::Proof,
        Status::UntrustedCsca,
        Status::AgeTooLow,
        Status::FutureProof,
        Status::ExpiredProof,
        Status::ChipNotAuthenticated,
        Status::DeviceKey,
        Status::Signature,
        Status::MalformedBody,
        Status::BadRequest,
    ];

    /// The reason codes of `docs/protocol.md`, section 5, plus `accepted`, `malformed_body`
    /// and `bad_request`.
    pub fn code(self) -> &'static str {
        match self {
            Status::Accepted => "accepted",
            Status::Proof => Rejection::Proof.code(),
            Status::UntrustedCsca => Rejection::UntrustedCsca.code(),
            Status::AgeTooLow => Rejection::AgeTooLow.code(),
            Status::FutureProof => Rejection::FutureProof.code(),
            Status::ExpiredProof => Rejection::ExpiredProof.code(),
            Status::ChipNotAuthenticated => Rejection::ChipNotAuthenticated.code(),
            Status::DeviceKey => Rejection::DeviceKey.code(),
            Status::Signature => Rejection::Signature.code(),
            Status::MalformedBody => "malformed_body",
            Status::BadRequest => "bad_request",
        }
    }
}

impl From<Rejection> for Status {
    fn from(rejection: Rejection) -> Self {
        match rejection {
            Rejection::Proof => Status::Proof,
            Rejection::UntrustedCsca => Status::UntrustedCsca,
            Rejection::AgeTooLow => Status::AgeTooLow,
            Rejection::FutureProof => Status::FutureProof,
            Rejection::ExpiredProof => Status::ExpiredProof,
            Rejection::ChipNotAuthenticated => Status::ChipNotAuthenticated,
            Rejection::DeviceKey => Status::DeviceKey,
            Rejection::Signature => Status::Signature,
        }
    }
}

/// What the host asks to check.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Request<'a> {
    /// The body exactly as the phone sent it.
    pub body: &'a [u8],
    /// The origin the verifier put in its QR code, byte for byte.
    pub origin: &'a str,
    pub challenge: [u8; 32],
    /// The verifier's date, in UTC.
    pub today: Date,
    pub min_age: u8,
    pub max_proof_age_days: u32,
    /// SHA-256 of CSCA SubjectPublicKeyInfos trusted besides Chile's.
    pub extra_cscas: Vec<[u8; 32]>,
}

impl<'a> Request<'a> {
    /// Parses a request. Integers are little-endian:
    ///
    /// ```text
    /// u32 body length | body
    /// u32 origin length | origin (UTF-8)
    /// challenge (32 bytes)
    /// year (u16) | month (u8) | day (u8)
    /// min_age (u8)
    /// max_proof_age_days (u32)
    /// u32 count | count × CSCA SHA-256 (32 bytes each)
    /// ```
    ///
    /// None if it has another shape or anything after the last field.
    pub fn parse(bytes: &'a [u8]) -> Option<Self> {
        let mut r = Reader(bytes);
        let body_len = r.u32()? as usize;
        let body = r.take(body_len)?;
        let origin_len = r.u32()? as usize;
        let origin = std::str::from_utf8(r.take(origin_len)?).ok()?;
        let challenge = r.array()?;
        let year = r.u16()?;
        let [month, day] = r.array()?;
        let [min_age] = r.array()?;
        let max_proof_age_days = r.u32()?;
        let count = r.u32()? as usize;
        let extra_cscas = (0..count).map(|_| r.array()).collect::<Option<Vec<_>>>()?;
        r.0.is_empty().then_some(Request {
            body,
            origin,
            challenge,
            today: Date { year, month, day },
            min_age,
            max_proof_age_days,
            extra_cscas,
        })
    }

    /// The bytes [`Self::parse`] reads; for tests and other Rust hosts.
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let len = |n: usize| u32::try_from(n).expect("field longer than 4 GiB").to_le_bytes();
        out.extend_from_slice(&len(self.body.len()));
        out.extend_from_slice(self.body);
        out.extend_from_slice(&len(self.origin.len()));
        out.extend_from_slice(self.origin.as_bytes());
        out.extend_from_slice(&self.challenge);
        out.extend_from_slice(&self.today.year.to_le_bytes());
        out.extend_from_slice(&[self.today.month, self.today.day, self.min_age]);
        out.extend_from_slice(&self.max_proof_age_days.to_le_bytes());
        out.extend_from_slice(&len(self.extra_cscas.len()));
        for hash in &self.extra_cscas {
            out.extend_from_slice(hash);
        }
        out
    }
}

struct Reader<'a>(&'a [u8]);

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        let (head, tail) = self.0.split_at_checked(n)?;
        self.0 = tail;
        Some(head)
    }

    fn array<const N: usize>(&mut self) -> Option<[u8; N]> {
        self.take(N)?.try_into().ok()
    }

    fn u16(&mut self) -> Option<u16> {
        self.array().map(u16::from_le_bytes)
    }

    fn u32(&mut self) -> Option<u32> {
        self.array().map(u32::from_le_bytes)
    }
}

/// The outcome of one check.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Verdict {
    pub status: Status,
    /// The proven claim, only when accepted.
    pub claim: Option<Claim>,
}

impl Verdict {
    /// [`RESULT_LEN`] bytes, zero when not accepted:
    ///
    /// ```text
    /// min_age (u8) | year (u16 LE) | month (u8) | day (u8) | chip_authenticated (u8)
    /// | CSCA SHA-256 (32 bytes)
    /// ```
    pub fn encode(&self) -> [u8; RESULT_LEN] {
        let mut out = [0; RESULT_LEN];
        if let Some(claim) = &self.claim {
            out[0] = claim.min_age;
            out[1..3].copy_from_slice(&claim.today.year.to_le_bytes());
            out[3] = claim.today.month;
            out[4] = claim.today.day;
            out[5] = u8::from(claim.chip_authenticated);
            out[6..].copy_from_slice(&claim.csca_sha256);
        }
        out
    }
}

/// Checks a request. The Rust-side entry point; [`verify`] wraps it for WebAssembly.
pub fn check(request: &[u8]) -> Verdict {
    let rejected = |status| Verdict { status, claim: None };
    let Some(request) = Request::parse(request) else {
        return rejected(Status::BadRequest);
    };
    if request.min_age < MIN_AGE || request.max_proof_age_days > MAX_PROOF_AGE_DAYS {
        return rejected(Status::BadRequest);
    }
    let Some(presentation) = Presentation::from_body(request.body) else {
        return rejected(Status::MalformedBody);
    };
    let policy = Policy {
        trusted_cscas: CHILE_CSCAS.iter().map(|(_, hash)| *hash).chain(request.extra_cscas).collect(),
        min_age: request.min_age,
        max_proof_age_days: request.max_proof_age_days,
        require_chip_authentication: true,
    };
    match verify_presentation(&presentation, request.origin, &request.challenge, request.today, &policy) {
        Ok(claim) => Verdict { status: Status::Accepted, claim: Some(claim) },
        Err(rejection) => rejected(rejection.into()),
    }
}

/// Reserves `len` bytes in the module's memory for the host to write into.
#[no_mangle]
pub extern "C" fn alloc(len: usize) -> *mut u8 {
    let mut buffer = Vec::<u8>::with_capacity(len);
    let ptr = buffer.as_mut_ptr();
    std::mem::forget(buffer);
    ptr
}

/// Frees memory from [`alloc`].
///
/// # Safety
///
/// `ptr` and `len` must come from one [`alloc`] call, and be freed once.
#[no_mangle]
pub unsafe extern "C" fn dealloc(ptr: *mut u8, len: usize) {
    drop(Vec::from_raw_parts(ptr, 0, len));
}

/// Checks the request at `request` and writes [`RESULT_LEN`] bytes to `result`. Returns the
/// [`Status`] as a number.
///
/// # Safety
///
/// `request` must point to `request_len` readable bytes and `result` to [`RESULT_LEN`]
/// writable ones, both from [`alloc`].
#[no_mangle]
pub unsafe extern "C" fn verify(request: *const u8, request_len: usize, result: *mut u8) -> u32 {
    let verdict = check(std::slice::from_raw_parts(request, request_len));
    std::ptr::copy_nonoverlapping(verdict.encode().as_ptr(), result, RESULT_LEN);
    verdict.status as u32
}

/// getrandom's source on wasm32-unknown-unknown (see `prover/.cargo/config.toml`). Verifying
/// needs no randomness, so anything asking for it fails.
#[cfg(target_arch = "wasm32")]
#[no_mangle]
unsafe extern "Rust" fn __getrandom_v03_custom(_dest: *mut u8, _len: usize) -> Result<(), getrandom::Error> {
    Err(getrandom::Error::UNSUPPORTED)
}
