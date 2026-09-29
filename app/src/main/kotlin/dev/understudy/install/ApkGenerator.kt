package dev.understudy.install

import android.content.Context
import dev.understudy.packaging.ProxyApkFactory
import dev.understudy.packaging.sign.SigningIdentity
import java.io.File
import java.io.IOException

/**
 * Produces installable proxy APKs on demand.
 *
 * Two responsibilities that are easy to conflate:
 *  - **identity**: load-or-create the signing key, which must stay stable across runs or a
 *    previously installed proxy can no longer be updated in place;
 *  - **materialisation**: read the template asset, re-target it, and write the result somewhere
 *    `PackageInstaller` can stream from.
 *
 * The template lives in assets rather than being generated because it is the output of the
 * `:proxy` Gradle module — real compiled Kotlin, type-checked and unit-tested by the normal
 * toolchain, not something assembled by hand at runtime.
 */
class ApkGenerator(private val context: Context) {

    /** Cached so repeated generations do not re-read the asset or re-parse the certificate. */
    private var cachedIdentity: SigningIdentity? = null
    private var cachedTemplate: ByteArray? = null

    /** The signing identity in use. Created on first access and persisted thereafter. */
    @Synchronized
    fun identity(): SigningIdentity {
        cachedIdentity?.let { return it }
        val created = SigningIdentity.loadOrCreate(File(context.filesDir, KEY_DIR))
        cachedIdentity = created
        return created
    }

    /** SHA-256 fingerprint of the signing certificate, for display and for diagnostics. */
    fun fingerprint(): String = identity().fingerprintHex

    /**
     * Generates a proxy APK impersonating [targetPackage] and writes it to [outputDir].
     *
     * @return the written file plus metadata worth showing the user
     */
    fun generate(targetPackage: String, outputDir: File = defaultOutputDir()): GeneratedApk {
        val template = template()
        val apk = ProxyApkFactory(identity()).generate(template, targetPackage)

        outputDir.mkdirs()
        val file = File(outputDir, fileNameFor(targetPackage))
        try {
            file.writeBytes(apk.bytes)
        } catch (e: IOException) {
            throw IOException("could not write ${file.name}: ${e.message}", e)
        }

        return GeneratedApk(
            file = file,
            packageName = apk.packageName,
            authority = apk.authority,
            sizeBytes = apk.sizeBytes,
            sha256Hex = apk.sha256Hex,
            manifestReplacements = apk.manifestReplacements,
        )
    }

    /** Removes every previously generated APK. Safe to call at any time. */
    fun clearGenerated() {
        defaultOutputDir().listFiles()?.forEach { it.delete() }
    }

    private fun template(): ByteArray {
        cachedTemplate?.let { return it }
        val bytes = context.assets.open(TEMPLATE_ASSET).use { it.readBytes() }
        require(bytes.size > MIN_TEMPLATE_SIZE) {
            "$TEMPLATE_ASSET is ${bytes.size} bytes — the :proxy module did not build into it. " +
                "Run ./gradlew :app:syncProxyTemplate."
        }
        cachedTemplate = bytes
        return bytes
    }

    private fun defaultOutputDir(): File = File(context.cacheDir, GENERATED_DIR)

    companion object {
        const val TEMPLATE_ASSET = "proxy-template.apk"
        private const val KEY_DIR = "signing"
        private const val GENERATED_DIR = "generated-proxies"
        private const val MIN_TEMPLATE_SIZE = 10_000

        /**
         * File name for a generated APK.
         *
         * Includes the target package so several proxies can coexist on disk, and stays inside
         * the cache dir so the system can reclaim it — the APK is trivially regenerable, unlike
         * the data it exists to rescue.
         */
        fun fileNameFor(packageName: String): String = "proxy-$packageName.apk"
    }
}

data class GeneratedApk(
    val file: File,
    val packageName: String,
    val authority: String,
    val sizeBytes: Int,
    val sha256Hex: String,
    val manifestReplacements: List<Pair<String, String>>,
)
