package dev.understudy.instrumented

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.understudy.bridge.BridgeClient
import dev.understudy.core.model.StorageRoot
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The **production caller-authentication** premise. Complements [BridgePremiseTest], which proves
 * the FUSE grant but installs a proxy signed with the *same* key as the app — so it only ever
 * exercises the `signature`-level permission path, never the digest path production depends on.
 *
 * In production the proxy is signed with a per-install key that cannot match the app's build-time
 * key. CI reproduces that exactly: `.github/scripts/run-premise-test.sh` installs a proxy signed
 * with a fresh random key and carrying the SHA-256 of *this app's* certificate (see
 * `ProdSignedProxyTest`), then runs this class against it. A successful call therefore proves the
 * thing no JVM test can: that the app reaches its own differently-signed proxy's provider.
 *
 * The security model under test is two gates in series:
 *  1. the platform's `android:permission` check on the provider, which runs **before** any of the
 *     proxy's code; and
 *  2. `ProxyFileBridge.enforceCaller()`'s generator-certificate digest check.
 *
 * Gate 1 is the subtle one. `BRIDGE` is `signature`-level, so it is granted only to packages signed
 * like whichever package *defines* it. If the proxy defines it (signed with the per-install key),
 * the app (build-time key) cannot hold it and the platform refuses the call before gate 2 is ever
 * reached — the digest fix would be dead code. The app must therefore be the definer. This test
 * prints which gate produced any failure so a red run is unambiguous.
 *
 * Arguments:
 *  - `prodTargetPackage` (required) — the package the differently-signed proxy impersonates
 *  - `userId`            (optional) — expected Android user id, cross-checked against the proxy's
 */
@RunWith(AndroidJUnit4::class)
class CallerAuthPremiseTest {

