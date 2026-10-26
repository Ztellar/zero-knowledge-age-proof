//! Verification of a Chilean ID card chip and the age check.
//!
//! The same code runs natively (tests, Android) and inside the RISC Zero guest, where
//! running it is what gets proven. It checks, in order:
//!
//! 1. the DSC certificate is signed by the CSCA key (RSA, SHA-256);
//! 2. the SOD signed attributes are signed by the DSC;
//! 3. the SOD `messageDigest` attribute matches the LDSSecurityObject it carries;
//! 4. the DG1 hash in the LDSSecurityObject matches DG1;
//! 5. the holder is at least `min_age` years old on `today`, and the card is not expired;
//! 6. if the chip answered Active Authentication: DG15 matches its hash in the
//!    LDSSecurityObject, and the chip's P-256 key in DG15 signed the first 8 bytes of the
//!    device key hash (decision 0006). That shows the chip itself was read for this phone,
//!    not a copy of its files.
//!
//! The claim also carries the hash of the phone's ML-DSA public key, unchecked: it only binds
//! the proof to that key, so later presentations can be signed with it (decision 0004).
//!
//! Parsing uses [`der_walk`], a zero-copy reader, because full ASN.1 decoding cost more zkVM
//! cycles than both RSA signatures together.

#![no_std]

extern crate alloc;

pub mod der_walk;

use alloc::vec::Vec;

use der_walk::{children, expect, Tlv, BIT_STRING, CONTEXT_0, INTEGER, OCTET_STRING, OID, SEQUENCE, SET};
use p256::ecdsa::signature::Verifier;
use rsa::pkcs1v15::Pkcs1v15Sign;
use rsa::{BigUint, RsaPublicKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

// OID contents (DER value bytes, without tag and length).
const OID_SHA256: &[u8] = &[0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01];
const OID_RSA_ENCRYPTION: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01];
const OID_SHA256_WITH_RSA: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0b];
const OID_MESSAGE_DIGEST: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x09, 0x04];
/// Application tag of the EF.SOD file on the chip.
const EF_SOD: u8 = 0x77;
const OID_LDS_SECURITY_OBJECT: &[u8] = &[0x67, 0x81, 0x08, 0x01, 0x01, 0x01];
/// Application tag of the EF.DG15 file: the Active Authentication public key.
const EF_DG15: u8 = 0x6f;
const OID_EC_PUBLIC_KEY: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01];
/// prime256v1, the curve the Chilean card uses for Active Authentication.
const OID_P256: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07];
const OID_PRIME_FIELD: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x01, 0x01];

/// What the prover feeds in. Everything except `today`, `min_age`, the CSCA and the device
/// key hash is private.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Inputs {
    /// DER SubjectPublicKeyInfo of the CSCA key. Public: it is one of Chile's published
    /// CSCAs. A key rather than a certificate, because the public registries of Chile's
    /// CSCAs carry only modulus and exponent.
    pub csca_spki: Vec<u8>,
    /// EF.SOD as read from the chip (tag 0x77 wrapping a CMS ContentInfo), or the bare
    /// ContentInfo. It includes the DSC certificate.
    pub sod_der: Vec<u8>,
    /// Raw DG1 file as read from the chip.
    pub dg1: Vec<u8>,
    /// Raw DG15 file, or empty if the chip has no Active Authentication.
    pub dg15: Vec<u8>,
    /// The chip's plain ECDSA signature (r || s) over the first 8 bytes of
    /// `device_key_sha256`, or empty with an empty `dg15`.
    pub aa_signature: Vec<u8>,
    /// Verification date.
    pub today: Date,
    pub min_age: u8,
    /// SHA-256 of the phone's ML-DSA public key (DER SubjectPublicKeyInfo). Public.
    pub device_key_sha256: [u8; 32],
}

