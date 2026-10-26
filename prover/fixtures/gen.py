"""Generate a mock Chilean ID card (TD1) chip: DG1, SOD and a CSCA -> DSC chain.

Everything here is test data signed with throwaway keys. No real person or real
Registro Civil key is involved. Run from anywhere:

    python3 -I prover/fixtures/gen.py [--csca-bits 3072] [--dsc-bits 2048]

The defaults match a real card issued in 2023: a DSC with RSA 2048 signed by the 2021
CSCA with RSA 3072. The stage 1 and 2 measurements used 4096 for both
(`--csca-bits 4096 --dsc-bits 4096`), the size of Chile's 2024 CSCA.

Outputs, next to this script in `out/`:
    csca.der  - self-signed test CSCA certificate (RSA, --csca-bits)
    dsc.der   - test DSC certificate signed by the CSCA (RSA, --dsc-bits)
    dg1.bin   - DG1 data group (tag 0x61 wrapping the 90-char TD1 MRZ)
    sod.der   - SOD: CMS SignedData over an LDSSecurityObject with the DG1 hash

Private keys go to `keys/`, which is git-ignored.
"""

import argparse
import hashlib
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "out"
KEYS = HERE / "keys"

# Mock holder. Born 1995-06-15, card expires 2030-01-01.
DOC_NUMBER = "A12345678"
BIRTH = "950615"
EXPIRY = "300101"
SEX = "F"
NAME = "PRUEBA<<PERSONA<DE<TEST"

OID_SHA256 = "2.16.840.1.101.3.4.2.1"
OID_LDS_SECURITY_OBJECT = "2.23.136.1.1.1"


def check_digit(field: str) -> str:
    """ICAO 9303 check digit: weights 7, 3, 1 over the character values."""
    weights = (7, 3, 1)
    total = 0
    for i, ch in enumerate(field):
        if ch.isdigit():
            value = int(ch)
        elif ch.isalpha():
            value = ord(ch) - ord("A") + 10
        else:  # filler '<'
            value = 0
        total += value * weights[i % 3]
    return str(total % 10)


def td1_mrz() -> str:
    """Three lines of 30 characters, as printed on the back of the card."""
    line1 = f"I<CHL{DOC_NUMBER}{check_digit(DOC_NUMBER)}".ljust(30, "<")
    line2_head = f"{BIRTH}{check_digit(BIRTH)}{SEX}{EXPIRY}{check_digit(EXPIRY)}CHL"
    line2_head = line2_head.ljust(29, "<")
    # Composite check covers line 1 positions 6-30 and line 2 positions 1-7, 9-15, 19-29.
    composite = line1[5:30] + line2_head[0:7] + line2_head[8:15] + line2_head[18:29]
    line2 = line2_head + check_digit(composite)
    line3 = NAME.ljust(30, "<")[:30]
    mrz = line1 + line2 + line3
    assert len(mrz) == 90, len(mrz)
    return mrz


# --- Minimal DER encoding (only the shapes this file needs) -----------------

def der_len(n: int) -> bytes:
    if n < 0x80:
        return bytes([n])
    body = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(body)]) + body


def tlv(tag: bytes, value: bytes) -> bytes:
    return tag + der_len(len(value)) + value


