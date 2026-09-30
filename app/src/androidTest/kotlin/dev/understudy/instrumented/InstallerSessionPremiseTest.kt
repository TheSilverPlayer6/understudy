package dev.understudy.instrumented

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.understudy.bridge.BridgeClient
import dev.understudy.core.model.BridgeInfo
import dev.understudy.install.ApkGenerator
import dev.understudy.install.InstallResultReceiver
import dev.understudy.install.PendingInstall
import dev.understudy.install.ProxyInstaller
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
 * Everything CI proved about installation before this test went through `adb install --user N`:
 * that pins the *artifacts* but exercises none of the machinery the app itself uses —
 * `PackageInstaller` session staging from inside the profile, the per-user
 * `REQUEST_INSTALL_PACKAGES` appop, the commit→broadcast round trip through
 * [InstallResultReceiver], and the session bookkeeping. This test walks the production path
 * with the real classes: [ApkGenerator] builds the proxy from the committed template with the
 * app's own per-install identity, [ProxyInstaller] stages and commits it, the results arrive on
 * the real receiver, and — the part that closes the circle — the freshly installed proxy
 * answers a `ping` through [BridgeClient], proving the app can also *see* the package it
 * installed (installer-of-record visibility, a different AppsFilter path from the `<queries>`
 * discovery the other suites pin).
 *
 * **The confirmation dialog is part of the flow, and run #50 is the evidence.** The first
 * version of this test asserted that a granted `REQUEST_INSTALL_PACKAGES` appop makes the
 * commit silent. Measured on API 35/36 AOSP: `canRequestInstalls=true`, and the platform still
 * answered `STATUS_PENDING_USER_ACTION` with a `CONFIRM_INSTALL` intent — per-install
 * confirmation is simply not skippable for a non-privileged installer, on any current build.
 * That matches how [dev.understudy.core.SessionManager] already models the flow
 * (`Installing(awaitingUser = true)`, the confirmation intent handed to the UI). So this test
 * now drives the whole truth: commit → PENDING_USER_ACTION → launch the confirmation → tap
 * Install (UiAutomator, because CI has no thumb) → SUCCESS broadcast → provider answers.
 * A platform that ever DOES commit silently is handled too: the first outcome is simply
 * Success and the dialog phase is skipped.
 *
 * Not covered here, deliberately: the *uninstall* half. `PackageInstaller.uninstall` also
 * routes through a dialog, and the proxy's `hasFragileUserData` guarantees one (to offer
 * "Keep app data") — but unlike install there is no programmatic tap that makes it faithful,
 * and the data-keeping uninstall is already asserted via `pm uninstall -k` in teardown.
 *
 * Preconditions the CI orchestration provides (`.github/scripts/run-premise-test.sh`):
 *  - the app under test is installed for this user (this runs inside it);
 *  - `appops set --user N <app> REQUEST_INSTALL_PACKAGES allow` — the automatable equivalent
 *    of the per-profile Settings toggle a real user performs; asserted below so a missing
 *    grant is named rather than surfacing as an opaque commit failure.
 *
 * Arguments:
 *  - `installerTargetPackage` (required) — package name the proxy impersonates; distinct from
 *    the other phases' targets so all proxies coexist
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
        // 0. The per-user appop. Asserted rather than assumed: without it the commit fails in a
        //    different way (blocked/abandoned), and that difference must be attributable.
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

        // 2. Stage + commit. Outcomes arrive on the real receiver; a queue rather than a latch
        //    because the honest flow delivers TWO: PENDING_USER_ACTION now, SUCCESS after the
        //    confirmation. (The platform reuses the commit's IntentSender for the final result.)
        val outcomes = LinkedBlockingQueue<InstallResultReceiver.Outcome>()
        InstallResultReceiver.listener = { outcomes.offer(it) }
        val pending = PendingIntent.getBroadcast(
            context,
            0x15E55,
            Intent(context, InstallResultReceiver::class.java)
                .putExtra(PendingInstall.PACKAGE_NAME, target),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val staged = installer.stage(generated.file, target, pending)
        println("INSTALLER-DIAG staged session=${staged.sessionId}")

        // 3. First outcome. 90 s: a commit on a busy emulator with dexopt ahead of it is not
        //    instant, and a false timeout here reads as a product failure.
        val first = outcomes.poll(90, TimeUnit.SECONDS)
        if (first == null) {
            InstallResultReceiver.listener = null
            val sessions = installer.mySessions().joinToString { "${it.sessionId}:active=${it.isActive}" }
            throw AssertionError(
                "no install result arrived within 90 s of commit. Sessions still open: " +
                    "[$sessions]. Nothing was delivered to the receiver at all.",
            )
        }
        println("INSTALLER-DIAG first outcome=$first")

        // 4. The confirmation dance — the flow every real user goes through.
        val success = when (first) {
            is InstallResultReceiver.Outcome.Success -> first
            is InstallResultReceiver.Outcome.Failure -> {
                InstallResultReceiver.listener = null
                throw AssertionError(
                    "session install failed before any confirmation: status=${first.status} " +
                        "reason=${first.reason}",
                )
            }
            is InstallResultReceiver.Outcome.NeedsConfirmation -> {
                val confirmation = first.confirmation
                assertNotNull(
                    confirmation,
                    "STATUS_PENDING_USER_ACTION arrived without the confirmation Intent — the " +
                        "receiver could not have forwarded what the platform did not include.",
                )
                println("INSTALLER-DIAG launching confirmation: $confirmation")
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmation)

                // The platform's dialog, tapped by the test. Selector cascade, because run
                // #53's hierarchy dump proved the button text differs by installer build:
                // AOSP's alert dialog says "INSTALL" (all caps, android:id/button1), the
                // Google installer on API 36 says "Install" (ok_button). A case-insensitive
                // exact-word pattern covers both; the id fallbacks cover a future restyling
                // that drops the word. If nothing matches, the hierarchy dump below says what
                // was on screen instead, so the next change is diagnosable from the artifact.
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                val installButton =
                    device.wait(
                        Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)^install$"))),
                        CONFIRMATION_TIMEOUT_MS,
                    )
                        ?: device.wait(
                            Until.findObject(By.res("com.android.packageinstaller", "ok_button")),
                            5_000,
                        )
                        ?: device.wait(
                            Until.findObject(By.res("com.google.android.packageinstaller", "ok_button")),
                            5_000,
                        )
                if (installButton == null) {
                    println("INSTALLER-DIAG confirmation window hierarchy dump follows")
                    val dumpFile = java.io.File(context.cacheDir, "installer-hierarchy.xml")
                    runCatching {
                        device.dumpWindowHierarchy(dumpFile)
                        println(dumpFile.readText().take(8_000))
                    }.onFailure { println("INSTALLER-DIAG hierarchy dump failed: $it") }
                    dumpFile.delete()
                    InstallResultReceiver.listener = null
                    throw AssertionError(
                        "the install-confirmation dialog did not show an 'Install' button " +
                            "within ${CONFIRMATION_TIMEOUT_MS / 1000}s of launching $confirmation.",
                    )
                }
                installButton.click()
                println("INSTALLER-DIAG tapped Install; waiting for the final outcome")

                val second = outcomes.poll(90, TimeUnit.SECONDS)
                InstallResultReceiver.listener = null
                when (second) {
                    is InstallResultReceiver.Outcome.Success -> second
                    is InstallResultReceiver.Outcome.Failure -> throw AssertionError(
                        "session install failed AFTER confirmation: status=${second.status} " +
                            "reason=${second.reason}",
                    )
                    else -> throw AssertionError(
                        "no final outcome within 90 s of tapping Install (got: $second)",
                    )
                }
            }
        }
        InstallResultReceiver.listener = null
        println("INSTALLER-DIAG final outcome=$success")
        assertEquals(target, success.packageName, "the SUCCESS broadcast named a different package")

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
        println("INSTALLER-SESSION VERIFIED: staged, committed, confirmed, SUCCESS received, " +
            "bridge answered — the production install path, end to end")
    }

    private companion object {
        const val CONFIRMATION_TIMEOUT_MS = 20_000L
    }
}