impl Inputs {
    /// Length-prefixed binary encoding. The zkVM's default serializer spends a 32-bit word per
    /// byte, which made reading these inputs cost more than verifying them.
    pub fn to_bytes(&self) -> Vec<u8> {
        let fields = [&self.csca_spki, &self.sod_der, &self.dg1, &self.dg15, &self.aa_signature];
        let mut out = Vec::with_capacity(fields.iter().map(|f| f.len() + 4).sum::<usize>() + 37);
        for field in fields {
            out.extend_from_slice(&(field.len() as u32).to_le_bytes());
            out.extend_from_slice(field);
        }
        out.extend_from_slice(&self.today.year.to_le_bytes());
        out.extend_from_slice(&[self.today.month, self.today.day, self.min_age]);
        out.extend_from_slice(&self.device_key_sha256);
        out
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, Error> {
        let r = InputsRef::from_bytes(bytes)?;
        Ok(Inputs {
            csca_spki: r.csca_spki.to_vec(),
            sod_der: r.sod_der.to_vec(),
            dg1: r.dg1.to_vec(),
            dg15: r.dg15.to_vec(),
            aa_signature: r.aa_signature.to_vec(),
            today: r.today,
            min_age: r.min_age,
            device_key_sha256: r.device_key_sha256,
        })
    }

    pub fn as_ref(&self) -> InputsRef<'_> {
        InputsRef {
            csca_spki: &self.csca_spki,
            sod_der: &self.sod_der,
            dg1: &self.dg1,
            dg15: &self.dg15,
            aa_signature: &self.aa_signature,
            today: self.today,
            min_age: self.min_age,
            device_key_sha256: self.device_key_sha256,
        }
    }
}

/// [`Inputs`] borrowed from an encoded buffer, so the guest never copies the chip data.
#[derive(Clone, Copy, Debug)]
pub struct InputsRef<'a> {
    pub csca_spki: &'a [u8],
    pub sod_der: &'a [u8],
    pub dg1: &'a [u8],
    pub dg15: &'a [u8],
    pub aa_signature: &'a [u8],
    pub today: Date,
    pub min_age: u8,
    pub device_key_sha256: [u8; 32],
}

impl<'a> InputsRef<'a> {
    pub fn from_bytes(bytes: &'a [u8]) -> Result<Self, Error> {
        let mut rest = bytes;
        let mut field = || -> Result<&'a [u8], Error> {
            let (len, tail) = rest.split_at_checked(4).ok_or(Error::Encoding)?;
            let len = u32::from_le_bytes(len.try_into().map_err(|_| Error::Encoding)?) as usize;
            let (value, tail) = tail.split_at_checked(len).ok_or(Error::Encoding)?;
            rest = tail;
            Ok(value)
        };
        let (csca_spki, sod_der, dg1, dg15, aa_signature) = (field()?, field()?, field()?, field()?, field()?);
        let ([y0, y1, month, day, min_age], device_key) = rest.split_first_chunk::<5>().ok_or(Error::Encoding)?;
        Ok(InputsRef {
            csca_spki,
            sod_der,
            dg1,
            dg15,
            aa_signature,
            today: Date { year: u16::from_le_bytes([*y0, *y1]), month: *month, day: *day },
            min_age: *min_age,
            device_key_sha256: device_key.try_into().map_err(|_| Error::Encoding)?,
        })
    }
}

/// What the proof makes public.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Claim {
    /// SHA-256 of the CSCA SubjectPublicKeyInfo DER: tells the verifier which country key
    /// was used.
    pub csca_sha256: [u8; 32],
    pub today: Date,
    pub min_age: u8,
    /// SHA-256 of the phone's ML-DSA public key (DER SubjectPublicKeyInfo): presentations
    /// are signed with this key.
    pub device_key_sha256: [u8; 32],
    /// The chip answered Active Authentication for this device key (check 6). False for a
    /// card read without it.
    pub chip_authenticated: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub struct Date {
    pub year: u16,
    pub month: u8,
    pub day: u8,
}

#[derive(Debug, PartialEq, Eq)]
pub enum Error {
    Encoding,
    Asn1,
    /// A signature or digest algorithm other than SHA-256 with RSA.
    Algorithm,
    UnsupportedKey,
    DscSignature,
    SodSignature,
    MessageDigest,
    Dg1Hash,
    Dg15Hash,
    /// DG15 present without a signature or the reverse, or a signature that does not verify.
    ActiveAuthentication,
    Mrz,
    Underage,
    Expired,
}

