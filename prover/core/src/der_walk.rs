//! Minimal zero-copy DER reader: just enough to find fields by position.
//!
//! Full ASN.1 decoding of the SOD and the certificates cost ~240k zkVM cycles, more than the
//! two RSA signatures together. This walks the bytes in place instead. It does not validate
//! whole structures; it only has to find the right bytes, because every byte it hands to a
//! later check is either covered by a signature that check verifies or is the signature.

/// One decoded TLV: its tag, its value, and the whole encoding (header included).
#[derive(Clone, Copy, Debug)]
pub struct Tlv<'a> {
    pub tag: u8,
    pub value: &'a [u8],
    pub raw: &'a [u8],
}

/// Reads the TLV at the start of `input` and returns it with the bytes after it.
/// Only single-byte tags and definite lengths up to 4 bytes, which is all DER allows here.
pub fn read(input: &[u8]) -> Option<(Tlv<'_>, &[u8])> {
    let (&tag, rest) = input.split_first()?;
    let (&first, rest) = rest.split_first()?;
    let (len, rest) = if first < 0x80 {
        (first as usize, rest)
    } else {
        let n = (first & 0x7f) as usize;
        if n == 0 || n > 4 {
            return None;
        }
        let (bytes, rest) = rest.split_at_checked(n)?;
        let len = bytes.iter().fold(0usize, |acc, b| (acc << 8) | *b as usize);
        (len, rest)
    };
    let (value, after) = rest.split_at_checked(len)?;
    let header = input.len() - rest.len();
    Some((Tlv { tag, value, raw: &input[..header + len] }, after))
}

/// Reads a TLV and requires its tag.
pub fn expect(input: &[u8], tag: u8) -> Option<(Tlv<'_>, &[u8])> {
    let (tlv, rest) = read(input)?;
    (tlv.tag == tag).then_some((tlv, rest))
}

/// Iterates the TLVs inside a constructed value.
pub fn children(value: &[u8]) -> impl Iterator<Item = Tlv<'_>> {
    let mut rest = value;
    core::iter::from_fn(move || {
        if rest.is_empty() {
            return None;
        }
        let (tlv, after) = read(rest)?;
        rest = after;
        Some(tlv)
    })
}

pub const SEQUENCE: u8 = 0x30;
pub const SET: u8 = 0x31;
pub const INTEGER: u8 = 0x02;
pub const BIT_STRING: u8 = 0x03;
pub const OCTET_STRING: u8 = 0x04;
pub const OID: u8 = 0x06;
pub const CONTEXT_0: u8 = 0xa0;
pub const CONTEXT_1: u8 = 0xa1;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn short_and_long_lengths() {
        let short = [0x04, 0x02, 0xaa, 0xbb, 0xff];
        let (tlv, rest) = read(&short).unwrap();
        assert_eq!((tlv.tag, tlv.value, tlv.raw.len(), rest), (0x04, &[0xaa, 0xbb][..], 4, &[0xff][..]));

        let mut long = alloc::vec![0x04, 0x81, 0x80];
        long.extend_from_slice(&[7; 0x80]);
        let (tlv, rest) = read(&long).unwrap();
        assert_eq!((tlv.value.len(), tlv.raw.len(), rest.len()), (0x80, 0x83, 0));
    }

    #[test]
    fn truncated_is_rejected() {
        assert!(read(&[0x04, 0x05, 1, 2]).is_none());
        assert!(read(&[0x04, 0x85, 0, 0, 0, 0, 1]).is_none());
        assert!(expect(&[0x04, 0x00], SEQUENCE).is_none());
    }
}