def der_int(n: int) -> bytes:
    body = n.to_bytes(max(1, (n.bit_length() + 8) // 8), "big", signed=True)
    return tlv(b"\x02", body)


def der_oid(dotted: str) -> bytes:
    parts = [int(p) for p in dotted.split(".")]
    body = bytes([40 * parts[0] + parts[1]])
    for p in parts[2:]:
        chunk = [p & 0x7F]
        p >>= 7
        while p:
            chunk.append(0x80 | (p & 0x7F))
            p >>= 7
        body += bytes(reversed(chunk))
    return tlv(b"\x06", body)


def seq(*items: bytes) -> bytes:
    return tlv(b"\x30", b"".join(items))


def dg1_bytes(mrz: str) -> bytes:
    # DG1 = [APPLICATION 1] { [APPLICATION 31 / 5F1F] MRZ }
    return tlv(b"\x61", tlv(b"\x5f\x1f", mrz.encode("ascii")))


def lds_security_object(dg1: bytes) -> bytes:
    # LDSSecurityObject ::= SEQUENCE { version 0, hashAlgorithm, dataGroupHashValues }
    dg_hash = seq(der_int(1), tlv(b"\x04", hashlib.sha256(dg1).digest()))
    return seq(der_int(0), seq(der_oid(OID_SHA256)), seq(dg_hash))


# --- OpenSSL -----------------------------------------------------------------

def openssl(*args: str) -> None:
    subprocess.run(["openssl", *args], check=True, capture_output=True)


def make_chain(csca_bits: int, dsc_bits: int) -> Path:
    """Returns the DSC private key. Keys are cached per size, so reruns stay stable."""
    csca_key, dsc_key = KEYS / f"csca-{csca_bits}.key", KEYS / f"dsc-{dsc_bits}.key"
    if not csca_key.exists():
        openssl("genpkey", "-algorithm", "RSA", "-pkeyopt", f"rsa_keygen_bits:{csca_bits}", "-out", str(csca_key))
    if not dsc_key.exists():
        openssl("genpkey", "-algorithm", "RSA", "-pkeyopt", f"rsa_keygen_bits:{dsc_bits}", "-out", str(dsc_key))

    csca_pem, dsc_csr, dsc_pem = KEYS / "csca.pem", KEYS / "dsc.csr", KEYS / "dsc.pem"
    openssl("req", "-x509", "-new", "-key", str(csca_key), "-sha256", "-days", "3650",
            "-subj", "/C=CL/O=TEST Registro Civil/CN=TEST CSCA Chile",
            "-addext", "basicConstraints=critical,CA:TRUE",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign",
            "-out", str(csca_pem))
    openssl("req", "-new", "-key", str(dsc_key), "-subj", "/C=CL/O=TEST Registro Civil/CN=TEST DSC Chile",
            "-out", str(dsc_csr))
    ext = KEYS / "dsc.ext"
    ext.write_text("keyUsage=critical,digitalSignature\n")
    openssl("x509", "-req", "-in", str(dsc_csr), "-CA", str(csca_pem), "-CAkey", str(csca_key),
            "-CAcreateserial", "-sha256", "-days", "1825", "-extfile", str(ext), "-out", str(dsc_pem))
    openssl("x509", "-in", str(csca_pem), "-outform", "DER", "-out", str(OUT / "csca.der"))
    openssl("x509", "-in", str(dsc_pem), "-outform", "DER", "-out", str(OUT / "dsc.der"))
    return dsc_key


def make_sod(lds: bytes, dsc_key: Path) -> None:
    lds_path = KEYS / "lds.der"
    lds_path.write_bytes(lds)
    openssl("cms", "-sign", "-binary", "-nodetach", "-nosmimecap",
            "-in", str(lds_path), "-outform", "DER", "-out", str(OUT / "sod.der"),
            "-econtent_type", OID_LDS_SECURITY_OBJECT, "-md", "sha256",
            "-signer", str(KEYS / "dsc.pem"), "-inkey", str(dsc_key))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--csca-bits", type=int, default=3072)
    parser.add_argument("--dsc-bits", type=int, default=2048)
    args = parser.parse_args()
    OUT.mkdir(exist_ok=True)
    KEYS.mkdir(exist_ok=True)
    mrz = td1_mrz()
    dg1 = dg1_bytes(mrz)
    (OUT / "dg1.bin").write_bytes(dg1)
    dsc_key = make_chain(args.csca_bits, args.dsc_bits)
    make_sod(lds_security_object(dg1), dsc_key)
    print(f"CSCA RSA {args.csca_bits}, DSC RSA {args.dsc_bits}")
    print("MRZ:")
    for i in range(0, 90, 30):
        print("  " + mrz[i:i + 30])
    for f in sorted(OUT.iterdir()):
        print(f"{f.name}: {f.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