pub fn verify(inputs: &Inputs) -> Result<Claim, Error> {
    verify_traced(inputs.as_ref(), &mut |_| {})
}

/// Same as [`verify`], calling `step` after each check with its name. The guest uses it to
/// count cycles per check.
pub fn verify_traced(inputs: InputsRef<'_>, step: &mut dyn FnMut(&'static str)) -> Result<Claim, Error> {
    let sod = Sod::parse(inputs.sod_der)?;
    let dsc = Cert::parse(sod.dsc_der)?;
    let csca_key = rsa_key(inputs.csca_spki)?;
    let dsc_key = rsa_key(dsc.spki)?;
    step("1 parse + keys");

    // 1. DSC signed by the CSCA.
    if dsc.signature_algorithm != OID_SHA256_WITH_RSA {
        return Err(Error::Algorithm);
    }
    verify_rsa(&csca_key, dsc.tbs, dsc.signature).map_err(|_| Error::DscSignature)?;
    step("2 DSC signature (RSA)");

    // 2. Signed attributes signed by the DSC. They are stored as [0] IMPLICIT but signed as a
    // DER SET, so the tag byte is swapped before hashing.
    if sod.digest_algorithm != OID_SHA256
        || !(sod.signature_algorithm == OID_RSA_ENCRYPTION || sod.signature_algorithm == OID_SHA256_WITH_RSA)
    {
        return Err(Error::Algorithm);
    }
    let attrs_hash = Sha256::new().chain_update([SET]).chain_update(&sod.signed_attrs.raw[1..]).finalize();
    verify_rsa_prehashed(&dsc_key, &attrs_hash, sod.signature).map_err(|_| Error::SodSignature)?;
    step("3 SOD signature (RSA)");

    // 3. messageDigest covers the LDSSecurityObject.
    let expected = message_digest(sod.signed_attrs.value).ok_or(Error::MessageDigest)?;
    if expected != Sha256::digest(sod.lds).as_slice() {
        return Err(Error::MessageDigest);
    }

    // 4. DG1 hash.
    if dg_hash(sod.lds, 1).ok_or(Error::Dg1Hash)? != Sha256::digest(inputs.dg1).as_slice() {
        return Err(Error::Dg1Hash);
    }
    step("4 messageDigest + DG1 hash");

    // 5. Age and expiry.
    let mrz = td1_mrz(inputs.dg1)?;
    let birth = parse_birth(&mrz[30..36], inputs.today)?;
    let expiry = parse_expiry(&mrz[38..44])?;
    if expiry <= inputs.today {
        return Err(Error::Expired);
    }
    if !is_at_least(birth, inputs.today, inputs.min_age) {
        return Err(Error::Underage);
    }
    step("5 age and expiry");

    // 6. Active Authentication, when the chip has it.
    let chip_authenticated = match (inputs.dg15.is_empty(), inputs.aa_signature.is_empty()) {
        (true, true) => false,
        (false, false) => {
            if dg_hash(sod.lds, 15).ok_or(Error::Dg15Hash)? != Sha256::digest(inputs.dg15).as_slice() {
                return Err(Error::Dg15Hash);
            }
            let key = p256_key(inputs.dg15)?;
            let signature =
                p256::ecdsa::Signature::from_slice(inputs.aa_signature).map_err(|_| Error::ActiveAuthentication)?;
            key.verify(&inputs.device_key_sha256[..8], &signature).map_err(|_| Error::ActiveAuthentication)?;
            true
        }
        _ => return Err(Error::ActiveAuthentication),
    };
    step("6 active authentication");

    Ok(Claim {
        csca_sha256: Sha256::digest(inputs.csca_spki).into(),
        today: inputs.today,
        min_age: inputs.min_age,
        device_key_sha256: inputs.device_key_sha256,
        chip_authenticated,
    })
}

