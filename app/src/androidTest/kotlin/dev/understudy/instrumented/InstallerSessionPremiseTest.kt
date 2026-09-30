package dev.understudy.instrumented

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.understudy.bridge.BridgeClient
import dev.understudy.core.model.BridgeInfo
import dev.understudy.install.ApkGenerator
import dev.understudy.install.InstallResultReceiver
import dev.understudy.install.PendingInstall
import dev.understudy.install.ProxyInstaller
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The **in-app install path**, on a device — HANDOFF §3.4.
 *
 * Everything CI proved about installation so far went through `adb install --user N`: that
 * pins the *artifacts* (a generated proxy is a valid, installable APK) but exercises none of
 * the machinery the app actually uses — `PackageInstaller` session staging from inside the
 * profile, the `REQUEST_INSTALL_PACKAGES` appop (which is per-user, and which the UI must
 * detect and route to Settings), the commit→broadcast round trip through
 * [InstallResultReceiver], and the session bookkeeping ([ProxyInstaller.stage]/abandon).
 *
 * This test walks the production path end to end with the real classes: [ApkGenerator] builds
 * the proxy from the committed template with the app's own per-install [dev.understudy.packaging.sign.SigningIdentity],
 * [ProxyInstaller] stages and commits it, the result arrives on the real receiver, and — the
 * part that closes the circle — the freshly installed proxy answers a `ping` through
 * [BridgeClient], proving the app can also *see* the package it installed (installer-of-record
 * visibility, a different AppsFilter path from the `<queries>` discovery the other suites pin).
 *
 * Preconditions the CI orchestration provides (`.github/scripts/run-premise-test.sh`):
 *  - the app under test is installed for this user (it is: this runs inside it);
 *  - `appops set --user N <app> REQUEST_INSTALL_PACKAGES allow` — on a real device the user
 *    grants this from the profile's own Settings; granting the appop from the shell is the
 *    automatable equivalent, and `canRequestInstalls()` below asserts it took. With the appop
 *    granted the platform commits without a confirmation dialog; if a future build starts
 *    answering with STATUS_PENDING_USER_ACTION anyway, this test fails saying exactly that,
 *    because there is no user here to tap.
 *
 * Not covered here, deliberately: the *uninstall* half. `PackageInstaller.uninstall` always
 * routes through a confirmation dialog (and the proxy's `hasFragileUserData` guarantees one,
 * to offer "Keep app data"), which has no headless path; the CI script asserts the data-keeping
 * uninstall via `pm uninstall -k` instead, and the in-app dialog flow needs a human or an
 * OEM-free UI automation rig.
 *
 * Arguments:
 *  - `installerTargetPackage` (required) — package name the proxy will impersonate; distinct
 *    from the other phases' targets so all three proxies coexist
 *  - `userId` (optional) — expected profile, cross-checked against the ping
 */
@RunWith(AndroidJUnit4::class)
class InstallerSessionPremiseTest {

