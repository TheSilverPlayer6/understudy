package dev.understudy.packaging

import dev.understudy.packaging.axml.ManifestPatcher
import dev.understudy.packaging.sign.SigningIdentity
import dev.understudy.packaging.sign.V1Signer
import dev.understudy.packaging.sign.V2Signer
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Turns the proxy **template** APK into an installable APK that impersonates a chosen
 * package.
 *
 * Pipeline:
 * ```
 * template APK ──read──▶ entries
 *                          │
 *        AndroidManifest.xml ──▶ ManifestPatcher.rename(target)
 *                          │
 *                          ▼
 *        [AndroidManifest.xml, classesN.dex]  ──▶ ZipWriter (aligned)
 *                          │
 *                          ▼
 *        V1Signer (MANIFEST.MF / UNDERS.SF / UNDERS.RSA)
 *                          │
 *                          ▼
 *        V2Signer (APK Signing Block) ──▶ installable APK
 * ```
 *
 * Two things are deliberately dropped from the template:
 *  - **everything under `META-INF/`** — the template's own signature is invalid the moment
 *    a single byte changes, and a stale `MANIFEST.MF` would make the installer reject the
 *    APK as tampered-with;
 *  - **`resources.arsc` and the `kotlin_builtins` metadata** — the proxy declares no
 *    resources of its own (every `android:` attribute value is a literal or a framework
 *    reference) and never reflects over Kotlin metadata. Dropping them removes ~60 KB of
 *    dead weight from every APK we generate and install.
 *
 * Entry order is normalised so `AndroidManifest.xml` comes first, then the dex files: this
 * is the layout the platform's installer and dexopt expect to see, and it keeps the output
 * deterministic.
 */
class ProxyApkFactory(private val identity: SigningIdentity) {

    class ProxyApk(
        val bytes: ByteArray,
        val packageName: String,
        val authority: String,
        val sizeBytes: Int,
        val sha256Hex: String,
        val manifestReplacements: List<Pair<String, String>>,
    )

    /**
     * @param templateApk the built `:proxy` APK, read from assets
     * @param targetPackage the package whose private storage should become reachable
     */
    fun generate(templateApk: ByteArray, targetPackage: String): ProxyApk {
        val template = readZip(templateApk)

        val manifest = template["AndroidManifest.xml"]
            ?: throw IllegalStateException("template APK has no AndroidManifest.xml")
        val patched = ManifestPatcher.rename(manifest, targetPackage)

        val dexEntries = template.keys
            .filter { it == "classes.dex" || (it.startsWith("classes") && it.endsWith(".dex")) }
            .sortedWith(compareBy({ it != "classes.dex" }, { it.length }, { it }))
        require(dexEntries.isNotEmpty()) { "template APK contains no classes.dex" }

        // Ordered: manifest, dex, then anything else we chose to keep.
        val ordered = LinkedHashMap<String, ByteArray>()
        ordered["AndroidManifest.xml"] = patched.bytes
        for (name in dexEntries) ordered[name] = template.getValue(name)
        for ((name, data) in template) {
            if (name in ordered) continue
            if (isDroppable(name)) continue
            ordered[name] = data
        }

        val entries = ordered.map { (name, data) ->
            ZipWriter.Entry(
                name = name,
                data = data,
                // arsc must never be compressed on modern Android; we drop it, but keep the
                // rule so a future template change cannot silently produce a bad APK.
                method = if (name == "resources.arsc") ZipWriter.Method.STORED
                else ZipWriter.Method.DEFLATE,
            )
        }

        val unsignedAligned = ZipWriter.build(entries)
        val v1 = V1Signer.sign(
            entries = entries.map { V1Signer.Entry(it.name, it.data) },
            identity = identity,
        )

        val withV1 = addSignatureEntries(unsignedAligned, v1)
        val signed = V2Signer.sign(withV1, identity.privateKey, identity.certificateDer)

        return ProxyApk(
            bytes = signed,
            packageName = targetPackage,
            // The manifest's provider authority is rewritten to the bare package name, so
            // Understudy can always predict where to find the bridge.
            authority = targetPackage,
            sizeBytes = signed.size,
            sha256Hex = sha256Hex(signed),
            manifestReplacements = patched.replacedStrings,
        )
    }

    private fun isDroppable(name: String): Boolean {
        if (name.startsWith("META-INF/")) return true
        if (name.endsWith(".kotlin_builtins")) return true
        if (name == "resources.arsc") return true
        if (name.endsWith(".version") || name.endsWith(".properties")) return true
        return false
    }

    /**
     * Re-opens the aligned unsigned APK and appends the three JAR-signature entries.
     *
     * Rewriting rather than appending in place is what keeps alignment correct: the `.SF`
     * and `.RSA` entries are tiny and STORED, and `ZipWriter` re-pads every entry, so the
     * result stays `zipalign`-clean without a second pass.
     */
    private fun addSignatureEntries(alignedUnsigned: ByteArray, v1: V1Signer.Output): ByteArray {
        val existing = readZip(alignedUnsigned).map { (name, data) ->
            ZipWriter.Entry(name, data, compressionFor(name))
        }
        val signatureEntries = listOf(
            ZipWriter.Entry(v1.manifestPath, v1.manifest, ZipWriter.Method.DEFLATE),
            ZipWriter.Entry(v1.signatureFilePath, v1.signatureFile, ZipWriter.Method.DEFLATE),
            ZipWriter.Entry(v1.rsaPath, v1.rsaBlock, ZipWriter.Method.STORED),
        )
        return ZipWriter.build(existing + signatureEntries)
    }

    private fun compressionFor(name: String): ZipWriter.Method =
        if (name == "resources.arsc") ZipWriter.Method.STORED else ZipWriter.Method.DEFLATE

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val current = entry!!
                if (!current.isDirectory) {
                    val buffer = ByteArrayOutputStream(maxOf(1024, current.size.toInt()))
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(chunk)
                        if (n <= 0) break
                        buffer.write(chunk, 0, n)
                    }
                    out[current.name] = buffer.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun sha256Hex(data: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }
}