/// The parts of an X.509 certificate the checks use, as slices into its DER.
struct Cert<'a> {
    /// Whole TBSCertificate encoding: what the issuer signed.
    tbs: &'a [u8],
    /// Whole SubjectPublicKeyInfo encoding.
    spki: &'a [u8],
    signature_algorithm: &'a [u8],
    signature: &'a [u8],
}

impl<'a> Cert<'a> {
    fn parse(der: &'a [u8]) -> Result<Self, Error> {
        let (cert, _) = expect(der, SEQUENCE).ok_or(Error::Asn1)?;
        let mut parts = children(cert.value);
        let tbs = next(&mut parts, SEQUENCE)?;
        let algorithm = next(&mut parts, SEQUENCE)?;
        let signature = next(&mut parts, BIT_STRING)?;

        // TBSCertificate: [0] version?, serial, signature, issuer, validity, subject, spki.
        let mut fields = children(tbs.value).peekable();
        if fields.peek().map(|f| f.tag) == Some(CONTEXT_0) {
            fields.next();
        }
        let spki = fields.nth(5).filter(|f| f.tag == SEQUENCE).ok_or(Error::Asn1)?;

        // BIT STRING: first byte is the count of unused bits, always 0 for signatures.
        let signature = match signature.value.split_first() {
            Some((0, bits)) => bits,
            _ => return Err(Error::Asn1),
        };
        Ok(Cert { tbs: tbs.raw, spki: spki.raw, signature_algorithm: algorithm_oid(algorithm)?, signature })
    }
}

/// The parts of the SOD (ContentInfo wrapping CMS SignedData) the checks use.
struct Sod<'a> {
    /// LDSSecurityObject DER (the OCTET STRING contents of eContent).
    lds: &'a [u8],
    /// First certificate in the SignedData: the DSC.
    dsc_der: &'a [u8],
    signed_attrs: Tlv<'a>,
    digest_algorithm: &'a [u8],
    signature_algorithm: &'a [u8],
    signature: &'a [u8],
}

impl<'a> Sod<'a> {
    fn parse(der: &'a [u8]) -> Result<Self, Error> {
        // On the chip, EF.SOD wraps the ContentInfo in application tag 0x77.
        let der = match der_walk::read(der) {
            Some((file, _)) if file.tag == EF_SOD => file.value,
            _ => der,
        };
        // ContentInfo ::= SEQUENCE { contentType, [0] EXPLICIT SignedData }
        let (content_info, _) = expect(der, SEQUENCE).ok_or(Error::Asn1)?;
        let mut parts = children(content_info.value);
        next(&mut parts, OID)?;
        let explicit = next(&mut parts, CONTEXT_0)?;
        let (signed_data, _) = expect(explicit.value, SEQUENCE).ok_or(Error::Asn1)?;

        // SignedData ::= SEQUENCE { version, digestAlgorithms, encapContentInfo,
        //   [0] certificates?, [1] crls?, signerInfos }
        let mut fields = children(signed_data.value);
        next(&mut fields, INTEGER)?;
        next(&mut fields, SET)?;
        let encap = next(&mut fields, SEQUENCE)?;
        let certificates = next(&mut fields, CONTEXT_0)?;
        let signer_infos = fields.find(|f| f.tag == SET).ok_or(Error::Asn1)?;

        let mut encap_fields = children(encap.value);
        if next(&mut encap_fields, OID)?.value != OID_LDS_SECURITY_OBJECT {
            return Err(Error::Asn1);
        }
        let econtent = next(&mut encap_fields, CONTEXT_0)?;
        let (lds, _) = expect(econtent.value, OCTET_STRING).ok_or(Error::Asn1)?;

        let dsc = children(certificates.value).next().filter(|c| c.tag == SEQUENCE).ok_or(Error::Asn1)?;

        // SignerInfo ::= SEQUENCE { version, sid, digestAlgorithm, [0] signedAttrs,
        //   signatureAlgorithm, signature, ... }
        let signer = children(signer_infos.value).next().filter(|s| s.tag == SEQUENCE).ok_or(Error::Asn1)?;
        let mut signer_fields = children(signer.value);
        next(&mut signer_fields, INTEGER)?;
        signer_fields.next().ok_or(Error::Asn1)?;
        let digest_algorithm = algorithm_oid(next(&mut signer_fields, SEQUENCE)?)?;
        let signed_attrs = next(&mut signer_fields, CONTEXT_0)?;
        let signature_algorithm = algorithm_oid(next(&mut signer_fields, SEQUENCE)?)?;
        let signature = next(&mut signer_fields, OCTET_STRING)?.value;

        Ok(Sod { lds: lds.value, dsc_der: dsc.raw, signed_attrs, digest_algorithm, signature_algorithm, signature })
    }
}