    private lateinit var context: Context
    private lateinit var installer: ProxyInstaller
    private lateinit var target: String
    private var expectedUser: Int = -1

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        installer = ProxyInstaller(context)
        val args = InstrumentationRegistry.getArguments()
        target = args.getString("installerTargetPackage") ?: ""
        expectedUser = args.getString("userId")?.toIntOrNull() ?: -1
        assumeTrue("no installerTargetPackage argument; nothing to verify", target.isNotBlank())
    }

    @Test
    fun theAppCanInstallItsOwnProxyThroughASessionAndReachIt() {
        // 0. The per-user appop. Asserted rather than assumed: without it the platform either
        //    refuses the commit or demands a confirmation nobody is here to give, and both
        //    outcomes must be attributed to the grant, not to the session code.
        val canInstall = installer.canRequestInstalls()
        println("INSTALLER-DIAG canRequestInstalls=$canInstall user=${installer.currentUserId} target=$target")
        assertTrue(
            canInstall,
            "REQUEST_INSTALL_PACKAGES is not granted for ${context.packageName} in this profile. " +
                "The appop is per-user; CI grants it with `appops set --user N … allow` before " +
                "this phase, so a failure here means the grant did not take on this build.",
        )

        // 1. Generate through the production path: committed template asset, the app's own
        //    persisted signing identity, the app's own certificate digest baked in.
        val generated = ApkGenerator(context).generate(target)
        println("INSTALLER-DIAG generated ${generated.file.name} (${generated.file.length()} bytes) " +
            "package=${generated.packageName}")
        assertTrue(generated.file.isFile && generated.file.length() > 0, "generated APK is empty")

        // 2. Stage + commit, with the result routed through the real receiver. The custom
        //    extra mirrors what the app's own PendingIntent carries so the outcome can be
        //    correlated to the target.
        val latch = CountDownLatch(1)
        val outcomeRef = AtomicReference<InstallResultReceiver.Outcome?>(null)
        InstallResultReceiver.listener = { o ->
            outcomeRef.set(o)
            latch.countDown()
        }
        val pending = PendingIntent.getBroadcast(
            context,
            0x15E55,
            Intent(context, InstallResultReceiver::class.java)
                .putExtra(PendingInstall.PACKAGE_NAME, target),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val staged = installer.stage(generated.file, target, pending)
        println("INSTALLER-DIAG staged session=${staged.sessionId}")

        // 3. Await the broadcast. 90 s: a session commit on a busy emulator with dexopt ahead
        //    of it is not instant, and a false timeout here reads as a product failure.
        val arrived = latch.await(90, TimeUnit.SECONDS)
        InstallResultReceiver.listener = null
        if (!arrived) {
            val sessions = installer.mySessions().joinToString { "${it.sessionId}:${it.isActive}" }
            throw AssertionError(
                "no install result arrived within 90 s. Sessions still open: [$sessions]. " +
                    "If logcat shows the confirmation UI, the appop grant did not make the " +
                    "commit silent on this build.",
            )
        }
        val result = outcomeRef.get()
        println("INSTALLER-DIAG outcome=$result")

        // 4. The verdict. NeedsConfirmation is reported as its own failure: it means the
        //    platform wanted a human despite the grant — a real finding about this API level,
        //    not a flake to retry.
        val success = when (result) {
            is InstallResultReceiver.Outcome.Success -> result
            is InstallResultReceiver.Outcome.NeedsConfirmation -> throw AssertionError(
                "STATUS_PENDING_USER_ACTION despite REQUEST_INSTALL_PACKAGES being granted — " +
                    "on this build a session commit is never silent, so the in-app install flow " +
                    "always needs the confirmation UI. session=${result.sessionId}",
            )
            is InstallResultReceiver.Outcome.Failure -> throw AssertionError(
                "session install failed: status=${result.status} reason=${result.reason}",
            )
            null -> throw AssertionError("the receiver fired but delivered no outcome")
        }
        assertEquals(target, success.packageName)

        // 5. Installed AND visible to us: getPackageInfo through the app's own resolver. The
        //    app is the installer of record, which is its own AppsFilter visibility path —
        //    worth pinning separately from the <queries> discovery the premise suite covers.
        val info = context.packageManager.getPackageInfo(target, 0)
        assertEquals(target, info.packageName)

        // 6. Close the circle: the proxy the app just installed with its own hands must answer
        //    the bridge. Retried briefly — a fresh process's provider publish is not instant,
        //    and the production verify() step retries for the same reason.
        val client = BridgeClient(context.contentResolver, target)
        var ping: BridgeInfo? = null
        var lastError: Throwable? = null
        var attempt = 0
        while (ping == null && attempt < 5) {
            attempt++
            runCatching { client.ping(target) }
                .onSuccess { ping = it }
                .onFailure {
                    lastError = it
                    println("INSTALLER-DIAG ping attempt $attempt failed: ${it.javaClass.name}: ${it.message}")
                    Thread.sleep(2_000)
                }
        }
        val answered = ping
        assertNotNull(
            answered,
            "the session-installed proxy never answered a ping: $lastError — the install " +
                "reported success but the bridge is not reachable, which is exactly the " +
                "silent-failure class milestone 5 exists to prevent.",
        )
        println("INSTALLER-DIAG ping OK: package=${answered.packageName} user=${answered.userId} " +
            "roots=${answered.roots}")
        assertEquals(target, answered.packageName)
        assertTrue(answered.protocolCompatible, "protocol ${answered.protocolVersion} is not ours")
        if (expectedUser >= 0) {
            assertEquals(expectedUser, answered.userId, "proxy is not in the requested profile")
        }
        println("INSTALLER-SESSION VERIFIED: staged, committed, broadcast received, bridge answered")
    }
}
