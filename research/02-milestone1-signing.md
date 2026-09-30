# Milestone 1 — on-device APK generation & signing (COMPLETE, independently verified)

Date: 2026-09-29 · Status: **green**

## Verified end state

| Check | Result |
|---|---|
| `gradle :app:assembleDebug :app:testDebugUnitTest` | **BUILD SUCCESSFUL** |
| Unit tests | **18 / 18 pass, 0 failures** |
| `apksigner verify` on a runtime-generated proxy APK | **Verifies** · v2 = **true** |
| `aapt2 dump badging` on the generated APK | `package: name='com.example.targetgame'` |
| `zipalign -c -p 4` | **ALIGNMENT OK** |
| `unzip -t` (jar/zip integrity) | **No errors detected** |
| `keytool -printcert` on our hand-built PKCS#7 | parses; `CN=Understudy Test`, SHA256withRSA, 2048-bit, X.509 v3 |

Everything above is produced **at runtime by our own code** — no `apksigner`, no `zipalign`,
no BouncyCastle. The only external tools involved were used to *verify*.

## What is implemented

| Component | File | Notes |
|---|---|---|
| AXML string-pool codec | `packaging/axml/AxmlStringPool.kt` | Re-encoding an unmodified pool is **byte-identical** to aapt2's output. |
| Manifest re-targeting | `packaging/axml/ManifestPatcher.kt` | Prefix substitution; also validates package names (ASCII-only, reserved namespaces, Java keywords). |
| DER writer | `packaging/sign/DerWriter.kt` | Enough ASN.1 to emit a self-signed X.509 v3 certificate. |
| DER reader | `packaging/sign/DerReader.kt` | ~60 lines of TLV walking; replaces a hand-rolled parser that was the source of a real bug. |
| Signing identity | `packaging/sign/SigningIdentity.kt` | RSA-2048 keygen + self-signed cert, persisted in app-private storage. |
| v1 (JAR) signer | `packaging/sign/V1Signer.kt` | `MANIFEST.MF` / `UNDERS.SF` / `UNDERS.RSA` with a hand-built PKCS#7 SignedData. |
| v2 signer | `packaging/sign/V2Signer.kt` | APK Signing Block, chunked SHA-256 content digest, zip surgery. |
| Aligned ZIP writer | `packaging/ZipWriter.kt` | Stateless; pads the extra field so uncompressed entries land on a 4-byte boundary. |
| Pipeline | `packaging/ProxyApkFactory.kt` | template → patch → repack → v1 → align → v2. |

## The five bugs that mattered (all found by verification, not by reading)

1. **`headerSize` vs `stringsStart`** — `ResStringPool_header` is a fixed 28 bytes; the offset
   arrays come *after* it. I initially folded them into `headerSize`, so the pool re-encoded
   to the right total length but with a wrong header. Caught by the byte-identical round-trip test.
2. **Splicing the file header** — I appended a bare `u32` size instead of rewriting the whole
   `u16 type | u16 headerSize | u32 size`, producing a corrupt 2876-byte file.
3. **EOCD central-directory offset** — after inserting the signing block the EOCD must point at
   the *new* CD location. Symptom: `ZipFile invalid LOC header`.
4. **Shifting the CD local-header offsets (wrong!)** — inserting the block *before* the CD does
   **not** move the zip entries, so their local-header offsets must be left alone. I was adding
   `block.size` to every one of them. This is the single most counter-intuitive part of v2.
5. **DigestInfo encoding** — I used `sequenceOf(oid + null + octetString)`, which emits
   `30 2f 06 09 …` and omits the inner `AlgorithmIdentifier` SEQUENCE. The standard SHA-256
   prefix is `30 31 30 0d 06 09 60 86 48 01 65 03 04 02 01 05 00 04 20`. Now asserted at runtime.

### The decisive one: three digest segments, not two

`V2SchemeVerifier.verifyIntegrity` passes **three separate `DataSource`s**:
`{beforeApkSigningBlock, centralDir, modifiedEocd}`.

I had concatenated the central directory and the EOCD into one segment. Chunking is *per
segment*, and the chunk count is hashed into the top-level digest (`0x5a || u32 chunkCount ||
chunkDigests…`), so 2 chunks instead of 3 produced a completely different digest:

```
ERROR: APK Signature Scheme v2 signer #1: APK integrity check failed.
       CHUNKED_SHA256 digest mismatch.
```

Splitting them fixed it immediately. **This is not documented on the v2 spec page** — the spec
describes "section 3" as the central directory *plus* EOCD, which reads as one region. It is
two. Anyone reimplementing v2 from the spec alone will hit this.

## v2 nesting, confirmed byte-for-byte against an apksig-produced block

```
pairValue    = u32 0x7109871a || len32 signers
signers      = len32 { len32 signer }                        // sequence prefix + element prefix
signer       = len32 signedData || len32 signatures || len32 publicKey
signedData   = len32 digests || len32 certificates || len32 attributes
digests      = len32 { len32 { u32 alg || len32 digest } }   // TWO prefixes (44 / 40 bytes)
certificates = len32 { len32 certificate }                   // TWO prefixes (748 / 744 bytes)
attributes   = len32 { }                                     // 4 bytes, zero length
signatures   = len32 { u32 alg || len32 signature }          // ONE prefix  (268 / 264 bytes)
publicKey    = len32 SubjectPublicKeyInfo                    // ONE prefix, raw SPKI not the cert
```

The `digests`/`certificates` vs `signatures` asymmetry is real and is exactly what produces
`Unknown signature algorithm: 0x108` (0x108 = 264 = the signature element's *length* being
read as an algorithm id) when you get it wrong.

## Process lesson

Three separate times I "fixed" correct code because a *diagnostic script* of mine had an
off-by-4, and once because a test parser unwrapped one level too many. The thing that finally
resolved it was diffing against a **known-good apksig-produced APK** field by field, and
adding `check()` assertions inside the encoder so the numbers had to agree at runtime.
`apksigner` as an oracle, plus a reference APK, is worth more than any amount of re-reading.

## Tooling built along the way (in `tools/`)

`dt` / `dt_create.py` / `dt_run.py` — Daytona sandbox control (create, run, put/get, putdir/getdir).
`dtbuild` — background Gradle build + log polling (the bash tool caps at 1800 s).
`verify_signed_apk.sh` — runs `apksigner` / `aapt2` / `zipalign` over generated APKs.
`ref_sign.sh` — re-signs our unsigned APK with the real `apksigner` to produce a reference block.
`nesting.py`, `sddump.py`, `digestprobe.py`, `apkcheck.py`, `cddump.py` — structural dumps.
`axml_ref.py` — the Python reference implementation the Kotlin AXML code was ported from.

## Not yet built

Bridge client · `PackageInstaller` integration · session orchestrator/state machine ·
transfer engine (SAF destination, recursive pull/push, delete/rename, streaming) ·
self-pairing wireless-ADB shell backend · all Compose UI · Robolectric/instrumented tests ·
`:app` release signing config · README.