fn next<'a>(it: &mut impl Iterator<Item = Tlv<'a>>, tag: u8) -> Result<Tlv<'a>, Error> {
    it.next().filter(|t| t.tag == tag).ok_or(Error::Asn1)
}

/// The OID inside an AlgorithmIdentifier SEQUENCE.
fn algorithm_oid(algorithm: Tlv<'_>) -> Result<&[u8], Error> {
    let (oid, _) = expect(algorithm.value, OID).ok_or(Error::Asn1)?;
    Ok(oid.value)
}

/// The messageDigest attribute value: Attribute ::= SEQUENCE { OID, SET { OCTET STRING } }.
fn message_digest(signed_attrs: &[u8]) -> Option<&[u8]> {
    children(signed_attrs).find_map(|attr| {
        let mut parts = children(attr.value);
        let oid = parts.next().filter(|o| o.tag == OID && o.value == OID_MESSAGE_DIGEST)?;
        let _ = oid;
        let values = parts.next().filter(|v| v.tag == SET)?;
        let (digest, _) = expect(values.value, OCTET_STRING)?;
        Some(digest.value)
    })
}

/// The SHA-256 hash of data group `number` in the LDSSecurityObject ::= SEQUENCE { version,
/// hashAlgorithm, SEQUENCE OF { dgNumber, hash } }, or None if the SOD does not sign it.
/// Only SHA-256 is accepted.
fn dg_hash(lds: &[u8], number: u8) -> Option<&[u8]> {
    let (object, _) = expect(lds, SEQUENCE)?;
    let mut fields = children(object.value);
    fields.next().filter(|v| v.tag == INTEGER)?;
    let algorithm = fields.next().filter(|a| a.tag == SEQUENCE)?;
    if algorithm_oid(algorithm).ok()? != OID_SHA256 {
        return None;
    }
    let hashes = fields.next().filter(|h| h.tag == SEQUENCE)?;
    children(hashes.value).find_map(|entry| {
        let mut parts = children(entry.value);
        parts.next().filter(|n| n.tag == INTEGER && n.value == [number])?;
        let (hash, _) = expect(parts.next()?.raw, OCTET_STRING)?;
        Some(hash.value)
    })
}

/// The P-256 key in DG15: [APPLICATION 15] { SubjectPublicKeyInfo }, with the curve named by
/// its OID or spelled out as explicit ECParameters (both occur on chips).
fn p256_key(dg15: &[u8]) -> Result<p256::ecdsa::VerifyingKey, Error> {
    let (file, _) = expect(dg15, EF_DG15).ok_or(Error::Asn1)?;
    let (spki, _) = expect(file.value, SEQUENCE).ok_or(Error::Asn1)?;
    let mut parts = children(spki.value);
    let algorithm = next(&mut parts, SEQUENCE)?;
    let mut algorithm = children(algorithm.value);
    if next(&mut algorithm, OID)?.value != OID_EC_PUBLIC_KEY {
        return Err(Error::UnsupportedKey);
    }
    let curve = algorithm.next().ok_or(Error::Asn1)?;
    let is_p256 = match curve.tag {
        OID => curve.value == OID_P256,
        SEQUENCE => explicit_p256(curve.value).ok_or(Error::Asn1)?,
        _ => false,
    };
    if !is_p256 {
        return Err(Error::UnsupportedKey);
    }
    let point = match next(&mut parts, BIT_STRING)?.value.split_first() {
        Some((0, point)) => point,
        _ => return Err(Error::Asn1),
    };
    p256::ecdsa::VerifyingKey::from_sec1_bytes(point).map_err(|_| Error::UnsupportedKey)
}