    private lateinit var context: Context
    private lateinit var target: String
    private var expectedUser: Int = -1

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val args = InstrumentationRegistry.getArguments()
        target = args.getString("prodTargetPackage") ?: ""
        expectedUser = args.getString("userId")?.toIntOrNull() ?: -1
        assumeTrue("no prodTargetPackage argument; nothing to verify", target.isNotBlank())
    }

    private fun client() = BridgeClient(context.contentResolver, target)

    /**
     * Everything needed to attribute a failure to the right gate, printed whether or not the
     * assertions pass. A red run with these lines is self-diagnosing:
     *  - `holdsBridge=GRANTED` + a permission `SecurityException` would be contradictory;
     *  - `holdsBridge=DENIED` + the platform's "requires … permission" message is gate 1 (the bug
     *    this milestone fixes: the proxy, not the app, defined `BRIDGE`);
     *  - `holdsBridge=GRANTED` + "not the Understudy install that generated this proxy" is gate 2
     *    (a digest mismatch — i.e. the harness baked the wrong digest, not a product defect).
     */
    @Test
    fun appCanReachItsOwnDifferentlySignedProxy() {
        val holdsBridge = context.checkSelfPermission(PERMISSION_BRIDGE)
        val holdsText = if (holdsBridge == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"
        val ownDigest = ownCertificateSha256Hex()
        println("CALLERAUTH-DIAG target=$target uid=${Process.myUid()} " +
            "userId=${Process.myUid() / PER_USER_RANGE}")
        println("CALLERAUTH-DIAG holdsBridge=$holdsText ($PERMISSION_BRIDGE)")
        println("CALLERAUTH-DIAG ownCertificateSha256=$ownDigest")

        val ping = runCatching { client().ping(target) }
        ping.onSuccess { info ->
            println("CALLERAUTH-DIAG ping OK: package=${info.packageName} user=${info.userId} " +
                "protocol=${info.protocolVersion} roots=${info.roots}")
        }.onFailure { e ->
            println("CALLERAUTH-DIAG ping FAILED: ${e.javaClass.name}: ${e.message}")
            // The gate is identified by the CAUSE, which is the platform's or the proxy's own
            // SecurityException. BridgeError.PermissionDenied wraps both, so unwrap it.
            var cause: Throwable? = e.cause
            while (cause != null) {
                println("CALLERAUTH-DIAG ping cause: ${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
            }
        }

        assertTrue(
            ping.isSuccess,
            "the app could not reach a proxy signed with a DIFFERENT key that carries the app's " +
                "certificate digest. holdsBridge=$holdsText. Failure: ${ping.exceptionOrNull()} " +
                "(cause: ${ping.exceptionOrNull()?.cause}). In production this is every bridge call.",
        )

        val info = ping.getOrThrow()
        assertEquals(target, info.packageName)
        assertTrue(info.protocolCompatible, "protocol ${info.protocolVersion} is not ours")
        if (expectedUser >= 0) {
            assertEquals(expectedUser, info.userId, "proxy is not in the requested profile")
        }
    }

    /**
     * `call()` (ping) is only one of the three gated provider entry points. `query()` and
     * `openFile()` are gated identically, so a full mkdir→write→read→list round trip proves the
     * digest authorisation holds for the whole surface, not just the handshake.
     *
     * The proxy owns `Android/data/<target>` (it creates it as its own uid on first access), so
     * this needs no root-planted fixtures — unlike the FUSE premise, which reads data that predates
     * the proxy. What is being tested here is authorisation, not the FUSE grant.
     *
     * The explicit `mkdirs` is required, not incidental: `ProxyFileBridge.openFile` refuses a
     * write whose parent directory does not exist (a write mode must not silently create a
     * directory entry, and must not follow a path whose parent is missing), and
     * `BridgeClient.openFile` does not create parents either. `BridgePremiseTest` gets away
     * without this only because CI root-plants its `planted/` directory first. Going through
     * `call(MKDIRS)` here is a bonus: it is a fourth gated entry point, so the round trip covers
     * `call`, `openFile` (both directions) and `query`.
     */
    @Test
    fun digestAuthorisationCoversQueryAndOpenFileToo() {
        val dir = "callerauth-probe"
        val path = "$dir/round-trip.txt"
        val payload = "production caller-auth round trip ${System.currentTimeMillis()}"

        val mkdirs = runCatching { client().mkdirs(StorageRoot.DATA, dir) }
        mkdirs.exceptionOrNull()?.let {
            println("CALLERAUTH-DIAG mkdirs FAILED: ${it.javaClass.name}: ${it.message} " +
                "(cause ${it.cause?.javaClass?.name}: ${it.cause?.message})")
        }
        assertTrue(mkdirs.isSuccess, "call(mkdirs) through the digest path failed: ${mkdirs.exceptionOrNull()}")

        val write = runCatching {
            client().openFile(StorageRoot.DATA, path, "w").use { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd).use {
                    it.write(payload.toByteArray())
                }
            }
        }
        write.exceptionOrNull()?.let {
            println("CALLERAUTH-DIAG openFile(w) FAILED: ${it.javaClass.name}: ${it.message} " +
                "(cause ${it.cause?.javaClass?.name}: ${it.cause?.message})")
        }
        assertTrue(write.isSuccess, "openFile('w') through the digest path failed: ${write.exceptionOrNull()}")

        val read = runCatching {
            client().openFile(StorageRoot.DATA, path, "r").use { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
            }
        }
        assertTrue(read.isSuccess, "openFile('r') through the digest path failed: ${read.exceptionOrNull()}")
        assertEquals(payload, String(read.getOrThrow(), Charsets.UTF_8))

        val listed = runCatching { client().list(StorageRoot.DATA, dir).map { it.name } }
        assertTrue(listed.isSuccess, "query() through the digest path failed: ${listed.exceptionOrNull()}")
        assertTrue("round-trip.txt" in listed.getOrThrow(), "written file not listed: ${listed.getOrNull()}")

        // Confirmed by the proxy itself, for the reason BridgePremiseTest documents at length:
        // the caller cannot stat this path, because the platform hides it — that is the point.
        val stated = runCatching { client().statPath(StorageRoot.DATA, path) }
        assertTrue(stated.isSuccess, "call(statPath) through the digest path failed: ${stated.exceptionOrNull()}")
        val onDisk = stated.getOrThrow()
        assertTrue(
            onDisk.exists && !onDisk.isDirectory && onDisk.sizeBytes == payload.toByteArray().size.toLong(),
            "the proxy does not report the file it just wrote: $onDisk",
        )
        println("CALLERAUTH-DIAG mkdirs+query+openFile round trip OK ($onDisk)")
    }

    /**
     * Mirrors `ApkGenerator.ownCertificateSha256Hex()` so the log shows the digest the platform
     * reports for THIS app — which is what `ProdSignedProxyTest` baked into the proxy from the test
     * keystore. If these two disagree the harness is wrong, not the product.
     */
    @Suppress("DEPRECATION")
    private fun ownCertificateSha256Hex(): String? = runCatching {
        val pm = context.packageManager
        val pkg = context.packageName
        val cert = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val signing = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return@runCatching null
            (if (signing.hasMultipleSigners()) signing.apkContentsSigners
                else signing.signingCertificateHistory)?.firstOrNull()
        } else {
            @Suppress("PackageManagerGetSignatures")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return@runCatching null
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }.getOrNull()

    companion object {
        private const val PERMISSION_BRIDGE = "dev.understudy.permission.BRIDGE"
        private const val PER_USER_RANGE = 100_000
    }
}