/// ECParameters ::= SEQUENCE { version, fieldID { prime-field, p }, curve { a, b, seed? },
/// base, order, cofactor? }: true when every number is P-256's.
fn explicit_p256(params: &[u8]) -> Option<bool> {
    let mut fields = children(params);
    fields.next().filter(|v| v.tag == INTEGER)?;
    let field = fields.next().filter(|f| f.tag == SEQUENCE)?;
    let mut field = children(field.value);
    let field_type = field.next().filter(|t| t.tag == OID)?;
    let p = field.next().filter(|p| p.tag == INTEGER)?;
    let curve = fields.next().filter(|c| c.tag == SEQUENCE)?;
    let mut curve = children(curve.value);
    let a = curve.next().filter(|a| a.tag == OCTET_STRING)?;
    let b = curve.next().filter(|b| b.tag == OCTET_STRING)?;
    let base = fields.next().filter(|g| g.tag == OCTET_STRING)?;
    let order = fields.next().filter(|n| n.tag == INTEGER)?;
    let generator = match base.value {
        [0x04, xy @ ..] => xy == [P256_GX, P256_GY].concat(),
        [0x03, x @ ..] => x == P256_GX, // Gy is odd
        _ => false,
    };
    Some(
        field_type.value == OID_PRIME_FIELD
            && unsigned(p.value) == P256_P
            && unsigned(a.value) == P256_A
            && unsigned(b.value) == P256_B
            && generator
            && unsigned(order.value) == P256_N,
    )
}

/// A DER INTEGER or field element without its leading zero bytes.
fn unsigned(bytes: &[u8]) -> &[u8] {
    let zeros = bytes.iter().take_while(|b| **b == 0).count();
    &bytes[zeros..]
}

// P-256 domain parameters (SEC 2, section 2.4.2); a test checks them against the p256 crate.
const P256_P: [u8; 32] = hex32("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff");
const P256_A: [u8; 32] = hex32("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc");
const P256_B: [u8; 32] = hex32("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b");
const P256_GX: [u8; 32] = hex32("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296");
const P256_GY: [u8; 32] = hex32("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5");
const P256_N: [u8; 32] = hex32("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551");

const fn hex32(s: &str) -> [u8; 32] {
    const fn nibble(c: u8) -> u8 {
        match c {
            b'0'..=b'9' => c - b'0',
            _ => c - b'a' + 10,
        }
    }
    let s = s.as_bytes();
    let mut out = [0; 32];
    let mut i = 0;
    while i < 32 {
        out[i] = nibble(s[2 * i]) << 4 | nibble(s[2 * i + 1]);
        i += 1;
    }
    out
}

/// SubjectPublicKeyInfo ::= SEQUENCE { AlgorithmIdentifier, BIT STRING { RSAPublicKey } },
/// RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }.
/// `RsaPublicKey::new` still enforces the size limits on both numbers.
fn rsa_key(spki: &[u8]) -> Result<RsaPublicKey, Error> {
    let (spki, _) = expect(spki, SEQUENCE).ok_or(Error::Asn1)?;
    let mut parts = children(spki.value);
    if algorithm_oid(next(&mut parts, SEQUENCE)?)? != OID_RSA_ENCRYPTION {
        return Err(Error::UnsupportedKey);
    }
    let bits = match next(&mut parts, BIT_STRING)?.value.split_first() {
        Some((0, bits)) => bits,
        _ => return Err(Error::Asn1),
    };
    let (key, _) = expect(bits, SEQUENCE).ok_or(Error::Asn1)?;
    let mut numbers = children(key.value);
    let n = next(&mut numbers, INTEGER)?.value;
    let e = next(&mut numbers, INTEGER)?.value;
    RsaPublicKey::new(BigUint::from_bytes_be(n), BigUint::from_bytes_be(e)).map_err(|_| Error::UnsupportedKey)
}

fn verify_rsa(key: &RsaPublicKey, message: &[u8], signature: &[u8]) -> Result<(), ()> {
    verify_rsa_prehashed(key, &Sha256::digest(message), signature)
}

fn verify_rsa_prehashed(key: &RsaPublicKey, hashed: &[u8], signature: &[u8]) -> Result<(), ()> {
    key.verify(Pkcs1v15Sign::new::<Sha256>(), hashed, signature).map_err(|_| ())
}

/// Extracts the 90-character TD1 MRZ from DG1: tag 0x61 { tag 0x5F1F MRZ }.
fn td1_mrz(dg1: &[u8]) -> Result<&[u8], Error> {
    let start = dg1.windows(2).position(|w| w == [0x5f, 0x1f]).ok_or(Error::Mrz)?;
    let rest = dg1.get(start + 2..).ok_or(Error::Mrz)?;
    let (len, skip) = match rest.first() {
        Some(&n) if n < 0x80 => (n as usize, 1),
        Some(0x81) => (*rest.get(1).ok_or(Error::Mrz)? as usize, 2),
        _ => return Err(Error::Mrz),
    };
    let mrz = rest.get(skip..skip + len).ok_or(Error::Mrz)?;
    if mrz.len() != 90 {
        return Err(Error::Mrz);
    }
    Ok(mrz)
}

fn two_digits(b: &[u8]) -> Result<u8, Error> {
    match b {
        [a, c] if a.is_ascii_digit() && c.is_ascii_digit() => Ok((a - b'0') * 10 + (c - b'0')),
        _ => Err(Error::Mrz),
    }
}

fn yymmdd(field: &[u8]) -> Result<(u8, u8, u8), Error> {
    if field.len() != 6 {
        return Err(Error::Mrz);
    }
    let (yy, mm, dd) = (two_digits(&field[0..2])?, two_digits(&field[2..4])?, two_digits(&field[4..6])?);
    if !(1..=12).contains(&mm) || !(1..=31).contains(&dd) {
        return Err(Error::Mrz);
    }
    Ok((yy, mm, dd))
}

/// Birth dates carry two-digit years: a year that would land in the future belongs to
/// the previous century. Holders over 100 are misread as children and fail closed.
fn parse_birth(field: &[u8], today: Date) -> Result<Date, Error> {
    let (yy, month, day) = yymmdd(field)?;
    let mut year = 2000 + yy as u16;
    if (Date { year, month, day }) > today {
        year -= 100;
    }
    Ok(Date { year, month, day })
}

/// Expiry dates of cards in circulation are always in the 2000s.
fn parse_expiry(field: &[u8]) -> Result<Date, Error> {
    let (yy, month, day) = yymmdd(field)?;
    Ok(Date { year: 2000 + yy as u16, month, day })
}

/// True when the holder turned `years` on or before `today`. Someone born on 29 February
/// turns a year older on 1 March in non-leap years, which this ordering gives for free.
pub fn is_at_least(birth: Date, today: Date, years: u8) -> bool {
    let birthday = Date { year: birth.year + years as u16, ..birth };
    birthday <= today
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(year: u16, month: u8, day: u8) -> Date {
        Date { year, month, day }
    }

    #[test]
    fn age_boundaries() {
        assert!(is_at_least(d(2008, 10, 22), d(2026, 10, 22), 18));
        assert!(!is_at_least(d(2008, 10, 23), d(2026, 10, 22), 18));
        assert!(!is_at_least(d(2008, 2, 29), d(2026, 2, 28), 18));
        assert!(is_at_least(d(2008, 2, 29), d(2026, 3, 1), 18));
    }

    #[test]
    fn inputs_round_trip() {
        let inputs = Inputs {
            csca_spki: alloc::vec![1, 2, 3],
            sod_der: alloc::vec![],
            dg1: alloc::vec![9; 300],
            dg15: alloc::vec![4; 90],
            aa_signature: alloc::vec![5; 64],
            today: d(2026, 10, 22),
            min_age: 18,
            device_key_sha256: [7; 32],
        };
        let back = Inputs::from_bytes(&inputs.to_bytes()).unwrap();
        assert_eq!(back.csca_spki, inputs.csca_spki);
        assert_eq!(back.dg1, inputs.dg1);
        assert_eq!((back.dg15, back.aa_signature), (inputs.dg15.clone(), inputs.aa_signature.clone()));
        assert_eq!((back.today, back.min_age), (inputs.today, inputs.min_age));
        assert_eq!(back.device_key_sha256, inputs.device_key_sha256);
        let full = inputs.to_bytes();
        assert_eq!(Inputs::from_bytes(&full[..full.len() - 1]).err(), Some(Error::Encoding));
        assert_eq!(Inputs::from_bytes(&inputs.to_bytes()[..10]).err(), Some(Error::Encoding));
    }

    fn tlv(tag: u8, value: &[u8]) -> Vec<u8> {
        let mut out = alloc::vec![tag];
        match value.len() {
            n if n < 0x80 => out.push(n as u8),
            n if n < 0x100 => out.extend_from_slice(&[0x81, n as u8]),
            n => out.extend_from_slice(&[0x82, (n >> 8) as u8, n as u8]),
        }
        out.extend_from_slice(value);
        out
    }

    #[test]
    fn dg15_with_explicit_parameters() {
        let key = p256::ecdsa::SigningKey::from_slice(&[0x42; 32]).unwrap();
        let point = key.verifying_key().to_encoded_point(false);
        let mut base = alloc::vec![0x04];
        base.extend_from_slice(&[P256_GX, P256_GY].concat());
        let with_order = |order: &[u8]| {
            let params = [
                tlv(INTEGER, &[1]),
                tlv(SEQUENCE, &[tlv(OID, OID_PRIME_FIELD), tlv(INTEGER, &[&[0][..], &P256_P].concat())].concat()),
                tlv(SEQUENCE, &[tlv(OCTET_STRING, &P256_A), tlv(OCTET_STRING, &P256_B)].concat()),
                tlv(OCTET_STRING, &base),
                tlv(INTEGER, &[&[0][..], order].concat()),
                tlv(INTEGER, &[1]),
            ]
            .concat();
            let algorithm = tlv(SEQUENCE, &[tlv(OID, OID_EC_PUBLIC_KEY), tlv(SEQUENCE, &params)].concat());
            let bits = tlv(BIT_STRING, &[&[0][..], point.as_bytes()].concat());
            tlv(EF_DG15, &tlv(SEQUENCE, &[algorithm, bits].concat()))
        };
        assert_eq!(p256_key(&with_order(&P256_N)).unwrap(), *key.verifying_key());
        // Any other curve, here one with a different order, is refused.
        let mut other = P256_N;
        other[31] ^= 2;
        assert_eq!(p256_key(&with_order(&other)).err(), Some(Error::UnsupportedKey));
    }

    #[test]
    fn p256_constants_match_the_crate() {
        use p256::elliptic_curve::sec1::ToEncodedPoint;
        use p256::elliptic_curve::bigint::Encoding;
        use p256::elliptic_curve::Curve;
        let g = p256::AffinePoint::GENERATOR.to_encoded_point(false);
        assert_eq!(g.x().unwrap().as_slice(), P256_GX);
        assert_eq!(g.y().unwrap().as_slice(), P256_GY);
        assert_eq!(p256::NistP256::ORDER.to_be_bytes().as_slice(), P256_N);
    }

    #[test]
    fn century_resolution() {
        let today = d(2026, 10, 22);
        assert_eq!(parse_birth(b"950615", today), Ok(d(1995, 6, 15)));
        assert_eq!(parse_birth(b"081022", today), Ok(d(2008, 10, 22)));
        assert_eq!(parse_birth(b"261023", today), Ok(d(1926, 10, 23)));
        assert_eq!(parse_birth(b"951315", today), Err(Error::Mrz));
    }
}
